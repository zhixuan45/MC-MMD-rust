package com.shiroha.mmdskin.render.effectpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FxWorldGraphTest {
    @TempDir Path temporary;
    private static String graph(String input, String output) {
        return """
            {"format":1,"output":"result","passes":[
              {"name":"grade","input":"%s","output":"%s","effect":"color.fx"}]}
            """.formatted(input, output);
    }
    @Test void directoryImportPreservesGraphDependenciesAndWorldOnlySelection() throws Exception {
        Path pack = temporary.resolve("source"); Files.createDirectories(pack);
        Files.writeString(pack.resolve("color.fx"), FxPackLibraryTest.colorFx("return tex2D(ScreenSampler, uv) * intensity;"));
        Files.writeString(pack.resolve("world.fxgraph.json"), graph("scene", "result"));
        Path library = temporary.resolve("library");
        Path imported = FxPackLibrary.importPack(pack, library);
        var scan = FxPackLibrary.scan(library);
        assertTrue(scan.failures().isEmpty(), scan.failures().toString());
        FxEffect effect = scan.effects().stream().filter(e -> e.graph() != null).findFirst().orElseThrow();
        assertTrue(effect.supportsWorld());
        assertFalse(FxProgram.of(List.of(effect)).enabled());
        assertTrue(Files.readString(imported.resolve("_converted/world.fxgraph.json.graph.json")).contains("colorFunction"));
    }
    @Test void rejectsFeedbackAndForwardReferences() throws Exception {
        Files.writeString(temporary.resolve("color.fx"), FxPackLibraryTest.colorFx("return tex2D(ScreenSampler, uv);"));
        for (String input : List.of("result", "missing"))
            assertThrows(java.io.IOException.class, () -> FxWorldGraph.parse(temporary, temporary.resolve("a.fxgraph.json"), graph(input, "result"), "a"));
    }
    @Test void graphFailureReleasesAllAllocatedTargetsAndDoesNotPresent() {
        var graph = FxRenderGraph.compile(List.of(new FxRenderGraph.Resource("a", 1, 1, "RGBA16F"), new FxRenderGraph.Resource("b", 1, 1, "RGBA16F")),
                List.of(new FxRenderGraph.Pass("a", List.of("scene"), "a", "a"), new FxRenderGraph.Pass("b", List.of("a"), "b", "b")), Set.of("scene"), "b");
        var released = new java.util.ArrayList<String>();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> graph.execute(Map.of("scene", "input"), new FxRenderGraph.Backend<String>() {
            public String create(FxRenderGraph.Resource r) { return r.name(); }
            public void draw(FxRenderGraph.Pass p, List<String> i, String o) { throw new IllegalStateException("draw"); }
            public void present(String o) { fail("failed graph must not present"); }
            public void release(String o) { released.add(o); throw new IllegalArgumentException("release"); }
        }));
        assertEquals("draw", failure.getMessage()); assertEquals(List.of("b", "a"), released); assertEquals(2, failure.getSuppressed().length);
    }
    @Test void rejectsUnsupportedGraphOptionsInsteadOfIgnoringThem() {
        String source = graph("scene", "result").replace("\"format\":1", "\"format\":1,\"depth\":true");
        assertThrows(java.io.IOException.class, () -> FxWorldGraph.parse(temporary, temporary.resolve("a.fxgraph.json"), source, "a"));
    }
}
