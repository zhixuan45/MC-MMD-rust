package com.shiroha.mmdskin.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Yes Steve Model 软兼容层 (NeoForge 1.21.1)
 */
public class YsmCompat {
    private static boolean ysmChecked = false;
    private static boolean ysmPresent = false;

    private static Method ysmIsAvailableMethod = null;
    private static final Map<Class<?>, Method> isYsmModelMethodCache = new ConcurrentHashMap<>();

    private static volatile Method ysmModelIdGetter = null;
    private static volatile Method ysmModelDisabledGetter = null;
    private static volatile Method ysmClientModelActiveGetter = null;
    private static volatile Method ysmClientModelObjectGetter = null;
    private static volatile Method ysmEntityCanRenderFlag1Getter = null;
    private static volatile Method ysmEntityCanRenderFlag2Getter = null;

    private static Object disableSelfModelValue = null;
    private static Object disableOtherModelValue = null;
    private static Object disableSelfHandsValue = null;
    private static Method booleanValueGetMethod = null;

    public static boolean isYsmActive(LivingEntity entity) {
        if (!isYsmModelActive(entity)) return false;
        Minecraft mc = Minecraft.getInstance();
        boolean isLocal = mc.player != null && mc.player.getUUID().equals(entity.getUUID());
        return isLocal ? !isDisableSelfModel() : !isDisableOtherModel();
    }

    public static boolean isYsmModelActive(LivingEntity entity) {
        ensureInit();
        if (!ysmPresent || !isYsmAvailable()) return false;

        try {
            Method m = isYsmModelMethodCache.computeIfAbsent(entity.getClass(), cls -> {
                try { return cls.getMethod("isYsmModel"); } catch (NoSuchMethodException e) { return null; }
            });
            if (m != null && Boolean.TRUE.equals(m.invoke(entity))) return true;
        } catch (Exception ignored) {}

        return false;
    }

    private static void ensureInit() {
        if (ysmChecked) return;
        ysmChecked = true;
        try {
            ysmPresent = ModList.get().isLoaded("yes_steve_model");
        } catch (Throwable e) {
            ysmPresent = false;
        }
        if (!ysmPresent) return;

        try {
            Class<?> ysmMainClass = Class.forName("com.elfmcys.yesstevemodel.YesSteveModel");
            ysmIsAvailableMethod = ysmMainClass.getMethod("isAvailable");

            Class<?> ysmConfigClass = Class.forName("com.elfmcys.yesstevemodel.o00oO00OOO00OOOOo00Oo00O");
            disableSelfModelValue = getStaticFieldValue(ysmConfigClass, "Ooo0oooO0oOOo0o0o0oO0O0o");
            disableOtherModelValue = getStaticFieldValue(ysmConfigClass, "oo00OO0o0oo00oO0O00ooooo");
            disableSelfHandsValue = getStaticFieldValue(ysmConfigClass, "o0OoO0O0OoO0oOoOO0oOooO0");

            if (disableSelfModelValue != null) booleanValueGetMethod = disableSelfModelValue.getClass().getMethod("get");
        } catch (Exception e) {
            ysmPresent = false;
        }
    }

    private static Object getStaticFieldValue(Class<?> clazz, String fieldName) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(null);
    }

    private static boolean isYsmAvailable() {
        ensureInit();
        if (!ysmPresent || ysmIsAvailableMethod == null) return false;
        try { return (Boolean) ysmIsAvailableMethod.invoke(null); } catch (Exception e) { return false; }
    }

    public static boolean isDisableSelfModel() { return getBooleanValue(disableSelfModelValue); }
    public static boolean isDisableOtherModel() { return getBooleanValue(disableOtherModelValue); }
    public static boolean isDisableSelfHands() { return getBooleanValue(disableSelfHandsValue); }

    private static boolean getBooleanValue(Object valueObj) {
        ensureInit();
        if (ysmPresent && valueObj != null && booleanValueGetMethod != null) {
            try { return (Boolean) booleanValueGetMethod.invoke(valueObj); } catch (Exception ignored) {}
        }
        return false;
    }
}
