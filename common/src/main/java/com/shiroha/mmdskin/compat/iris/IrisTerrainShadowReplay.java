package com.shiroha.mmdskin.compat.iris;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Replay only the verified single-output BSL shadow protocol, before any entities are submitted. */
final class IrisTerrainShadowReplay {
    private static final Pattern UNSAFE = Pattern.compile(
            "\\b(?:image\\w*|atomic\\w*|buffer|shadowtex\\d*|shadowcolor\\d*|gl_FragDepth)\\b|\\blayout\\s*\\(");

    private IrisTerrainShadowReplay() {}

    static boolean supports(String fragment, String vertex) {
        String code = fragment.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "").replaceAll("\\s+", "");
        return code.contains("albedo.rgb=mix(vec3(1.0),albedo.rgb,1.0-pow(1.0-albedo.a,1.5))")
                && code.contains("albedo.rgb*=1.0-pow(albedo.a,96.0)")
                && code.contains("gl_FragData[0]=albedo;")
                && !Pattern.compile("gl_FragData\\[[1-9]").matcher(code + vertex.replaceAll("\\s+", "")).find()
                && !UNSAFE.matcher(fragment + "\n" + vertex).find();
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
        if ((boolean) targets.getClass().getMethod("isFlipped", int.class).invoke(targets, 0))
            return unavailable("shadowcolor0 is flipped");
        int color = (int) targets.getClass().getMethod("getColorTextureId", int.class).invoke(targets, 0);
        Class<?> accessor = Class.forName("net.irisshaders.iris.mixin.LevelRendererAccessor");
        Method layer = Arrays.stream(accessor.getMethods())
                .filter(m -> m.getName().equals("invokeRenderSectionLayer") && m.getParameterCount() == 6)
                .findFirst().orElseThrow();
        Object view = type.getField("MODELVIEW").get(null), projection = type.getField("PROJECTION").get(null);
        var position = camera.getPosition();
        // Native RenderType calls update Blaze3D caches; the unified state restores both layers.
        try (var state = new ShadowReplayGlState(true)) {
            boolean captured = IrisTerrainTransmission.capture(
                    source.framebuffer(), color, opaqueFbo, source.resolution(), format, () -> {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
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
        frame.packStatus = "unsupported shader pack shadow protocol";
        Class<? extends Enum> ids = (Class<? extends Enum>) Class.forName("net.irisshaders.iris.shaderpack.loading.ProgramId");
        Class<?> resolverType = Class.forName("net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver");
        Object resolver = resolverType.getConstructor(set.getClass()).newInstance(set);
        Object shadow = ((Optional<?>) resolverType.getMethod("resolve", ids)
                .invoke(resolver, Enum.valueOf(ids, "ShadowWater"))).orElse(null);
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
        if (!Arrays.equals(buffers, new int[]{0})) {
            frame.packStatus = "unsupported shadow draw buffers: " + Arrays.toString(buffers);
            return false;
        }
        String fragment = ((Optional<?>) shadow.getClass().getMethod("getFragmentSource").invoke(shadow)).map(Object::toString).orElse("");
        String vertex = ((Optional<?>) shadow.getClass().getMethod("getVertexSource").invoke(shadow)).map(Object::toString).orElse("");
        return frame.replaySupported = supports(fragment, vertex);
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
