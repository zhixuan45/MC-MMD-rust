package com.shiroha.mmdskin.neoforge.register;

import com.mojang.blaze3d.platform.InputConstants;
import com.shiroha.mmdskin.neoforge.config.ModConfigScreen;
import com.shiroha.mmdskin.render.entity.EntityRenderFactory;
import com.shiroha.mmdskin.ui.wheel.ConfigWheelScreen;
import com.shiroha.mmdskin.util.KeyMappingUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * NeoForge 客户端注册入口
 */
@OnlyIn(Dist.CLIENT)
public class MmdSkinRegisterClient {
    static final Logger logger = LogManager.getLogger();

    private static final NeoForgeClientNetworkBindings NETWORK_BINDINGS = new NeoForgeClientNetworkBindings();

    public static final KeyMapping keyConfigWheel = new KeyMapping(
            "key.mmdskin.config_wheel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.mmdskin"
    );

    public static final KeyMapping keyMaidConfigWheel = new KeyMapping(
            "key.mmdskin.maid_config_wheel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.mmdskin"
    );

    public static final KeyMapping[] keyQuickModels = new KeyMapping[4];
    static {
        for (int i = 0; i < 4; i++) {
            keyQuickModels[i] = new KeyMapping(
                    "key.mmdskin.quick_model_" + (i + 1),
                    KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM,
                    InputConstants.UNKNOWN.getValue(),
                    "key.categories.mmdskin"
            );
        }
    }

    private static final NeoForgeClientRuntimeHooks RUNTIME_HOOKS =
            new NeoForgeClientRuntimeHooks(keyConfigWheel, keyMaidConfigWheel, keyQuickModels);

    public static void init(IEventBus modEventBus) {
        KeyMappingUtil.setBoundKeyGetter(KeyMapping::getKey);
        ConfigWheelScreen.setModSettingsScreenFactory(() -> ModConfigScreen.create(null));
        NETWORK_BINDINGS.register();

        modEventBus.addListener(MmdSkinRegisterClient::onRegisterKeyMappings);
        modEventBus.addListener(MmdSkinRegisterClient::onRegisterEntityRenderers);

        NeoForge.EVENT_BUS.register(NeoForgeClientEventHandler.class);
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(keyConfigWheel);
        event.register(keyMaidConfigWheel);
        for (KeyMapping keyQuickModel : keyQuickModels) {
            event.register(keyQuickModel);
        }
    }

    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        Minecraft mcInstance = Minecraft.getInstance();
        File[] modelDirs = new File(mcInstance.gameDirectory, "3d-skin").listFiles();

        if (modelDirs != null) {
            for (File i : modelDirs) {
                String name = i.getName();
                if (!name.startsWith("EntityPlayer") &&
                        !name.equals("DefaultAnim") &&
                        !name.equals("CustomAnim") &&
                        !name.equals("Shader")) {

                    String mcEntityName = name.replace('.', ':');
                    if (EntityType.byString(mcEntityName).isPresent()) {
                        event.registerEntityRenderer(
                                EntityType.byString(mcEntityName).get(),
                                new EntityRenderFactory<>(mcEntityName));
                    } else {
                        logger.warn("{} 实体不存在，跳过渲染注册", mcEntityName);
                    }
                }
            }
        }
    }

    public static class NeoForgeClientEventHandler {

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            RUNTIME_HOOKS.onClientTick(event);
        }

        @SubscribeEvent
        public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            RUNTIME_HOOKS.onPlayerLoggedIn(event);
        }

        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            RUNTIME_HOOKS.onPlayerLoggedOut(event);
        }

        @SubscribeEvent
        public static void onPlayerDeath(LivingDeathEvent event) {
            RUNTIME_HOOKS.onPlayerDeath(event);
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            RUNTIME_HOOKS.onRenderGui(event);
        }

        @SubscribeEvent
        public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            RUNTIME_HOOKS.onPlayerRespawn(event);
        }
    }
}
