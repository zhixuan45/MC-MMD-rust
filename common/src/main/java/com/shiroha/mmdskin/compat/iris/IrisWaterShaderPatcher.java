package com.shiroha.mmdskin.compat.iris;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded adapters for verified BSL, Complementary early, and modern Complementary water contracts. */
public final class IrisWaterShaderPatcher {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final Pattern OUTPUT = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");
    private static final Pattern VERSION = Pattern.compile("#version[^\\r\\n]*");
    private static final Pattern BUFFER_DIRECTIVE = Pattern.compile(
            "^(?:DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9][0-9,\\s]*)$");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_fragdata\\[(\\d+)]=(.*?);");
    private static final Pattern SSBO_DECLARATION = Pattern.compile("\\bbuffer\\s+[A-Za-z_]\\w*\\s*\\{");
    private static final Pattern IMAGE_TYPE = Pattern.compile(
            "image(?:1d|2d|3d|cube|buffer|rect|2dms)");
    private static final OutputLayout[] MODERN_LAYOUTS = {
            new OutputLayout("03", new int[]{0, 3}),
            new OutputLayout("036", new int[]{0, 3, 6}),
            new OutputLayout("0348", new int[]{0, 3, 4, 8}),
            new OutputLayout("03648", new int[]{0, 3, 6, 4, 8}),
            new OutputLayout("0369", new int[]{0, 3, 6, 9}),
            new OutputLayout("036489", new int[]{0, 3, 6, 4, 8, 9})
    };
    private static final OutputLayout[] EARLY_LAYOUTS = {
            new OutputLayout("03", new int[]{0, 3}),
            new OutputLayout("031", new int[]{0, 3, 1}),
            new OutputLayout("0318", new int[]{0, 3, 1, 8}),
            new OutputLayout("038", new int[]{0, 3, 8})
    };

    private IrisWaterShaderPatcher() {}

    public record Diagnostic(boolean accepted, String family, String reason) {}

    public static boolean supports(String name, String source) {
        return classify(name, source) != null;
    }

    public static Diagnostic diagnose(String name, String source) {
        Analysis analysis = analyze(name, source);
        return analysis.contract() == null
                ? new Diagnostic(false, "unclassified", analysis.reason())
                : acceptedDiagnostic(analysis.contract());
    }

    public static <K> Diagnostic diagnose(String name, String source, Map<K, String> sources) {
        Analysis analysis = analyze(name, source);
        if (analysis.contract() == null) {
            return new Diagnostic(false, "unclassified", analysis.reason());
        }
        String transformedReason = transformedReason(sources, analysis.contract());
        return transformedReason == null
                ? acceptedDiagnostic(analysis.contract())
                : new Diagnostic(false, analysis.contract().family().displayName(), transformedReason);
    }

    public static <K> Map<K, String> patch(String name, String originalFragment, Map<K, String> sources) {
        WaterContract contract = classify(name, originalFragment);
        if (contract == null || sources == null) return sources;
        K fragmentKey = null;
        for (var entry : sources.entrySet()) {
            String stage = entry.getKey().toString();
            if (stage.equals("FRAGMENT")) fragmentKey = entry.getKey();
            else if (!stage.equals("VERTEX") && entry.getValue() != null) return sources;
        }
        if (fragmentKey == null) return sources;
        String fragment = sources.get(fragmentKey);
        String patched = patchFragment(fragment, contract.layout());
        if (patched == null) return sources;
        // Iris caches transformed maps. Never mutate its cached sources in place.
        Map<K, String> result = new HashMap<>(sources);
        result.put(fragmentKey, patched);
        return result;
    }

    /**
     * The one-argument form retains the BSL entry point and still verifies the
     * transformed primary output plus all side-effect exclusions.
     */
    public static String patchFragment(String fragment) {
        return patchFragment(fragment, null);
    }

    private static String patchFragment(String fragment, OutputLayout modernLayout) {
        String unavailable = transformedReason(fragment, modernLayout);
        if (unavailable != null) return null;
        String clean = COMMENTS.matcher(fragment).replaceAll(match -> " ".repeat(match.group().length()));
        var output = readOutputs(clean);
        var main = MAIN.matcher(clean);
        if (!main.find()) return null;
        int mainStart = main.start();
        int mainEnd = main.end();

        String declarations;
        try (var stream = IrisWaterShaderPatcher.class.getResourceAsStream(
                "/assets/mmdskin/shader/world_transparency_capture.glsl")) {
            if (stream == null) throw new IllegalStateException("Missing water capture declarations");
            declarations = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("layout(early_fragment_tests) in;", "")
                    .replace("layout(std430, binding = MMD_META_BINDING)", "layout(std430)")
                    .replace("layout(std430, binding = MMD_NODE_BINDING)", "layout(std430)")
                    .replace("buffer MmdHeads", "buffer MmdWaterHeads")
                    .replace("buffer MmdNodes", "buffer MmdWaterNodes");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        // Keep native alpha, discard, late depth and every MRT write. Only primary RGB joins OIT.
        String result = fragment.substring(0, mainStart) + declarations
                + "\nuniform bool MmdWaterCapture;\nvoid mmdWaterOriginalMain() {"
                + fragment.substring(mainEnd)
                + "\nvoid main() { mmdWaterOriginalMain(); if (MmdWaterCapture) { mmdStore("
                + output.get(0) + "); } }\n";
        return result.replaceFirst("#version[^\\r\\n]*", "#version 430 core");
    }

    private static WaterContract classify(String name, String source) {
        return analyze(name, source).contract();
    }

    private static Analysis analyze(String name, String source) {
        if (!"gbuffers_water".equals(name)) {
            return new Analysis(null, "unsupported program name: " + name);
        }
        if (source == null) return new Analysis(null, "water fragment source is missing");
        String clean = COMMENTS.matcher(source).replaceAll(" ");
        String code = compact(clean);
        String sourceReason = sourceReason(clean, false);
        if (sourceReason != null) return new Analysis(null, sourceReason);
        if (looksLikeModernComplementary(code)) {
            LayoutAnalysis layout = findActiveLayout(source, MODERN_LAYOUTS,
                    "modern Complementary");
            if (layout.layout() == null) return new Analysis(null, layout.reason());
            String assignmentReason = modernAssignmentReason(code, layout.layout());
            if (assignmentReason != null) return new Analysis(null, assignmentReason);
            return new Analysis(new WaterContract(WaterFamily.COMPLEMENTARY_MODERN, layout.layout()), null);
        }
        if (looksLikeEarlyComplementarySkeleton(code)) {
            LayoutAnalysis layout = findActiveLayout(source, EARLY_LAYOUTS,
                    "early Complementary");
            if (layout.layout() == null) return new Analysis(null, layout.reason());
            String semanticsReason = earlySemanticsReason(code);
            if (semanticsReason != null) return new Analysis(null, semanticsReason);
            String assignmentReason = earlyAssignmentReason(code, layout.layout());
            if (assignmentReason != null) return new Analysis(null, assignmentReason);
            String consumerReason = earlyConsumerReason(code, layout.layout());
            if (consumerReason != null) return new Analysis(null, consumerReason);
            return new Analysis(new WaterContract(WaterFamily.COMPLEMENTARY_EARLY, layout.layout()), null);
        }
        if (hasBslContract(code)) return new Analysis(new WaterContract(WaterFamily.BSL, null), null);
        return new Analysis(null, "unknown water protocol: neither verified BSL, Complementary early, "
                + "nor modern Complementary semantics matched");
    }

    private static Diagnostic acceptedDiagnostic(WaterContract contract) {
        String family = contract.family().displayName();
        String reason = switch (contract.family()) {
            case BSL -> "verified BSL water contract";
            case COMPLEMENTARY_EARLY ->
                    "verified Complementary early water contract layout " + contract.layout().id();
            case COMPLEMENTARY_MODERN ->
                    "verified modern Complementary water contract layout " + contract.layout().id();
        };
        return new Diagnostic(true, family, reason);
    }

    private static boolean hasBslContract(String code) {
        return code.contains("vlalbedo=mix(vec3(1.0),vlalbedo,sqrt(albedo.a))*(1.0-pow(albedo.a,64.0))")
                && code.contains("gl_fragdata[0]=albedo;")
                && code.contains("gl_fragdata[1]=vec4(vlalbedo,1.0);")
                && code.contains("getwaterfog(") && code.contains("cloudblendopacity");
    }

    private static boolean looksLikeModernComplementary(String code) {
        return code.contains("vec4translucentmult=vec4(1.0)")
                && code.contains("floatmaterialmask=0.0")
                && code.contains("vec3normalm=")
                && code.contains("floatfresnelm=pow3(fresnel)")
                && code.contains("translucentmult=vec4(mix(vec3(0.666),color.rgb*(1.0-pow2(pow2(color.a))),color.a),1.0)")
                && code.contains("translucentmult.rgb=mix(translucentmult.rgb,vec3(1.0),min1(pow2(pow2(lviewpos/far))))")
                && code.contains("dolighting(color,shadowmult,playerpos,viewpos,lviewpos,geonormal,normalm,dither,worldgeonormal,lmcoordm,")
                && code.contains("skylightfactor=getskylightfactor(lmcoordm,shadowmult)")
                && code.contains("floatprevalpha=color.a")
                && code.contains("dofog(color,skyfade,lviewpos,playerpos,vdotu,vdots,dither,false,0.0)")
                && code.contains("floatfogalpha=color.a")
                && code.contains("color.a=prevalpha*(1.0-skyfade)");
    }

    private static boolean looksLikeEarlyComplementarySkeleton(String code) {
        return code.contains("dolighting(color,shadowmult,playerpos,viewpos,lviewpos,normalm,lmcoordm,"
                        + "nosmoothlighting,nodirectionalshading,false,false,subsurfacemode,smoothnessg,"
                        + "highlightmult,emission)")
                && code.contains("floatsky=0.0")
                && code.contains("dofog(color.rgb,sky,lviewpos,playerpos,vdotu,vdots,dither)")
                && code.contains("color.a*=1.0-sky");
    }

    private static String earlySemanticsReason(String code) {
        if (!code.contains("vec4color=colorp*vec4(glcolor.rgb,1.0)")) {
            return "Complementary early color input contract is missing";
        }
        if (!code.contains("vec4translucentmult=vec4(1.0)")) {
            return "Complementary early translucentMult declaration is missing";
        }
        if (!code.contains("boolnosmoothlighting=false,nodirectionalshading=false,"
                + "translucentmultcalculated=false")) {
            return "Complementary early lighting/material flags do not match";
        }
        if (!code.contains("if(!translucentmultcalculated)translucentmult=vec4(mix(vec3(0.666),"
                + "color.rgb*(1.0-pow2(pow2(color.a))),color.a),1.0)")) {
            return "Complementary early translucentMult color formula does not match";
        }
        if (!code.contains("translucentmult.rgb=mix(translucentmult.rgb,vec3(1.0),"
                + "min1(pow2(pow2(lviewpos/far))))")) {
            return "Complementary early translucentMult distance fade does not match";
        }
        if (code.contains("skylightfactor=getskylightfactor(")) {
            return "Complementary early water must not use modern GetSkyLightFactor";
        }
        return null;
    }

    private static String modernAssignmentReason(String code, OutputLayout layout) {
        return assignmentReason(code, layout, false);
    }

    private static String earlyAssignmentReason(String code, OutputLayout layout) {
        return assignmentReason(code, layout, true);
    }

    private static String assignmentReason(String code, OutputLayout layout, boolean early) {
        Map<Integer, List<String>> assignments = assignments(code);
        if (assignments == null) {
            return "output assignments contain an unparsed gl_FragData write";
        }
        for (var entry : assignments.entrySet()) {
            if (entry.getValue().size() != 1) {
                return "duplicate gl_FragData[" + entry.getKey() + "] assignment for layout " + layout.id();
            }
        }
        if (assignments.size() != layout.targets.length) {
            return "output assignments do not match layout " + layout.id() + ": expected "
                    + layout.targets.length + " slots, found " + assignments.size();
        }
        for (int slot = 0; slot < layout.targets.length; slot++) {
            List<String> values = assignments.get(slot);
            if (values == null) {
                return "layout " + layout.id() + " is missing gl_FragData[" + slot + "]";
            }
            if (early) {
                String expected = expectedEarlyAssignment(layout, slot);
                if (!values.get(0).equals(expected)) {
                    return "gl_FragData[" + slot + "] does not match layout " + layout.id()
                            + " semantics: expected " + expected + ", found " + values.get(0);
                }
            } else if (!matchesModernAssignment(layout, slot, values.get(0))) {
                return "gl_FragData[" + slot + "] does not match layout " + layout.id()
                        + " semantics: expected " + expectedModernAssignment(layout, slot)
                        + ", found " + values.get(0);
            }
        }
        return null;
    }

    private static Map<Integer, List<String>> assignments(String code) {
        int occurrences = count(code, "gl_fragdata[");
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        Matcher matcher = FRAG_DATA.matcher(code);
        int matched = 0;
        while (matcher.find()) {
            matched++;
            int slot = Integer.parseInt(matcher.group(1));
            result.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(matcher.group(2));
        }
        return matched == occurrences ? result : null;
    }

    private static boolean matchesModernAssignment(OutputLayout layout, int slot, String actual) {
        if (layout.targets[slot] == 6) {
            return actual.equals("vec4(1.0,materialmask,skylightfactor,1.0)")
                    || actual.equals("vec4(1.0,materialmask,skylightfactor,lmcoord.x+clamp01(purkinjeoverwrite)+clamp01(emission))");
        }
        return actual.equals(expectedModernAssignment(layout, slot));
    }

    private static String expectedModernAssignment(OutputLayout layout, int slot) {
        if (slot == 0) return "color";
        if (slot == 1) return "vec4(1.0-translucentmult.rgb,translucentmult.a)";
        return switch (layout.targets[slot]) {
            case 6 -> "vec4(1.0,materialmask,skylightfactor,1.0)";
            case 4 -> "vec4(mat3(gbuffermodelviewinverse)*normalm,sqrt(fresnelm*color.a*fogalpha))";
            case 8 -> "vec4(reflection.rgb*fresnelm*color.a*fogalpha,reflection.a)";
            case 9 -> "vec4(lightalbedo,ssblalpha)";
            default -> "";
        };
    }

    private static String expectedEarlyAssignment(OutputLayout layout, int slot) {
        if (slot == 0) return "color";
        if (slot == 1) return "vec4(1.0-translucentmult.rgb,translucentmult.a)";
        return switch (layout.targets[slot]) {
            case 1 -> "vec4(0.0,materialmask,0.0,1.0)";
            case 8 -> "vec4(lightalbedo,1.0)";
            default -> "";
        };
    }

    private static String earlyConsumerReason(String code, OutputLayout layout) {
        if (hasTarget(layout, 1) && !code.contains("materialmask=osiebca*241.0")) {
            return "layout " + layout.id() + " colortex1 material mask does not match "
                    + "OSIEBCA * 241.0 water producer semantics";
        }
        if (hasTarget(layout, 8)) {
            String[] required = {
                    "vec3opaquelightalbedo=texture2d(colortex8,screenpos.xy).rgb",
                    "opaquelightalbedo*=normalizedcolor",
                    "vec3lightalbedo=normalizedcolor*min1(emission)",
                    "lightalbedo=mix(opaquelightalbedo,lightalbedo,color.a)"
            };
            for (String fragment : required) {
                if (!code.contains(fragment)) {
                    return "layout " + layout.id() + " colortex8 light albedo does not match "
                            + "the complementary composite consumer contract: missing " + fragment;
                }
            }
        }
        return null;
    }

    private static boolean hasTarget(OutputLayout layout, int target) {
        for (int candidate : layout.targets) {
            if (candidate == target) return true;
        }
        return false;
    }

    private static LayoutAnalysis findActiveLayout(String source, OutputLayout[] supported,
                                                   String protocol) {
        String activeDirective = null;
        Matcher comments = COMMENTS.matcher(source);
        while (comments.find()) {
            String comment = comments.group().trim();
            if (comment.startsWith("/*")) comment = comment.substring(2, comment.length() - 2).trim();
            else if (comment.startsWith("//")) comment = comment.substring(2).trim();
            if (!comment.startsWith("DRAWBUFFERS") && !comment.startsWith("RENDERTARGETS")) continue;
            activeDirective = comment;
        }
        if (activeDirective == null) {
            return new LayoutAnalysis(null, protocol
                    + " water source has no active DRAWBUFFERS/RENDERTARGETS");
        }
        Matcher directive = BUFFER_DIRECTIVE.matcher(activeDirective);
        if (!directive.matches()) {
            return new LayoutAnalysis(null, "invalid final output directive: " + activeDirective);
        }
        OutputLayout layout = canonicalLayout(directive.group(1), supported);
        return layout == null
                ? new LayoutAnalysis(null, "unsupported final output layout: " + activeDirective)
                : new LayoutAnalysis(layout, null);
    }

    private static OutputLayout canonicalLayout(String token, OutputLayout[] supported) {
        String value = token.replaceAll("\\s+", "");
        int[] targets;
        try {
            if (value.contains(",")) {
                String[] parts = value.split(",", -1);
                targets = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].isEmpty()) return null;
                    targets[i] = Integer.parseInt(parts[i]);
                }
            } else {
                targets = value.chars().map(character -> character - '0').toArray();
            }
        } catch (NumberFormatException exception) {
            return null;
        }
        boolean[] seen = new boolean[16];
        for (int target : targets) {
            if (target < 0 || target >= seen.length || seen[target]) return null;
            seen[target] = true;
        }
        for (OutputLayout layout : supported) {
            if (Arrays.equals(layout.targets, targets)) return layout;
        }
        return null;
    }

    private static Map<Integer, String> readOutputs(String clean) {
        Map<Integer, String> outputs = new LinkedHashMap<>();
        Matcher matcher = OUTPUT.matcher(clean);
        while (matcher.find()) {
            int location = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2);
            if (outputs.putIfAbsent(location, name) != null) return null;
        }
        return outputs;
    }

    private static <K> String transformedReason(Map<K, String> sources, WaterContract contract) {
        if (sources == null) return "transformed shader source map is missing";
        K fragmentKey = null;
        for (var entry : sources.entrySet()) {
            String stage = entry.getKey().toString();
            if (stage.equals("FRAGMENT")) {
                fragmentKey = entry.getKey();
            } else if (!stage.equals("VERTEX") && entry.getValue() != null) {
                return "unsupported transformed shader stage: " + stage;
            }
        }
        if (fragmentKey == null) return "transformed shader source map has no FRAGMENT stage";
        return transformedReason(sources.get(fragmentKey), contract.layout());
    }

    private static String transformedReason(String fragment, OutputLayout modernLayout) {
        if (fragment == null) return "transformed water fragment source is missing";
        String clean = COMMENTS.matcher(fragment).replaceAll(match -> " ".repeat(match.group().length()));
        String sourceReason = sourceReason(clean, true);
        if (sourceReason != null) return sourceReason;
        Map<Integer, String> outputs = readOutputs(clean);
        if (outputs == null) return "transformed water source declares duplicate output locations";
        if (!outputs.containsKey(0)) return "transformed water source has no location 0 primary output";
        if (modernLayout == null) return null;
        return exactOutputReason(outputs, modernLayout);
    }

    private static String exactOutputReason(Map<Integer, String> outputs, OutputLayout layout) {
        if (outputs.size() != layout.targets.length) {
            return "transformed output count does not match layout " + layout.id() + ": expected "
                    + layout.targets.length + ", found " + outputs.size();
        }
        for (int slot = 0; slot < layout.targets.length; slot++) {
            String expected = "iris_FragData" + slot;
            String actual = outputs.get(slot);
            if (actual == null) {
                return "transformed output location " + slot + " is missing for layout " + layout.id();
            }
            if (!expected.equals(actual)) {
                return "transformed output location " + slot + " is " + actual
                        + ", expected " + expected + " for layout " + layout.id();
            }
        }
        return null;
    }

    private static String sourceReason(String source, boolean requireVersion) {
        String code = compact(source);
        if (code.contains("mmdwatercapture") || code.contains("mmdwateroriginalmain")) {
            return "duplicate water injection";
        }
        if (code.contains("gl_fragdepth") || code.contains("gl_depthrange")) {
            return "explicit depth output or gl_DepthRange control";
        }
        if (code.contains("early_fragment_tests")) {
            return "early_fragment_tests changes verified late-depth semantics";
        }
        if (code.contains("imageload(") || code.contains("imagestore(")
                || code.contains("imageatomic") || IMAGE_TYPE.matcher(code).find()) {
            return "image load/store, atomic or image declaration side effect";
        }
        if (code.contains("atomic_uint") || code.contains("atomiccounter")) {
            return "GLSL atomic counter declaration or operation side effect";
        }
        if (SSBO_DECLARATION.matcher(source).find()) {
            return "shader storage buffer declaration side effect";
        }
        Matcher main = MAIN.matcher(source);
        if (!main.find()) return "water fragment has no main function";
        if (main.find()) return "water fragment has multiple main functions";
        if (requireVersion && !VERSION.matcher(source).find()) {
            return "transformed water fragment has no #version directive";
        }
        return null;
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static int count(String text, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }

    private record Analysis(WaterContract contract, String reason) {}

    private record LayoutAnalysis(OutputLayout layout, String reason) {}

    private record WaterContract(WaterFamily family, OutputLayout layout) {}

    private record OutputLayout(String id, int[] targets) {}

    private enum WaterFamily {
        BSL("BSL"),
        COMPLEMENTARY_EARLY("Complementary early"),
        COMPLEMENTARY_MODERN("Complementary modern");

        private final String displayName;

        WaterFamily(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }
    }
}
