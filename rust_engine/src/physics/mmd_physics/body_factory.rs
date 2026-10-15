//! 有效代理必须在 shape、惯量、offset 和约束 frame 构建之前落定。
use super::super::{hair_collision_proxies::HairCollisionProxy, mmd_rigid_body::MmdRigidBodyData};
use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

pub(super) fn create_body(
    raw: &RigidBody,
    bone_bind: Option<Mat4>,
    scaled_size: [f32; 3],
    proxy: Option<&HairCollisionProxy>,
) -> MmdRigidBodyData {
    let proxy = proxy.filter(|p| valid_proxy(raw, p));
    let mut effective = raw.clone();
    let size = if let Some(p) = proxy {
        effective.shape = p.effective_shape;
        effective.size = p.effective_size;
        p.effective_size
    } else {
        scaled_size
    };
    let mut data = MmdRigidBodyData::from_pmx(&effective, bone_bind, size);
    data.raw_shape_name = match raw.shape {
        RigidBodyShape::Sphere => "sphere",
        RigidBodyShape::Box => "box",
        RigidBodyShape::Capsule => "capsule_y",
    };
    data.raw_shape_size = raw.size;
    if let Some(p) = proxy {
        data.initial_transform = p.bind_transform;
        data.body_offset_matrix = bone_bind.map_or(p.bind_transform, |bind| {
            super::super::inv_z(bind).inverse() * p.bind_transform
        });
        data.body_offset_matrix_inverse = data.body_offset_matrix.inverse();
        data.proxy_source = "hair_geometry_experimental";
    }
    let shape = MmdRigidBodyData::create_shape(&effective, size);
    data.bullet_body = shape
        .as_ref()
        .and_then(|shape| data.create_rigid_body(&effective, shape));
    data.bullet_shape = shape;
    data
}

fn valid_proxy(raw: &RigidBody, proxy: &HairCollisionProxy) -> bool {
    if raw.mode == RigidBodyMode::Static
        || !proxy.bind_transform.is_finite()
        || proxy.effective_size.iter().any(|v| !v.is_finite())
    {
        return false;
    }
    if proxy.reason.is_some()
        || proxy.effective_shape != RigidBodyShape::Capsule
        || proxy.raw_shape != raw.shape
        || proxy.raw_size != raw.size
    {
        return false;
    }
    let (scale, _, _) = proxy.bind_transform.to_scale_rotation_translation();
    let tolerance = f32::EPSILON.sqrt() * 4.0;
    let raw_bind = MmdRigidBodyData::from_pmx(raw, None, raw.size).initial_transform;
    if !proxy.raw_bind.abs_diff_eq(raw_bind, tolerance) {
        return false;
    }
    if !scale.abs_diff_eq(Vec3::ONE, tolerance) || proxy.bind_transform.determinant() <= 0.0 {
        return false;
    }
    match proxy.effective_shape {
        RigidBodyShape::Sphere => proxy.effective_size[0] > 0.0,
        RigidBodyShape::Capsule => proxy.effective_size[0] > 0.0 && proxy.effective_size[1] > 0.0,
        RigidBodyShape::Box => proxy.effective_size.iter().all(|v| *v > 0.0),
    }
}

#[cfg(test)]
#[path = "body_factory_tests.rs"]
mod tests;
