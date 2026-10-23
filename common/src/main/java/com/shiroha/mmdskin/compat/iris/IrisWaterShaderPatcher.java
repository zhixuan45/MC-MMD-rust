package com.shiroha.mmdskin.compat.iris;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded source adapter for the verified BSL water contract after Iris's GLSL transform. */
public final class IrisWaterShaderPatcher {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final Pattern OUTPUT = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");

    private IrisWaterShaderPatcher() {}

    public static boolean supports(String name, String source) {
        if (!"gbuffers_water".equals(name) || source == null) return false;
        String code = COMMENTS.matcher(source).replaceAll("").replaceAll("\\s+", "");
        return code.contains("vlAlbedo=mix(vec3(1.0),vlAlbedo,sqrt(albedo.a))*(1.0-pow(albedo.a,64.0))")
                && code.contains("gl_FragData[0]=albedo;")
                && code.contains("gl_FragData[1]=vec4(vlAlbedo,1.0);")
                && code.contains("GetWaterFog(") && code.contains("cloudBlendOpacity")
                && !code.contains("gl_FragDepth") && !code.contains("early_fragment_tests")
                && !code.contains("MmdWaterCapture");
    }

    public static <K> Map<K, String> patch(String name, String originalFragment, Map<K, String> sources) {
        if (!supports(name, originalFragment) || sources == null) return sources;
        K fragmentKey = null;
        for (var entry : sources.entrySet()) {
            String stage = entry.getKey().toString();
            if (stage.equals("FRAGMENT")) fragmentKey = entry.getKey();
            else if (!stage.equals("VERTEX") && entry.getValue() != null) return sources;
        }
        if (fragmentKey == null) return sources;
        String fragment = sources.get(fragmentKey);
        String patched = patchFragment(fragment);
        if (patched == null) return sources;
        // Iris caches transformed maps. Never mutate its cached sources in place.
        Map<K, String> result = new HashMap<>(sources);
        result.put(fragmentKey, patched);
        return result;
    }

    public static String patchFragment(String fragment) {
        if (fragment == null) return null;
        String clean = COMMENTS.matcher(fragment).replaceAll(m -> " ".repeat(m.group().length()));
        if (clean.contains("gl_FragDepth") || clean.contains("early_fragment_tests")
                || clean.contains("MmdWaterCapture")) return null;
        var output = OUTPUT.matcher(clean);
        var main = MAIN.matcher(clean);
        if (!output.find() || !main.find()) return null;
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
        } catch (IOException e) { throw new IllegalStateException(e); }
        // Keep native discard, outputs and late depth semantics. The scope controls primary RGB blending.
        String result = fragment.substring(0, main.start()) + declarations
                + "\nuniform bool MmdWaterCapture;\nvoid mmdWaterOriginalMain() {"
                + fragment.substring(main.end())
                + "\nvoid main() { mmdWaterOriginalMain(); if (MmdWaterCapture) { mmdStore("
                + output.group(1) + "); } }\n";
        return result.replaceFirst("#version[^\\r\\n]*", "#version 430 core");
    }
}
