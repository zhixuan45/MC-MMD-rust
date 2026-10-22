package com.shiroha.mmdskin.asset.catalog;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogEntryPmxTest {
    @Test
    void listsOnlyDirectPmxFilesAndResolvesValidSelection(@TempDir Path root) throws Exception {
        Path folder = Files.createDirectory(root.resolve("model"));
        Files.writeString(folder.resolve("model.pmx"), "test");
        Files.writeString(folder.resolve("with_prop.PMX"), "test");
        Files.writeString(folder.resolve("other.pmd"), "test");
        Files.createDirectories(folder.resolve("nested"));
        Files.writeString(folder.resolve("nested/hidden.pmx"), "test");
        List<String> names = ModelCatalogEntry.listPmxFiles(folder.toFile());

        assertEquals(List.of("model.pmx", "with_prop.PMX"), names);
        File selected = ModelCatalogEntry.resolvePmxFile(folder.toFile(), "with_prop.PMX");
        assertNotNull(selected);
        assertEquals("with_prop.PMX", selected.getName());
        assertNull(ModelCatalogEntry.resolvePmxFile(folder.toFile(), "missing.pmx"));
        assertNull(ModelCatalogEntry.resolvePmxFile(folder.toFile(), "../model.pmx"));
        assertNull(ModelCatalogEntry.resolvePmxFile(folder.toFile(), "nested/hidden.pmx"));
        assertNull(ModelCatalogEntry.resolvePmxFile(folder.toFile(), "other.pmd"));
    }
}
