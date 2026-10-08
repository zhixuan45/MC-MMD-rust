use super::*;

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

    let synthesized =
        synthesize_missing_body_colliders(&rigid_bodies, &bone_names, &bone_positions);
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

#[test]
fn push_out_moves_point_at_sphere_center_to_surface() {
    let center = Vec3::new(1.0, 10.0, -2.0);
    let spheres = vec![BodyColliderSphere {
        center,
        radius: 1.25,
    }];

    let pushed = push_out_dynamic_bone_position(center, &spheres, &[]);

    assert!(((pushed - center).length() - 1.25).abs() < 1e-5);
}

#[test]
fn push_out_moves_point_on_capsule_axis_to_surface() {
    let capsule = BodyColliderCapsule {
        start: Vec3::new(0.0, 0.0, 0.0),
        end: Vec3::new(0.0, 2.0, 0.0),
        radius: 0.75,
    };
    let point_on_axis = Vec3::new(0.0, 1.0, 0.0);

    let pushed = push_out_dynamic_bone_position(point_on_axis, &[], &[capsule]);

    assert!((pushed.y - point_on_axis.y).abs() < 1e-5);
    assert!(((pushed - point_on_axis).length() - capsule.radius).abs() < 1e-5);
}
