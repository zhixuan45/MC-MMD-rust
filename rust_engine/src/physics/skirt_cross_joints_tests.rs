use super::*;

use mmd::pmx::joint::JointType;
use mmd::pmx::rigid_body::RigidBodyShape;

fn body(name: &str, universal: &str, position: [f32; 3]) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.to_owned(),
        universal_name: universal.to_owned(),
        bone_index: -1,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Capsule,
        size: [0.2, 0.4, 0.0],
        position,
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode: RigidBodyMode::Dynamic,
    }
}

fn link(a: usize, b: usize) -> PmxJoint {
    PmxJoint {
        local_name: "縦チェーン".to_owned(),
        universal_name: "vertical chain".to_owned(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: a as i32,
        rigid_body_b_index: b as i32,
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

#[test]
fn synthesizes_skirt_rings_without_english_skirt_accessories() {
    let mut bodies = Vec::new();
    let mut joints = Vec::new();
    for (chain, angle) in [0.0_f32, 2.0, 4.0].into_iter().enumerate() {
        let x = angle.cos();
        let z = angle.sin();
        let root = bodies.len();
        bodies.push(body(&format!("裙片_{chain}_上"), "Skirt", [x, 1.0, z]));
        let lower = bodies.len();
        bodies.push(body(
            &format!("裾_{chain}_下"),
            "Skirt",
            [x * 1.3, 0.0, z * 1.3],
        ));
        joints.push(link(root, lower));
    }

    let skirt_indices = 0..bodies.len();
    let chest_tassel = bodies.len();
    bodies.push(body("胸前穗_2_1", "Skirt_2_1", [0.0, 1.0, 0.0]));
    let shoe_tassel = bodies.len();
    bodies.push(body("鞋穗_2_1", "Skirt_2_2", [0.0, 0.0, 0.0]));
    joints.push(link(0, chest_tassel));
    joints.push(link(2, shoe_tassel));

    let synthesized = synthesize_missing_skirt_cross_joints(&bodies, &joints);

    assert!(synthesized.len() >= 3, "真实裙片应生成环向连接");
    assert!(synthesized.iter().all(|joint| {
        skirt_indices.contains(&(joint.rigid_body_a_index as usize))
            && skirt_indices.contains(&(joint.rigid_body_b_index as usize))
    }));
    assert!(synthesized
        .iter()
        .any(|joint| joint.local_name.contains("L0")));
    assert!(synthesized
        .iter()
        .any(|joint| joint.local_name.contains("L1")));
}

#[test]
fn local_part_semantics_take_priority_over_english_skirt_fallback() {
    for name in [
        "胸前穗_2_1",
        "chest_front_01",
        "鞋穗_2_1",
        "胸リボン",
        "長袖_左",
        "髪飾り",
        "右腕",
        "尾巴_01",
    ] {
        assert!(
            !is_skirt_or_lower_garment(&body(name, "Skirt_2_1", [0.0; 3])),
            "附件或尾巴不能因通用名 Skirt 被认作裙摆：{name}"
        );
    }

    for name in ["裙摆_左", "スカート_後", "coat hem", "cape_panel"] {
        assert!(
            is_skirt_or_lower_garment(&body(name, "", [0.0; 3])),
            "{name}"
        );
    }
    assert!(is_skirt_or_lower_garment(&body(
        "RigidBody_01",
        "Skirt_01",
        [0.0; 3]
    )));
    assert!(is_skirt_or_lower_garment(&body(
        "lower_garment_01",
        "Skirt_01",
        [0.0; 3]
    )));
}


fn skirt_body(name: &str, position: [f32; 3]) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: 0,
        group: 1,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [0.2, 0.0, 0.0],
        position,
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.1,
        rotation_attenuation: 0.1,
        repulsion: 0.0,
        friction: 0.5,
        mode: RigidBodyMode::Dynamic,
    }
}

fn legacy_joint(a: i32, b: i32) -> PmxJoint {
    PmxJoint {
        local_name: "vertical".into(),
        universal_name: String::new(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: a,
        rigid_body_b_index: b,
        position: [0.0; 3],
        rotation: [0.0; 3],
        position_min: [0.0; 3],
        position_max: [0.0; 3],
        rotation_min: [-0.5; 3],
        rotation_max: [0.5; 3],
        position_spring: [0.0; 3],
        rotation_spring: [1.0; 3],
    }
}

fn sloped_skirt() -> (Vec<PmxRigidBody>, Vec<PmxJoint>) {
    let mut bodies = Vec::new();
    let mut joints = Vec::new();
    for (i, (x, z, height)) in [
        (1.0, 0.0, 8.0),
        (0.0, 1.0, 6.0),
        (-1.0, 0.0, 7.0),
        (0.0, -1.0, 9.0),
    ]
    .into_iter()
    .enumerate()
    {
        bodies.push(skirt_body(&format!("skirt_{i}_root"), [x, height, z]));
        bodies.push(skirt_body(
            &format!("skirt_{i}_tip"),
            [x * 1.2, height - 1.0, z * 1.2],
        ));
        joints.push(legacy_joint((i * 2) as i32, (i * 2 + 1) as i32));
    }
    (bodies, joints)
}

#[test]
fn unequal_heights_form_rings_at_each_topological_depth() {
    let (bodies, joints) = sloped_skirt();
    let generated = synthesize_missing_skirt_cross_joints(&bodies, &joints);
    assert_eq!(generated.len(), 8);
    for j in &generated {
        assert_eq!(j.rigid_body_a_index % 2, j.rigid_body_b_index % 2);
        assert!(j
            .position
            .iter()
            .chain(j.rotation.iter())
            .all(|v| v.is_finite()));
    }
}

#[test]
fn existing_horizontal_constraints_are_not_duplicated() {
    let (bodies, mut joints) = sloped_skirt();
    joints.extend(synthesize_missing_skirt_cross_joints(&bodies, &joints));
    assert!(synthesize_missing_skirt_cross_joints(&bodies, &joints).is_empty());
}

#[test]
fn malformed_cycle_attached_to_root_terminates() {
    let (bodies, mut joints) = sloped_skirt();
    joints.extend([legacy_joint(1, 3), legacy_joint(3, 1)]);
    assert!(synthesize_missing_skirt_cross_joints(&bodies, &joints).len() <= bodies.len());
}

#[test]
fn non_garment_bodies_and_static_anchors_do_not_form_rings() {
    let (mut bodies, _) = sloped_skirt();
    for b in &mut bodies {
        b.local_name = "hair".into();
    }
    assert!(synthesize_missing_skirt_cross_joints(&bodies, &[]).is_empty());
    for b in &mut bodies {
        b.local_name = "skirt".into();
        b.mode = RigidBodyMode::Static;
    }
    assert!(synthesize_missing_skirt_cross_joints(&bodies, &[]).is_empty());
}
