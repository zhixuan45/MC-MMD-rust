package com.shiroha.mmdskin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一配置数据类
 */
public class ConfigData {
    private static final Logger logger = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean openGLEnableLighting = true;
    public int modelPoolMaxCount = 20;
    public boolean mmdShaderEnabled = false;

    public boolean gpuSkinningEnabled = false;
    public boolean gpuMorphEnabled = false;
    public int maxBones = 2048;
    public boolean performanceProfilingEnabled = false;
    public int performanceLogIntervalSeconds = 5;
    public int maxVisibleModelsPerFrame = 10;
    public float animationLodMediumDistance = 24.0f;
    public float animationLodFarDistance = 48.0f;
    public int animationLodMediumUpdateInterval = 2;
    public int animationLodFarUpdateInterval = 4;

    public boolean toonRenderingEnabled = false;
    public int toonLevels = 4;
    public float toonRimPower = 5.6f;
    public float toonRimIntensity = 0.02f;
    public float toonShadowR = 0.78f;
    public float toonShadowG = 0.84f;
    public float toonShadowB = 0.94f;
    public float toonSpecularPower = 96.0f;
    public float toonSpecularIntensity = 0.015f;
    public boolean toonOutlineEnabled = true;
    public float toonOutlineWidth = 0.0022f;
    public float toonOutlineR = 0.06f;
    public float toonOutlineG = 0.08f;
    public float toonOutlineB = 0.12f;

    public boolean physicsEnabled = true;
    public float physicsGravityY = -98.0f;
    public float physicsFps = 60.0f;
    public int physicsMaxSubstepCount = 5;
    public float physicsInertiaStrength = 0.5f;
    public float physicsMaxLinearVelocity = 20.0f;
    public float physicsMaxAngularVelocity = 20.0f;
    public boolean physicsJointsEnabled = true;
    /** 实验性过滤器：禁用所有运动学刚体与动态刚体之间的碰撞。 */
    public boolean physicsKinematicFilter = false;
    /** 刚体接触碰撞开关；默认开启以遵循 PMX 碰撞组并防止衣物穿模。 */
    public boolean physicsCollisionEnabled = true;
    /** 默认忽略关节图距离不超过 2 的内部碰撞，减少接触与关节约束竞争。 */
    public PhysicsCollisionStabilityMode physicsCollisionStabilityMode =
            PhysicsCollisionStabilityMode.STABLE;
    /** 跟随骨骼的人体/下半身碰撞体厚度缩放倍率（默认 0.8f，范围 0.1f~1.0f） */
    public float physicsStaticColliderScale = 0.8f;
    public boolean physicsDebugLog = false;
    public int maxPhysicsModelsPerFrame = 10;
    public float physicsLodMaxDistance = 24.0f;

    public boolean firstPersonModelEnabled = false;
    public float firstPersonCameraForwardOffset = 0.0f;
    public float firstPersonCameraVerticalOffset = 0.0f;

    /** 直播防走光模式：在第三人称低机位仰角可能走光时，虚化并隐藏角色 */
    public boolean antiPeekModeEnabled = false;
    /** 防走光起始虚化角度阈值（度）：当相机视线与竖直向上夹角小于等于该值时开始虚化 */
    public float antiPeekThresholdAngle = 25.0f;
    /** 防走光完全隐藏角度阈值（度）：当夹角小于等于该值时彻底隐藏 */
    public float antiPeekHideAngle = 10.0f;

    public int textureCacheBudgetMB = 256;

    public boolean debugHudEnabled = false;

    /** 屏幕角落纸娃娃渲染总开关 */
    public boolean paperDollEnabled = true;
    /** 纸娃娃在屏幕上的锚点位置 */
    public PaperDollPosition paperDollPosition = PaperDollPosition.TOP_LEFT;
    /** 纸娃娃水平边距偏移（像素） */
    public int paperDollOffsetX = 20;
    /** 纸娃娃垂直边距偏移（像素） */
    public int paperDollOffsetY = 20;
    /** 纸娃娃模型缩放比例 */
    public float paperDollScale = 30.0f;
    /** 纸娃娃显示触发模式：ALWAYS(常驻), DYNAMIC(动作触发) */
    public PaperDollDisplayMode paperDollDisplayMode = PaperDollDisplayMode.ALWAYS;
    /** 纸娃娃模型朝向模式：FIXED(固定微侧身), FOLLOW_PLAYER(跟随视角) */
    public PaperDollRotationMode paperDollRotationMode = PaperDollRotationMode.FIXED;
    /** 是否在游戏暂停菜单等常规屏幕中显示纸娃娃 */
    public boolean paperDollShowInScreens = true;

    public boolean vrEnabled = false;
    public float vrArmIKStrength = 1.0f;

    public Map<String, String> mobModelReplacements = new LinkedHashMap<>();

    public static ConfigData load(Path configPath) {
        Path configFile = configPath.resolve("config.json");

        if (!Files.exists(configFile)) {
            ConfigData defaultConfig = new ConfigData();
            defaultConfig.save(configPath);
            return defaultConfig;
        }

        try (Reader reader = Files.newBufferedReader(configFile)) {
            ConfigData config = GSON.fromJson(reader, ConfigData.class);
            if (config == null) {
                logger.warn("配置文件为空，使用默认配置");
                return new ConfigData();
            }
            config.normalize();
            return config;
        } catch (Exception e) {
            logger.error("配置加载失败，使用默认配置: {}", e.getMessage());
            return new ConfigData();
        }
    }

    public void save(Path configPath) {
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath);
            }

            Path configFile = configPath.resolve("config.json");
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            logger.error("保存配置失败: {}", e.getMessage());
        }
    }

    private void normalize() {
        if (mobModelReplacements == null) {
            mobModelReplacements = new LinkedHashMap<>();
        }
        if (physicsCollisionStabilityMode == null) {
            physicsCollisionStabilityMode = PhysicsCollisionStabilityMode.STABLE;
        }

        performanceLogIntervalSeconds = Math.max(1, performanceLogIntervalSeconds);
        maxVisibleModelsPerFrame = Math.max(1, maxVisibleModelsPerFrame);
        animationLodMediumDistance = Math.max(0.0f, animationLodMediumDistance);
        animationLodFarDistance = Math.max(animationLodMediumDistance, animationLodFarDistance);
        animationLodMediumUpdateInterval = Math.max(1, animationLodMediumUpdateInterval);
        animationLodFarUpdateInterval = Math.max(animationLodMediumUpdateInterval, animationLodFarUpdateInterval);
        toonLevels = Math.max(2, Math.min(5, toonLevels));
        toonRimPower = clamp(toonRimPower, 0.1f, 10.0f);
        toonRimIntensity = clamp(toonRimIntensity, 0.0f, 1.0f);
        toonShadowR = clamp(toonShadowR, 0.0f, 1.0f);
        toonShadowG = clamp(toonShadowG, 0.0f, 1.0f);
        toonShadowB = clamp(toonShadowB, 0.0f, 1.0f);
        toonSpecularPower = clamp(toonSpecularPower, 1.0f, 128.0f);
        toonSpecularIntensity = clamp(toonSpecularIntensity, 0.0f, 1.0f);
        toonOutlineWidth = clamp(toonOutlineWidth, 0.001f, 0.02f);
        toonOutlineR = clamp(toonOutlineR, 0.0f, 1.0f);
        toonOutlineG = clamp(toonOutlineG, 0.0f, 1.0f);
        toonOutlineB = clamp(toonOutlineB, 0.0f, 1.0f);
        maxPhysicsModelsPerFrame = Math.max(1, maxPhysicsModelsPerFrame);
        physicsLodMaxDistance = Math.max(0.0f, physicsLodMaxDistance);
        // 身体/下半身碰撞体缩放倍率，范围 0.1x ~ 1.5x
        physicsStaticColliderScale = clamp(physicsStaticColliderScale, 0.1f, 1.5f);
        antiPeekThresholdAngle = clamp(antiPeekThresholdAngle, 5.0f, 60.0f);
        antiPeekHideAngle = clamp(antiPeekHideAngle, 0.0f, antiPeekThresholdAngle);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public void copyTo(ConfigData other) {
        ConfigData copy = GSON.fromJson(GSON.toJson(this), ConfigData.class);

        try {
            for (var field : ConfigData.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.set(other, field.get(copy));
            }
        } catch (IllegalAccessException e) {
            logger.error("配置复制失败", e);
        }
    }
}
