use super::*;

use glam::Vec3;
use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyShape};

fn body(name: &str, universal: &str, group: u8, raw_mask: u16, mode: RigidBodyMode) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.into(),
        universal_name: universal.into(),
        bone_index: 0,
        group,
        un_collision_group_flag: raw_mask,
        shape: RigidBodyShape::Sphere,
        size: [1.0, 0.0, 0.0],
        position: Vec3::ZERO.to_array(),
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    }
}

fn stable_plan(bodies: &[PmxRigidBody], eligible: &[bool]) -> GarmentContactPlan {
    build_garment_contact_plan(bodies, eligible, true, CollisionStabilityMode::Stable)
}

#[test]
fn skirt_group_14_gets_only_the_pelvis_thigh_collision_pair() {
    let bodies = [
        // 蝶律原值：CBE0 允许裙组 14，BFFF 允许身体组 0 但禁止同组 14。
        body("腰", "", 0, 0xCBE0, RigidBodyMode::Static),
        body("スカート_11_7", "", 14, 0xBFFF, RigidBodyMode::Dynamic),
        body("左脛", "", 0, 0x0000, RigidBodyMode::Static),
        body(
            "胸前穗_0_1",
            "Skirt_0_1",
            14,
            0xBFFF,
            RigidBodyMode::Dynamic,
        ),
    ];
    let plan = stable_plan(&bodies, &[true, false, false, false]);
    assert_eq!(plan.allowed_pairs, vec![(0, 1)]);
    assert_eq!(plan.effective_masks, vec![0xCBE0, 0xBFFF, 0x0000, 0xBFFF]);
    assert!(!plan.forced_ignore_pairs.contains(&(1, 2)));
    assert!(!plan.forced_ignore_pairs.contains(&(1, 3)));
}


#[test]
fn chest_tassel_with_english_skirt_name_is_not_a_garment() {
    let bodies = [
        body("腰", "", 0, 0, RigidBodyMode::Static),
        body(
            "胸前穗_0_1",
            "Skirt_0_1",
            14,
            0xBFFF,
            RigidBodyMode::Dynamic,
        ),
    ];
    let plan = stable_plan(&bodies, &[true, false]);
    assert!(plan.allowed_pairs.is_empty());
    assert!(plan.forced_ignore_pairs.is_empty());
    assert_eq!(plan.effective_masks, vec![0, 0xBFFF]);
}

#[test]
fn strict_and_collision_disabled_preserve_authored_masks() {
    let bodies = [
        body("腰", "", 0, 0, RigidBodyMode::Static),
        body("スカート_11_7", "", 14, 0xBFFF, RigidBodyMode::Dynamic),
    ];
    for plan in [
        build_garment_contact_plan(
            &bodies,
            &[true, false],
            true,
            CollisionStabilityMode::Strict,
        ),
        build_garment_contact_plan(
            &bodies,
            &[true, false],
            false,
            CollisionStabilityMode::Stable,
        ),
    ] {
        assert_eq!(plan.effective_masks, vec![0, 0xBFFF]);
        assert!(plan.allowed_pairs.is_empty());
        assert!(plan.forced_ignore_pairs.is_empty());
    }
}
