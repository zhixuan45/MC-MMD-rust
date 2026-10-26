//! MMD 物理系统模块

use glam::{Mat4, Vec3};

pub mod bullet_ffi;
pub mod config;
mod mmd_joint;
mod mmd_physics;
mod mmd_rigid_body;

/// Z 轴翻转变换（左手 ↔ 右手坐标系转换，与 saba InvZ 一致）
///
/// 骨骼系统在右手坐标（Z 翻转），Bullet3 物理在左手坐标（MMD 原生）。
/// InvZ(M) = Z * M * Z，其中 Z = diag(1, 1, -1, 1)。
#[inline]
pub(crate) fn inv_z(m: Mat4) -> Mat4 {
    let z = Mat4::from_scale(Vec3::new(1.0, 1.0, -1.0));
    z * m * z
}

// 本模块只对外暴露真正被使用的上游实现入口；其余 `*::` 子模块由调用方按全路径引用，
// 这里不再重复 re-export（否则私有模块下的 re-export 全是未使用导入）。
pub use mmd_physics::MMDPhysics;
