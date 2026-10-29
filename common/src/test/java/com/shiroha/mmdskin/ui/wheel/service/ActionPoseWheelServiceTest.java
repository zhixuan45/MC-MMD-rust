package com.shiroha.mmdskin.ui.wheel.service;

import com.shiroha.mmdskin.asset.catalog.AnimationInfo;
import com.shiroha.mmdskin.ui.config.ActionWheelConfig;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ActionPoseWheelServiceTest {
    @Test void enabledPosesProvideRestoreAndFailedSelectionsNeverBroadcast() {
        var pose = ActionWheelConfig.ActionEntry.from(new AnimationInfo("pose", "Pose", "/pose.vpd",
                "pose.vpd", AnimationInfo.AnimSource.CUSTOM, null, 12));
        var messages = new ArrayList<String>();
        var successes = new ArrayList<String>();
        var service = new DefaultActionWheelService(() -> List.of(pose), id -> {
            successes.add(id);
            return !id.equals(pose.animId);
        }, new ActionSyncPort() {
            public void syncAction(String id) { messages.add(id); }
            public void syncAnimStop() { fail("Restoring a pose must not stop stage playback"); }
        });
        assertEquals(List.of(pose.animId, AnimationInfo.RESET_POSE_ACTION),
                service.loadActions().stream().map(ActionOption::animId).toList());
        service.selectAction(pose.animId);
        assertTrue(messages.isEmpty());
        service.selectAction(AnimationInfo.RESET_POSE_ACTION);
        assertEquals(List.of(AnimationInfo.RESET_POSE_ACTION), messages);
        assertEquals(2, successes.size());
    }
}
