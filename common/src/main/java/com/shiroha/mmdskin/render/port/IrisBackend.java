package com.shiroha.mmdskin.render.port;

import com.shiroha.mmdskin.render.pipeline.LightingHelper;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.function.BooleanSupplier;

/**
 * Render-facing view of the optional Iris integration.
 */
public interface IrisBackend {
    TerrainShadowTextures terrainShadowTextures();

    boolean isShaderPackActive();

    boolean isShadowPass();

    boolean isIrisProgram(ShaderInstance shader);

    int entityColorOutputSlot(ShaderInstance shader);

    void clearProgram(ShaderInstance shader, int shadowDraw, int shadowRead);

    boolean prepareToon(ToonShaderBase shader, boolean worldScene);

    ShaderInstance preparedEntityShader();

    boolean hasPrimaryColorOutput();

    boolean hasStablePrimaryColorOutput();

    BooleanSupplier worldColorTarget();

    String targetDiagnostic();

    void resetTransparencyVerification();

    void recordEntityDiagnostics(ShaderInstance shader, LightingHelper.LightData light);

    void warnTerrainShadowUnavailable();

    record TerrainShadowTextures(int depth, int transmissionDepth, int transmissionColor) {
        public static final TerrainShadowTextures NONE = new TerrainShadowTextures(0, 0, 0);

        public boolean hasTransmission() {
            return transmissionDepth != 0 && transmissionColor != 0;
        }
    }

    static IrisBackend current() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final IrisBackend INSTANCE = load();

        private Holder() {}

        private static IrisBackend load() {
            try {
                Class<?> type = Class.forName(
                        "com.shiroha.mmdskin.compat.iris.IrisBackendAdapter");
                return (IrisBackend) type.getMethod("instance").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return NoOp.INSTANCE;
            }
        }
    }

    final class NoOp implements IrisBackend {
        private static final NoOp INSTANCE = new NoOp();

        private NoOp() {}

        @Override public TerrainShadowTextures terrainShadowTextures() {
            return TerrainShadowTextures.NONE;
        }

        @Override public boolean isShaderPackActive() { return false; }
        @Override public boolean isShadowPass() { return false; }
        @Override public boolean isIrisProgram(ShaderInstance shader) { return false; }
        @Override public int entityColorOutputSlot(ShaderInstance shader) { return 0; }

        @Override
        public void clearProgram(ShaderInstance shader, int shadowDraw, int shadowRead) {
            if (shader != null) shader.clear();
        }

        @Override public boolean prepareToon(ToonShaderBase shader, boolean worldScene) { return false; }
        @Override public ShaderInstance preparedEntityShader() { return null; }
        @Override public boolean hasPrimaryColorOutput() { return false; }
        @Override public boolean hasStablePrimaryColorOutput() { return false; }
        @Override public BooleanSupplier worldColorTarget() { return null; }
        @Override public String targetDiagnostic() { return "Iris backend unavailable"; }
        @Override public void resetTransparencyVerification() {}
        @Override public void recordEntityDiagnostics(ShaderInstance shader, LightingHelper.LightData light) {}
        @Override public void warnTerrainShadowUnavailable() {}
    }
}
