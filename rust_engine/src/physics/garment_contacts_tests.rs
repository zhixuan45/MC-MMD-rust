use super::*;

use glam::Vec3;
use mmd::pmx::joint::{Joint as PmxJoint, JointType};
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

fn body_on_bone(mut body: PmxRigidBody, bone_index: i32) -> PmxRigidBody {
    body.bone_index = bone_index;
    body
}

fn joint(a: i32, b: i32) -> PmxJoint {
    PmxJoint {
        local_name: "裙根".into(),
        universal_name: String::new(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: a,
        rigid_body_b_index: b,
        position: [0.0; 3],
        rotation: [0.0; 3],
        position_min: [0.0; 3],
        position_max: [0.0; 3],
        rotation_min: [0.0; 3],
        rotation_max: [0.0; 3],
        position_spring: [0.0; 3],
        rotation_spring: [0.0; 3],
    }
}

fn garment_flags(
    bodies: &[PmxRigidBody],
    joints: &[PmxJoint],
    bone_names: &[String],
) -> Vec<bool> {
    super::super::mmd_rigid_body::garment_contact_body_flags(bodies, joints, bone_names)
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

#[test]
fn stable_and_relaxed_expand_real_lower_bodies_but_not_upper_bound_g_shell() {
    let mut anchor = body_on_bone(
        body("下半身", "", 0, 0x0000, RigidBodyMode::Static),
        1,
    );
    anchor.shape = RigidBodyShape::Capsule;
    anchor.size = [0.77, 0.45, 0.0];
    let bodies = [
        body_on_bone(
            body("G-下半身", "", 5, 0xFFFF, RigidBodyMode::Static),
            0,
        ),
        anchor,
        body_on_bone(body("下半身+", "", 0, 0x0000, RigidBodyMode::Static), 1),
        body_on_bone(body("下半身++", "", 0, 0x0000, RigidBodyMode::Static), 1),
        body_on_bone(body("左大腿", "", 0, 0x0000, RigidBodyMode::Static), 2),
        body_on_bone(
            body(
                "Synthesized_Glutes_Collider",
                "",
                0,
                0x0000,
                RigidBodyMode::Static,
            ),
            3,
        ),
        body_on_bone(
            body("q_0_14", "Skirt_0_14", 2, 0xFF01, RigidBodyMode::DynamicWithBonePosition),
            4,
        ),
    ];
    let joints = [joint(1, 6)];
    let names = ["上半身", "下半身", "左足", "腰", "q_0_14"]
        .map(str::to_owned);
    let eligible = garment_flags(&bodies, &joints, &names);

    // 稳定模式只拒绝实绑上身的误标体，真实骨盆、大腿和臀壳仍可补碰撞。
    assert_eq!(eligible, [false, true, true, true, true, true, false]);
    for mode in [CollisionStabilityMode::Stable, CollisionStabilityMode::Relaxed] {
        let plan = build_garment_contact_plan(&bodies, &eligible, true, mode);
        assert!(!plan.allowed_pairs.contains(&(0, 6)));
        for body_index in 1..=5 {
            assert!(plan.allowed_pairs.contains(&(body_index, 6)));
            assert_ne!(plan.effective_masks[body_index] & (1 << 2), 0);
        }
        assert_eq!(plan.effective_masks[0], 0xFFFF);
    }
}

#[test]
fn known_upper_bound_pair_keeps_authored_collision_without_new_extension() {
    let bodies = [
        body_on_bone(body("G-下半身", "", 5, 0xFFFF, RigidBodyMode::Static), 0),
        body_on_bone(body("q_0_14", "Skirt_0_14", 2, 0xFFFF, RigidBodyMode::Dynamic), 1),
    ];
    let names = ["上半身", "q_0_14"].map(str::to_owned);
    let eligible = garment_flags(&bodies, &[], &names);
    let plan = stable_plan(&bodies, &eligible);

    assert_eq!(eligible, [false, false]);
    assert!(plan.allowed_pairs.is_empty());
    assert!(plan.forced_ignore_pairs.is_empty());
    assert_eq!(plan.effective_masks, [0xFFFF, 0xFFFF]);
}

#[test]
fn empty_or_unknown_bone_names_keep_legacy_eligibility_but_invalid_index_is_rejected() {
    let cases = [
        (0, Vec::<String>::new(), true),
        (0, vec!["未知锚点".to_owned()], true),
        (-1, vec!["下半身".to_owned()], false),
        (2, vec!["下半身".to_owned()], false),
    ];
    for (bone_index, names, expected) in cases {
        let bodies = [
            body_on_bone(body("G-下半身", "", 5, 0xFFFF, RigidBodyMode::Static), bone_index),
            body("q_0_14", "Skirt_0_14", 2, 0xFF01, RigidBodyMode::Dynamic),
        ];
        assert_eq!(garment_flags(&bodies, &[], &names), [expected, false]);
    }
}

#[test]
fn strict_keeps_g_and_skirt_authored_masks_unchanged() {
    let bodies = [
        body_on_bone(body("G-下半身", "", 5, 0xFFFF, RigidBodyMode::Static), 0),
        body_on_bone(body("q_0_14", "Skirt_0_14", 2, 0xFF01, RigidBodyMode::Dynamic), 1),
    ];
    let names = ["上半身", "q_0_14"].map(str::to_owned);
    let eligible = garment_flags(&bodies, &[], &names);
    let plan = build_garment_contact_plan(
        &bodies,
        &eligible,
        true,
        CollisionStabilityMode::Strict,
    );

    assert_eq!(plan.effective_masks, [0xFFFF, 0xFF01]);
    assert!(plan.allowed_pairs.is_empty());
    assert!(plan.forced_ignore_pairs.is_empty());
}


#[test]
fn authored_skirt_group_route_does_not_expand_forbidden_body_pairs() {
    let bodies = [
        body("腰小", "", 0, 0xFFFF, RigidBodyMode::Static),
        body("腰大", "", 0, 0x0200, RigidBodyMode::Static),
        body("腰中", "", 0, 0xFF7F, RigidBodyMode::Static),
        body("裙片", "", 7, 0xEF7F, RigidBodyMode::Dynamic),
        body("裙片另一段", "", 7, 0xFF7E, RigidBodyMode::Dynamic),
        body("hair", "", 9, 0xEDFF, RigidBodyMode::Dynamic),
    ];
    let eligible = [true, true, true, false, false, false];
    let authored_masks = bodies
        .iter()
        .map(|body| body.un_collision_group_flag)
        .collect::<Vec<_>>();

    for mode in [CollisionStabilityMode::Stable, CollisionStabilityMode::Relaxed] {
        let plan = build_garment_contact_plan(&bodies, &eligible, true, mode);

        assert_eq!(plan.effective_masks, authored_masks);
        assert_eq!(plan.allowed_pairs, vec![(0, 3)]);
        assert!(plan.forced_ignore_pairs.is_empty());
    }
}

#[test]
fn one_sided_masks_do_not_count_as_an_authored_skirt_body_route() {
    let bodies = [
        body("腰小", "", 0, 0xFF7F, RigidBodyMode::Static),
        body("腰大", "", 0, 0x0200, RigidBodyMode::Static),
        body("裙片", "", 7, 0xFF7E, RigidBodyMode::Dynamic),
        body("裙片另一段", "", 7, 0xEF7F, RigidBodyMode::Dynamic),
    ];
    let eligible = [true, true, false, false];
    let plan = stable_plan(&bodies, &eligible);

    // 每对只有一侧掩码允许，不能据此认定作者已配置身体碰撞通路。
    assert_eq!(plan.allowed_pairs, vec![(0, 2), (0, 3), (1, 2), (1, 3)]);
    assert_eq!(plan.effective_masks, vec![0xFFFF, 0x0280, 0xFF7F, 0xEF7F]);
}

#[test]
fn authored_and_missing_skirt_groups_are_handled_independently() {
    let bodies = [
        body("腰小", "", 0, 0xFFFF, RigidBodyMode::Static),
        body("腰大", "", 0, 0x0200, RigidBodyMode::Static),
        body("腰中", "", 0, 0xFF7F, RigidBodyMode::Static),
        body("裙组7片", "", 7, 0xEF7F, RigidBodyMode::Dynamic),
        body("裙组7另一段", "", 7, 0xFF7E, RigidBodyMode::Dynamic),
        body("腰新组", "", 0, 0x0000, RigidBodyMode::Static),
        body("裙组9片", "", 9, 0x0000, RigidBodyMode::Dynamic),
        body("hair", "", 9, 0xEDFF, RigidBodyMode::Dynamic),
    ];
    let plan = stable_plan(&bodies, &[true, true, true, false, false, true, false, false]);

    assert_eq!(plan.allowed_pairs, vec![(0, 3), (0, 6), (1, 6), (2, 6), (5, 6)]);
    assert_eq!(
        plan.effective_masks,
        vec![0xFFFF, 0x0200, 0xFF7F, 0xEF7F, 0xFF7E, 0x0200, 0x0001, 0xEDFF]
    );
    assert_eq!(plan.forced_ignore_pairs, vec![(5, 7)]);
}



