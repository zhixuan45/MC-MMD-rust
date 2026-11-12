package com.shiroha.mmdskin.forge;

import com.shiroha.mmdskin.MmdSkin;
import com.shiroha.mmdskin.forge.register.MmdSkinRegisterCommon;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 平台模组主入口
 */
@Mod(MmdSkin.MOD_ID)
public class MmdSkinForge {
    public static final Logger logger = LogManager.getLogger();

    public MmdSkinForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        MmdSkin.init();
        MmdSkinRegisterCommon.init(modEventBus);
        DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> MmdSkinForgeClient.init(modEventBus)
        );
    }
}
