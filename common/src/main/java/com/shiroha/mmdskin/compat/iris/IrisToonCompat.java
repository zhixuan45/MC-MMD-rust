package com.shiroha.mmdskin.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** CPU、GPU 和描边共用输出策略；未知契约仅回退当前 pass。 */
public final class IrisToonCompat {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(12000);
    private static final Set<String> NOTIFIED = new HashSet<>();
    private static boolean primaryColorOutput;
    private static boolean stablePrimaryColorOutput;
    private static ShaderInstance preparedEntityShader;
    private static String targetDiagnostic = "not prepared";
    private static final Set<String> TARGET_DIAGNOSTICS = new HashSet<>();

    private IrisToonCompat() {}

    public static boolean prepare(ToonShaderBase shader) {
        return prepare(shader, false);
    }

    public static boolean prepare(ToonShaderBase shader, boolean worldScene) {
        ShaderInstance current = RenderSystem.getShader();
        boolean shaderPack = IrisCompat.isIrisShaderActive() && !IrisCompat.isRenderingShadows();
        var selection = shaderPack && worldScene ? IrisWorldTransparencyTarget.entityShader(current)
                : new IrisWorldTransparencyTarget.ShaderResult(current, "current scene shader");
        ShaderInstance source = selection.shader();
        preparedEntityShader = IrisCompat.isIrisProgram(source) ? source : null;
        IrisToonMetadata.Result result = IrisToonMetadata.read(source);
        ToonOutputProfile profile = result.profile();
        primaryColorOutput = false;
        stablePrimaryColorOutput = false;
        var target = shaderPack ? IrisWorldTransparencyTarget.inspect() : null;
        targetDiagnostic = "sourceStatus=" + (preparedEntityShader == null ? "NON_IRIS_SHADER" : "IRIS_SHADER")
                + ", shader=" + shaderDescription(source) + ", selection=" + selection.detail()
                + ", profile=" + profile.id() + ", target=" + target;
        if (!profile.isSupported()) {
            targetDiagnostic += ", outputVerified=false, reason=" + profile.reason();
            reportTarget(shaderPack, current);
            notifyOnce(result, profile.reason());
            return false;
        }
        boolean selected = selectCurrentPassProfile(profile, shader::selectOutputProfile);
        if (!selected) notifyOnce(result, "Toon 兼容程序编译失败");
        primaryColorOutput = selected && profile.colorOutputSlot() == 0;
        stablePrimaryColorOutput = primaryColorOutput
                && preparedEntityShader != null && target != null && target.stable();
        targetDiagnostic += ", outputVerified=" + primaryColorOutput + ", stablePrimary=" + stablePrimaryColorOutput;
        reportTarget(shaderPack, current);
        return selected;
    }

    static boolean selectCurrentPassProfile(ToonOutputProfile profile, Predicate<ToonOutputProfile> selector) {
        return profile.isSupported() && selector.test(profile);
    }

    static String notificationKey(String pack, String program, String reason) {
        return pack + "|" + program + "|" + reason;
    }

    private static void notifyOnce(IrisToonMetadata.Result result, String reason) {
        String key = notificationKey(result.packName(), result.programName(), reason);
        if (!NOTIFIED.add(key)) return;
        LOGGER.warn("[Iris/Toon] pack={}, program={}, reason={}, action=current_pass_standard; Toon 设置未改",
                result.packName(), result.programName(), reason);
        Minecraft minecraft = Minecraft.getInstance();
        Component message = Component.literal(result.packName() + "：" + reason
                + "。当前 pass 使用标准渲染，Toon 设置未改。");
        minecraft.getToasts().addToast(SystemToast.multiline(minecraft, TOAST_ID,
                Component.literal("MMD Toon 光影兼容提示"), message));
        // 聊天记录保留原因，提示消失后仍可查看。
        if (minecraft.player != null) {
            minecraft.gui.getChat().addMessage(Component.literal("[MMD] ").append(message));
        }
    }

    /** True only when the verified Iris program writes colortex0 as GLSL output 0. */
    public static boolean hasPrimaryColorOutput() { return primaryColorOutput; }

    /** The verified entity colortex0 remains the input to the later composite stage. */
    public static boolean hasStablePrimaryColorOutput() { return stablePrimaryColorOutput; }

    public static ShaderInstance preparedEntityShader() { return preparedEntityShader; }

    public static String targetDiagnostic() { return targetDiagnostic; }

    private static String shaderDescription(ShaderInstance shader) {
        return shader == null ? "null" : shader.getClass().getName() + "/" + shader.getName();
    }

    private static void reportTarget(boolean shaderPack, ShaderInstance current) {
        if (shaderPack && TARGET_DIAGNOSTICS.add(targetDiagnostic))
            LOGGER.info("[MMD transparency] Iris target verification: currentShader={}, {}",
                    shaderDescription(current), targetDiagnostic);
    }

    public static void resetTransparencyVerification() {
        primaryColorOutput = false;
        stablePrimaryColorOutput = false;
        preparedEntityShader = null;
        targetDiagnostic = "not prepared";
        TARGET_DIAGNOSTICS.clear();
        IrisWorldTransparencyTarget.reset();
    }

    static void reset() {
        resetTransparencyVerification();
        NOTIFIED.clear();
        IrisToonMetadata.reset();
    }
}
