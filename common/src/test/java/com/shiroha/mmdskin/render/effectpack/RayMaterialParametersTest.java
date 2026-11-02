package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RayMaterialParametersTest {
    private FxEffect effect(String source) throws IOException {
        Path root = Path.of(".");
        return RayMaterialImporter.convert(root, root.resolve("material.fx"), RayMaterialParameters.parse(source), "test");
    }

    @Test void consumesStaticDeclarationsFunctionMacrosAndConstantReferences() throws Exception {
        var parameters = RayMaterialParameters.parse("""
                #define SSS(x) exp((1 - saturate(x)) * float3(-8, -40, -64))
                #define SCALE (255.0f)
                static const float3 emissive = pow(float3(155,64,227)/SCALE, 2.2);
                const float factor = .8;
                static const float3 customB = SSS(factor);
                #include "material_common_2.0.fxsub"
                """);
        assertEquals(3, parameters.constants().get("emissive").length);
        assertEquals((float) Math.exp(-8), parameters.constants().get("customB")[1], 1e-9f);
        assertEquals("material_common_2.0.fxsub", parameters.includes().getFirst());
    }

    @Test void preservesStaticAlphaDiffuseAndColorSynthesisOptions() throws Exception {
        var effect = effect("""
                #define ALPHA_MAP_FROM (0)
                #define ALBEDO_MAP_APPLY_DIFFUSE 0
                #define ALBEDO_SUB_ENABLE 4
                const float alpha = .25;
                const float3 albedo = float3(102,204,255)/255;
                const float3 albedoSub = .1;
                """);
        assertEquals(0, effect.channels().get("ALPHA").source());
        assertEquals(.25f, effect.channels().get("ALPHA").value()[0]);
        assertEquals(0, effect.channels().get("ALBEDO").type());
        assertEquals(4, effect.channels().get("ALBEDO_SUB").type());
        assertArrayEquals(new float[]{.4f, .8f, 1}, effect.channels().get("ALBEDO").value(), 1e-7f);
    }

    @Test void keepsOriginalTextureAlphaAndDiffuseDefaults() throws Exception {
        var effect = effect("const float alpha = .5;");
        assertFalse(effect.channels().containsKey("ALPHA"));
        assertEquals(1, effect.channels().get("ALBEDO").type());
    }

    @Test void diagnosesControllerDependenciesInsteadOfInventingValues() {
        var exception = assertThrows(IOException.class, () -> RayMaterialParameters.parse("""
                #define CONTROLLER_NAME "material_aniso_1.pmx"
                #include "material_editor.fxsub"
                static const float3 albedo = mAlbedoColor;
                """));
        assertTrue(exception.getMessage().contains("CONTROLOBJECT"));
        assertThrows(IOException.class, () -> RayMaterialParameters.parse("const float3 a=mAlbedoColor;"));
    }

    @Test void refusesUnknownCodeAmbiguousDeclarationsAndUnsupportedRenderModels() {
        for (String source : new String[]{"float f(){return 1;}", "#if 1\nconst float a=1;\n#endif",
                "const float a=1; const float a=2;", "#define a 1\nconst float a=2;",
                "#define f(x,x) x\n", "#define f(x) x\n#define f(y) y\n"}) {
            assertThrows(IOException.class, () -> RayMaterialParameters.parse(source), source);
        }
        assertThrows(IOException.class, () -> effect("#define CUSTOM_ENABLE 10\n"));
        assertThrows(IOException.class, () -> effect("#define EMISSIVE_MAP_APPLY_BLINK 1\n"));
        assertThrows(IOException.class, () -> effect("#define ALBEDO_MAP_APPLY_DIFFUSE 2\n"));
    }
}
