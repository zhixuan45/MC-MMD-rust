package com.shiroha.mmdskin.asset.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HeldPmxMotionFilesTest {
    @TempDir Path dir;
    @Test void animsTakesPrecedenceAndOtherExtensionsAreNotOffered() throws Exception {
        Files.createDirectories(dir.resolve("anims"));
        Files.write(dir.resolve("prop.vmd"),new byte[]{1});
        Files.write(dir.resolve("anims/prop.vmd"),new byte[]{2});
        Files.write(dir.resolve("anims/舞台.VMD"),new byte[]{3});
        Files.write(dir.resolve("unrelated.pmx"),new byte[]{4});
        assertEquals(List.of("prop.vmd","舞台.VMD"),HeldPmxMotionFiles.list(dir));
        assertEquals(dir.resolve("anims/prop.vmd"),HeldPmxMotionFiles.resolve(dir,"prop.vmd"));
        assertNull(HeldPmxMotionFiles.resolve(dir,"../prop.vmd"));
        assertNull(HeldPmxMotionFiles.resolve(dir,"missing.vmd"));
    }
    @Test void symlinksAreNotLoadedAsLocalPropMotions() throws Exception {
        Path outside=Files.createTempFile("prop-outside-",".vmd");
        try {
            Files.createSymbolicLink(dir.resolve("link.vmd"),outside);
            assertTrue(HeldPmxMotionFiles.list(dir).isEmpty());
            assertNull(HeldPmxMotionFiles.resolve(dir,"link.vmd"));
        } finally { Files.deleteIfExists(outside); }
    }
}
