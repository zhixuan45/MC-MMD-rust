package com.shiroha.mmdskin.player.runtime;

import com.shiroha.mmdskin.bridge.runtime.NativeMorphPort;
import com.shiroha.mmdskin.expression.ExpressionApplicationService;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VpdPosePlaybackTest {
    @TempDir Path dir;
    @AfterEach void cleanup() { ExpressionApplicationService.configureRuntimeCollaborators(null); }

    @Test void matched_pose_detaches_idle_and_hand_layers_but_not_stage_playback() throws Exception {
        Path file = Files.writeString(dir.resolve("pose.vpd"), "pose");
        ExpressionApplicationService.configureRuntimeCollaborators(port((2 << 16) | 1));
        var layers = new ArrayList<Long>();
        var model = model(layers);
        var state = new EntityAnimState(3, null);
        var result = VpdPosePlayback.apply(model, state, file.toString(), "test");
        assertTrue(result.applied()); assertEquals(2, result.bones()); assertEquals(1, result.morphs());
        assertEquals(List.of(0L, 1L, 2L), layers);
        assertTrue(state.playCustomAnim); assertTrue(state.playVpdPose);
        VpdPosePlayback.resumeAfterReset(model, state);
        assertFalse(state.playCustomAnim); assertFalse(state.playVpdPose);
        layers.clear(); state.playStageAnim = true;
        VpdPosePlayback.apply(model, state, file.toString(), "test");
        assertTrue(layers.isEmpty(), "Applying an overlay cannot detach a playing stage's animation layers");
    }

    @Test void invalid_or_unmatched_files_do_not_silently_enter_pose_playback() throws Exception {
        Path file = Files.writeString(dir.resolve("pose.vpd"), "pose");
        var layers = new ArrayList<Long>();
        var model = model(layers);
        var state = new EntityAnimState(3, null);
        for (int code : new int[]{-1, -2, 0}) {
            ExpressionApplicationService.configureRuntimeCollaborators(port(code));
            var result = VpdPosePlayback.apply(model, state, file.toString(), "test");
            assertFalse(result.applied()); assertFalse(state.playVpdPose); assertFalse(state.playCustomAnim);
            assertEquals(code == -1 ? ExpressionApplicationService.VpdStatus.LOAD_FAILED
                    : code == -2 ? ExpressionApplicationService.VpdStatus.MODEL_MISSING : ExpressionApplicationService.VpdStatus.NO_MATCH, result.status());
        }
        assertTrue(layers.isEmpty());
        assertEquals(ExpressionApplicationService.VpdStatus.MISSING_FILE,
                VpdPosePlayback.apply(model, state, dir.resolve("missing.vpd").toString(), "test").status());
    }

    private static ModelInstance model(List<Long> layers) {
        return (ModelInstance) Proxy.newProxyInstance(ModelInstance.class.getClassLoader(), new Class<?>[]{ModelInstance.class}, (p,m,a) -> {
            if (m.getName().equals("getModelHandle")) return 42L;
            if (m.getName().equals("setLayerSpeed")) { assertEquals(0L, a[0]); assertEquals(1.0f, a[1]); return null; }
            if (m.getName().equals("changeAnim")) { assertEquals(0L, a[0]); layers.add((Long)a[1]); return null; }
            throw new AssertionError(m.getName());
        });
    }

    private static NativeMorphPort port(int result) {
        return new NativeMorphPort() {
            public int applyVpdMorph(long model, String file) { assertEquals(42L, model); return result; }
            public void resetAllMorphs(long model) { fail(); }
            public void setMorphWeight(long model, int index, float weight) { fail(); }
            public void syncGpuMorphWeights(long model) { }
        };
    }
}
