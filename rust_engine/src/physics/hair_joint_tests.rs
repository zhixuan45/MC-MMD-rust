use super::*;
use crate::physics::bullet_ffi::{BulletRigidBody, BulletShape, RigidBodyInfo};
use mmd::pmx::{
    joint::{Joint, JointType},
    rigid_body::RigidBodyShape,
};

fn body(name: &str, mode: RigidBodyMode, pos: Vec3) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: -1,
        group: 0,
        un_collision_group_flag: 0xffff,
        shape: RigidBodyShape::Sphere,
        size: [0.2, 0.0, 0.0],
        position: pos.to_array(),
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.8,
        rotation_attenuation: 0.8,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    }
}

fn parameters(spring: [f32; 3]) -> JointParameters {
    JointParameters::from_pmx(
        [0.0; 3],
        [0.0; 3],
        [-0.7, -0.2, -0.35],
        [0.6, 0.2, 0.4],
        [0.0; 3],
        spring,
    )
}

fn apply(p: &mut JointParameters, a: &PmxRigidBody, b: &PmxRigidBody, control: &str) {
    apply_hair_joint_control(
        p,
        a,
        b,
        Vec3::new(0.0, 10.0, 0.0),
        Quat::IDENTITY,
        Mat4::from_translation(Vec3::from(a.position)),
        Mat4::from_translation(Vec3::from(b.position)),
        control,
    );
}

#[test]
fn authored_hair_roots_keep_both_sides_and_springs_in_both_orders_and_modes() {
    for mode in [
        RigidBodyMode::Dynamic,
        RigidBodyMode::DynamicWithBonePosition,
    ] {
        let head = body("頭", RigidBodyMode::Static, Vec3::new(0.0, 11.0, 0.0));
        let hair = body("後髪_0", mode, Vec3::new(0.0, 9.0, 1.0));
        for spring in [[0.0; 3], [16.0, 8.0, 3.0]] {
            for (a, b) in [(&head, &hair), (&hair, &head)] {
                let expected = parameters(spring);
                for control in ["", "authored", "unknown"] {
                    let mut actual = expected;
                    apply(&mut actual, a, b, control);
                    assert_eq!(actual, expected, "{mode:?} {control}");
                }
            }
        }
    }
}

#[test]
fn authored_hair_chain_does_not_acquire_fourteen_degree_limits_or_springs() {
    let a = body(
        "后长发_0_1",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 9.0, 1.0),
    );
    let b = body(
        "后长发_1_1",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 7.0, 1.0),
    );
    let expected = parameters([0.0; 3]);
    let mut actual = expected;
    apply(&mut actual, &a, &b, "");
    assert_eq!(actual, expected);
    apply(&mut actual, &a, &b, "legacy");
    assert!(actual.angular[0].upper < expected.angular[0].upper);
    assert!(actual.angular.iter().all(|axis| axis.spring_enabled));
}

#[test]
fn mirrored_long_hair_chain_gets_extreme_angle_stability_fallback() {
    let a = body("M-后长发_0_1", RigidBodyMode::Dynamic, Vec3::new(0.0, 9.0, 1.0));
    let b = body("M-后长发_1_1", RigidBodyMode::Dynamic, Vec3::new(0.0, 7.0, 1.0));
    let expected = JointParameters::from_pmx(
        [0.0; 3],
        [0.0; 3],
        [-1.0; 3],
        [1.0; 3],
        [0.0; 3],
        [0.0; 3],
    );
    let mut actual = expected;
    apply(&mut actual, &a, &b, "");
    assert!(actual.angular.iter().all(|axis| axis.spring_enabled));
    assert!(actual
        .angular
        .iter()
        .all(|axis| axis.upper <= 14.0_f32.to_radians()));
}

#[test]
fn authored_locked_and_free_hair_axes_stay_locked_and_free() {
    let a = body("頭", RigidBodyMode::Static, Vec3::Y * 11.0);
    let b = body("后长发", RigidBodyMode::Dynamic, Vec3::new(0.0, 9.0, 1.0));
    let expected = JointParameters::from_pmx(
        [0.0; 3],
        [0.0; 3],
        [0.3, 1.0, -0.2],
        [0.3, -1.0, 0.5],
        [0.0; 3],
        [0.0; 3],
    );
    let mut actual = expected;
    apply(&mut actual, &a, &b, "");
    assert_eq!(actual, expected);
}

#[test]
fn legacy_control_still_reproduces_one_sided_root_for_independent_ab() {
    let a = body("頭", RigidBodyMode::Static, Vec3::Y * 11.0);
    let b = body("后长发", RigidBodyMode::Dynamic, Vec3::new(0.0, 9.0, 1.0));
    let mut actual =
        JointParameters::from_pmx([0.0; 3], [0.0; 3], [-0.3; 3], [0.3; 3], [0.0; 3], [0.0; 3]);
    apply(&mut actual, &a, &b, "legacy");
    assert_eq!(actual.angular[0].upper, 0.0);
    assert_eq!(actual.angular[0].lower, -0.3);
}

#[test]
fn bullet_hair_joint_uses_authored_limits_and_world_anchors() {
    let shape = BulletShape::sphere(0.2).unwrap();
    let a = body("頭", RigidBodyMode::Static, Vec3::Y * 11.0);
    let b = body("后长发", RigidBodyMode::Dynamic, Vec3::new(0.0, 9.0, 1.0));
    let make = |pmx: &PmxRigidBody| {
        BulletRigidBody::new(
            &RigidBodyInfo {
                mass: pmx.mass,
                linear_damping: 0.8,
                angular_damping: 0.8,
                friction: 0.0,
                restitution: 0.0,
                additional_damping: false,
                is_kinematic: pmx.mode == RigidBodyMode::Static,
                disable_deactivation: true,
                no_contact_response: false,
                initial_transform: Mat4::from_translation(Vec3::from(pmx.position)),
            },
            &shape,
        )
        .unwrap()
    };
    let ra = make(&a);
    let rb = make(&b);
    let j = Joint {
        local_name: "后长发".into(),
        universal_name: String::new(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: 0,
        rigid_body_b_index: 1,
        position: [0.0, 10.0, 0.0],
        rotation: [0.1, -0.2, 0.3],
        position_min: [0.0; 3],
        position_max: [0.0; 3],
        rotation_min: [-0.7, -0.2, -0.35],
        rotation_max: [0.6, 0.2, 0.4],
        position_spring: [0.0; 3],
        rotation_spring: [0.0; 3],
    };
    let c = MmdJointData::from_pmx(
        &j,
        &ra,
        &rb,
        &a,
        &b,
        ra.get_simulation_transform(),
        rb.get_simulation_transform(),
    );
    let diagnostic = c.constraint.as_ref().unwrap().diagnostic().unwrap();
    assert!(diagnostic
        .angular_lower
        .abs_diff_eq(Vec3::from(j.rotation_min), 1e-6));
    assert!(diagnostic
        .angular_upper
        .abs_diff_eq(Vec3::from(j.rotation_max), 1e-6));
    assert_eq!(&diagnostic.spring_enabled[3..], &[false; 3]);
    let (error, _, _) = joint_anchor_position_error(
        ra.get_simulation_transform(),
        c.frame_a,
        rb.get_simulation_transform(),
        c.frame_b,
    );
    assert!(error < 1e-5);
}
