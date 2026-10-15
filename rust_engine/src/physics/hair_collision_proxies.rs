//! 生成显式实验用的头发碰撞代理计划，不创建或修改 Bullet 对象。

use crate::model::hair_geometry::{audit_hair_geometry, classify_hair_name, AuditStatus};
use crate::model::MmdModel;
use glam::{Mat4, Quat, Vec3};
use mmd::pmx::rigid_body::{RigidBodyMode, RigidBodyShape};
use std::collections::{HashMap, HashSet};

#[derive(Clone, Debug, PartialEq)]
pub struct HairCollisionProxy {
    pub body_index: usize,
    pub effective_shape: RigidBodyShape,
    pub effective_size: [f32; 3],
    pub bind_transform: Mat4,
    pub raw_shape: RigidBodyShape,
    pub raw_size: [f32; 3],
    pub raw_bind: Mat4,
    pub reason: Option<String>,
    pub covered_point_count: usize,
    pub volume_ratio: Option<f32>,
    pub fitted_center: Vec3,
    pub fitted_axis: usize,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HairCollisionProxySkip {
    pub body_index: usize,
    pub reason: String,
    pub covered_point_count: usize,
    pub volume_ratio: Option<f32>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct HairCollisionProxyPlan {
    pub proxies: Vec<HairCollisionProxy>,
    pub skipped: Vec<HairCollisionProxySkip>,
}

/// 为可靠的动态头发末端生成只读胶囊代理计划。
pub fn build_hair_collision_proxies(model: &MmdModel) -> HairCollisionProxyPlan {
    let audits = audit_hair_geometry(model);
    let dynamic_hair_bodies: HashSet<_> = model
        .rigid_bodies
        .iter()
        .enumerate()
        .filter_map(|(index, body)| {
            let bone_name = usize::try_from(body.bone_index)
                .ok()
                .and_then(|bone| model.bone_manager.get_bone(bone))
                .map(|bone| bone.name.as_str())
                .unwrap_or("");
            (body.mode != RigidBodyMode::Static
                && classify_hair_name(bone_name, &body.local_name, &body.universal_name).candidate)
                .then_some(index)
        })
        .collect();
    let adjacency = dynamic_hair_joint_graph(model, &dynamic_hair_bodies);
    let mut plan = HairCollisionProxyPlan::default();

    for audit in audits {
        let Some(body) = model.rigid_bodies.get(audit.body_index) else {
            continue;
        };
        let mut covered_point_count = 0;
        let mut volume_ratio = audit
            .geometry
            .as_ref()
            .and_then(|geometry| geometry.fitted_to_original_volume_ratio);
        let reason = if audit.status != AuditStatus::Candidate {
            audit
                .skip_reason
                .clone()
                .unwrap_or_else(|| "几何审计未确认候选".into())
        } else if body.shape != RigidBodyShape::Sphere {
            "原始刚体不是球体".into()
        } else {
            match audit.geometry.as_ref() {
                None => "缺少有效几何摘要".into(),
                Some(geometry) => {
                    covered_point_count = geometry.body_local_points.len();
                    volume_ratio = geometry.fitted_to_original_volume_ratio;
                    if let Some(reason) =
                        endpoint_reason(model, audit.body_index, &dynamic_hair_bodies, &adjacency)
                    {
                        reason
                    } else if !geometry.fitted_capsule_radius.is_finite()
                        || geometry.fitted_capsule_radius <= 0.0
                        || !geometry.fitted_capsule_segment_length.is_finite()
                        || geometry.fitted_capsule_segment_length <= 0.0
                    {
                        "拟合胶囊半径或段长无效".into()
                    } else if !capsule_covers_points(geometry) {
                        "拟合胶囊未覆盖全部正权绑定顶点".into()
                    } else if !smaller_than_original(geometry, body.size[0]) {
                        "拟合胶囊未显著小于原球".into()
                    } else {
                        String::new()
                    }
                }
            }
        };

        if !reason.is_empty() {
            plan.skipped.push(HairCollisionProxySkip {
                body_index: audit.body_index,
                reason,
                covered_point_count,
                volume_ratio,
            });
            continue;
        }

        let geometry = audit.geometry.as_ref().unwrap();
        let raw_bind =
            crate::physics::MmdRigidBodyData::from_pmx(body, None, body.size).initial_transform;
        let mut axis = Vec3::ZERO;
        axis[geometry.capsule_axis] = 1.0;
        let orient_capsule = Quat::from_rotation_arc(Vec3::Y, axis);
        let bind_transform = raw_bind
            * Mat4::from_translation(geometry.fitted_capsule_center)
            * Mat4::from_quat(orient_capsule);
        plan.proxies.push(HairCollisionProxy {
            body_index: audit.body_index,
            effective_shape: RigidBodyShape::Capsule,
            effective_size: [
                geometry.fitted_capsule_radius,
                geometry.fitted_capsule_segment_length,
                0.0,
            ],
            bind_transform,
            raw_shape: body.shape,
            raw_size: body.size,
            raw_bind,
            reason: None,
            covered_point_count,
            volume_ratio,
            fitted_center: geometry.fitted_capsule_center,
            fitted_axis: geometry.capsule_axis,
        });
    }
    plan
}

fn dynamic_hair_joint_graph(
    model: &MmdModel,
    dynamic_hair_bodies: &HashSet<usize>,
) -> HashMap<usize, HashSet<usize>> {
    let mut adjacency: HashMap<usize, HashSet<usize>> = HashMap::new();
    for joint in &model.joints {
        let (Ok(a), Ok(b)) = (
            usize::try_from(joint.rigid_body_a_index),
            usize::try_from(joint.rigid_body_b_index),
        ) else {
            continue;
        };
        if a != b
            && dynamic_hair_bodies.contains(&a)
            && dynamic_hair_bodies.contains(&b)
            && a < model.rigid_bodies.len()
            && b < model.rigid_bodies.len()
        {
            adjacency.entry(a).or_default().insert(b);
            adjacency.entry(b).or_default().insert(a);
        }
    }
    adjacency
}

fn endpoint_reason(
    model: &MmdModel,
    body_index: usize,
    dynamic_hair_bodies: &HashSet<usize>,
    adjacency: &HashMap<usize, HashSet<usize>>,
) -> Option<String> {
    let neighbors = adjacency.get(&body_index);
    let degree = neighbors.map_or(0, HashSet::len);
    if degree > 1 {
        return Some("动态头发关节邻居超过一个".into());
    }
    if degree == 0 {
        return Some("没有动态头发关节前驱".into());
    }
    let body = &model.rigid_bodies[body_index];
    let Ok(bone) = usize::try_from(body.bone_index) else {
        return Some("动态头发刚体骨骼索引无效".into());
    };
    let has_predecessor = neighbors.into_iter().flatten().any(|neighbor| {
        let parent_bone = usize::try_from(model.rigid_bodies[*neighbor].bone_index).ok();
        parent_bone.is_some_and(|parent| is_ancestor(model, parent, bone))
    });
    if !has_predecessor {
        return Some("动态头发关节邻居不是骨骼前驱".into());
    }
    let has_continuation = dynamic_hair_bodies.iter().any(|other_body| {
        if *other_body == body_index {
            return false;
        }
        let child_bone = usize::try_from(model.rigid_bodies[*other_body].bone_index).ok();
        child_bone.is_some_and(|child| is_ancestor(model, bone, child))
    });
    has_continuation.then(|| "骨骼存在动态头发后续".into())
}

fn is_ancestor(model: &MmdModel, ancestor: usize, mut bone: usize) -> bool {
    let mut seen = HashSet::new();
    while seen.insert(bone) {
        let Some(link) = model.bone_manager.get_bone(bone) else {
            return false;
        };
        let Ok(parent) = usize::try_from(link.parent_index) else {
            return false;
        };
        if parent == ancestor {
            return true;
        }
        bone = parent;
    }
    false
}

fn capsule_covers_points(geometry: &crate::model::hair_geometry::GeometrySummary) -> bool {
    let scale = geometry
        .body_local_points
        .iter()
        .map(|point| {
            (*point - geometry.fitted_capsule_center)
                .abs()
                .max_element()
        })
        .fold(
            geometry
                .fitted_capsule_radius
                .max(geometry.fitted_capsule_segment_length),
            f32::max,
        );
    if !scale.is_finite() || scale <= 0.0 {
        return false;
    }
    let tolerance = scale * f32::EPSILON * 8.0;
    let half_segment = geometry.fitted_capsule_segment_length * 0.5;
    geometry.body_local_points.iter().all(|point| {
        let delta = *point - geometry.fitted_capsule_center;
        let axial = (delta[geometry.capsule_axis].abs() - half_segment).max(0.0);
        let radial_squared = (0..3)
            .filter(|axis| *axis != geometry.capsule_axis)
            .map(|axis| delta[axis] * delta[axis])
            .sum::<f32>();
        (radial_squared + axial * axial).sqrt() <= geometry.fitted_capsule_radius + tolerance
    })
}

fn smaller_than_original(
    geometry: &crate::model::hair_geometry::GeometrySummary,
    original_radius: f32,
) -> bool {
    let Some(original_volume) = geometry.original_shape_volume else {
        return false;
    };
    if !original_radius.is_finite()
        || !original_volume.is_finite()
        || !geometry.fitted_capsule_volume.is_finite()
    {
        return false;
    }
    let radius_tolerance = original_radius.max(geometry.fitted_capsule_radius) * f32::EPSILON * 8.0;
    let volume_tolerance = original_volume.max(geometry.fitted_capsule_volume) * f32::EPSILON * 8.0;
    geometry.fitted_capsule_radius + radius_tolerance < original_radius
        && geometry.fitted_capsule_volume + volume_tolerance < original_volume
}

#[cfg(test)]
#[path = "hair_collision_proxies/tests.rs"]
mod tests;
