use super::{effective_collision_mask, ActivePhysicsDebugConfig, PhysicsDebugTelemetry};
use crate::physics::config::PhysicsConfig;
use glam::{Mat4, Vec3};

#[test]
fn movement_velocity_smooths_start_and_settles_after_stop() {
    let mut physics = super::MMDPhysics::new().expect("Bullet world must initialize");
    let dt = 1.0 / 60.0;
    physics.sync_bodies_with_model_velocity(&[], dt, glam::Mat4::IDENTITY);
    let moved = glam::Mat4::from_translation(Vec3::new(0.0, 0.0, 50.0 * dt));
    physics.sync_bodies_with_model_velocity(&[], dt, moved);
    assert!(physics.smoothed_model_velocity.z > 0.0);
    assert!(physics.smoothed_model_velocity.z < 50.0);
    for _ in 0..120 {
        physics.sync_bodies_with_model_velocity(&[], dt, moved);
    }
    assert!(physics.smoothed_model_velocity.length() < 1e-5);
}

#[test]
fn invalid_motion_sample_resets_smoothed_velocity() {
    let mut physics = super::MMDPhysics::new().expect("Bullet world must initialize");
    physics.smoothed_model_velocity = Vec3::ONE;
    physics.sync_bodies_with_model_velocity(&[], f32::NAN, glam::Mat4::IDENTITY);
    assert_eq!(physics.smoothed_model_velocity, Vec3::ZERO);
    assert!(physics.prev_model_position.is_none());
}

#[test]
fn collision_switch_disables_all_contact_pairs() {
    assert_eq!(effective_collision_mask(false, 0xFFFE), 0);
    assert_eq!(effective_collision_mask(true, 0xFFFE), 0xFFFE);
}






#[test]
fn skirt_compatibility_preserves_other_originally_blocked_pairs() {
    use super::MMDPhysics;
    use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

    let make_body = |name: &str, group: u8, raw_mask: u16, mode: RigidBodyMode| RigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: 0,
        group,
        un_collision_group_flag: raw_mask,
        shape: RigidBodyShape::Sphere,
        size: [1.0, 0.0, 0.0],
        position: [0.0; 3],
        rotation: [0.0; 3],
        mass: if mode == RigidBodyMode::Static {
            0.0
        } else {
            1.0
        },
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    };
    let bodies = [
        make_body("腰", 0, 0, RigidBodyMode::Static),
        make_body("スカート_11_7", 14, 0, RigidBodyMode::Dynamic),
        make_body("左脛", 0, 1 << 14, RigidBodyMode::Static),
        make_body("右袖", 14, 1, RigidBodyMode::Dynamic),
    ];
    let mut physics = MMDPhysics::new().expect("Bullet world should be available");
    physics.build_physics(&bodies, &[], &[Mat4::IDENTITY]);
    physics.world.detect_collisions();

    let pairs: Vec<_> = physics
        .world
        .contact_manifolds()
        .into_iter()
        .filter_map(|contact| {
            Some((
                *physics.debug_body_pointer_indices.get(&contact.body_a)?,
                *physics.debug_body_pointer_indices.get(&contact.body_b)?,
            ))
        })
        .map(|(a, b)| (a.min(b), a.max(b)))
        .collect();
    assert!(pairs.contains(&(0, 1)), "skirt should contact the pelvis");
    assert!(
        !pairs.contains(&(1, 2)),
        "the compatibility mask must not enable its shin pair"
    );
    assert!(
        !pairs.contains(&(0, 3)),
        "the compatibility mask must not enable a sleeve pair"
    );
}

#[test]
fn raw_pmx_masks_allow_cbe0_pelvis_with_bfff_skirt_but_block_group_14_self_collision() {
    use super::MMDPhysics;
    use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

    let make_body = |name: &str, group: u8, raw_mask: u16, mode: RigidBodyMode| RigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: 0,
        group,
        un_collision_group_flag: raw_mask,
        shape: RigidBodyShape::Sphere,
        size: [1.0, 0.0, 0.0],
        position: [0.0; 3],
        rotation: [0.0; 3],
        mass: if mode == RigidBodyMode::Static { 0.0 } else { 1.0 },
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    };
    let bodies = [
        make_body("下半身", 0, 0xCBE0, RigidBodyMode::Static),
        make_body("スカート_11_7", 14, 0xBFFF, RigidBodyMode::Dynamic),
        make_body("スカート_11_8", 14, 0xBFFF, RigidBodyMode::Dynamic),
    ];
    let mut physics = MMDPhysics::new().expect("Bullet world should be available");
    physics.build_physics(&bodies, &[], &[Mat4::IDENTITY]);
    physics.world.detect_collisions();

    let pairs: Vec<_> = physics
        .world
        .contact_manifolds()
        .into_iter()
        .filter_map(|contact| {
            Some((
                *physics.debug_body_pointer_indices.get(&contact.body_a)?,
                *physics.debug_body_pointer_indices.get(&contact.body_b)?,
            ))
        })
        .map(|(a, b)| (a.min(b), a.max(b)))
        .collect();
    assert!(pairs.contains(&(0, 1)), "CBE0 and BFFF allow the bilateral pair");
    assert!(pairs.contains(&(0, 2)), "CBE0 and BFFF allow the second body pair");
    assert!(
        !pairs.contains(&(1, 2)),
        "BFFF excludes its own group-14 pair"
    );
}


#[test]
fn dynamic_writeback_uses_contact_solver_pose_in_both_modes() {
    use super::MMDPhysics;
    use glam::{Mat4, Quat};
    use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

    for mode in [
        RigidBodyMode::Dynamic,
        RigidBodyMode::DynamicWithBonePosition,
    ] {
        let body = RigidBody {
            local_name: "测试动态体".into(),
            universal_name: String::new(),
            bone_index: 0,
            group: 1,
            un_collision_group_flag: u16::MAX,
            shape: RigidBodyShape::Sphere,
            size: [0.1, 0.0, 0.0],
            position: [0.0; 3],
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode,
        };
        let mut physics = MMDPhysics::new().unwrap();
        physics.build_physics(&[body], &[], &[Mat4::IDENTITY]);
        physics.world.set_gravity(0.0, 0.0, 0.0);
        let rb = physics.rigid_bodies[0].bullet_body.as_ref().unwrap();
        rb.set_linear_velocity(3.0, 0.0, 0.0);
        rb.set_angular_velocity(0.0, 3.0, 0.0);
        physics.world.step(1.0 / 60.0, 1, 1.0 / 60.0);
        let solved = rb.get_simulation_transform();
        let delayed = rb.get_transform();
        assert!((solved.w_axis.x - delayed.w_axis.x).abs() > 0.04);
        let current = Mat4::from_translation(Vec3::new(7.0, 8.0, 9.0));
        let result = physics.get_dynamic_bone_transforms(&[current])[0].1;
        let solved_right = crate::physics::inv_z(solved);
        let actual_rotation = Quat::from_mat4(&result);
        let expected_rotation = Quat::from_mat4(&solved_right);
        assert!(actual_rotation.dot(expected_rotation).abs() > 0.99999);
        let expected_position = if mode == RigidBodyMode::Dynamic {
            solved_right.w_axis.truncate()
        } else {
            current.w_axis.truncate()
        };
        assert!(result.w_axis.truncate().distance(expected_position) < 1e-5);
    }
}
