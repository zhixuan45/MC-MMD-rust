package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.render.port.IrisBackend;
import com.shiroha.mmdskin.render.pipeline.LightingHelper;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.function.BooleanSupplier;

/**
 * Keeps Iris runtime reflection and terrain resource state inside the compatibility layer.
 */
public final class IrisBackendAdapter implements IrisBackend {
    private static final IrisBackendAdapter INSTANCE = new IrisBackendAdapter();

    private IrisBackendAdapter() {}

    public static IrisBackendAdapter instance() {
        return INSTANCE;
    }

    @Override
    public TerrainShadowTextures terrainShadowTextures() {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        return new TerrainShadowTextures(
                frame.captureValid() ? frame.texture : 0,
                frame.transmissionValid() ? frame.transmissionDepth : 0,
                frame.transmissionValid() ? frame.transmissionColor : 0);
    }

    @Override public boolean isShaderPackActive() { return IrisCompat.isIrisShaderActive(); }
    @Override public boolean isShadowPass() { return IrisCompat.isRenderingShadows(); }
    @Override public boolean isIrisProgram(ShaderInstance shader) { return IrisCompat.isIrisProgram(shader); }
    @Override public int entityColorOutputSlot(ShaderInstance shader) { return IrisCompat.entityColorOutputSlot(shader); }

    @Override
    public void clearProgram(ShaderInstance shader, int shadowDraw, int shadowRead) {
        IrisCompat.clearProgram(shader, shadowDraw, shadowRead);
    }

    @Override
    public boolean prepareToon(ToonShaderBase shader, boolean worldScene) {
        return IrisToonCompat.prepare(shader, worldScene);
    }

    @Override public ShaderInstance preparedEntityShader() { return IrisToonCompat.preparedEntityShader(); }
    @Override public boolean hasPrimaryColorOutput() { return IrisToonCompat.hasPrimaryColorOutput(); }
    @Override public boolean hasStablePrimaryColorOutput() { return IrisToonCompat.hasStablePrimaryColorOutput(); }
    @Override public BooleanSupplier worldColorTarget() { return IrisToonCompat.worldColorTarget(); }
    @Override public String targetDiagnostic() { return IrisToonCompat.targetDiagnostic(); }
    @Override public void resetTransparencyVerification() { IrisToonCompat.resetTransparencyVerification(); }

    @Override
    public void recordEntityDiagnostics(ShaderInstance shader, LightingHelper.LightData light) {
        IrisEntityDiagnostics.record(shader, light);
    }

    @Override
    public void warnTerrainShadowUnavailable() {
        IrisTerrainShadow.warnUnavailable();
    }
}
