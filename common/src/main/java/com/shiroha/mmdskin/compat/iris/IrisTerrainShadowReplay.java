package com.shiroha.mmdskin.compat.iris;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Replay verified BSL/Complementary terrain shadow programs before any entities are submitted. */
final class IrisTerrainShadowReplay {
    private static final Pattern UNSAFE = Pattern.compile(
            "\\b(?:image\\w*|atomic\\w*|buffer|shadowtex\\d*|shadowcolor\\d*|gl_FragDepth)\\b|\\blayout\\s*\\(");

    private IrisTerrainShadowReplay() {}

    static boolean supports(String fragment, String vertex) {
        return colorTargetCount(fragment, vertex) != 0;
    }

    static int colorTargetCount(String fragment, String vertex) {
        if (fragment == null || vertex == null) return 0;
        String clean = fragment.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        String cleanVertex = vertex.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        String code = clean.replaceAll("\\s+", "");
        boolean bsl = code.contains("albedo.rgb=mix(vec3(1.0),albedo.rgb,1.0-pow(1.0-albedo.a,1.5))")
                && code.contains("albedo.rgb*=1.0-pow(albedo.a,96.0)")
                && code.contains("gl_FragData[0]=albedo;");
        boolean naturalShadow = code.contains("voidDoNaturalShadowCalculation(inoutvec4color1,inoutvec4color2)")
                && code.contains("color1.rgb*=glColor.rgb;")
                && code.contains("color1.rgb=mix(vec3(1.0),color1.rgb,pow(color1.a,(1.0-color1.a)*0.5)*1.05);")
                && code.contains("color1.rgb*=1.0-pow(color1.a,64.0);")
                && code.contains("color2.rgb=normalize(color1.rgb)*0.5;")
                && (code.contains("gl_FragData[0]=color1;")
                || code.contains("gl_FragData[0]=vec4(saturateColors(color1.rgb,"));
        boolean currentScale = code.contains("color1.rgb*=0.1;");
        boolean legacyScale = code.contains("color1.rgb*=0.2;");
        boolean complementaryNatural = naturalShadow && currentScale != legacyScale;
        if (!bsl && !complementaryNatural) return 0;
        String effects = clean + "\n" + cleanVertex;
        if (complementaryNatural) {
            // Complementary shares this uniform header with shaders that read the
            // shadow history. Remove only declarations of the four known samplers;
            // any remaining reference is a real replay feedback read and is rejected.
            effects = effects.replaceAll(
                    "\\buniform\\s+(?:sampler2D\\s+shadowcolor[01]|sampler2DShadow\\s+shadowtex[01])\\s*;", "");
        }
        if (UNSAFE.matcher(effects).find()) return 0;
        var outputs = Pattern.compile("gl_FragData\\[(\\d+)]\\s*=\\s*([^;]+);").matcher(code);
        int count = 0, matched = 0;
        boolean hasSaturate = code.contains("vec3saturateColors(vec3col,floatsaturationMult)");
        while (outputs.find()) {
            matched++;
            int slot = Integer.parseInt(outputs.group(1));
            if (slot > (bsl ? 0 : 1)) return 0;
            String value = outputs.group(2);
            boolean valid = bsl ? value.equals("albedo")
                    : slot == 0 ? (value.equals("color1")
                    || (hasSaturate && value.matches("vec4\\(saturateColors\\(color1\\.rgb,[0-9.]+\\),color1\\.a\\)")))
                    : (value.equals("color2")
                    || (hasSaturate && value.matches("vec4\\(saturateColors\\(color2\\.rgb,pow\\([0-9.]+,0\\.8\\)\\),color2\\.a\\)")));
            if (!valid) return 0;
            count = Math.max(count, slot + 1);
        }
        if (cleanVertex.contains("gl_FragData")
                || matched != code.split("gl_FragData", -1).length - 1) return 0;
        return count;
    }

    static int[] effectiveShadowDrawBuffers(Object directives) throws ReflectiveOperationException {
        boolean unknown = (boolean) directives.getClass().getMethod("hasUnknownDrawBuffers")
                .invoke(directives);
        if (unknown) {
            // IrisRenderingPipeline creates the read/write shadow framebuffer with
            // both targets when ProgramDirectives cannot determine DRAWBUFFERS.
            return new int[]{0, 1};
        }
        return (int[]) directives.getClass().getMethod("getDrawBuffers").invoke(directives);
    }

    static boolean shadowDrawBuffersCover(int[] drawBuffers, int writtenTargets) {
        if (drawBuffers == null || writtenTargets < 1 || writtenTargets > 2) return false;
        if (!Arrays.equals(drawBuffers, new int[]{0}) && !Arrays.equals(drawBuffers, new int[]{0, 1}))
            return false;
        return writtenTargets <= drawBuffers.length;
    }

    static int verifiedReplayColorTargets(int[] drawBuffers, int writtenTargets) {
        return shadowDrawBuffersCover(drawBuffers, writtenTargets) ? drawBuffers.length : 0;
    }

    static boolean capture(Object owner, Object level, Camera camera, IrisShadowSource.Source source,
                           int opaqueFbo, int format) throws ReflectiveOperationException {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43) return unavailable("OpenGL 4.3 unavailable");
        Class<?> type = owner.getClass();
        var translucent = type.getDeclaredField("shouldRenderTranslucent");
        translucent.setAccessible(true);
        var images = type.getDeclaredField("packHasVoxelization");
        images.setAccessible(true);
        if (!translucent.getBoolean(owner)) return unavailable("Iris translucent terrain shadows disabled");
        if (images.getBoolean(owner)) return unavailable("Iris reports image/voxelization side effects");
        if (!currentPackSupported()) return unavailable(TerrainShadowFrame.current().packStatus);
        var targetsField = type.getDeclaredField("targets");
        targetsField.setAccessible(true);
        Object targets = targetsField.get(owner);
        int count = TerrainShadowFrame.current().replayColorTargets;
        int availableColors = (int) targets.getClass().getMethod("getNumColorTextures").invoke(targets);
        if (availableColors < count)
            return unavailable("shadow renderer exposes " + availableColors
                    + " color targets for a " + count + "-target program");
        Method flipped = targets.getClass().getMethod("isFlipped", int.class);
        Method colorTexture = targets.getClass().getMethod("getColorTextureId", int.class);
        Method colorFormat = targets.getClass().getMethod("getColorTextureFormat", int.class);
        int[] colors = new int[count];
        int[] formats = new int[count];
        for (int i = 0; i < count; i++) {
            if ((boolean) flipped.invoke(targets, i))
                return unavailable("shadowcolor" + i + " is flipped");
            colors[i] = (int) colorTexture.invoke(targets, i);
            formats[i] = declaredColorFormat(colorFormat.invoke(targets, i));
            if (!supportedColorFormat(formats[i]))
                return unavailable("unsupported shadowcolor" + i + " format " + formats[i]);
        }
        Class<?> accessor = Class.forName("net.irisshaders.iris.mixin.LevelRendererAccessor");
        Method layer = Arrays.stream(accessor.getMethods())
                .filter(m -> m.getName().equals("invokeRenderSectionLayer")
                        && Arrays.equals(m.getParameterTypes(), new Class<?>[]{
                        RenderType.class, double.class, double.class, double.class, Matrix4f.class, Matrix4f.class}))
                .findFirst().orElseThrow();
        Matrix4f view = (Matrix4f) type.getField("MODELVIEW").get(null);
        Matrix4f projection = (Matrix4f) type.getField("PROJECTION").get(null);
        var position = camera.getPosition();
        // Native RenderType calls update Blaze3D caches; the unified state restores both layers.
        try (var state = new ShadowReplayGlState(true)) {
            boolean captured = IrisTerrainTransmission.capture(
                    source.framebuffer(), colors, formats, opaqueFbo, source.resolution(), format, () -> {
                try {
                    layer.invoke(level, RenderType.translucent(), position.x, position.y, position.z, view, projection);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Iris translucent terrain replay failed", e);
                }
            });
            TerrainShadowFrame.current().replayStatus = IrisTerrainTransmission.status();
            return captured;
        }
    }

    private static boolean currentPackSupported() throws ReflectiveOperationException {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        Object pack = ((Optional<?>) iris.getMethod("getCurrentPack").invoke(null)).orElse(null);
        if (pack == null) {
            frame.packStatus = "no active shader pack";
            return false;
        }
        Object dimension = iris.getMethod("getCurrentDimension").invoke(null);
        Object set = pack.getClass().getMethod("getProgramSet", dimension.getClass()).invoke(pack, dimension);
        if (set == frame.replayProgramSet) return frame.replaySupported;
        frame.replayProgramSet = set;
        frame.replaySupported = false;
        frame.replayColorTargets = 0;
        frame.packStatus = "unsupported shader pack shadow protocol";
        Class<?> ids = Class.forName("net.irisshaders.iris.shaderpack.loading.ProgramId");
        // ShadowRenderer resolves ShadowSolid and falls back to Shadow. Only the
        // fallback path is verified; a direct solid/water override must not reuse it.
        if (hasDedicatedShadowOverride(set, ids)) {
            frame.packStatus = "unsupported shader pack with dedicated solid/water shadow overrides";
            return false;
        }
        Object shadow = shadowProgram(set, ids);
        if (shadow == null) { frame.packStatus = "no shadow program"; return false; }
        Object[] compute = (Object[]) set.getClass().getMethod("getShadowCompute").invoke(set);
        if (compute != null && Arrays.stream(compute).anyMatch(java.util.Objects::nonNull)) {
            frame.packStatus = "shadow compute programs cannot be replayed";
            return false;
        }
        for (String stage : new String[]{"getGeometrySource", "getTessControlSource", "getTessEvalSource"})
            if (((Optional<?>) shadow.getClass().getMethod(stage).invoke(shadow)).isPresent()) {
                frame.packStatus = "unsupported shadow stage: " + stage;
                return false;
            }
        Object directives = shadow.getClass().getMethod("getDirectives").invoke(shadow);
        int[] buffers = effectiveShadowDrawBuffers(directives);
        String fragment = ((Optional<?>) shadow.getClass().getMethod("getFragmentSource").invoke(shadow)).map(Object::toString).orElse("");
        String vertex = ((Optional<?>) shadow.getClass().getMethod("getVertexSource").invoke(shadow)).map(Object::toString).orElse("");
        int count = colorTargetCount(fragment, vertex);
        int replayTargets = verifiedReplayColorTargets(buffers, count);
        if (count == 0 || replayTargets == 0) {
            frame.packStatus = "unsupported shadow output contract: written=" + count
                    + ", host=" + Arrays.toString(buffers);
            return false;
        }
        // Missing fragment outputs still leave enabled host attachments undefined.
        frame.replayColorTargets = replayTargets;
        return frame.replaySupported = true;
    }

    static Object shadowProgram(Object programSet, Class<?> programIds) throws ReflectiveOperationException {
        return program(programSet, programIds, "Shadow");
    }

    static boolean hasDedicatedShadowOverride(Object programSet, Class<?> programIds)
            throws ReflectiveOperationException {
        return program(programSet, programIds, "ShadowSolid") != null
                || program(programSet, programIds, "ShadowWater") != null;
    }

    static Object program(Object programSet, Class<?> programIds, String programName)
            throws ReflectiveOperationException {
        Object requestedId = null;
        Object[] constants = programIds.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals(programName)) {
                    requestedId = constant;
                    break;
                }
            }
        }
        if (requestedId == null) throw new IllegalArgumentException("ProgramId." + programName + " is unavailable");
        return ((Optional<?>) programSet.getClass().getMethod("get", programIds)
                .invoke(programSet, requestedId)).orElse(null);
    }

    private static int declaredColorFormat(Object format) throws ReflectiveOperationException {
        if (format == null) return 0;
        return (int) format.getClass().getMethod("getGlFormat").invoke(format);
    }

    private static boolean supportedColorFormat(int format) {
        return format == org.lwjgl.opengl.GL46C.GL_RGBA
                || format == org.lwjgl.opengl.GL46C.GL_RGBA8
                || format == org.lwjgl.opengl.GL46C.GL_RGBA16F
                || format == org.lwjgl.opengl.GL46C.GL_RGBA32F;
    }

    static String status() {
        return TerrainShadowFrame.current().replayStatus;
    }

    private static boolean unavailable(String reason) {
        TerrainShadowFrame.current().replayStatus = reason;
        return false;
    }

    static void reset() {
        TerrainShadowFrame.current().resetReplayState();
    }
}
