package com.shiroha.mmdskin.neoforge;

import com.shiroha.mmdskin.MmdSkin;
import com.shiroha.mmdskin.neoforge.register.MmdSkinRegisterCommon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NeoForge 平台模组主入口
 */
@Mod(MmdSkin.MOD_ID)
public class MmdSkinNeoForge {
    public static final Logger logger = LogManager.getLogger();

    public MmdSkinNeoForge(IEventBus modEventBus) {
        MmdSkin.init();
        MmdSkinRegisterCommon.init(modEventBus);
        if (FMLEnvironment.dist.isClient()) {
            MmdSkinNeoForgeClient.init(modEventBus);
        }
    }
}
