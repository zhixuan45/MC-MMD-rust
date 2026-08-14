package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.config.ConfigData;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.config.RuntimeConfigPortHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiPeekEvaluatorTest {

    private ConfigData testConfig;

    @BeforeEach
    void setUp() {
        testConfig = new ConfigData();
        ConfigManager.init(new TestConfigProvider(testConfig));
        RuntimeConfigPortHolder.set(com.shiroha.mmdskin.config.RuntimeConfigPort.fromConfigManager());
    }

    @AfterEach
    void tearDown() {
        RuntimeConfigPortHolder.reset();
    }

    @Test
    void nonLocalPlayerOrUiShouldAlwaysBeNormal() {
        AntiPeekEvaluator.Result r1 = AntiPeekEvaluator.evaluate(null, false, false, false, false);
        assertEquals(AntiPeekEvaluator.Result.NORMAL, r1);

        AntiPeekEvaluator.Result r2 = AntiPeekEvaluator.evaluate(null, true, false, true, false);
        assertEquals(AntiPeekEvaluator.Result.NORMAL, r2);

        AntiPeekEvaluator.Result r3 = AntiPeekEvaluator.evaluate(null, true, false, false, true);
        assertEquals(AntiPeekEvaluator.Result.NORMAL, r3);
    }

    @Test
    void disabledAntiPeekModeReturnsNormalByDefault() {
        testConfig.antiPeekModeEnabled = false;

        AntiPeekEvaluator.Result result = AntiPeekEvaluator.evaluate(null, true, false, false, false);
        assertFalse(result.fullyHidden());
        assertEquals(1.0f, result.alpha(), 0.001f);
    }

    private static class TestConfigProvider extends com.shiroha.mmdskin.config.AbstractMmdSkinConfig {
        TestConfigProvider(ConfigData data) {
            super(data);
        }
    }
}
