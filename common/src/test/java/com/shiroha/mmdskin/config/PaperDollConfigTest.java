package com.shiroha.mmdskin.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纸娃娃配置与枚举单元测试。
 */
class PaperDollConfigTest {

    @Test
    void testPaperDollDefaultConfig() {
        ConfigData config = new ConfigData();
        assertTrue(config.paperDollEnabled, "默认应开启纸娃娃");
        assertEquals(PaperDollPosition.TOP_LEFT, config.paperDollPosition, "默认锚点应为左上角");
        assertEquals(20, config.paperDollOffsetX, "默认X偏移应为20");
        assertEquals(20, config.paperDollOffsetY, "默认Y偏移应为20");
        assertEquals(30.0f, config.paperDollScale, 0.001f, "默认缩放比应为30");
        assertEquals(PaperDollDisplayMode.ALWAYS, config.paperDollDisplayMode, "默认模式应为常驻显示");
        assertEquals(PaperDollRotationMode.FIXED, config.paperDollRotationMode, "默认朝向模式应为固定微侧身");
        assertTrue(config.paperDollShowInScreens, "默认在暂停菜单中显示");
    }

    @Test
    void testPaperDollPositionEnum() {
        assertEquals("左上角", PaperDollPosition.TOP_LEFT.getDisplayName());
        assertEquals("右上角", PaperDollPosition.TOP_RIGHT.getDisplayName());
        assertEquals("左下角", PaperDollPosition.BOTTOM_LEFT.getDisplayName());
        assertEquals("右下角", PaperDollPosition.BOTTOM_RIGHT.getDisplayName());
    }

    @Test
    void testPaperDollDisplayModeEnum() {
        assertEquals("常驻显示", PaperDollDisplayMode.ALWAYS.getDisplayName());
        assertEquals("仅动作触发", PaperDollDisplayMode.DYNAMIC.getDisplayName());
    }

    @Test
    void testPaperDollRotationModeEnum() {
        assertEquals("固定微侧身", PaperDollRotationMode.FIXED.getDisplayName());
        assertEquals("跟随玩家视角", PaperDollRotationMode.FOLLOW_PLAYER.getDisplayName());
    }
}
