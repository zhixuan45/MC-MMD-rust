package com.shiroha.mmdskin.compat.iris;

import com.mojang.blaze3d.vertex.PoseStack;
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
        boolean modern = code.contains("voidDoNaturalShadowCalculation(inoutvec4color1,inoutvec4color2)")
                && code.contains("color1.rgb*=glColor.rgb;")
                && code.contains("color1.rgb=mix(vec3(1.0),color1.rgb,pow(color1.a,(1.0-color1.a)*0.5)*1.05);")
                && code.contains("color1.rgb*=1.0-pow(color1.a,64.0);")
                && code.contains("color1.rgb*=0.1;")
                && code.contains("color2.rgb=normalize(color1.rgb)*0.5;")
                && code.contains("gl_FragData[0]=color1;");
        if (!bsl && !modern) return 0;
        String effects = clean + "\n" + cleanVertex;
        if (modern) {
            // Complementary's shared uniform header declares shadow samplers even
            // in shadow programs. Only unused declarations are harmless; every
            // remaining reference would read attachments that replay overwrites.
            effects = effects.replaceAll(
                    "\\buniform\\s+(?:sampler2D\\s+shadowcolor[01]|sampler2DShadow\\s+shadowtex[01])\\s*;", "");
        }
        if (UNSAFE.matcher(effects).find()) return 0;
        var outputs = Pattern.compile("gl_FragData\\[(\\d+)]\\s*=\\s*([^;]+);").matcher(code);
        int count = 0, matched = 0;
        while (outputs.find()) {
            matched++;
            int slot = Integer.parseInt(outputs.group(1));
            String expected = bsl ? "albedo" : slot == 0 ? "color1" : "color2";
            if (slot > (bsl ? 0 : 1) || !outputs.group(2).equals(expected)) return 0;
            count = Math.max(count, slot + 1);
        }
        if (cleanVertex.contains("gl_FragData")
                || matched != code.split("gl_FragData", -1).length - 1) return 0;
        return count;
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
        int[] colors = new int[TerrainShadowFrame.current().replayColorTargets];
        for (int i = 0; i < colors.length; i++) {
            if ((boolean) targets.getClass().getMethod("isFlipped", int.class).invoke(targets, i))
                return unavailable("shadowcolor" + i + " is flipped");
            colors[i] = (int) targets.getClass().getMethod("getColorTextureId", int.class).invoke(targets, i);
        }
        Class<?> accessor = Class.forName("net.irisshaders.iris.mixin.LevelRendererAccessor");
        Method layer = Arrays.stream(accessor.getMethods())
                .filter(m -> m.getName().equals("invokeRenderChunkLayer")
                        && Arrays.equals(m.getParameterTypes(), new Class<?>[]{
                        RenderType.class, PoseStack.class, double.class, double.class, double.class, Matrix4f.class}))
                .findFirst().orElseThrow();
        Matrix4f view = (Matrix4f) type.getField("MODELVIEW").get(null);
        Matrix4f projection = (Matrix4f) type.getField("PROJECTION").get(null);
        PoseStack shadowPose = new PoseStack();
        // renderChunkLayer 从 PoseStack 读取 MODEL_VIEW_MATRIX；这里复用 Oculus 当帧阴影视图。
        shadowPose.last().pose().set(view);
        var position = camera.getPosition();
        // Native RenderType calls update Blaze3D caches; the unified state restores both layers.
        try (var state = new ShadowReplayGlState(true)) {
            boolean captured = IrisTerrainTransmission.capture(
                    source.framebuffer(), colors, opaqueFbo, source.resolution(), format, () -> {
                try {
                    layer.invoke(level, RenderType.translucent(), shadowPose,
                            position.x, position.y, position.z, projection);
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
        Object shadow = shadowProgram(set);
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
        int[] buffers = (int[]) directives.getClass().getMethod("getDrawBuffers").invoke(directives);
        String fragment = ((Optional<?>) shadow.getClass().getMethod("getFragmentSource").invoke(shadow)).map(Object::toString).orElse("");
        String vertex = ((Optional<?>) shadow.getClass().getMethod("getVertexSource").invoke(shadow)).map(Object::toString).orElse("");
        int count = colorTargetCount(fragment, vertex);
        int[] expected = count == 2 ? new int[]{0, 1} : new int[]{0};
        if (count == 0 || !Arrays.equals(buffers, expected)) {
            frame.packStatus = "unsupported shadow output contract: " + Arrays.toString(buffers);
            return false;
        }
        frame.replayColorTargets = count;
        return frame.replaySupported = true;
    }

    static Object shadowProgram(Object programSet) throws ReflectiveOperationException {
        return ((Optional<?>) programSet.getClass().getMethod("getShadow").invoke(programSet)).orElse(null);
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
