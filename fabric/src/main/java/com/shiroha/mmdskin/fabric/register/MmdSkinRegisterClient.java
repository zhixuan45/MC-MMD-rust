package com.shiroha.mmdskin.fabric.register;

import com.mojang.blaze3d.platform.InputConstants;
import com.shiroha.mmdskin.fabric.config.ModConfigScreen;
import com.shiroha.mmdskin.fabric.maid.MaidCompatMixinPlugin;
import com.shiroha.mmdskin.fabric.network.MmdSkinNetworkPack;
import com.shiroha.mmdskin.mixin.fabric.KeyMappingAccessor;
import com.shiroha.mmdskin.render.entity.EntityRenderFactory;
import com.shiroha.mmdskin.ui.wheel.ConfigWheelScreen;
import com.shiroha.mmdskin.util.KeyMappingUtil;
import java.io.File;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric 客户端注册入口
 * 负责平台按键注册、实体渲染器接管与 1.21.1 客户端网络包监听
 */
@Environment(EnvType.CLIENT)
public class MmdSkinRegisterClient {
    static final Logger logger = LogManager.getLogger();

    private static final FabricClientNetworkBindings NETWORK_BINDINGS = new FabricClientNetworkBindings();

    static KeyMapping keyConfigWheel = new KeyMapping("key.mmdskin.config_wheel",
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "key.categories.mmdskin");

    static KeyMapping keyMaidConfigWheel = new KeyMapping("key.mmdskin.maid_config_wheel",
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.mmdskin");

    static final KeyMapping[] keyQuickModels = new KeyMapping[4];
    static {
        for (int i = 0; i < 4; i++) {
            keyQuickModels[i] = new KeyMapping("key.mmdskin.quick_model_" + (i + 1),
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.mmdskin");
        }
    }

    private static final FabricClientRuntimeHooks RUNTIME_HOOKS =
        new FabricClientRuntimeHooks(keyConfigWheel, keyMaidConfigWheel, keyQuickModels);

    public static void Register() {
        Minecraft MCinstance = Minecraft.getInstance();

        KeyMappingUtil.setBoundKeyGetter(k -> {
            if (k instanceof KeyMappingAccessor accessor) {
                return accessor.mmd$getBoundKey();
            }
            return InputConstants.UNKNOWN;
        });

        KeyBindingHelper.registerKeyBinding(keyConfigWheel);
        if (MaidCompatMixinPlugin.isMaidModLoaded()) {
            KeyBindingHelper.registerKeyBinding(keyMaidConfigWheel);
        }
        for (KeyMapping keyQuickModel : keyQuickModels) {
            KeyBindingHelper.registerKeyBinding(keyQuickModel);
        }

        ConfigWheelScreen.setModSettingsScreenFactory(() -> ModConfigScreen.create(null));
        NETWORK_BINDINGS.register(MCinstance);
        RUNTIME_HOOKS.register(MCinstance);

        File[] modelDirs = new File(MCinstance.gameDirectory, "3d-skin").listFiles();
        if (modelDirs != null) {
            for (File i : modelDirs) {
                String name = i.getName();
                if (!name.startsWith("EntityPlayer") &&
                        !name.equals("DefaultAnim") &&
                        !name.equals("CustomAnim") &&
                        !name.equals("StageAnim") &&
                        !name.equals("SceneModel") &&
                        !name.equals("DefaultMorph") &&
                        !name.equals("CustomMorph") &&
                        !name.equals("Shader")) {

                    String mcEntityName = name.replace('.', ':');
                    if (EntityType.byString(mcEntityName).isPresent()) {
                        EntityRendererRegistry.register(EntityType.byString(mcEntityName).get(), new EntityRenderFactory<>(mcEntityName));
                    } else {
                        logger.warn("{} 实体不存在，跳过渲染注册", mcEntityName);
                    }
                }
            }
        }

        // Fabric 1.21.1 客户端载荷接收
        ClientPlayNetworking.registerGlobalReceiver(MmdSkinNetworkPack.TYPE, (payload, context) -> {
            context.client().execute(payload::doInClient);
        });
    }
}
