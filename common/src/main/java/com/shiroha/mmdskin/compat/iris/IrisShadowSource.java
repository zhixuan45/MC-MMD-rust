package com.shiroha.mmdskin.compat.iris;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads the renderer-owned shadow targets; a shader clear may already have rebound the main framebuffer. */
final class IrisShadowSource {
    record Source(int framebuffer, int texture, int resolution) {}
    private final Class<?> rendererType;
    private final Field targets;
    private final Method sourceFramebuffer, depthTexture, resolution, framebufferId, textureId;

    IrisShadowSource(Class<?> rendererType) throws ReflectiveOperationException {
        this.rendererType = rendererType;
        targets = rendererType.getDeclaredField("targets");
        targets.setAccessible(true);
        Class<?> targetType = targets.getType();
        sourceFramebuffer = targetType.getMethod("getDepthSourceFb");
        depthTexture = targetType.getMethod("getDepthTexture");
        resolution = targetType.getMethod("getResolution");
        framebufferId = sourceFramebuffer.getReturnType().getMethod("getId");
        textureId = depthTexture.getReturnType().getMethod("getTextureId");
    }

    boolean matches(Object renderer) { return renderer.getClass() == rendererType; }

    Source read(Object renderer) throws ReflectiveOperationException {
        Object target = targets.get(renderer);
        return new Source((Integer) framebufferId.invoke(sourceFramebuffer.invoke(target)),
                (Integer) textureId.invoke(depthTexture.invoke(target)), (Integer) resolution.invoke(target));
    }
}
