package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IrisWaterShaderPatcherTest {
    private static final String CONTRACT = """
            void main() {
                vlAlbedo = mix(vec3(1.0), vlAlbedo, sqrt(albedo.a)) * (1.0 - pow(albedo.a, 64.0));
                GetWaterFog(viewPos); albedo.a *= cloudBlendOpacity;
                gl_FragData[0] = albedo; gl_FragData[1] = vec4(vlAlbedo, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            #extension GL_ARB_shader_texture_lod : enable
            layout(location = 0) out vec4 iris_FragData0;
            layout(location = 1) out vec4 iris_FragData1;
            void main() {
                iris_FragData0 = vec4(.2); iris_FragData1 = vec4(.75);
                if (iris_FragData0.a < .001) discard;
            }
            """;

    @Test void copiesCachedSourcesAndKeepsNativeDiscardAndAuxiliaryOutputs() {
        var original = Map.of("VERTEX", "vertex", "FRAGMENT", FRAGMENT);
        var result = IrisWaterShaderPatcher.patch("gbuffers_water", CONTRACT, original);
        assertNotSame(original, result);
        assertEquals(FRAGMENT, original.get("FRAGMENT"));
        String source = result.get("FRAGMENT");
        assertTrue(source.startsWith("#version 430 core"));
        assertTrue(source.contains("#extension GL_ARB_shader_texture_lod : enable"));
        assertTrue(source.contains("if (iris_FragData0.a < .001) discard;"));
        assertTrue(source.contains("iris_FragData1 = vec4(.75);"));
        assertTrue(source.contains("if (MmdWaterCapture) { mmdStore(iris_FragData0)"));
        assertFalse(source.contains("atomicAdd(mmdReserved0"));
        assertFalse(source.contains("atomicOr(mmdReserved0"));
        assertFalse(source.contains("early_fragment_tests"));
        assertFalse(source.contains("gl_FragDepth"));
    }

    @Test void unrelatedProgramsAndUnknownContractsRemainUntouched() {
        var original = Map.of("FRAGMENT", FRAGMENT);
        assertSame(original, IrisWaterShaderPatcher.patch("gbuffers_entities", CONTRACT, original));
        assertSame(original, IrisWaterShaderPatcher.patch("gbuffers_water", "unknown water", original));
    }

    @Test void rejectsUnverifiedStagesDepthWritesAndMissingPrimaryOutput() {
        var geometry = Map.of("FRAGMENT", FRAGMENT, "GEOMETRY", "geometry");
        assertSame(geometry, IrisWaterShaderPatcher.patch("gbuffers_water", CONTRACT, geometry));
        assertNull(IrisWaterShaderPatcher.patchFragment(FRAGMENT.replace("discard;", "gl_FragDepth=.2;")));
        assertNull(IrisWaterShaderPatcher.patchFragment("layout(early_fragment_tests) in;\n" + FRAGMENT));
        assertNull(IrisWaterShaderPatcher.patchFragment(FRAGMENT.replace("location = 0", "location = 2")));
    }

    @Test void refusesDoubleInstrumentationAndHandlesVoidMain() {
        String patched = IrisWaterShaderPatcher.patchFragment(FRAGMENT.replace("main()", "main(void)"));
        assertNotNull(patched);
        assertNull(IrisWaterShaderPatcher.patchFragment(patched));
    }
}
