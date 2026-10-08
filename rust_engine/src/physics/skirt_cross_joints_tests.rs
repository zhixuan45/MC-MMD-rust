use super::*;
use mmd::pmx::rigid_body::RigidBodyShape;

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

fn joint(a: i32, b: i32) -> PmxJoint {
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
        joints.push(joint((i * 2) as i32, (i * 2 + 1) as i32));
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
    joints.extend([joint(1, 3), joint(3, 1)]);
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
