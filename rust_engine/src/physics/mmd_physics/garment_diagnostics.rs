use super::super::mmd_rigid_body::has_non_skirt_local_semantics;
use super::{MMDPhysics, PhysicsMode};

use crate::physics::mmd_rigid_body::MmdRigidBodyData;

#[derive(Debug, Clone)]
pub struct GarmentContactSnapshot {
    pub body_a: String,
    pub body_b: String,
    pub contact_count: i32,
    pub max_penetration: f32,
}

#[derive(Debug, Clone, Default)]
pub struct GarmentPhysicsSnapshot {
    pub contacts: Vec<GarmentContactSnapshot>,
    pub max_simulation_motion_state_delta: f32,
}

fn is_leg(name: &str) -> bool {
    let name = name.to_lowercase();
    [
        "leg",
        "thigh",
        "shin",
        "calf",
        "足",
        "脚",
        "腿",
        "膝",
        "下半身",
        "腰",
        "hip",
        "pelvis",
        "waist",
    ]
    .iter()
    .any(|part| name.contains(part))
}

fn is_body_collider(body: &MmdRigidBodyData) -> bool {
    let local = body.name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    (is_leg(&local) || is_leg(&universal))
        && !has_non_skirt_local_semantics(&local)
        && !has_non_skirt_local_semantics(&universal)
}

fn is_garment(body: &MmdRigidBodyData) -> bool {
    let local = body.name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    // 英文模板名可能出现在胸饰、鞋饰或头发上；本地部位语义优先排除这些假阳性。
    if has_non_skirt_local_semantics(&local) || has_non_skirt_local_semantics(&universal) {
        return false;
    }
    [
        "skirt",
        "dress",
        "petticoat",
        "hem",
        "布",
        "裙",
        "衣摆",
        "スカート",
        "下摆",
        "裾",
    ]
    .iter()
    .any(|part| local.contains(part) || universal.contains(part))
}

impl MMDPhysics {
    /// 读取腿部与裙摆刚体接触及模拟姿态差值，不修改物理状态。
    pub fn garment_snapshot(&self) -> GarmentPhysicsSnapshot {
        let mut snapshot = GarmentPhysicsSnapshot::default();
        for manifold in self.world.contact_manifolds() {
            let (Some(&a), Some(&b)) = (
                self.debug_body_pointer_indices.get(&manifold.body_a),
                self.debug_body_pointer_indices.get(&manifold.body_b),
            ) else {
                continue;
            };
            let (Some(body_a), Some(body_b)) = (self.rigid_bodies.get(a), self.rigid_bodies.get(b))
            else {
                continue;
            };
            let (leg, garment) = if body_a.physics_mode == PhysicsMode::FollowBone
                && matches!(
                    body_b.physics_mode,
                    PhysicsMode::Physics | PhysicsMode::PhysicsWithBone
                ) {
                (body_a, body_b)
            } else if body_b.physics_mode == PhysicsMode::FollowBone
                && matches!(
                    body_a.physics_mode,
                    PhysicsMode::Physics | PhysicsMode::PhysicsWithBone
                )
            {
                (body_b, body_a)
            } else {
                continue;
            };
            if is_body_collider(leg) && is_garment(garment) && manifold.contact_count > 0 {
                snapshot.contacts.push(GarmentContactSnapshot {
                    body_a: body_a.name.clone(),
                    body_b: body_b.name.clone(),
                    contact_count: manifold.contact_count,
                    max_penetration: manifold.max_penetration_depth,
                });
            }
        }
        for body in &self.rigid_bodies {
            if !matches!(
                body.physics_mode,
                PhysicsMode::Physics | PhysicsMode::PhysicsWithBone
            ) || !is_garment(body)
            {
                continue;
            }
            let Some(bullet_body) = body.bullet_body.as_ref() else {
                continue;
            };
            let simulation = bullet_body.get_simulation_transform().w_axis.truncate();
            let motion_state = bullet_body.get_transform().w_axis.truncate();
            let delta = simulation.distance(motion_state);
            if delta.is_finite() {
                snapshot.max_simulation_motion_state_delta =
                    snapshot.max_simulation_motion_state_delta.max(delta);
            }
        }
        snapshot
    }
}
