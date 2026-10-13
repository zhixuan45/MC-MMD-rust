use super::*;

fn fill_all(
    bodies: &[PmxRigidBody],
    names: &[impl AsRef<str>],
    positions: &[[f32; 3]],
) -> Vec<PmxRigidBody> {
    let plan = detect_missing_body_colliders(bodies, names, positions);
    apply_body_collider_fill(plan, bodies, true, true)
}

fn test_pmx_body(
    name: &str,
    mode: RigidBodyMode,
    shape: RigidBodyShape,
    size: [f32; 3],
) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.to_owned(),
        universal_name: String::new(),
        bone_index: 0,
        group: 0,
        un_collision_group_flag: 0xFFFF,
        shape,
        size,
        position: [0.0; 3],
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    }
}

#[test]
fn detects_missing_upper_body_collider_when_absent() {
    let only_skirt_colliders = vec![
        test_pmx_body(
            "left_thigh_skirt_collider",
            RigidBodyMode::Static,
            RigidBodyShape::Capsule,
            [1.0, 4.0, 0.0],
        ),
        test_pmx_body(
            "Sp_Hi_Tail0_B_00_body_blocker",
            RigidBodyMode::Static,
            RigidBodyShape::Sphere,
            [1.0, 0.0, 0.0],
        ),
    ];
    assert!(!has_upper_body_collider(&only_skirt_colliders));
}

#[test]
fn detects_upper_body_collider_when_present() {
    let with_chest = vec![test_pmx_body(
        "上半身",
        RigidBodyMode::Static,
        RigidBodyShape::Capsule,
        [0.8, 1.2, 0.0],
    )];
    assert!(has_upper_body_collider(&with_chest));
}

#[test]
fn authored_bust_alias_prevents_duplicate_chest_with_custom_or_missing_bone() {
    let names = ["custom_support", "上半身", "首"];
    let positions = [[0.0, 12.0, 0.0], [0.0, 12.0, 0.0], [0.0, 15.0, 0.0]];
    for bone_index in [0, -1, 10] {
        for (local, universal) in [("bust", ""), ("custom_support", "bust")] {
            let mut body = test_pmx_body(
                local,
                RigidBodyMode::Static,
                RigidBodyShape::Capsule,
                [0.8, 1.2, 0.0],
            );
            body.universal_name = universal.to_owned();
            body.bone_index = bone_index;
            let plan = detect_missing_body_colliders(&[body], &names, &positions);
            assert_eq!(
                plan.upper.len(),
                1,
                "bust must supply existing chest support"
            );
            assert_eq!(plan.upper[0].local_name, "Synthesized_Neck_Collider");
        }
    }
}

#[test]
fn detects_lower_body_collider_when_present_or_absent() {
    let only_tail = vec![test_pmx_body(
        "Sp_Hi_Tail0_B_00_anchor",
        RigidBodyMode::Static,
        RigidBodyShape::Sphere,
        [0.1, 0.0, 0.0],
    )];
    assert!(
        !has_lower_body_collider(&only_tail),
        "尾巴微小锚点不应视为骨盆碰撞体"
    );

    let with_blocker = vec![test_pmx_body(
        "Sp_Hi_Tail0_B_00_body_blocker",
        RigidBodyMode::Static,
        RigidBodyShape::Sphere,
        [1.0, 0.0, 0.0],
    )];
    assert!(
        has_lower_body_collider(&with_blocker),
        "身体阻挡体应视为下半身碰撞体"
    );

    let with_pelvis = vec![test_pmx_body(
        "下半身",
        RigidBodyMode::Static,
        RigidBodyShape::Capsule,
        [0.8, 1.0, 0.0],
    )];
    assert!(has_lower_body_collider(&with_pelvis), "应识别下半身碰撞体");
}

#[test]
fn synthesizes_colliders_for_missing_model() {
    let rigid_bodies = vec![test_pmx_body(
        "Sp_He_Hair2_L_00_anchor",
        RigidBodyMode::Static,
        RigidBodyShape::Sphere,
        [0.1, 0.0, 0.0],
    )];
    let bone_names = vec!["センター", "下半身", "上半身", "首", "頭"];
    let bone_positions = vec![
        [0.0, 0.0, 0.0],
        [0.0, 8.0, 0.0],
        [0.0, 12.0, 0.0],
        [0.0, 15.0, 0.0],
        [0.0, 16.5, 0.0],
    ];

    let synthesized = fill_all(&rigid_bodies, &bone_names, &bone_positions);
    assert_eq!(synthesized.len(), 3, "应合成胸部、颈部和骨盆三个跟骨碰撞体");
    assert_eq!(synthesized[0].bone_index, 2);
    assert_eq!(synthesized[1].bone_index, 3);
    assert_eq!(synthesized[2].bone_index, 1);
    assert_eq!(synthesized[0].mode, RigidBodyMode::Static);
    assert_eq!(synthesized[2].mode, RigidBodyMode::Static);
    assert!(synthesized
        .iter()
        .all(|body| body.un_collision_group_flag == 0xFFFF));
}

#[test]
fn recognizes_native_lower_body_on_a_different_bone_with_custom_name() {
    let mut native = test_pmx_body(
        "custom_support",
        RigidBodyMode::Static,
        RigidBodyShape::Capsule,
        [1.0, 1.0, 0.0],
    );
    native.bone_index = 1;
    let names = ["腰", "下半身"];
    let positions = [[0.0, 12.0, -0.7], [0.0, 12.8, 0.06]];
    assert!(
        fill_all(&[native], &names, &positions).is_empty(),
        "原生下半身不必恰好绑定腰骨，不能再补臀部碰撞体"
    );
}

#[test]
fn thin_capsule_anchor_does_not_count_as_body_support() {
    let anchor = test_pmx_body(
        "下半身",
        RigidBodyMode::Static,
        RigidBodyShape::Capsule,
        [0.01, 4.0, 0.0],
    );
    assert!(!has_lower_body_collider(&[anchor]));
}

#[test]
fn unrelated_english_substrings_are_not_body_regions_or_target_bones() {
    let body = test_pmx_body(
        "membership",
        RigidBodyMode::Static,
        RigidBodyShape::Sphere,
        [1.0, 0.0, 0.0],
    );
    assert!(!has_lower_body_collider(&[body]));
    assert!(fill_all(
        &[],
        &["membership", "necklace"],
        &[[0.0, 10.0, 0.0], [0.0, 15.0, 0.0]]
    )
    .is_empty());
}

#[test]
fn upper_body_bone_overrides_misleading_lower_body_rigid_body_name() {
    let body = test_pmx_body(
        "下半身1",
        RigidBodyMode::Static,
        RigidBodyShape::Sphere,
        [1.0, 0.0, 0.0],
    );
    let generated = fill_all(
        &[body],
        &["上半身", "腰"],
        &[[0.0, 12.0, 0.0], [0.0, 8.0, 0.0]],
    );
    assert_eq!(generated.len(), 1);
    assert_eq!(generated[0].local_name, "Synthesized_Pelvis_Collider");
}

#[test]
fn converts_synthesized_bone_positions_to_pmx_space_and_ignores_missing_positions() {
    let generated = fill_all(&[], &["腰"], &[[1.0, 8.0, 3.0]]);
    assert_eq!(generated.len(), 1);
    assert_eq!(generated[0].position, [1.0, 8.0, -3.0]);
    assert!(fill_all(&[], &["腰"], &[]).is_empty());
}

#[test]
fn skips_candidate_overlapping_a_rotated_dynamic_box_even_when_center_is_outside() {
    let mut skirt = test_pmx_body(
        "skirt",
        RigidBodyMode::Dynamic,
        RigidBodyShape::Box,
        [0.1, 2.0, 0.1],
    );
    skirt.position = [1.3, 10.0, -2.0];
    skirt.rotation[2] = std::f32::consts::FRAC_PI_2;
    let generated = fill_all(&[skirt], &["上半身"], &[[0.0, 10.0, 2.0]]);
    assert!(
        generated.is_empty(),
        "需要检查旋转后的完整形状，不能只检查刚体中心"
    );
}

#[test]
fn long_dynamic_capsule_nearby_does_not_block_a_non_overlapping_candidate() {
    let mut hair = test_pmx_body(
        "hair",
        RigidBodyMode::Dynamic,
        RigidBodyShape::Capsule,
        [0.1, 10.0, 0.0],
    );
    hair.position = [1.5, 10.0, 0.0];
    let generated = fill_all(&[hair], &["上半身"], &[[0.0, 10.0, 0.0]]);
    assert_eq!(generated.len(), 1, "实际形状不相交，包围球重叠不应造成误判");
}

#[test]
fn neck_is_filled_independently_when_chest_already_exists() {
    let body = test_pmx_body(
        "上半身",
        RigidBodyMode::Static,
        RigidBodyShape::Capsule,
        [0.8, 1.2, 0.0],
    );
    let generated = fill_all(
        &[body],
        &["上半身", "首"],
        &[[0.0, 12.0, 0.0], [0.0, 15.0, 0.0]],
    );
    assert_eq!(generated.len(), 1);
    assert_eq!(generated[0].local_name, "Synthesized_Neck_Collider");
}

#[test]
fn detection_and_application_are_separate_for_each_region() {
    let names = ["腰", "上半身", "首"];
    let positions = [[0.0, 8.0, 2.0], [0.0, 12.0, 2.0], [0.0, 15.0, 2.0]];
    let plan = detect_missing_body_colliders(&[], &names, &positions);
    assert_eq!(plan.upper.len(), 2);
    assert_eq!(plan.lower.len(), 1);
    assert_eq!(plan.lower[0].position, [0.0, 8.0, -2.0]);
    let lower = apply_body_collider_fill(plan, &[], false, true);
    assert_eq!(lower.len(), 1);
    assert_eq!(lower[0].local_name, "Synthesized_Pelvis_Collider");
    let upper = apply_body_collider_fill(
        detect_missing_body_colliders(&[], &names, &positions),
        &[],
        true,
        false,
    );
    assert_eq!(upper.len(), 2);
    assert!(upper.iter().all(|body| body.bone_index != 0));
    assert!(apply_body_collider_fill(
        detect_missing_body_colliders(&[], &names, &positions),
        &[],
        false,
        false,
    )
    .is_empty());
}

#[test]
fn push_out_moves_penetrated_point_to_surface() {
    let spheres = vec![BodyColliderSphere {
        center: Vec3::new(0.0, 10.0, 0.0),
        radius: 1.0,
    }];
    let capsules = vec![];

    let inside = Vec3::new(0.0, 10.0, 0.5);
    let pushed = push_out_dynamic_bone_position(inside, &spheres, &capsules);

    assert!((pushed - Vec3::new(0.0, 10.0, 1.0)).length() < 1e-5);
}
