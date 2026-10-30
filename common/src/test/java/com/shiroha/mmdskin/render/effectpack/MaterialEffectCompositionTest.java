package com.shiroha.mmdskin.render.effectpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MaterialEffectCompositionTest {
    @TempDir Path temporary;

    private EffectPackRuntime runtime() throws Exception {
        Path library = temporary.resolve("library");
        Files.createDirectories(library.resolve("packA")); Files.createDirectories(library.resolve("packB"));
        Files.writeString(library.resolve("packA/gray.fx"), FxPackLibraryTest.colorFx("float4 c = tex2D(ScreenSampler, uv); c.rgb = float3(0.3,0.3,0.3); return c;"));
        Files.writeString(library.resolve("packB/warm.fx"), FxPackLibraryTest.colorFx("float4 c = tex2D(ScreenSampler, uv); c.rgb = saturate(c.rgb * float3(1.1,1.0,0.9)); return c;"));
        return new EffectPackRuntime(library, temporary.resolve("config.json"));
    }

    @Test void bindsAcrossPacksAndSeparatesInheritedOffAndOverride() throws Exception {
        var runtime = runtime(); var settings = runtime.settings();
        settings.characters = "packA/gray.fx";
        settings.bind(false, "hero", 1, "hair", "packB/warm.fx");
        settings.bind(false, "hero", 2, "eyes", "");
        try (var scope = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.WORLD, "hero")) {
            assertEquals("packA/gray.fx", runtime.materialEffect(0, "skin").id());
            assertEquals("packB/warm.fx", runtime.materialEffect(1, "hair").id());
            assertNull(runtime.materialEffect(2, "eyes"));
            assertEquals(2, runtime.program().effects().size());
            assertTrue(runtime.program().colorFunction().contains("mmdFxColor_1"));
            assertTrue(runtime.program().colorFunction().contains("mmdFxColor_2"));
        }
        settings.bind(false, "hero", 1, "hair", null);
        assertNull(settings.rule(false, "hero", 1, "hair"));
    }

    @Test void explicitMaterialCanEnableWhenWholeModelIsOff() throws Exception {
        var runtime = runtime();
        runtime.settings().bind(false, "hero", 0, "skin", "packA/gray.fx");
        try (var scope = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.WORLD, "hero")) {
            assertNull(runtime.current()); assertTrue(runtime.enabled());
            assertNotNull(runtime.materialEffect(0, "skin"));
            assertNull(runtime.materialEffect(1, "hair"));
        }
    }

    @Test void modelNameIndexAndOriginalNameAllPreventWrongAssignments() throws Exception {
        var runtime = runtime();
        runtime.settings().bind(false, "hero", 0, "skin", "packA/gray.fx");
        try (var scope = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.WORLD, "hero")) {
            assertNull(runtime.materialEffect(0, "coat"));
            assertNull(runtime.materialEffect(1, "skin"));
        }
        try (var scope = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.WORLD, "another")) {
            assertFalse(runtime.enabled()); assertNull(runtime.materialEffect(0, "skin"));
        }
    }

    @Test void sceneAndNestedScopesHaveIndependentBindings() throws Exception {
        var runtime = runtime(); var scene = com.shiroha.mmdskin.render.scene.RenderScene.PLACED_SCENE;
        runtime.settings().bind(false, "same", 0, "skin", "packA/gray.fx");
        runtime.settings().bind(true, "same", 0, "skin", "packB/warm.fx");
        try (var outer = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.WORLD, "same")) {
            FxProgram original = runtime.program();
            try (var inner = runtime.enter(scene, "same")) { assertEquals("packB/warm.fx", runtime.materialEffect(0, "skin").id()); }
            assertSame(original, runtime.program());
            assertEquals("packA/gray.fx", runtime.materialEffect(0, "skin").id());
        }
        assertFalse(runtime.enabled());
    }

    @Test void worldDeduplicationAlsoAppliesToMaterialOverrides() throws Exception {
        var runtime = runtime();
        runtime.settings().world = "packA/gray.fx";
        runtime.settings().bind(false, "hero", 0, "skin", "packA/gray.fx");
        runtime.settings().bind(false, "hero", 1, "hair", "packB/warm.fx");
        try (var scope = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.WORLD, "hero")) {
            assertNull(runtime.materialEffect(0, "skin")); assertNotNull(runtime.materialEffect(1, "hair"));
            assertEquals(1, runtime.program().effects().size());
        }
        try (var scope = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.INVENTORY, "hero")) {
            assertNotNull(runtime.materialEffect(0, "skin")); assertEquals(2, runtime.program().effects().size());
        }
    }

    @Test void settingsSurviveSaveAndOldConfigsStayCompatible() throws Exception {
        Path file = temporary.resolve("settings.json");
        Files.writeString(file, "{\"characters\":\"old.fx\",\"characterMaterials\":null}");
        var settings = EffectPackSettings.load(file);
        assertEquals("old.fx", settings.characters); assertTrue(settings.characterMaterials.isEmpty());
        settings.bind(false, "hero", 2, "hair", "packB/warm.fx"); settings.bind(true, "stage", 0, "floor", "");
        settings.save(file); var loaded = EffectPackSettings.load(file);
        assertEquals("packB/warm.fx", loaded.rule(false, "hero", 2, "hair").effect());
        assertEquals("", loaded.rule(true, "stage", 0, "floor").effect());
        assertNull(loaded.rule(false, "stage", 0, "floor"));
    }

    @Test void missingBoundEffectIsReportedAndDoesNotFallBackToDefault() throws Exception {
        var runtime = runtime(); runtime.settings().characters = "packA/gray.fx";
        runtime.settings().bind(false, "hero", 0, "skin", "missing/effect.fx"); runtime.reload();
        assertTrue(runtime.problems().stream().anyMatch(p -> p.contains("missing/effect.fx")));
        try (var scope = runtime.enter(com.shiroha.mmdskin.render.scene.RenderScene.WORLD, "hero")) { assertNull(runtime.materialEffect(0, "skin")); }
    }

    @Test void programKeyAndFunctionNamesAreStableRegardlessOfRuleOrder() throws Exception {
        var runtime = runtime(); FxEffect a = runtime.effect("packA/gray.fx"), b = runtime.effect("packB/warm.fx");
        FxProgram first = FxProgram.of(List.of(a, b, a)), second = FxProgram.of(List.of(b, a));
        assertEquals(first.key(), second.key()); assertEquals(2, first.effects().size());
        assertFalse(first.colorFunction().contains("vec4 mmdFxColor(vec4 mmdInput)"));
        assertTrue(first.colorFunction().contains("mmdFxSaturate_1"));
    }
}
