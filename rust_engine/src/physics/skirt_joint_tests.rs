use super::MmdJointData;
use crate::physics::bullet_ffi::{BulletRigidBody, BulletShape, RigidBodyInfo};
use glam::{Mat4, Vec3};
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
fn authored_garment_roots_survive_legacy_skirt_heuristic() {
    for (local, alias, mode) in [
        (
            "下擺_0_0",
            "Skirt_0_0",
            RigidBodyMode::DynamicWithBonePosition,
        ),
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
        let c = MmdJointData::from_pmx(
            &j,
            &root,
            &cloth,
            &anchor,
            &skirt,
            root.get_simulation_transform(),
            cloth.get_simulation_transform(),
        );
        let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
        assert!(
            d.angular_lower
                .abs_diff_eq(Vec3::from(j.rotation_min), 1e-5),
            "{local}: {d:?}"
        );
        assert!(
            d.angular_upper
                .abs_diff_eq(Vec3::from(j.rotation_max), 1e-5),
            "{local}: {d:?}"
        );
        assert_eq!(&d.stiffness[3..], &[0.0; 3], "{local}");
    }
}

#[test]
fn authored_garment_chains_survive_legacy_skirt_heuristic() {
    for (parent_name, child_name, with_alias, mode) in [
        (
            "下擺_0_0",
            "下擺_1_0",
            true,
            RigidBodyMode::DynamicWithBonePosition,
        ),
        ("裙_0_0", "裙_1_0", false, RigidBodyMode::Dynamic),
        (
            "Skirt_0_0",
            "Skirt_1_0",
            false,
            RigidBodyMode::DynamicWithBonePosition,
        ),
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
        let c = MmdJointData::from_pmx(
            &j,
            &ar,
            &br,
            &a,
            &b,
            ar.get_simulation_transform(),
            br.get_simulation_transform(),
        );
        let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
        assert!(
            d.angular_lower
                .abs_diff_eq(Vec3::from(j.rotation_min), 1e-5),
            "{parent_name}: {d:?}"
        );
        assert!(
            d.angular_upper
                .abs_diff_eq(Vec3::from(j.rotation_max), 1e-5),
            "{parent_name}: {d:?}"
        );
        assert_eq!(&d.stiffness[3..], &[0.0; 3], "{parent_name}");
    }
}

#[test]
fn existing_legacy_skirt_grid_retains_fallback() {
    let shape = BulletShape::sphere(0.1).unwrap();
    let a = pmx_body(
        "Skirt_0_0",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 10.0, 0.0),
    );
    let b = pmx_body(
        "Skirt_1_0",
        RigidBodyMode::Dynamic,
        Vec3::new(0.0, 8.0, 1.0),
    );
    let ar = bullet_body(&shape, &a);
    let br = bullet_body(&shape, &b);
    let mut j = joint([0.0; 3], 0.0, false);
    j.rotation_min = [-1.5; 3];
    j.rotation_max = [1.5; 3];
    let c = MmdJointData::from_pmx(
        &j,
        &ar,
        &br,
        &a,
        &b,
        ar.get_simulation_transform(),
        br.get_simulation_transform(),
    );
    let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
    let budget = 8.0_f32.to_radians();
    assert!((d.angular_lower.x + budget).abs() < 1e-5);
    assert!((d.angular_upper.x - budget).abs() < 1e-5);
    assert!((d.stiffness[3] - 12.0).abs() < 1e-5);

    let anchor = pmx_body("下半身", RigidBodyMode::Static, Vec3::new(0.0, 12.0, 0.0));
    let root = bullet_body(&shape, &anchor);
    let c = MmdJointData::from_pmx(
        &j,
        &root,
        &ar,
        &anchor,
        &a,
        root.get_simulation_transform(),
        ar.get_simulation_transform(),
    );
    let d = c.constraint.as_ref().unwrap().diagnostic().unwrap();
    let root_budget = Vec3::splat(16.0_f32.to_radians());
    assert!(d.angular_lower.abs_diff_eq(-root_budget, 1e-5));
    assert!(d.angular_upper.abs_diff_eq(root_budget, 1e-5));
    assert_eq!(&d.stiffness[3..], &[16.0, 6.0, 12.0]);
}
