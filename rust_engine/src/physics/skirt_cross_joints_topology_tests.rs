use super::*;

use std::collections::BTreeSet;

use mmd::pmx::joint::JointType;
use mmd::pmx::rigid_body::RigidBodyShape;

fn body(name: &str, position: [f32; 3], mode: RigidBodyMode) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.to_owned(),
        universal_name: String::new(),
        bone_index: -1,
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
        mode,
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

fn three_chains() -> (Vec<PmxRigidBody>, Vec<PmxJoint>) {
    let mut bodies = Vec::new();
    let mut joints = Vec::new();
    for (column, angle) in [0.0_f32, 2.0, 4.0].into_iter().enumerate() {
        let x = angle.cos();
        let z = angle.sin();
        let root = bodies.len();
        bodies.push(body(
            &format!("Skirt_{column}_0"),
            [x, 1.0, z],
            RigidBodyMode::Dynamic,
        ));
        let lower = bodies.len();
        bodies.push(body(
            &format!("Skirt_{column}_1"),
            [x * 1.3, 0.0, z * 1.3],
            RigidBodyMode::Dynamic,
        ));
        joints.push(authored_joint(root, lower));
    }
    (bodies, joints)
}

fn generated_pairs(joints: &[PmxJoint]) -> BTreeSet<(usize, usize)> {
    joints
        .iter()
        .map(|joint| {
            let a = joint.rigid_body_a_index as usize;
            let b = joint.rigid_body_b_index as usize;
            (a.min(b), a.max(b))
        })
        .collect()
}

#[test]
fn authored_cross_ring_edge_does_not_break_vertical_chain_detection() {
    let (bodies, mut joints) = three_chains();
    // PMX 已有一条上层横向约束，仍应补齐剩余环向边和下层环。
    joints.push(authored_joint(0, 2));

    let synthesized = synthesize_missing_skirt_cross_joints(&bodies, &joints);

    assert!(synthesized.len() >= 5, "已有横向边不能让两层环都消失");
}

#[test]
fn reversing_authored_joint_endpoints_preserves_ring_candidates() {
    let (bodies, joints) = three_chains();
    let forward = generated_pairs(&synthesize_missing_skirt_cross_joints(&bodies, &joints));
    let reversed_joints = joints
        .iter()
        .map(|joint| {
            authored_joint(
                joint.rigid_body_b_index as usize,
                joint.rigid_body_a_index as usize,
            )
        })
        .collect::<Vec<_>>();
    let reversed = generated_pairs(&synthesize_missing_skirt_cross_joints(
        &bodies,
        &reversed_joints,
    ));

    assert_eq!(forward, reversed, "PMX A/B 顺序不应改变无向拓扑");
}

#[test]
fn disconnected_static_anchor_families_are_not_cross_connected() {
    let (mut bodies, mut joints) = three_chains();
    // 每个裙片链挂在不同的静态锚点上，分别代表独立服装组件。
    for chain in 0..3 {
        let root = chain * 2;
        let anchor = bodies.len();
        let position = bodies[root].position;
        bodies.push(body(
            &format!("static_anchor_{chain}"),
            position,
            RigidBodyMode::Static,
        ));
        joints.push(authored_joint(anchor, root));
    }

    let synthesized = synthesize_missing_skirt_cross_joints(&bodies, &joints);

    assert!(
        synthesized.is_empty(),
        "不同静态锚点归属的独立服装链不能被合成跨组件环"
    );
}

#[test]
fn non_finite_body_position_does_not_panic_angle_sorting() {
    let (mut bodies, joints) = three_chains();
    bodies[0].position[0] = f32::NAN;

    let result =
        std::panic::catch_unwind(|| synthesize_missing_skirt_cross_joints(&bodies, &joints));

    assert!(result.is_ok(), "非有限几何输入应被安全跳过");
}

#[test]
fn cape_and_cloak_three_chain_rings_remain_eligible() {
    for family in ["cape_panel", "cloak_panel"] {
        let mut bodies = Vec::new();
        let mut joints = Vec::new();
        for (column, angle) in [0.0_f32, 2.0, 4.0].into_iter().enumerate() {
            let x = angle.cos();
            let z = angle.sin();
            let root = bodies.len();
            bodies.push(body(
                &format!("{family}_{column}_0"),
                [x, 1.0, z],
                RigidBodyMode::Dynamic,
            ));
            let lower = bodies.len();
            bodies.push(body(
                &format!("{family}_{column}_1"),
                [x * 1.3, 0.0, z * 1.3],
                RigidBodyMode::Dynamic,
            ));
            joints.push(authored_joint(root, lower));
        }

        let synthesized = synthesize_missing_skirt_cross_joints(&bodies, &joints);

        assert_eq!(
            synthesized.len(),
            6,
            "{family} 是共享服装分类中的合法三链，两层都应生成环"
        );
    }
}

#[test]
fn outer_and_inner_skirt_rings_under_same_pelvis_stay_separate() {
    let mut bone_names = vec!["pelvis".to_owned()];
    let mut bone_parents = vec![-1];
    let mut bodies = Vec::new();
    let mut joints = Vec::new();
    let mut family_by_body = Vec::new();
    for family in ["Skirt_Outer", "Skirt_Inner"] {
        for (column, angle) in [0.0_f32, 2.0, 4.0].into_iter().enumerate() {
            let x = angle.cos();
            let z = angle.sin();
            let root_bone = bone_names.len();
            bone_names.push(format!("{family}_{column}_root"));
            bone_parents.push(0);
            let tip_bone = bone_names.len();
            bone_names.push(format!("{family}_{column}_tip"));
            bone_parents.push(root_bone as i32);
            let root = bodies.len();
            family_by_body.push(family);
            let mut root_body = body(
                &format!("{family}_{column}_0"),
                [x, 1.0, z],
                RigidBodyMode::Dynamic,
            );
            root_body.bone_index = root_bone as i32;
            bodies.push(root_body);
            let lower = bodies.len();
            family_by_body.push(family);
            let mut lower_body = body(
                &format!("{family}_{column}_1"),
                [x * 1.3, 0.0, z * 1.3],
                RigidBodyMode::Dynamic,
            );
            lower_body.bone_index = tip_bone as i32;
            bodies.push(lower_body);
            joints.push(authored_joint(root, lower));
        }
    }

    let synthesized = synthesize_missing_skirt_cross_joints_with_bones(
        &bodies,
        &joints,
        &bone_names,
        &bone_parents,
    );

    assert_eq!(synthesized.len(), 12, "同骨盆下两层裙摆应各保留上下环");
    for joint in synthesized {
        let a = joint.rigid_body_a_index as usize;
        let b = joint.rigid_body_b_index as usize;
        assert_eq!(
            family_by_body[a], family_by_body[b],
            "外裙和内裙不能因共享 pelvis 被横向缝合"
        );
    }
}
