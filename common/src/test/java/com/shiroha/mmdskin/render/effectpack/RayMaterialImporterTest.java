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
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".fx")).toList()) {
                var defs = RayMaterialParameters.parse(Files.readString(file)).definitions();
                for (var entry : defs.entrySet()) {
                    if (!entry.getKey().endsWith("_MAP_FILE")) continue;
                    String key = entry.getKey().replace("_MAP_FILE", "_MAP_FROM");
                    if (RayMaterialImporter.integer(defs, key, 0) != 1) continue;
                    String name = entry.getValue();
                    Path resource = FxPaths.resolve(root, file.getParent(), name.substring(1,name.length()-1));
                    Files.createDirectories(resource.getParent()); Files.write(resource,new byte[]{0});
                }
            }
        }
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
            assertEquals(name.equals("material_cloth.fx") ? 1 : 0, effect.channels().get("ALBEDO").type());
            var normal = effect.channels().get("NORMAL");
            assertEquals("_MaterialMap/Fabric02_N by 2gou.bmp", normal.file());
            assertArrayEquals(new float[]{5,5}, normal.repeat());
            assertFalse(effect.supportsWorld());
            assertFalse(effect.channels().containsKey("ALPHA"));
        }
        var scan = FxPackLibrary.scan(temporary.resolve("library"));
        assertTrue(scan.failures().isEmpty(), scan.failures().toString());
        assertEquals(10, scan.effects().size());
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
                source.replace("CUSTOM_ENABLE 5", "CUSTOM_ENABLE 10"))).getMessage().contains("CUSTOM_ENABLE=10"));
    }

    @Test void defaultMaterialKeepsPmxDiffuseAndShininess() throws Exception {
        var effect = parse(cloth, Files.readString(cloth).replace("CUSTOM_ENABLE 5", "CUSTOM_ENABLE 0")
                .replace("SMOOTHNESS_MAP_FROM 0", "SMOOTHNESS_MAP_FROM 9"));
        assertEquals(1, effect.channels().get("ALBEDO").type());
        assertEquals(9, effect.channels().get("SMOOTHNESS").source());
    }

    @Test void importsEveryModelAndIgnoresInputsThatTheModelDoesNotUse() throws Exception {
        String original = Files.readString(cloth);
        for (int model=0; model<=9; model++) {
            var effect = parse(cloth,original.replace("CUSTOM_ENABLE 5","CUSTOM_ENABLE "+model));
            if (model==0) assertFalse(effect.channels().containsKey("CUSTOM_A"));
            else assertEquals(model,effect.channels().get("CUSTOM_A").type());
            assertEquals(model!=0 && model!=2 && model!=6,effect.channels().containsKey("CUSTOM_B"));
        }
        parse(cloth,original.replace("CUSTOM_ENABLE 5","CUSTOM_ENABLE 2")
                .replace("CUSTOM_A_MAP_FROM 0","CUSTOM_A_MAP_FROM 1").replace("CUSTOM_B_MAP_FROM 0","CUSTOM_B_MAP_FROM 1"));
        parse(cloth,original.replace("CUSTOM_ENABLE 5","CUSTOM_ENABLE 6").replace("CUSTOM_B_MAP_FROM 0","CUSTOM_B_MAP_FROM 1"));
    }

    @Test void retainsSpecularTypesPmxInputsAndSeparateEmissionIntensity() throws Exception {
        var effect = RayMaterialImporter.convert(root,cloth,RayMaterialParameters.parse("""
                #define CUSTOM_ENABLE 3
                #define CUSTOM_A_MAP_FROM 7
                #define CUSTOM_B_MAP_FROM 8
                #define SPECULAR_MAP_FROM 3
                #define SPECULAR_MAP_TYPE 3
                #define SPECULAR_MAP_SWIZZLE 2
                #define EMISSIVE_ENABLE 1
                const float3 emissive = .5;
                const float emissiveIntensity = 4;
                """),"aniso");
        assertEquals(7,effect.channels().get("CUSTOM_A").source());
        assertEquals(8,effect.channels().get("CUSTOM_B").source());
        assertEquals(3,effect.channels().get("SPECULAR").type());
        assertEquals(2,effect.channels().get("SPECULAR").component());
        assertArrayEquals(new float[]{.5f,.5f,.5f,4},effect.channels().get("EMISSIVE").value());
        assertThrows(IOException.class,()->RayMaterialImporter.convert(root,cloth,RayMaterialParameters.parse("""
                #define CUSTOM_ENABLE 3
                #define SPECULAR_MAP_FROM 3
                #define SPECULAR_MAP_TYPE 0
                """),"bad"));
    }
}
