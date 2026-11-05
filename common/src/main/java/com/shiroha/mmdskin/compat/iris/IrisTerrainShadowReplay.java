package com.shiroha.mmdskin.compat.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL46C;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Replay only the verified single-output BSL shadow protocol, before any entities are submitted. */
final class IrisTerrainShadowReplay {
    private static final Pattern UNSAFE = Pattern.compile(
            "\\b(?:image\\w*|atomic\\w*|buffer|shadowtex\\d*|shadowcolor\\d*|gl_FragDepth)\\b|\\blayout\\s*\\(");
    private static Object cachedSet;
    private static boolean supported;

    private IrisTerrainShadowReplay() {}

    static boolean supports(String fragment, String vertex) {
        String code = fragment.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "").replaceAll("\\s+", "");
        return code.contains("albedo.rgb=mix(vec3(1.0),albedo.rgb,1.0-pow(1.0-albedo.a,1.5))")
                && code.contains("albedo.rgb*=1.0-pow(albedo.a,96.0)")
                && code.contains("gl_FragData[0]=albedo;")
                && !Pattern.compile("gl_FragData\\[[1-9]").matcher(code).find()
                && !UNSAFE.matcher(fragment + "\n" + vertex).find();
    }

    static boolean capture(Object owner, Object level, Camera camera, IrisShadowSource.Source source,
                           int opaqueFbo, int format) throws ReflectiveOperationException {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43) return false;
        Class<?> type = owner.getClass();
        var translucent = type.getDeclaredField("shouldRenderTranslucent");
        translucent.setAccessible(true);
        var images = type.getDeclaredField("packHasVoxelization");
        images.setAccessible(true);
        if (!translucent.getBoolean(owner) || images.getBoolean(owner) || !currentPackSupported()) return false;
        var targetsField = type.getDeclaredField("targets");
        targetsField.setAccessible(true);
        Object targets = targetsField.get(owner);
        if ((boolean) targets.getClass().getMethod("isFlipped", int.class).invoke(targets, 0)) return false;
        int color = (int) targets.getClass().getMethod("getColorTextureId", int.class).invoke(targets, 0);
        Class<?> accessor = Class.forName("net.irisshaders.iris.mixin.LevelRendererAccessor");
        Method layer = Arrays.stream(accessor.getMethods())
                .filter(m -> m.getName().equals("invokeRenderSectionLayer") && m.getParameterCount() == 6)
                .findFirst().orElseThrow();
        Object view = type.getField("MODELVIEW").get(null), projection = type.getField("PROJECTION").get(null);
        var position = camera.getPosition();
        // Native RenderType calls update Blaze3D caches. Restore those as well as actual GL state.
        try (var cache = new EngineCacheState()) {
            return IrisTerrainTransmission.capture(source.framebuffer(), color, opaqueFbo, source.resolution(), format, () -> {
                try {
                    layer.invoke(level, RenderType.translucent(), position.x, position.y, position.z, view, projection);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Iris translucent terrain replay failed", e);
                }
            });
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean currentPackSupported() throws ReflectiveOperationException {
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        Object pack = ((Optional<?>) iris.getMethod("getCurrentPack").invoke(null)).orElse(null);
        if (pack == null) return false;
        Object dimension = iris.getMethod("getCurrentDimension").invoke(null);
        Object set = pack.getClass().getMethod("getProgramSet", dimension.getClass()).invoke(pack, dimension);
        if (set == cachedSet) return supported;
        cachedSet = set;
        supported = false;
        Class<? extends Enum> ids = (Class<? extends Enum>) Class.forName("net.irisshaders.iris.shaderpack.loading.ProgramId");
        Class<?> resolverType = Class.forName("net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver");
        Object resolver = resolverType.getConstructor(set.getClass()).newInstance(set);
        Object shadow = ((Optional<?>) resolverType.getMethod("resolve", ids)
                .invoke(resolver, Enum.valueOf(ids, "ShadowWater"))).orElse(null);
        if (shadow == null) return false;
        Object[] compute = (Object[]) set.getClass().getMethod("getShadowCompute").invoke(set);
        if (compute != null && Arrays.stream(compute).anyMatch(java.util.Objects::nonNull)) return false;
        for (String stage : new String[]{"getGeometrySource", "getTessControlSource", "getTessEvalSource"})
            if (((Optional<?>) shadow.getClass().getMethod(stage).invoke(shadow)).isPresent()) return false;
        Object directives = shadow.getClass().getMethod("getDirectives").invoke(shadow);
        int[] buffers = (int[]) directives.getClass().getMethod("getDrawBuffers").invoke(directives);
        if (!Arrays.equals(buffers, new int[]{0})) return false;
        String fragment = ((Optional<?>) shadow.getClass().getMethod("getFragmentSource").invoke(shadow)).map(Object::toString).orElse("");
        String vertex = ((Optional<?>) shadow.getClass().getMethod("getVertexSource").invoke(shadow)).map(Object::toString).orElse("");
        return supported = supports(fragment, vertex);
    }

    static void reset() { cachedSet = null; supported = false; }

    private static final class EngineCacheState implements AutoCloseable {
        final net.minecraft.client.renderer.ShaderInstance shader = RenderSystem.getShader();
        final int[] shaderTextures = new int[12];
        final ShadowReplayGlState gl = new ShadowReplayGlState();

        EngineCacheState() {
            for (int i = 0; i < shaderTextures.length; i++) shaderTextures[i] = RenderSystem.getShaderTexture(i);
        }

        @Override public void close() {
            try {
                // Clearing invalidates ShaderInstance/Iris program caches before raw GL restoration.
                var current = RenderSystem.getShader();
                if (current != null) current.clear();
            } finally {
                gl.close();
                restoreCaches();
            }
        }

        private void restoreCaches() {
            RenderSystem.setShader(() -> shader);
            for (int i = 0; i < shaderTextures.length; i++) RenderSystem.setShaderTexture(i, shaderTextures[i]);
            for (int i = 0; i < Math.min(shaderTextures.length, gl.textures.length); i++) {
                GlStateManager._activeTexture(GL46C.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(gl.textures[i]);
            }
            GlStateManager._activeTexture(gl.active);
            if (gl.depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            GlStateManager._depthFunc(gl.depthFunc);
            GlStateManager._depthMask(gl.depthMask);
            if (gl.cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (gl.blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
            GlStateManager._blendFuncSeparate(gl.srcRgb, gl.dstRgb, gl.srcAlpha, gl.dstAlpha);
            GlStateManager._blendEquation(gl.eqRgb);
            GlStateManager._colorMask(gl.colorMask.get(0) != 0, gl.colorMask.get(1) != 0,
                    gl.colorMask.get(2) != 0, gl.colorMask.get(3) != 0);
            // Preserve a distinct alpha equation after synchronizing the engine's RGB cache.
            gl.restoreBlendTargets();
            BufferUploader.invalidate();
        }
    }
}
