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

/** Bounded source adapters for the verified BSL and modern Complementary water contracts. */
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
            new OutputLayout("03648", new int[]{0, 3, 6, 4, 8})
    };

    private IrisWaterShaderPatcher() {}

    public static boolean supports(String name, String source) {
        return classify(name, source) != null;
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
     * The one-argument form retains the original BSL entry point and still verifies the
     * transformed primary output plus all side-effect exclusions.
     */
    public static String patchFragment(String fragment) {
        return patchFragment(fragment, null);
    }

    private static String patchFragment(String fragment, OutputLayout modernLayout) {
        if (fragment == null) return null;
        String clean = COMMENTS.matcher(fragment).replaceAll(match -> " ".repeat(match.group().length()));
        if (hasUnsafeSideEffects(clean)) return null;
        var output = readOutputs(clean);
        var main = MAIN.matcher(clean);
        if (output == null || !output.containsKey(0) || !main.find()) return null;
        int mainStart = main.start();
        int mainEnd = main.end();
        if (main.find() || !VERSION.matcher(clean).find()) return null;
        if (modernLayout != null && !hasExactIrisOutputs(output, modernLayout)) return null;

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
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
        if (!"gbuffers_water".equals(name) || source == null) return null;
        String clean = COMMENTS.matcher(source).replaceAll(" ");
        String code = compact(clean);
        if (hasUnsafeSideEffects(clean) || !hasSingleMain(clean)) return null;
        if (looksLikeModernComplementary(code)) {
            OutputLayout layout = findActiveLayout(source);
            if (layout == null || !hasModernAssignments(code, layout)) return null;
            return new WaterContract(WaterFamily.COMPLEMENTARY_MODERN, layout);
        }
        if (hasBslContract(code)) return new WaterContract(WaterFamily.BSL, null);
        return null;
    }

    private static boolean hasSingleMain(String clean) {
        Matcher main = MAIN.matcher(clean);
        return main.find() && !main.find();
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

    private static boolean hasModernAssignments(String code, OutputLayout layout) {
        Map<Integer, List<String>> assignments = assignments(code);
        if (assignments == null || assignments.size() != layout.targets.length) return false;
        for (int slot = 0; slot < layout.targets.length; slot++) {
            List<String> values = assignments.get(slot);
            if (values == null || values.size() != 1
                    || !values.get(0).equals(expectedAssignment(layout, slot))) return false;
        }
        return true;
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

    private static String expectedAssignment(OutputLayout layout, int slot) {
        if (slot == 0) return "color";
        if (slot == 1) return "vec4(1.0-translucentmult.rgb,translucentmult.a)";
        return switch (layout.targets[slot]) {
            case 6 -> "vec4(1.0,materialmask,skylightfactor,1.0)";
            case 4 -> "vec4(mat3(gbuffermodelviewinverse)*normalm,sqrt(fresnelm*color.a*fogalpha))";
            case 8 -> "vec4(reflection.rgb*fresnelm*color.a*fogalpha,reflection.a)";
            default -> "";
        };
    }

    private static OutputLayout findActiveLayout(String source) {
        OutputLayout found = null;
        Matcher comments = COMMENTS.matcher(source);
        while (comments.find()) {
            String comment = comments.group().trim();
            if (comment.startsWith("/*")) comment = comment.substring(2, comment.length() - 2).trim();
            else if (comment.startsWith("//")) comment = comment.substring(2).trim();
            if (!comment.startsWith("DRAWBUFFERS") && !comment.startsWith("RENDERTARGETS")) {
                continue;
            }
            Matcher directive = BUFFER_DIRECTIVE.matcher(comment);
            if (!directive.matches()) return null;
            found = canonicalLayout(directive.group(1));
            if (found == null) return null;
        }
        return found;
    }

    private static OutputLayout canonicalLayout(String token) {
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
        for (OutputLayout layout : MODERN_LAYOUTS) {
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

    private static boolean hasExactIrisOutputs(Map<Integer, String> outputs, OutputLayout layout) {
        if (outputs == null || outputs.size() != layout.targets.length) return false;
        for (int slot = 0; slot < layout.targets.length; slot++) {
            if (!("iris_FragData" + slot).equals(outputs.get(slot))) return false;
        }
        return true;
    }

    private static boolean hasUnsafeSideEffects(String source) {
        String code = compact(source);
        return code.contains("gl_fragdepth") || code.contains("gl_depthrange")
                || code.contains("early_fragment_tests") || code.contains("mmdwatercapture")
                || code.contains("mmdwateroriginalmain") || code.contains("imageload(")
                || code.contains("imagestore(") || code.contains("imageatomic")
                || IMAGE_TYPE.matcher(code).find() || SSBO_DECLARATION.matcher(source).find();
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

    private record WaterContract(WaterFamily family, OutputLayout layout) {}

    private record OutputLayout(String id, int[] targets) {}

    private enum WaterFamily { BSL, COMPLEMENTARY_MODERN }
}
