package com.shiroha.mmdskin.render.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldRenderPolicyTest {
    @Test
    void animationLodKeepsPhysicsEnabledAcrossSkippedFrames() {
        // 模拟每三帧更新一次：已有物理链应持续启用。
        for (boolean update : new boolean[]{true, false, false, true, false, false}) {
            var decision = WorldRenderPolicy.worldDecision(update, true);
            assertTrue(decision.shouldRender());
            assertEquals(update, decision.shouldUpdate());
            assertTrue(decision.physicsEnabled(), "LOD 跳帧不能触发物理关闭/重启");
        }
    }

    @Test
    void physicsBudgetCanDisablePhysicsWithoutDisablingAnimation() {
        var updating = WorldRenderPolicy.worldDecision(true, false);
        assertTrue(updating.shouldUpdate());
        assertFalse(updating.physicsEnabled());
        assertFalse(WorldRenderPolicy.worldDecision(false, false).physicsEnabled());
    }
}
