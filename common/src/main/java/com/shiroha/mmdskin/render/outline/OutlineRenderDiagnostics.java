package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.render.shader.ToonConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;

/** 描边问题采样日志；仅在调试位 bit8 开启时读取 GL 状态。 */
public final class OutlineRenderDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int FLAG = 8;
    private static final long INTERVAL_NANOS = 2_000_000_000L;
    private static final RateLimiter LIMITER = new RateLimiter(512);

    private OutlineRenderDiagnostics() { }

    public static boolean shouldSample(int flags, long modelHandle, RenderScene scene, long nowNanos) {
        if ((flags & FLAG) == 0) return false;
        // 光影的阴影与主场景分别采样，避免早绘制的阴影通道占用全部窗口。
        String sampleScene = sceneKey(scene) + ":shadow=" + IrisCompat.isRenderingShadows();
        return LIMITER.shouldSample(true, modelHandle, sampleScene, nowNanos);
    }

    public static void log(long modelHandle, String modelName, String backend, RenderScene scene,
                           boolean toonSelected, FloatBuffer projection, FloatBuffer modelView,
                           float rootScale, ByteBuffer subMeshes, int subMeshCount,
                           OutlineRenderPass.AlphaResolver alphaResolver) {
        boolean toonEnabled = ToonConfig.getInstance().isEnabled();
        boolean outlineEnabled = ToonConfig.getInstance().isOutlineEnabled();
        boolean shadow = IrisCompat.isRenderingShadows();
        boolean iris = IrisCompat.isIrisShaderActive();
        boolean orthographic = projection != null && isOrthographic(projection);
        float rootWidth = ToonConfig.getInstance().getOutlineWidth();
        float viewScale = modelView == null ? Float.NaN : viewScale(modelView);
        float effectiveWidth = rootWidth;
        if (orthographic) {
            effectiveWidth = Float.isFinite(viewScale) && Float.isFinite(rootScale) && rootScale != 0.0f
                    ? rootWidth * viewScale / Math.abs(rootScale) : 0.0f;
        }
        String outlinePath = !toonSelected ? "skip:toon未选中"
                : !toonEnabled ? "skip:toon关闭"
                : !outlineEnabled ? "skip:outline关闭" : "planned:selected";
        String mainPath = toonSelected ? "toon主渲染"
                : !toonEnabled ? "standard:toon关闭"
                : shadow ? "standard:shadow_pass" : "standard:toon不可用";
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        AlphaSample alphaSamples = sampleAlphas(subMeshes, subMeshCount, alphaResolver);
        double guiScale = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
        LOGGER.info("[MMD诊断][OUTLINE] modelHandle={} modelName={} backend={} scene={} guiScale={} toonEnabled={} toonSelected={} mainPath={} outlineEnabled={} outlinePath={} shadow={} iris={} projection={} widthRoot={} widthViewScale={} widthEffective={} rootScale={} state_phase=before_main_pass depthFunc={} depthMask={} cullEnabled={} cullFace={} outline_depth_func_expected={} sampled_submeshes={}/{} sample_limit=8 alpha_no_texture_sample={} transparent_texture_cutout_not_sampled=true",
                modelHandle, safe(modelName), backend, sceneKey(scene), guiScale, toonEnabled, toonSelected,
                mainPath, outlineEnabled, outlinePath, shadow, iris,
                orthographic ? "orthographic" : "perspective_or_unknown", rootWidth, viewScale,
                effectiveWidth, rootScale, depthFunc, depthMask, cullEnabled, cullFace,
                orthographic ? GL11.GL_LESS : "unchanged", alphaSamples.sampledCount(),
                alphaSamples.totalCount(), alphaSamples.values());
    }

    private static AlphaSample sampleAlphas(ByteBuffer data, int count, OutlineRenderPass.AlphaResolver resolver) {
        if (data == null || resolver == null) return new AlphaSample("unavailable", 0, 0);
        StringBuilder result = new StringBuilder("[");
        int emitted = 0;
        int total = 0;
        for (int i = 0; i < count; i++) {
            int base = i * 20;
            if (data.get(base + 16) == 0) continue;
            total++;
            if (emitted < 8) {
                float alpha = resolver.resolve(data.getInt(base), data.getFloat(base + 12));
                if (emitted > 0) result.append(',');
                result.append(i).append(':').append(String.format(java.util.Locale.ROOT, "%.3f", alpha));
                emitted++;
            }
        }
        return new AlphaSample(result.append(']').toString(), emitted, total);
    }

    private record AlphaSample(String values, int sampledCount, int totalCount) { }

    private static boolean isOrthographic(FloatBuffer matrix) {
        int start = matrix.position();
        return Math.abs(matrix.get(start + 11)) < 0.000001f
                && Math.abs(matrix.get(start + 15) - 1.0f) < 0.000001f;
    }

    private static float viewScale(FloatBuffer matrix) {
        int start = matrix.position();
        float x = matrix.get(start), y = matrix.get(start + 1), z = matrix.get(start + 2);
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static String sceneKey(RenderScene scene) {
        if (scene == null) return "unknown";
        return scene.sceneType() + ":firstPerson=" + scene.isFirstPerson()
                + ":paperDoll=" + scene.isPaperDoll() + ":mirror=" + scene.isMirror();
    }

    private static String safe(String value) {
        if (value == null) return "unknown";
        return value.replace(' ', '_').replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    /** 有界限频表，避免模型反复加载时保留无主键。 */
    // 调用点只在客户端 render 路径，保持此表为单线程状态。
    static final class RateLimiter {
        private final int capacity;
        private final LinkedHashMap<String, Long> lastSamples = new LinkedHashMap<>(16, 0.75f, true);

        RateLimiter(int capacity) { this.capacity = capacity; }

        boolean shouldSample(boolean enabled, long handle, String scene, long nowNanos) {
            if (!enabled) return false;
            String key = handle + "|" + scene;
            Long previous = lastSamples.get(key);
            if (previous != null && nowNanos - previous < INTERVAL_NANOS) return false;
            lastSamples.put(key, nowNanos);
            while (lastSamples.size() > capacity) lastSamples.remove(lastSamples.keySet().iterator().next());
            return true;
        }
    }
}
