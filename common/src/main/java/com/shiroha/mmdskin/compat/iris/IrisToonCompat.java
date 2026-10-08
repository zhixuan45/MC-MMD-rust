package com.shiroha.mmdskin.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/** CPU、GPU 和描边共用输出策略；未知契约只提示。 */
public final class IrisToonCompat {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final SystemToast.SystemToastIds TOAST_ID = SystemToast.SystemToastIds.TUTORIAL_HINT;
    private static final Set<String> NOTIFIED = new HashSet<>();

    private IrisToonCompat() {}

    public static boolean prepare(ToonShaderBase shader) {
        IrisToonMetadata.Result result = IrisToonMetadata.read(RenderSystem.getShader());
        ToonOutputProfile profile = result.profile();
        if (!profile.isSupported()) {
            notifyOnce(result, profile.reason());
            // 用户要求手动回退，不修改 Toon 配置或擅自关闭。
            profile = ToonOutputProfile.vanilla();
        }
        if (shader.selectOutputProfile(profile)) return true;
        notifyOnce(result, "Toon 兼容程序编译失败");
        return shader.selectOutputProfile(ToonOutputProfile.vanilla());
    }

    private static void notifyOnce(IrisToonMetadata.Result result, String reason) {
        String key = result.packName() + "|" + reason;
        if (!NOTIFIED.add(key)) return;
        LOGGER.warn("[Iris/Toon] pack={}, program={}, reason={}; Toon 保持开启，请手动回退",
                result.packName(), result.programName(), reason);
        Minecraft minecraft = Minecraft.getInstance();
        Component message = Component.literal(result.packName() + "：" + reason
                + "。如画面异常，请手动关闭 Toon；设置未改。");
        minecraft.getToasts().addToast(SystemToast.multiline(minecraft, TOAST_ID,
                Component.literal("MMD Toon 光影兼容提示"), message));
        // 聊天记录保留原因，提示消失后仍可查看。
        if (minecraft.player != null) {
            minecraft.gui.getChat().addMessage(Component.literal("[MMD] ").append(message));
        }
    }

    static void reset() {
        NOTIFIED.clear();
        IrisToonMetadata.reset();
    }
}
