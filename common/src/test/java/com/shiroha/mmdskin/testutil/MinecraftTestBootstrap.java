package com.shiroha.mmdskin.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class MinecraftTestBootstrap {
    private MinecraftTestBootstrap() {}

    public static void initialize() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
