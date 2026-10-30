package com.shiroha.mmdskin.render.effectpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class FxPackLibraryTest {
    @TempDir Path temporary;
    static String colorFx(String body) {
        return """
                float Script : STANDARDSGLOBAL < string ScriptClass = "scene"; > = 0.8;
                texture Screen : RENDERCOLORTARGET;
                sampler ScreenSampler = sampler_state { Texture = <Screen>; };
                const float intensity = 0.8;
                float4 PS(float2 uv : TEXCOORD0) : COLOR0 {
                %s
                }
                technique Color { pass P { PixelShader = compile ps_3_0 PS(); } }
                """.formatted(body);
    }

    private FxPackLibrary.Scan scan(String name, String source) throws IOException {
        Path root = temporary.resolve("library");
        Path pack = root.resolve("test");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve(name), source);
        return FxPackLibrary.scan(root);
    }

    @Test void translatesMonochromeAndKeepsExplicitInput() throws Exception {
        var scan = scan("gray.fx", colorFx("float4 c = tex2D(ScreenSampler, uv); float gray = dot(c.rgb, float3(0.299, 0.587, 0.114)); c.rgb = lerp(c.rgb, float3(gray, gray, gray), intensity); return c;"));
        assertEquals(1, scan.effects().size(), scan.failures().toString());
        var effect = scan.effects().getFirst();
        assertTrue(effect.supportsWorld());
        assertTrue(effect.colorFunction().contains("mix"));
        assertTrue(effect.colorFunction().contains("mmdInput"));
        assertFalse(effect.colorFunction().contains("tex2D"));
    }

    @Test void translatesSeveralFxIndependentlyAndReportsUnknownOnes() throws Exception {
        scan("gray.fx", colorFx("float4 c = tex2D(ScreenSampler, uv); return saturate(c * intensity);"));
        var result = scan("invert.fx", colorFx("float4 c = tex2D(ScreenSampler, uv); c.rgb = float3(1, 1, 1) - c.rgb; return c;"));
        assertEquals(2, result.effects().size());
        result = scan("unsupported.fx", "float4 PS() : COLOR { return 1; }");
        assertEquals(2, result.effects().size());
        assertEquals(1, result.failures().size());
    }

    @Test void rejectsNeighborSamplingRatherThanChangingItsMeaning() throws Exception {
        var result = scan("blur.fx", colorFx("return tex2D(ScreenSampler, uv + float2(0.1, 0.0));"));
        assertTrue(result.effects().isEmpty());
        assertTrue(result.failures().getFirst().reason().contains("tex2D"));
    }

    @Test void rejectsMultipassAndDynamicUniforms() throws Exception {
        var result = scan("passes.fx", colorFx("return float4(1, 1, 1, 1);") + "technique Other { pass X { PixelShader = compile ps_3_0 PS(); } }");
        assertTrue(result.failures().getFirst().reason().contains("多 pass"));
        result = scan("time.fx", colorFx("float4 c = tex2D(ScreenSampler, uv); return c * time;"));
        assertTrue(result.failures().stream().anyMatch(f -> f.reason().contains("time")));
    }

    @Test void rejectsModifiedRayCommonCode() throws Exception {
        var result = scan("material_2.0.fx", "#include \"material_common_2.0.fxsub\"");
        Path pack = temporary.resolve("library/test");
        Files.writeString(pack.resolve("material_common_2.0.fxsub"), "float4 injected() { return 1; }");
        result = FxPackLibrary.scan(temporary.resolve("library"));
        assertTrue(result.failures().getFirst().reason().contains("未适配"));
    }

    @Test void zipTraversalCannotWriteOutsideItsNewPack() throws Exception {
        Path archive = temporary.resolve("evil.zip"), library = temporary.resolve("library");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../../escape.txt"));
            zip.write("bad".getBytes());
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> FxPackLibrary.importPack(archive, library));
        assertFalse(Files.exists(temporary.resolve("escape.txt")));
        try (var files = Files.list(library)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsDuplicateZipEntriesAndAbsoluteResourcePaths() throws Exception {
        assertThrows(IOException.class, () -> FxPaths.resolve(temporary, temporary, "C:\\outside.tga"));
        assertThrows(IOException.class, () -> FxPaths.resolve(temporary, temporary, "../outside.tga"));
        Path archive = temporary.resolve("duplicate.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("a.fx")); zip.write("x".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("./a.fx")); zip.write("x".getBytes()); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> FxPackLibrary.importPack(archive, temporary.resolve("library")));
    }

    @Test void importedFxHasAReportAndDoesNotCopyUnrelatedNeighbors() throws Exception {
        Path input = temporary.resolve("input");
        Files.createDirectories(input);
        Path fx = input.resolve("color.fx");
        Files.writeString(fx, colorFx("return tex2D(ScreenSampler, uv);"));
        Files.writeString(input.resolve("private.txt"), "unrelated");
        Path library = temporary.resolve("library");
        Path pack = FxPackLibrary.importPack(fx, library);
        assertTrue(Files.exists(pack.resolve("import-report.json")));
        assertTrue(Files.exists(pack.resolve("effectpack.json")));
        assertTrue(Files.readString(pack.resolve("_converted/color.fx.glsl")).contains("mmdFxColor"));
        assertFalse(Files.exists(pack.resolve("private.txt")));
        assertEquals(1, FxPackLibrary.scan(library).effects().size());
    }

    @Test void refusesAnUntranslatedVertexStage() throws Exception {
        var result = scan("vertex.fx", colorFx("return tex2D(ScreenSampler, uv);")
                .replace("PixelShader =", "VertexShader = compile vs_3_0 Warp(); PixelShader ="));
        assertTrue(result.effects().isEmpty());
        assertTrue(result.failures().getFirst().reason().contains("顶点阶段"));
    }

    @Test void settingsRoundTripKeepsTargetsIndependentAndWorldOffByDefault() throws Exception {
        Path config = temporary.resolve("config/effectpacks.json");
        var settings = EffectPackSettings.load(config);
        assertEquals("", settings.world);
        settings.characters = "pack/a.fx";
        settings.scenes = "pack/b.fx";
        settings.characterModel = "星次";
        settings.save(config);
        var loaded = EffectPackSettings.load(config);
        assertEquals("pack/a.fx", loaded.characters);
        assertEquals("pack/b.fx", loaded.scenes);
        assertEquals("", loaded.world);
        assertTrue(EffectPackSettings.matches("*", "any"));
        assertTrue(EffectPackSettings.matches("星次", "星次"));
        assertFalse(EffectPackSettings.matches("星次", "stage"));
        assertFalse(EffectPackSettings.matches("", "stage"));
    }

    @Test void importsRealTex1AndPreservesItsActiveSemantics() throws Exception {
        String input = System.getenv("MMD_FX_TEST_ZIP");
        org.junit.jupiter.api.Assumptions.assumeTrue(input != null, "可选真实资源包验证，设置 MMD_FX_TEST_ZIP");
        Path library = temporary.resolve("library");
        FxPackLibrary.importPack(Path.of(input), library);
        var result = FxPackLibrary.scan(library);
        assertEquals(1, result.effects().size(), result.failures().toString());
        var effect = result.effects().getFirst();
        assertEquals(FxEffect.Kind.RAY_MATERIAL, effect.kind());
        assertFalse(effect.supportsWorld());
        assertEquals(3, effect.channels().get("ALBEDO").source());
        var normal = effect.channels().get("NORMAL");
        assertEquals(1, normal.source());
        assertEquals(1, normal.type());
        assertEquals(0.2f, normal.value()[0]);
        assertTrue(normal.file().endsWith("Nor2.tga"));
        assertEquals(0, effect.channels().get("SMOOTHNESS").scaleMode());
        assertEquals(1, effect.channels().get("OCCLUSION").source());
        assertFalse(effect.channels().containsKey("EMISSIVE"));
        assertEquals(3, effect.channels().values().stream().filter(c -> c.source() == 1).count());
    }
}
