package com.shiroha.mmdskin.render.effectpack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RayMaterialImporterTest {
    @TempDir Path temporary;
    private Path root;
    private Path cloth;

    @BeforeEach void fixtures() throws Exception {
        root = temporary.resolve("library/pack/Materials");
        Path source = Path.of("src/test/resources/ray-mmd-1.5.2/Materials");
        try (var paths = Files.walk(source)) {
            for (Path input : paths.toList()) {
                Path output = root.resolve(source.relativize(input));
                if (Files.isDirectory(input)) Files.createDirectories(output);
                else Files.copy(input, output);
            }
        }
        // Importer checks resource existence; bitmap decoding is a separate GL probe.
        Files.createDirectories(root.resolve("_MaterialMap"));
        Files.write(root.resolve("_MaterialMap/Fabric02_N by 2gou.bmp"), new byte[]{0});
        cloth = root.resolve("Cloth/material_cloth.fx");
    }

    private FxEffect parse(Path file, String source) throws IOException {
        return RayMaterialImporter.parse(root, file, source, file.getFileName().toString());
    }

    @Test void importsAllThreeOriginalClothPresetsAndTheirNormalMap() throws Exception {
        for (String name : new String[]{"material_black_coat.fx", "material_cloth.fx", "material_white.fx"}) {
            Path file = root.resolve("Cloth").resolve(name);
            FxEffect effect = parse(file, Files.readString(file));
            assertEquals(5, effect.channels().get("CUSTOM_A").type());
            assertEquals(1f, effect.channels().get("CUSTOM_A").value()[0]);
            assertArrayEquals(name.equals("material_black_coat.fx") ? new float[]{.4f,.4f,.4f} : new float[]{1,1,1},
                    effect.channels().get("CUSTOM_B").value());
            assertEquals(name.equals("material_cloth.fx") ? 0 : 1, effect.channels().get("ALBEDO").flags());
            var normal = effect.channels().get("NORMAL");
            assertEquals("_MaterialMap/Fabric02_N by 2gou.bmp", normal.file());
            assertArrayEquals(new float[]{5,5}, normal.repeat());
            assertFalse(effect.supportsWorld());
            assertFalse(effect.channels().containsKey("ALPHA"));
        }
        var scan = FxPackLibrary.scan(temporary.resolve("library"));
        assertTrue(scan.failures().isEmpty(), scan.failures().toString());
        assertEquals(3, scan.effects().size());
    }

    @Test void preservesCustomMapSamplingAndInvertOptions() throws Exception {
        Files.write(root.resolve("Cloth/custom.png"), new byte[]{0});
        String source = Files.readString(cloth)
                .replace("CUSTOM_A_MAP_FROM 0", "CUSTOM_A_MAP_FROM 1")
                .replace("CUSTOM_A_MAP_COLOR_FLIP 0", "CUSTOM_A_MAP_COLOR_FLIP 1")
                .replace("CUSTOM_A_MAP_SWIZZLE 0", "CUSTOM_A_MAP_SWIZZLE 2")
                .replace("CUSTOM_A_MAP_APPLY_SCALE 0", "CUSTOM_A_MAP_APPLY_SCALE 2")
                .replace("CUSTOM_B_MAP_FROM 0", "CUSTOM_B_MAP_FROM 1")
                .replace("CUSTOM_B_MAP_COLOR_FLIP 0", "CUSTOM_B_MAP_COLOR_FLIP 1")
                + "\n#define CUSTOM_B_MAP_TYPE 1\n";
        var effect = parse(cloth, source);
        var a = effect.channels().get("CUSTOM_A");
        var b = effect.channels().get("CUSTOM_B");
        assertEquals(1, a.source()); assertEquals(1, b.source());
        assertEquals(1, a.flags()); assertEquals(1, b.flags());
        assertEquals(2, a.component()); assertEquals(2, a.scaleMode());
        assertEquals(5, a.type()); assertEquals(1, b.type());
        assertEquals("Cloth/custom.png", a.file());
    }

    @Test void inactiveCustomOptionsDoNotLoadTexturesOrEnableCloth() throws Exception {
        var effect = parse(cloth, Files.readString(cloth).replace("CUSTOM_ENABLE 5", "CUSTOM_ENABLE 0")
                .replace("CUSTOM_A_MAP_FROM 0", "CUSTOM_A_MAP_FROM 1"));
        assertFalse(effect.channels().containsKey("CUSTOM_A"));
        assertFalse(effect.channels().containsKey("CUSTOM_B"));
    }

    @Test void missingCustomTextureAndUnknownShadingModelStayExplicitFailures() throws Exception {
        String source = Files.readString(cloth);
        assertTrue(assertThrows(IOException.class, () -> parse(cloth,
                source.replace("CUSTOM_A_MAP_FROM 0", "CUSTOM_A_MAP_FROM 1"))).getMessage().contains("缺失活动贴图"));
        assertTrue(assertThrows(IOException.class, () -> parse(cloth,
                source.replace("CUSTOM_ENABLE 5", "CUSTOM_ENABLE 4"))).getMessage().contains("CUSTOM_ENABLE=4"));
    }

    @Test void defaultMaterialKeepsPmxDiffuseAndShininess() throws Exception {
        var effect = parse(cloth, Files.readString(cloth).replace("CUSTOM_ENABLE 5", "CUSTOM_ENABLE 0")
                .replace("SMOOTHNESS_MAP_FROM 0", "SMOOTHNESS_MAP_FROM 9"));
        assertEquals(0, effect.channels().get("ALBEDO").flags());
        assertEquals(9, effect.channels().get("SMOOTHNESS").source());
    }
}
