use super::*;

use std::collections::BTreeSet;

use mmd::pmx::joint::JointType;
use mmd::pmx::rigid_body::RigidBodyShape;

fn body(name: &str, bone_index: i32, position: [f32; 3]) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.to_owned(),
        universal_name: String::new(),
        bone_index,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [0.2; 3],
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

fn authored_joint(a: usize, b: usize) -> PmxJoint {
    PmxJoint {
        local_name: "authored".to_owned(),
        universal_name: String::new(),
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

fn positions_for_chain(column: usize, owner_offset: f32) -> ([f32; 3], [f32; 3]) {
    let angle = column as f32 * std::f32::consts::TAU / 3.0;
    let x = angle.cos();
    let z = angle.sin();
    (
        [owner_offset + x, 1.0, z],
        [owner_offset + x * 1.2, 0.0, z * 1.2],
    )
}

fn assert_pair_inside_owner(joints: &[PmxJoint], body_owners: &[usize]) {
    for joint in joints {
        let a = joint.rigid_body_a_index as usize;
        let b = joint.rigid_body_b_index as usize;
        assert_eq!(body_owners[a], body_owners[b], "合成环跨越了骨骼 owner");
    }
}

#[test]
fn head_attached_skirt_names_are_excluded_but_pelvis_chains_form_rings() {
    let names = vec![
        "pelvis".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
        "head".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
    ];
    let parents = vec![-1, 0, 1, 0, 3, 0, 5, -1, 7, 8, 7, 10, 7, 12];
    let mut bodies = Vec::new();
    for column in 0..3 {
        let (upper, lower) = positions_for_chain(column, 0.0);
        bodies.push(body(
            &format!("Skirt_P_{column}_0"),
            (1 + column * 2) as i32,
            upper,
        ));
        bodies.push(body(
            &format!("Skirt_P_{column}_1"),
            (2 + column * 2) as i32,
            lower,
        ));
    }
    for column in 0..3 {
        let (upper, lower) = positions_for_chain(column, 4.0);
        bodies.push(body(
            &format!("Skirt_H_{column}_0"),
            (8 + column * 2) as i32,
            upper,
        ));
        bodies.push(body(
            &format!("Skirt_H_{column}_1"),
            (9 + column * 2) as i32,
            lower,
        ));
    }
    let vertical = (0..6)
        .map(|column| authored_joint(column * 2, column * 2 + 1))
        .collect::<Vec<_>>();

    let synthesized =
        synthesize_missing_skirt_cross_joints_with_bones(&bodies, &vertical, &names, &parents);

    assert_eq!(synthesized.len(), 6, "仅骨盆下的三条裙链应各形成上下两环");
    assert!(synthesized.iter().all(|joint| {
        (joint.rigid_body_a_index as usize) < 6 && (joint.rigid_body_b_index as usize) < 6
    }));
}

#[test]
fn two_bone_owners_get_separate_three_chain_rings() {
    let mut names = vec!["pelvis_a".to_owned(), "pelvis_b".to_owned()];
    let mut parents = vec![-1, -1];
    let mut bodies = Vec::new();
    let mut vertical = Vec::new();
    let mut owners = Vec::new();
    for owner in 0..2 {
        let owner_bone = owner;
        for column in 0..3 {
            let root_bone = names.len();
            names.push("skirt root".to_owned());
            parents.push(owner_bone as i32);
            let tip_bone = names.len();
            names.push("skirt tip".to_owned());
            parents.push(root_bone as i32);
            let (upper, lower) = positions_for_chain(column, owner as f32 * 5.0);
            let root = bodies.len();
            bodies.push(body(
                &format!("Skirt_{owner}_{column}_0"),
                root_bone as i32,
                upper,
            ));
            bodies.push(body(
                &format!("Skirt_{owner}_{column}_1"),
                tip_bone as i32,
                lower,
            ));
            vertical.push(authored_joint(root, root + 1));
            owners.extend([owner, owner]);
        }
    }

    let synthesized =
        synthesize_missing_skirt_cross_joints_with_bones(&bodies, &vertical, &names, &parents);

    assert_eq!(synthesized.len(), 12, "两个 owner 应各保留上下两环");
    assert_pair_inside_owner(&synthesized, &owners);
}

#[test]
fn authored_ring_endpoint_is_not_synthesized_twice() {
    let names = vec![
        "pelvis".to_owned(),
        "skirt root".to_owned(),
        "skirt root".to_owned(),
        "skirt root".to_owned(),
    ];
    let parents = vec![-1, 0, 0, 0];
    let bodies = (0..3)
        .map(|column| {
            let (upper, _) = positions_for_chain(column, 0.0);
            body(&format!("Skirt_{column}_0"), column as i32 + 1, upper)
        })
        .collect::<Vec<_>>();
    let existing = vec![authored_joint(0, 1)];

    let synthesized =
        synthesize_missing_skirt_cross_joints_with_bones(&bodies, &existing, &names, &parents);
    let pairs = synthesized
        .iter()
        .map(|joint| {
            let a = joint.rigid_body_a_index as usize;
            let b = joint.rigid_body_b_index as usize;
            (a.min(b), a.max(b))
        })
        .collect::<BTreeSet<_>>();

    assert_eq!(pairs.len(), synthesized.len(), "候选端点不能重复");
    assert!(
        !pairs.contains(&(0, 1)),
        "原生 PMX 端点应被保留且不重复合成"
    );
}

#[test]
fn malformed_parent_cycle_and_branch_are_rejected_without_unbounded_walk() {
    let mut names = vec![
        "pelvis".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
        "skirt root".to_owned(),
        "skirt tip".to_owned(),
    ];
    let mut parents = vec![-1, 0, 1, 0, 3, 0, 5];
    let mut bodies = Vec::new();
    let mut joints = Vec::new();
    for column in 0..3 {
        let (upper, lower) = positions_for_chain(column, 0.0);
        let root = bodies.len();
        bodies.push(body(
            &format!("Skirt_{column}_0"),
            (1 + column * 2) as i32,
            upper,
        ));
        bodies.push(body(
            &format!("Skirt_{column}_1"),
            (2 + column * 2) as i32,
            lower,
        ));
        joints.push(authored_joint(root, root + 1));
    }
    // 同一根骨骼连到两个子刚体，形成分支。
    names.push("skirt branch".to_owned());
    parents.push(1);
    let (branch_position, _) = positions_for_chain(0, 0.5);
    bodies.push(body("Skirt_branch_0", 7, branch_position));
    joints.push(authored_joint(0, 6));
    let branched =
        synthesize_missing_skirt_cross_joints_with_bones(&bodies, &joints, &names, &parents);
    assert!(branched.is_empty(), "分支链不能生成环候选");

    // 骨架父级环是损坏输入，遍历必须有界并拒绝合成。
    parents[0] = 1;
    let cyclic =
        synthesize_missing_skirt_cross_joints_with_bones(&bodies, &joints, &names, &parents);
    assert!(cyclic.is_empty(), "循环骨架不应产生裙环候选");
}
