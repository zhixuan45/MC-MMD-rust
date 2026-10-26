//! MMD 物理系统模块

use glam::{Mat4, Vec3};

// 仅 legacy 后端使用的模块：默认（upstream1201）构建下不参与编译，
// 否则整套 legacy 物理实现只会产生 dead_code 告警并拖慢构建。
#[cfg(feature = "legacy-physics")]
pub(crate) mod body_collider_synthesis;
pub mod bullet_ffi;
pub mod collision_topology;
pub mod config;
#[cfg(feature = "legacy-physics")]
mod embedded_body_contacts;
#[cfg(feature = "legacy-physics")]
mod garment_contacts;
pub mod hair_collision_proxies;
mod hair_parameters;
#[cfg(feature = "legacy-physics")]
mod initialization_diagnostics;
mod joint_parameters;
#[cfg(feature = "legacy-physics")]
mod kinematic_target_filter;
mod mmd_joint;
mod mmd_physics;
#[cfg(not(feature = "legacy-physics"))]
mod upstream1201;
#[cfg(not(feature = "legacy-physics"))]
pub(crate) mod upstream_adapter;
mod mmd_rigid_body;
#[cfg(feature = "legacy-physics")]
mod physics_diagnostics;
pub mod solver_quality;
#[cfg(feature = "legacy-physics")]
pub(crate) mod skirt_cross_joints;
#[cfg(feature = "legacy-physics")]
mod skirt_cross_topology;
// tail_forces 两个后端都用（默认构建用波形，legacy 用抬升/加速）；
// tail_wave 同理，两个后端各自使用不同的波形计算入口。
mod tail_forces;
// tail_idle_motion 仅 upstream1201 使用。
#[cfg(not(feature = "legacy-physics"))]
mod tail_idle_motion;
mod tail_wave;

/// Z 轴翻转变换（左手 ↔ 右手坐标系转换，与 saba InvZ 一致）
///
/// 骨骼系统在右手坐标（Z 翻转），Bullet3 物理在左手坐标（MMD 原生）。
/// InvZ(M) = Z * M * Z，其中 Z = diag(1, 1, -1, 1)。
#[inline]
pub(crate) fn inv_z(m: Mat4) -> Mat4 {
    let z = Mat4::from_scale(Vec3::new(1.0, 1.0, -1.0));
    z * m * z
}

#[cfg(all(test, feature = "legacy-physics"))]
mod coordinate_tests {
    use super::inv_z;
    use glam::{Mat4, Quat, Vec3};

    #[test]
    fn inv_z_is_an_involution_for_non_symmetric_joint_pose() {
        let pose = Mat4::from_rotation_translation(
            Quat::from_euler(glam::EulerRot::XYZ, 0.17, -0.52, 0.91),
            Vec3::new(0.3, -1.4, 2.7),
        );
        assert!(inv_z(inv_z(pose)).abs_diff_eq(pose, 1e-5));
    }
}

pub use bullet_ffi::BulletAllocStats;
pub fn get_alloc_stats() -> BulletAllocStats {
    let total = bullet_ffi::get_alloc_stats();
    #[cfg(not(feature = "legacy-physics"))]
    let total = {
        let mut total = total;
        let upstream = upstream1201::bullet_ffi::get_alloc_stats();
        total.worlds += upstream.worlds;
        total.shapes += upstream.shapes;
        total.rigid_bodies += upstream.rigid_bodies;
        total.constraints += upstream.constraints;
        total.motion_states += upstream.motion_states;
        total
    };
    total
}
pub use collision_topology::CollisionStabilityMode;
pub use config::{get_config, reset_config, set_config, PhysicsConfig};
pub use mmd_joint::MmdJointData;
pub use mmd_physics::{
    GarmentContactSnapshot, GarmentPhysicsSnapshot, PhysicsBodySnapshot,
    PhysicsContactPointSnapshot, PhysicsContactPointSnapshotBatch, PhysicsJointSnapshot,
};
pub use mmd_rigid_body::{
    body_collider_scale_flags, effective_collision_shape_size,
    effective_collision_shape_size_with_static_scale, MmdRigidBodyData, PhysicsMode,
    STATIC_COLLISION_SHAPE_SCALE,
};

#[cfg(feature = "legacy-physics")]
pub use mmd_physics::MMDPhysics;
#[cfg(not(feature = "legacy-physics"))]
pub use upstream_adapter::MMDPhysics;
