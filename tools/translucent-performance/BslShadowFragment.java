package com.shiroha.mmdskin.render.material;

import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTestFunction;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Real pack fragment plus Iris's alpha-test expression; controlled varyings isolate submission. */
final class BslShadowFragment {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"[^\\r\\n]*");

    static String load(String path) throws Exception {
        try (ZipFile zip = new ZipFile(path)) {
            String fragment = JcppProcessor.glslPreprocessSource(include(zip, "shaders/world0/shadow.fsh", 0),
                    List.of(new StringPair("MC_VERSION", "12101"), new StringPair("IS_IRIS", "1")));
            fragment = fragment.replaceFirst("void\\s+main\\s*\\(\\s*\\)", "void mmdPackMain()");
            String alphaTest = new AlphaTest(AlphaTestFunction.GREATER, .1f)
                    .toExpression("gl_FragData[0].a", "AlphaCutoff", "");
            System.out.println("BSL fragment source=" + Path.of(path).getFileName() + "; alpha test=" + alphaTest.trim());
            return fragment + "\nuniform float AlphaCutoff;\nvoid main(){ mmdPackMain();\n" + alphaTest + "\n}\n";
        }
    }

    private static String include(ZipFile zip, String path, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("recursive include: " + path);
        var entry = zip.getEntry(path);
        if (entry == null) throw new IllegalArgumentException("missing include: " + path);
        String source;
        try (var stream = zip.getInputStream(entry)) { source = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        Matcher matcher = INCLUDE.matcher(source);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String target = name.startsWith("/") ? "shaders" + name : path.substring(0, path.lastIndexOf('/') + 1) + name;
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(include(zip, target, depth + 1)));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }
}
