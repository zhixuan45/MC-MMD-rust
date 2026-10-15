package com.shiroha.mmdskin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDataTest {
    @Test
    void bodyFillSwitchesDefaultForOldFilesAndPersistIndependently(@TempDir Path configDir)
            throws Exception {
        Files.writeString(configDir.resolve("config.json"), "{\"physicsEnabled\":true}");
        ConfigData loaded = ConfigData.load(configDir);
        assertTrue(loaded.physicsAutoUpperBodyColliders);
        assertTrue(loaded.physicsAutoLowerBodyColliders);
        loaded.physicsAutoUpperBodyColliders = false;
        loaded.save(configDir);
        loaded = ConfigData.load(configDir);
        assertFalse(PhysicsConfigSnapshot.from(loaded).autoUpperBodyColliders());
        assertTrue(PhysicsConfigSnapshot.from(loaded).autoLowerBodyColliders());
        ConfigManager.init(new TestConfig(loaded));
        try {
            assertFalse(ConfigManager.isPhysicsAutoUpperBodyColliders());
            assertTrue(ConfigManager.isPhysicsAutoLowerBodyColliders());
            assertFalse(PhysicsConfigSnapshot.fromConfigManager().autoUpperBodyColliders());
            assertTrue(PhysicsConfigSnapshot.fromConfigManager().autoLowerBodyColliders());
        } finally {
            ConfigManager.init(null);
        }
        loaded.physicsAutoUpperBodyColliders = true;
        loaded.physicsAutoLowerBodyColliders = false;
        loaded.save(configDir);
        var second = PhysicsConfigSnapshot.from(ConfigData.load(configDir));
        assertTrue(second.autoUpperBodyColliders());
        assertFalse(second.autoLowerBodyColliders());
    }

    @Test
    void missingIssueLogFieldsDefaultToFalse(@TempDir Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), "{\"debugHudEnabled\":true}");

        ConfigData loaded = ConfigData.load(configDir);

        assertFalse(loaded.debugModelDeformationLog);
        assertFalse(loaded.debugSidePhysicsLog);
        assertFalse(loaded.debugCollisionModeLog);
        assertFalse(loaded.debugOutlinePaperDollLog);
        assertFalse(loaded.debugGpuLog);
        assertEquals(0, new TestConfig(loaded).getIssueDiagnosticFlags());
    }

    @Test
    void gpuLogPersistsIndependentlyAndTakesEffectThroughProvider(@TempDir Path configDir) {
        ConfigData data = new ConfigData();
        data.debugGpuLog = true;
        data.save(configDir);
        ConfigData loaded = ConfigData.load(configDir);
        assertTrue(loaded.debugGpuLog);
        assertFalse(loaded.debugHudEnabled);
        assertFalse(loaded.performanceProfilingEnabled);
        TestConfig provider = new TestConfig(loaded);
        ConfigManager.init(provider);
        try {
            assertTrue(ConfigManager.isDebugGpuLogEnabled());
            loaded.debugGpuLog = false;
            assertFalse(ConfigManager.isDebugGpuLogEnabled());
            loaded.debugModelDeformationLog = true;
            loaded.save(configDir);
            ConfigData roundTrip = ConfigData.load(configDir);
            assertFalse(roundTrip.debugGpuLog);
            assertTrue(roundTrip.debugModelDeformationLog);
        } finally {
            ConfigManager.init(null);
        }
    }

    @Test
    void issueLogFieldsPersistIndependently(@TempDir Path configDir) {
        ConfigData data = new ConfigData();
        data.debugModelDeformationLog = true;
        data.debugSidePhysicsLog = false;
        data.debugCollisionModeLog = true;
        data.debugOutlinePaperDollLog = false;
        data.save(configDir);

        ConfigData loaded = ConfigData.load(configDir);

        assertEquals(IssueDiagnosticOptions.MODEL_DEFORMATION | IssueDiagnosticOptions.COLLISION_MODE,
                new TestConfig(loaded).getIssueDiagnosticFlags());
        loaded.debugModelDeformationLog = false;
        loaded.debugSidePhysicsLog = true;
        loaded.debugCollisionModeLog = false;
        loaded.debugOutlinePaperDollLog = true;
        loaded.save(configDir);
        ConfigData secondRoundTrip = ConfigData.load(configDir);
        assertEquals(IssueDiagnosticOptions.SIDE_PHYSICS | IssueDiagnosticOptions.OUTLINE_PAPER_DOLL,
                new TestConfig(secondRoundTrip).getIssueDiagnosticFlags());
    }

    @Test
    void staticColliderScalePersistsThroughConfigProviderAndSnapshot(@TempDir Path configDir) {
        ConfigData data = new ConfigData();
        data.physicsStaticColliderScale = 1.23f;
        data.save(configDir);

        ConfigData loaded = ConfigData.load(configDir);
        TestConfig provider = new TestConfig(loaded);
        assertEquals(1.23f, provider.getPhysicsStaticColliderScale(), 0.0001f);

        ConfigManager.init(provider);
        try {
            assertEquals(1.23f, PhysicsConfigSnapshot.fromConfigManager().staticColliderScale(), 0.0001f);
        } finally {
            ConfigManager.init(null);
        }
    }

    @Test
    void eyeEmissionDefaultsAndPersistsThroughProvider(@TempDir Path configDir) {
        ConfigData data = new ConfigData();
        assertEquals(0.0f, data.toonEyeEmission);
        data.toonEyeEmission = 1.25f;
        data.save(configDir);
        ConfigManager.init(new TestConfig(ConfigData.load(configDir)));
        try {
            assertEquals(1.25f, ConfigManager.getToonEyeEmission(), 0.0001f);
        } finally {
            ConfigManager.init(null);
        }
        data.toonEyeEmission = 9;
        data.save(configDir);
        assertEquals(3.0f, ConfigData.load(configDir).toonEyeEmission);
    }

    @Test
    void toonTransitionSoftnessDefaultsAndClampsToRange(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve("config.json"), "{\"toonLevels\":4}");
        ConfigData loaded = ConfigData.load(configDir);
        assertEquals(1.2f, loaded.toonTransitionSoftness, 0.0001f);
        assertEquals(1.2f, new TestConfig(loaded).getToonTransitionSoftness(), 0.0001f);

        for (float value : new float[]{0.0f, 1.0f, 1.2f, 1.5f, 2.0f}) {
            loaded.toonTransitionSoftness = value;
            loaded.save(configDir);
            assertEquals(value, ConfigData.load(configDir).toonTransitionSoftness, 0.0001f);
        }
        loaded.toonTransitionSoftness = 3.0f;
        loaded.save(configDir);
        assertEquals(2.0f, ConfigData.load(configDir).toonTransitionSoftness, 0.0001f);
        loaded.toonTransitionSoftness = -1.0f;
        loaded.save(configDir);
        assertEquals(0.0f, ConfigData.load(configDir).toonTransitionSoftness, 0.0001f);
    }

    @Test
    void toonOutlineFollowLightingDefaultsAndPersistsFalse(@TempDir Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), "{\"toonOutlineEnabled\":true}");
        ConfigData loaded = ConfigData.load(configDir);
        assertTrue(loaded.toonOutlineFollowLighting);
        assertTrue(new TestConfig(loaded).isToonOutlineFollowLighting());

        loaded.toonOutlineFollowLighting = false;
        loaded.save(configDir);
        ConfigData roundTrip = ConfigData.load(configDir);
        assertFalse(roundTrip.toonOutlineFollowLighting);
        ConfigManager.init(new TestConfig(roundTrip));
        try {
            assertFalse(ConfigManager.isToonOutlineFollowLighting());
        } finally {
            ConfigManager.init(null);
        }
    }

    @Test
    void toonTransitionSoftnessReplacesNonFiniteValuesWithDefault() throws Exception {
        ConfigData data = new ConfigData();
        var normalize = ConfigData.class.getDeclaredMethod("normalize");
        normalize.setAccessible(true);
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            data.toonTransitionSoftness = invalid;
            normalize.invoke(data);
            assertEquals(1.2f, data.toonTransitionSoftness, 0.0001f);
        }
    }

    private static final class TestConfig extends AbstractMmdSkinConfig {
        private TestConfig(ConfigData data) { super(data); }

        @Override public String getMobModelReplacement(String entityTypeId) { return ""; }
    }
}
