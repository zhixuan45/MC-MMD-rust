package com.shiroha.mmdskin.asset.catalog;

import com.shiroha.mmdskin.ui.config.ActionWheelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AnimationInfoPoseTest {
    @Test void customFolderOffersVpdAlongsideVmdWithoutLosingSameNamedFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("扇舞.vmd"), "vmd");
        Files.writeString(dir.resolve("扇舞.VPD"), "pose");
        Files.writeString(dir.resolve("other.fbx"), "fbx");
        Files.createDirectory(dir.resolve("folder.vpd"));
        var files = AnimationInfo.scanCustomAnimations(dir.toFile());
        assertEquals(3, files.size());
        var pose = files.stream().filter(AnimationInfo::isPose).findFirst().orElseThrow();
        var vmd = files.stream().filter(info -> info.getFileName().endsWith(".vmd")).findFirst().orElseThrow();
        assertNotEquals(vmd.getCatalogKey(), pose.getCatalogKey());
        var selected = ActionWheelConfig.ActionEntry.from(pose);
        assertTrue(selected.name.endsWith("[VPD]"));
        assertEquals(pose.getActionId(), selected.animId);
        assertTrue(selected.matches(ActionWheelConfig.ActionEntry.from(pose)));
        assertEquals("扇舞", vmd.getActionId());
        assertSame(pose, AnimationInfo.resolvePoseAction(selected.animId, files));
        assertNull(AnimationInfo.resolvePoseAction("vpd-action:../../outside.vpd", files));
        assertNull(AnimationInfo.resolvePoseAction(vmd.getActionId(), files));
    }
}
