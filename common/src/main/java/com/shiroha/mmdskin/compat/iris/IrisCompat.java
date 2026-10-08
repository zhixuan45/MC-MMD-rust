package com.shiroha.mmdskin.compat.iris;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Iris 光影模组运行时兼容检测。
 */
public class IrisCompat {
    private static final Logger logger = LogManager.getLogger();

    private static volatile Boolean irisPresent = null;
    private static Method isShaderPackInUseMethod = null;
    private static Object irisApiInstance = null;

    private static volatile boolean shadowStateDetected = false;
    private static Method areShadowsBeingRenderedMethod = null;

    /** 背包的原版程序不能按全局光影开关判断。 */
    public static boolean isIrisProgram(net.minecraft.client.renderer.ShaderInstance shader) {
        return shader != null && shader.getClass().getName().startsWith("net.irisshaders.iris.");
    }

    /** Iris clear 会绑定主目标，阴影通道须恢复原目标。 */
    public static void clearProgram(net.minecraft.client.renderer.ShaderInstance shader,
                                    int shadowDraw, int shadowRead) {
        if (!isIrisProgram(shader)) return;
        try {
            shader.clear();
        } finally {
            if (shadowDraw >= 0) {
                com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                        org.lwjgl.opengl.GL46C.GL_DRAW_FRAMEBUFFER, shadowDraw);
                com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                        org.lwjgl.opengl.GL46C.GL_READ_FRAMEBUFFER, shadowRead);
            }
        }
    }

    public static boolean isIrisShaderActive() {
        if (irisPresent == null) {
            detectIris();
        }
        if (!irisPresent) return false;

        try {
            return (Boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
        } catch (Exception e) {
            return false;
        }
    }

    private static void detectIris() {
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstanceMethod = irisApiClass.getMethod("getInstance");
            irisApiInstance = getInstanceMethod.invoke(null);
            isShaderPackInUseMethod = irisApiClass.getMethod("isShaderPackInUse");
            irisPresent = true;
        } catch (ClassNotFoundException e) {
            irisPresent = false;
        } catch (Exception e) {
            irisPresent = false;
            logger.warn("[IrisCompat] Iris API 检测异常", e);
        }
    }

    public static boolean isRenderingShadows() {
        if (!shadowStateDetected) {
            detectShadowState();
        }
        if (areShadowsBeingRenderedMethod == null) return false;

        try {
            return (Boolean) areShadowsBeingRenderedMethod.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    private static void detectShadowState() {
        shadowStateDetected = true;

        String[] classNames = {
            "net.irisshaders.iris.shadows.ShadowRenderingState",
            "net.coderbot.iris.shadows.ShadowRenderingState"
        };
        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);
                areShadowsBeingRenderedMethod = clazz.getMethod("areShadowsCurrentlyBeingRendered");
                return;
            } catch (Exception ignored) {}
        }
    }

    public static void reset() {
        IrisToonCompat.reset();
        IrisEntityDiagnostics.reset();
        irisPresent = null;
        isShaderPackInUseMethod = null;
        irisApiInstance = null;
        shadowStateDetected = false;
        areShadowsBeingRenderedMethod = null;
    }
}
