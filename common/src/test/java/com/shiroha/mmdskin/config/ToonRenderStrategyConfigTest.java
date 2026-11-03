package com.shiroha.mmdskin.config;

import com.shiroha.mmdskin.render.shader.ToonRenderStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToonRenderStrategyConfigTest {
    @TempDir Path directory;

    @Test void olderAndUnknownConfigurationsDefaultToAuto() throws Exception {
        Files.writeString(directory.resolve("config.json"), "{\"toonRenderingEnabled\":true}");
        assertEquals(ToonRenderStrategy.AUTO, ConfigData.load(directory).toonRenderStrategy);
        Files.writeString(directory.resolve("config.json"), "{\"toonRenderStrategy\":\"FUTURE\"}");
        assertEquals(ToonRenderStrategy.AUTO, ConfigData.load(directory).toonRenderStrategy);
        Files.writeString(directory.resolve("config.json"), "{\"toonRenderStrategy\":null}");
        assertEquals(ToonRenderStrategy.AUTO, ConfigData.load(directory).toonRenderStrategy);
    }

    @Test void allStrategiesRoundTripThroughSharedConfig() {
        for (var strategy : ToonRenderStrategy.values()) {
            var data = new ConfigData();
            data.toonRenderStrategy = strategy;
            data.save(directory);
            var loaded = ConfigData.load(directory);
            assertEquals(strategy, loaded.toonRenderStrategy);
            var provider = new AbstractMmdSkinConfig(loaded) {};
            assertEquals(strategy, provider.getToonRenderStrategy());
        }
    }
}
