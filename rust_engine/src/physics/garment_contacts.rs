//! 仅为裙摆与骨盆/大腿补齐 PMX 双向碰撞组位。

use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyMode};

use super::collision_topology::CollisionStabilityMode;
use super::mmd_rigid_body::{is_pelvis_or_thigh_collider, is_skirt_or_lower_garment};

#[derive(Debug, Default, PartialEq, Eq)]
pub(super) struct GarmentContactPlan {
    pub effective_masks: Vec<u16>,
    pub allowed_pairs: Vec<(usize, usize)>,
    pub forced_ignore_pairs: Vec<(usize, usize)>,
}

pub(super) fn build_garment_contact_plan(
    bodies: &[PmxRigidBody],
    eligible_body_colliders: &[bool],
    collision_enabled: bool,
    stability_mode: CollisionStabilityMode,
) -> GarmentContactPlan {
    let mut plan = GarmentContactPlan {
        effective_masks: bodies
            .iter()
            .map(|body| body.un_collision_group_flag)
            .collect(),
        ..GarmentContactPlan::default()
    };
    if !collision_enabled || stability_mode == CollisionStabilityMode::Strict {
        return plan;
    }

    for (garment_index, garment) in bodies.iter().enumerate() {
        if garment.mode == RigidBodyMode::Static || !is_skirt_or_lower_garment(garment) {
            continue;
        }
        for (body_index, body) in bodies.iter().enumerate() {
            if body_index == garment_index
                || body.mode != RigidBodyMode::Static
                || !eligible_body_colliders
                    .get(body_index)
                    .copied()
                    .unwrap_or(false)
                || !is_pelvis_or_thigh_collider(body)
            {
                continue;
            }
            plan.effective_masks[garment_index] |= 1u16 << body.group.min(15);
            plan.effective_masks[body_index] |= 1u16 << garment.group.min(15);
            plan.allowed_pairs
                .push((garment_index.min(body_index), garment_index.max(body_index)));
        }
    }
    plan.allowed_pairs.sort_unstable();
    plan.allowed_pairs.dedup();

    for a in 0..bodies.len() {
        for b in (a + 1)..bodies.len() {
            let originally_enabled = masks_allow_pair(
                &bodies[a],
                bodies[a].un_collision_group_flag,
                &bodies[b],
                bodies[b].un_collision_group_flag,
            );
            let now_enabled = masks_allow_pair(
                &bodies[a],
                plan.effective_masks[a],
                &bodies[b],
                plan.effective_masks[b],
            );
            if !originally_enabled
                && now_enabled
                && plan.allowed_pairs.binary_search(&(a, b)).is_err()
            {
                plan.forced_ignore_pairs.push((a, b));
            }
        }
    }
    plan
}

fn masks_allow_pair(a: &PmxRigidBody, a_mask: u16, b: &PmxRigidBody, b_mask: u16) -> bool {
    (a_mask & (1u16 << b.group.min(15))) != 0 && (b_mask & (1u16 << a.group.min(15))) != 0
}

#[cfg(test)]
#[path = "garment_contacts_tests.rs"]
mod tests;
