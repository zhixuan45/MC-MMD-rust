package com.shiroha.mmdskin.config;

import java.util.Objects;

/** 文件职责：封装提交给 native 运行时的物理配置快照。 */
public record PhysicsConfigSnapshot(
        boolean enabled,
        float gravityY,
        float physicsFps,
        int solverIterations,
        int maxSubstepCount,
        float inertiaStrength,
        float maxLinearVelocity,
        float maxAngularVelocity,
        boolean jointsEnabled,
        boolean kinematicFilter,
        boolean collisionEnabled,
        PhysicsCollisionStabilityMode collisionStabilityMode,
        float staticColliderScale,
        boolean autoUpperBodyColliders,
        boolean autoLowerBodyColliders,
        boolean debugLog) {

    public static PhysicsConfigSnapshot from(ConfigData data) {
        Objects.requireNonNull(data, "data");
        return new PhysicsConfigSnapshot(
                data.physicsEnabled,
                data.physicsGravityY,
                data.physicsFps,
                data.physicsSolverIterations,
                data.physicsMaxSubstepCount,
                data.physicsInertiaStrength,
                data.physicsMaxLinearVelocity,
                data.physicsMaxAngularVelocity,
                data.physicsJointsEnabled,
                data.physicsKinematicFilter,
                data.physicsCollisionEnabled,
                Objects.requireNonNullElse(
                        data.physicsCollisionStabilityMode,
                        PhysicsCollisionStabilityMode.STABLE),
                data.physicsStaticColliderScale,
                data.physicsAutoUpperBodyColliders,
                data.physicsAutoLowerBodyColliders,
                data.physicsDebugLog);
    }

    /** 在平台配置完成注册后生成启动快照，避免只有设置页保存时才同步 native。 */
    public static PhysicsConfigSnapshot fromConfigManager() {
        return new PhysicsConfigSnapshot(
                ConfigManager.isPhysicsEnabled(),
                ConfigManager.getPhysicsGravityY(),
                ConfigManager.getPhysicsFps(),
                ConfigManager.getPhysicsSolverIterations(),
                ConfigManager.getPhysicsMaxSubstepCount(),
                ConfigManager.getPhysicsInertiaStrength(),
                ConfigManager.getPhysicsMaxLinearVelocity(),
                ConfigManager.getPhysicsMaxAngularVelocity(),
                ConfigManager.isPhysicsJointsEnabled(),
                ConfigManager.isPhysicsKinematicFilter(),
                ConfigManager.isPhysicsCollisionEnabled(),
                ConfigManager.getPhysicsCollisionStabilityMode(),
                ConfigManager.getPhysicsStaticColliderScale(),
                ConfigManager.isPhysicsAutoUpperBodyColliders(),
                ConfigManager.isPhysicsAutoLowerBodyColliders(),
                ConfigManager.isPhysicsDebugLog());
    }
}
