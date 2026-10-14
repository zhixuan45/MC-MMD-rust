use super::MmdJointData;
use crate::physics::bullet_ffi::{BulletRigidBody, BulletShape, RigidBodyInfo};
use glam::{Mat4, Quat, Vec3};
use mmd::pmx::{
    joint::{Joint, JointType},
    rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape},
};

fn pmx_body(name: &str, mode: RigidBodyMode, pos: Vec3) -> RigidBody {
    RigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: -1,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [0.1, 0.0, 0.0],
        position: pos.to_array(),
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    }
}

fn bullet_body(shape: &BulletShape, pmx: &RigidBody) -> BulletRigidBody {
    BulletRigidBody::new(
        &RigidBodyInfo {
            mass: pmx.mass,
            linear_damping: 0.0,
            angular_damping: 0.0,
            friction: 0.0,
            restitution: 0.0,
            additional_damping: false,
            is_kinematic: pmx.mode == RigidBodyMode::Static,
            disable_deactivation: true,
            no_contact_response: true,
            initial_transform: Mat4::from_translation(Vec3::from(pmx.position)),
        },
        shape,
    )
    .unwrap()
}

fn joint(rotation: [f32; 3], spring: f32, reversed: bool) -> Joint {
    Joint {
        local_name: "probe".into(),
        universal_name: String::new(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: if reversed { 1 } else { 0 },
        rigid_body_b_index: if reversed { 0 } else { 1 },
        position: [0.0, 10.0, 1.0],
        rotation,
        position_min: [0.0; 3],
        position_max: [0.0; 3],
        rotation_min: [-0.6; 3],
        rotation_max: [0.6; 3],
        position_spring: [0.0; 3],
        rotation_spring: [spring; 3],
    }
}

#[test]
fn back_hair_skirt_alias_does_not_apply_garment_limits() {
    let shape = BulletShape::sphere(0.1).unwrap();
    let head = pmx_body("頭", RigidBodyMode::Static, Vec3::new(0.0, 10.0, 0.0));
    let mut hair = pmx_body(
        "后长发_0_1",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 8.0, 1.0),
    );
    hair.universal_name = "Skirt_0_1".into();
    let a = bullet_body(&shape, &head);
    let b = bullet_body(&shape, &hair);
    let mut j = joint([0.0; 3], 0.0, false);
    j.rotation_min = [-std::f32::consts::FRAC_PI_2, -0.17, 0.0];
    j.rotation_max = [0.26, 0.17, 1.04];
    let c = MmdJointData::from_pmx(
        &j,
        &a,
        &b,
        &head,
        &hair,
        a.get_simulation_transform(),
        b.get_simulation_transform(),
    );
    let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
    assert!(
        (d.angular_lower.x - j.rotation_min[0]).abs() < 1e-5,
        "author's broad hair range must survive: {d:?}"
    );
    assert!((d.angular_upper.z - j.rotation_max[2]).abs() < 1e-5);
}

#[test]
fn hair_bow_with_skirt_alias_keeps_authored_joint_parameters() {
    let shape = BulletShape::sphere(0.1).unwrap();
    let a_pmx = pmx_body(
        "后长发_0_1",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 10.0, 1.0),
    );
    let mut b_pmx = pmx_body(
        "长发蝴蝶结L",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 8.0, 1.0),
    );
    b_pmx.universal_name = "Skirt_1_1".into();
    let a = bullet_body(&shape, &a_pmx);
    let b = bullet_body(&shape, &b_pmx);
    let j = joint([0.0; 3], 0.0, false);
    let c = MmdJointData::from_pmx(
        &j,
        &a,
        &b,
        &a_pmx,
        &b_pmx,
        a.get_simulation_transform(),
        b.get_simulation_transform(),
    );
    let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
    assert!(d.angular_lower.abs_diff_eq(Vec3::splat(-0.6), 1e-5));
    assert!(d.angular_upper.abs_diff_eq(Vec3::splat(0.6), 1e-5));
    assert!(!d.spring_enabled.iter().any(|on| *on));
}

#[test]
fn back_hair_limit_allows_outward_motion_for_both_orders_and_joint_frames() {
    for rotation in [[0.0; 3], [0.0, std::f32::consts::PI, 0.0], [0.2, -0.4, 0.3]] {
        for axis in [Vec3::X, Vec3::Z] {
            for reversed in [false, true] {
                for angle in [-0.17_f32, 0.17] {
                    let shape = BulletShape::sphere(0.1).unwrap();
                    let center = Vec3::new(1.0, 8.0, 1.0);
                    let pivot = Vec3::new(1.0, 10.0, 1.0);
                    let head = pmx_body("頭", RigidBodyMode::Static, Vec3::new(0.0, 10.0, 0.0));
                    let hair = pmx_body("後髪_01", RigidBodyMode::Dynamic, center);
                    let head_rb = bullet_body(&shape, &head);
                    let hair_rb = bullet_body(&shape, &hair);
                    let mut j = joint(rotation, 16.0, reversed);
                    j.position = pivot.to_array();
                    let (a, b, pa, pb) = if reversed {
                        (&hair_rb, &head_rb, &hair, &head)
                    } else {
                        (&head_rb, &hair_rb, &head, &hair)
                    };
                    let c = MmdJointData::from_pmx(
                        &j,
                        a,
                        b,
                        pa,
                        pb,
                        a.get_simulation_transform(),
                        b.get_simulation_transform(),
                    );
                    // PMX joint basis follows Bullet setEulerZYX, independently
                    // of the rigid-body Y-X-Z convention.
                    let joint_rotation = Quat::from_rotation_z(rotation[2])
                        * Quat::from_rotation_y(rotation[1])
                        * Quat::from_rotation_x(rotation[0]);
                    let delta = Quat::from_axis_angle(joint_rotation * axis, angle);
                    let position = pivot + delta * (center - pivot);
                    hair_rb.set_transform(Mat4::from_rotation_translation(delta, position));
                    let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
                    // Physical outward motion is measured in world space, independently
                    // of Bullet's angle sign and rigid-body A/B order.
                    let outward = (position - center).dot(Vec3::new(1.0, 0.0, 1.0)) > 0.0;
                    assert_eq!(d.angular_violation.length() < 1e-4, outward,
                        "outward={outward}, rotation={rotation:?}, axis={axis:?}, reversed={reversed}, actual={d:?}");
                }
            }
        }
    }
}

#[test]
fn genuine_skirt_fallback_still_works_with_reversed_body_order() {
    for reversed in [false, true] {
        let shape = BulletShape::sphere(0.1).unwrap();
        let anchor = pmx_body("下半身", RigidBodyMode::Static, Vec3::new(0.0, 10.0, 0.0));
        let skirt = pmx_body(
            "Skirt_0_1",
            RigidBodyMode::Dynamic,
            Vec3::new(0.0, 8.0, 1.0),
        );
        let root = bullet_body(&shape, &anchor);
        let dynamic = bullet_body(&shape, &skirt);
        let mut j = joint([0.0; 3], 0.0, reversed);
        j.rotation_min = [-1.5; 3];
        j.rotation_max = [1.5; 3];
        let (a, b, pa, pb) = if reversed {
            (&dynamic, &root, &skirt, &anchor)
        } else {
            (&root, &dynamic, &anchor, &skirt)
        };
        let c = MmdJointData::from_pmx(
            &j,
            a,
            b,
            pa,
            pb,
            a.get_simulation_transform(),
            b.get_simulation_transform(),
        );
        let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
        assert!(d
            .angular_lower
            .abs_diff_eq(Vec3::splat(-16.0_f32.to_radians()), 1e-5));
        assert!(d
            .angular_upper
            .abs_diff_eq(Vec3::splat(16.0_f32.to_radians()), 1e-5));
        assert_eq!(&d.stiffness[3..], &[16.0, 6.0, 12.0]);
    }
}

#[test]
fn generic_hair_and_non_head_anchors_keep_authored_ranges_and_springs() {
    for (anchor_name, part_name) in [
        ("頭", "hair_01"),
        ("上半身", "後髪_01"),
        ("頭", "hairbow_01"),
        ("頭", "Skirt_0_1"),
    ] {
        let shape = BulletShape::sphere(0.1).unwrap();
        let anchor = pmx_body(
            anchor_name,
            RigidBodyMode::Static,
            Vec3::new(0.0, 10.0, 0.0),
        );
        let part = pmx_body(part_name, RigidBodyMode::Dynamic, Vec3::new(0.0, 8.0, 1.0));
        let a = bullet_body(&shape, &anchor);
        let b = bullet_body(&shape, &part);
        let j = joint([0.0; 3], 0.0, false);
        let c = MmdJointData::from_pmx(
            &j,
            &a,
            &b,
            &anchor,
            &part,
            a.get_simulation_transform(),
            b.get_simulation_transform(),
        );
        let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
        assert!(d.angular_lower.abs_diff_eq(Vec3::splat(-0.6), 1e-5));
        assert!(d.angular_upper.abs_diff_eq(Vec3::splat(0.6), 1e-5));
        assert_eq!(d.stiffness, [0.0; 6], "{anchor_name}/{part_name}");
    }
}

#[test]
fn runtime_uses_direct_bone_semantics_for_template_aliases() {
    use crate::physics::{get_config, set_config, MMDPhysics, PhysicsConfig};
    struct Restore(PhysicsConfig);
    impl Drop for Restore {
        fn drop(&mut self) {
            set_config(self.0.clone());
        }
    }
    let _restore = Restore(get_config());
    set_config(PhysicsConfig::default());
    let mut head = pmx_body("頭", RigidBodyMode::Static, Vec3::new(0.0, 10.0, 0.0));
    let mut hair = pmx_body(
        "Skirt_0_1",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 8.0, 1.0),
    );
    head.bone_index = 0;
    hair.bone_index = 1;
    let bind = [
        Mat4::from_translation(Vec3::new(0.0, 10.0, 0.0)),
        Mat4::from_translation(Vec3::new(0.0, 8.0, -1.0)),
    ];
    let mut j = joint([0.0; 3], 0.0, false);
    j.rotation_min = [-1.57, -0.17, 0.0];
    j.rotation_max = [0.26, 0.17, 1.04];
    let mut physics = MMDPhysics::new().unwrap();
    physics.build_physics_with_bone_names(
        &[head, hair],
        &[j.clone()],
        &bind,
        &["頭".into(), "後髪_01".into()],
    );
    let d = physics.joint_snapshots_matching("").remove(0).diagnostic;
    assert!(d
        .angular_lower
        .abs_diff_eq(Vec3::from(j.rotation_min), 1e-5));
    assert!(d
        .angular_upper
        .abs_diff_eq(Vec3::from(j.rotation_max), 1e-5));
    assert_eq!(&d.stiffness[3..], &[16.0, 10.0, 14.0]);
}

#[test]
fn authored_garment_roots_survive_legacy_skirt_heuristic() {
    for (local, alias, mode) in [
        ("下擺_0_0", "Skirt_0_0", RigidBodyMode::DynamicWithBonePosition),
        ("裙_0_0", "", RigidBodyMode::Dynamic),
        ("Skirt_0_0", "", RigidBodyMode::DynamicWithBonePosition),
    ] {
        let shape = BulletShape::sphere(0.1).unwrap();
        let anchor = pmx_body("下半身", RigidBodyMode::Static, Vec3::new(0.0, 10.0, 0.0));
        let mut skirt = pmx_body(local, mode, Vec3::new(0.0, 8.0, 1.0));
        skirt.universal_name = alias.into();
        let root = bullet_body(&shape, &anchor);
        let cloth = bullet_body(&shape, &skirt);
        let mut j = joint([0.0; 3], 0.0, false);
        j.rotation_min = [-1.5, -0.5, -0.6];
        j.rotation_max = [1.5, 0.5, 0.6];
        let c = MmdJointData::from_pmx(&j, &root, &cloth, &anchor, &skirt,
            root.get_simulation_transform(), cloth.get_simulation_transform());
        let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
        assert!(d.angular_lower.abs_diff_eq(Vec3::from(j.rotation_min), 1e-5), "{local}: {d:?}");
        assert!(d.angular_upper.abs_diff_eq(Vec3::from(j.rotation_max), 1e-5), "{local}: {d:?}");
        assert_eq!(&d.stiffness[3..], &[0.0; 3], "{local}");
    }
}

#[test]
fn authored_garment_chains_survive_legacy_skirt_heuristic() {
    for (parent_name, child_name, with_alias, mode) in [
        ("下擺_0_0", "下擺_1_0", true, RigidBodyMode::DynamicWithBonePosition),
        ("裙_0_0", "裙_1_0", false, RigidBodyMode::Dynamic),
        ("Skirt_0_0", "Skirt_1_0", false, RigidBodyMode::DynamicWithBonePosition),
    ] {
        let shape = BulletShape::sphere(0.1).unwrap();
        let mut a = pmx_body(parent_name, mode, Vec3::new(0.0, 10.0, 0.0));
        let mut b = pmx_body(child_name, mode, Vec3::new(0.0, 8.0, 1.0));
        if with_alias {
            a.universal_name = "Skirt_0_0".into();
            b.universal_name = "Skirt_1_0".into();
        }
        let ar = bullet_body(&shape, &a);
        let br = bullet_body(&shape, &b);
        let mut j = joint([0.0; 3], 0.0, false);
        j.rotation_min = [-1.1, -0.25, -0.4];
        j.rotation_max = [1.1, 0.25, 0.4];
        let c = MmdJointData::from_pmx(&j, &ar, &br, &a, &b,
            ar.get_simulation_transform(), br.get_simulation_transform());
        let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
        assert!(d.angular_lower.abs_diff_eq(Vec3::from(j.rotation_min), 1e-5), "{parent_name}: {d:?}");
        assert!(d.angular_upper.abs_diff_eq(Vec3::from(j.rotation_max), 1e-5), "{parent_name}: {d:?}");
        assert_eq!(&d.stiffness[3..], &[0.0; 3], "{parent_name}");
    }
}

#[test]
fn existing_legacy_skirt_chain_retains_fallback() {
    let shape = BulletShape::sphere(0.1).unwrap();
    let a = pmx_body("Skirt_0_0", RigidBodyMode::Dynamic, Vec3::new(0.0, 10.0, 0.0));
    let b = pmx_body("Skirt_1_0", RigidBodyMode::Dynamic, Vec3::new(0.0, 8.0, 1.0));
    let ar = bullet_body(&shape, &a);
    let br = bullet_body(&shape, &b);
    let mut j = joint([0.0; 3], 0.0, false);
    j.rotation_min = [-1.5; 3];
    j.rotation_max = [1.5; 3];
    let c = MmdJointData::from_pmx(&j, &ar, &br, &a, &b,
        ar.get_simulation_transform(), br.get_simulation_transform());
    let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
    let budget = 8.0_f32.to_radians();
    assert!((d.angular_lower.x + budget).abs() < 1e-5);
    assert!((d.angular_upper.x - budget).abs() < 1e-5);
    assert!((d.stiffness[3] - 12.0).abs() < 1e-5);
}
