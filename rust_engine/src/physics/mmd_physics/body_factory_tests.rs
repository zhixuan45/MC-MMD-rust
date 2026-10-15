use super::*;
use crate::physics::{MMDPhysics, PhysicsMode};
use mmd::pmx::joint::{Joint, JointType};

fn body(name: &str, bone_index: i32, mode: RigidBodyMode) -> RigidBody {
    RigidBody {
        local_name: name.into(),
        universal_name: name.into(),
        bone_index,
        mode,
        group: 3,
        un_collision_group_flag: 0xffff,
        shape: RigidBodyShape::Sphere,
        size: [1.5, 0.0, 0.0],
        position: [0.4, 2.0, 0.6],
        rotation: [0.2, -0.3, 0.1],
        mass: 0.7,
        move_attenuation: 0.5,
        rotation_attenuation: 0.5,
        repulsion: 0.0,
        friction: 0.0,
    }
}

fn proxy(raw: &RigidBody, index: usize) -> HairCollisionProxy {
    let raw_bind = MmdRigidBodyData::from_pmx(raw, None, raw.size).initial_transform;
    let local = Mat4::from_rotation_translation(
        glam::Quat::from_rotation_z(0.8),
        Vec3::new(0.2, -0.3, 0.4),
    );
    HairCollisionProxy {
        body_index: index,
        effective_shape: RigidBodyShape::Capsule,
        effective_size: [0.35, 1.3, 0.0],
        bind_transform: raw_bind * local,
        raw_shape: raw.shape,
        raw_size: raw.size,
        raw_bind,
        reason: None,
        covered_point_count: 10,
        volume_ratio: Some(0.5),
        fitted_center: Vec3::new(0.2, -0.3, 0.4),
        fitted_axis: 1,
    }
}

#[test]
fn changed_proxy_center_and_axis_preserve_author_world_joint_anchors_and_bone_bind() {
    for mode in [
        RigidBodyMode::Dynamic,
        RigidBodyMode::DynamicWithBonePosition,
    ] {
        let bodies = [
            body("頭", 0, RigidBodyMode::Static),
            body("HairTip", 1, mode),
        ];
        let plan = [proxy(&bodies[1], 1)];
        let anchor = Vec3::new(0.4, 2.8, 0.6);
        let joint = Joint {
            local_name: "HairTipJoint".into(),
            universal_name: String::new(),
            type_: JointType::Spring6DOF,
            rigid_body_a_index: 0,
            rigid_body_b_index: 1,
            position: anchor.to_array(),
            rotation: [0.1, 0.2, -0.1],
            position_min: [0.0; 3],
            position_max: [0.0; 3],
            rotation_min: [-0.2; 3],
            rotation_max: [0.2; 3],
            position_spring: [0.0; 3],
            rotation_spring: [1.0; 3],
        };
        let bind = [
            Mat4::from_translation(Vec3::new(0.0, 2.8, -0.2)),
            Mat4::from_translation(Vec3::new(0.4, 2.5, -0.6)),
        ];
        let mut physics = MMDPhysics::new().unwrap();
        physics.build_physics_with_proxies(
            &bodies,
            &[joint],
            &bind,
            &["頭".into(), "HairTip".into()],
            &plan,
        );
        physics.initialize(&bind);
        let state = physics.body_snapshots_matching("HairTip").remove(0);
        assert_eq!(state.shape_name, "capsule_y");
        assert_eq!(state.raw_shape_name, "sphere");
        assert_eq!(state.raw_shape_size, bodies[1].size);
        assert_eq!(state.mass, bodies[1].mass);
        assert_eq!(
            physics.rigid_bodies[1].collision_mask,
            bodies[1].un_collision_group_flag
        );
        assert_eq!(
            state.mode,
            if mode == RigidBodyMode::Dynamic {
                PhysicsMode::Physics
            } else {
                PhysicsMode::PhysicsWithBone
            }
        );
        let restored = physics.rigid_bodies[1].compute_bone_matrix(state.simulation_transform);
        assert!(restored.abs_diff_eq(crate::physics::inv_z(bind[1]), 1e-5));
        let constraint = physics.joint_snapshots_matching("HairTipJoint").remove(0);
        assert!(constraint.anchor_error < 1e-5);
        assert!(constraint.anchor_a.abs_diff_eq(anchor, 1e-5));
        assert!(constraint.anchor_b.abs_diff_eq(anchor, 1e-5));
    }
}

#[test]
fn invalid_proxy_falls_back_and_snapshot_reads_do_not_advance_or_mutate_world() {
    let raw = body("HairTip", -1, RigidBodyMode::Dynamic);
    let mut candidate = proxy(&raw, 0);
    candidate.bind_transform = Mat4::from_scale(Vec3::ZERO);
    let data = create_body(&raw, None, raw.size, Some(&candidate));
    assert_eq!(data.shape_name, "sphere");
    assert_eq!(data.proxy_source, "pmx");
    candidate = proxy(&raw, 0);
    candidate.raw_size[0] *= 2.0;
    assert_eq!(
        create_body(&raw, None, raw.size, Some(&candidate)).proxy_source,
        "pmx"
    );
    let mut physics = MMDPhysics::new().unwrap();
    physics.build_physics(&[raw], &[], &[]);
    physics.step_simulation(1.0 / 60.0);
    let before = physics.body_snapshots_matching("").remove(0);
    let unmatched = physics.contact_points_matching("missing", 64);
    assert!(unmatched.points.is_empty());
    assert_eq!(unmatched.total_points, 0);
    let _ = physics.contact_points_matching("HairTip", 64);
    let after = physics.body_snapshots_matching("").remove(0);
    assert_eq!(before.simulation_transform, after.simulation_transform);
    assert_eq!(before.render_transform, after.render_transform);
    assert_eq!(before.linear_velocity, after.linear_velocity);
}

#[test]
fn geometry_proxy_keeps_authored_masks_in_every_stability_mode() {
    use crate::physics::{get_config, set_config, CollisionStabilityMode};
    struct Restore(crate::physics::PhysicsConfig);
    impl Drop for Restore {
        fn drop(&mut self) {
            set_config(self.0.clone());
        }
    }
    let _restore = Restore(get_config());
    for mode in [
        CollisionStabilityMode::Strict,
        CollisionStabilityMode::Stable,
        CollisionStabilityMode::Relaxed,
    ] {
        let mut config = get_config();
        config.collision_stability_mode = mode;
        config.collision_enabled = true;
        set_config(config);
        for masks in [
            (0xffff, 0xffff, true),
            (0, 0xffff, false),
            (0xffff, 0, false),
        ] {
            let mut raw = [
                body("Shoulder", -1, RigidBodyMode::Static),
                body("HairTip", -1, RigidBodyMode::Dynamic),
            ];
            raw[0].group = 0;
            raw[1].group = 1;
            raw[0].un_collision_group_flag = masks.0;
            raw[1].un_collision_group_flag = masks.1;
            let planned = [proxy(&raw[1], 1)];
            let mut physics = MMDPhysics::new().unwrap();
            physics.build_physics_with_proxies(&raw, &[], &[], &[], &planned);
            physics.world.detect_collisions();
            let points = physics.contact_points_matching("HairTip", 64);
            assert_eq!(!points.points.is_empty(), masks.2, "mode={mode:?}");
        }
    }
}
