#![cfg(not(feature = "legacy-physics"))]
use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};
use mmd_engine::skeleton::BoneLink;
use mmd_engine::{
    physics::{MMDPhysics, PhysicsMode},
    MmdModel,
};

fn body(index: i32, mode: RigidBodyMode) -> RigidBody {
    RigidBody {
        local_name: ["頭", "后长发_0_1", "M-后长发_0_1"][index as usize].into(),
        universal_name: String::new(),
        bone_index: index,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [0.25, 0.0, 0.0],
        position: [0.4, 3.6, -0.3],
        rotation: [0.2, 0.3, -0.1],
        mass: 0.25,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    }
}

#[test]
fn imported_world_owns_simulation_and_preserves_upstream_motion_state_writeback() {
    let old_before = mmd_engine::physics::bullet_ffi::get_alloc_stats();
    {
        let bodies = [
            body(0, RigidBodyMode::Static),
            body(1, RigidBodyMode::Dynamic),
            body(2, RigidBodyMode::DynamicWithBonePosition),
        ];
        let bind = Mat4::from_translation(Vec3::new(0.0, 3.0, 0.0));
        let mut physics = MMDPhysics::new().unwrap();
        physics.build_physics(&bodies, &[], &[bind; 3]);
        physics.initialize(&[bind; 3]);
        physics.set_gravity(0.0, -9.8, 0.0);
        let animated = Mat4::from_translation(Vec3::new(2.0, 4.0, -0.5));
        for _ in 0..120 {
            physics.sync_bodies(&[animated; 3]);
            physics.step_simulation(1.0 / 60.0);
        }
        let states = physics.body_snapshots_matching("");
        assert_eq!(states.len(), 3);
        assert!(states[1]
            .simulation_transform
            .abs_diff_eq(states[2].simulation_transform, 1e-5));
        assert!(states[1].simulation_transform.w_axis.y < -10.0);
        assert!(matches!(states[0].mode, PhysicsMode::FollowBone));
        assert!(matches!(states[2].mode, PhysicsMode::Physics));
        assert!(states.iter().all(|s| s.shape_size == [0.25, 0.0, 0.0]));
        let expected = physics.rigid_bodies[1].compute_bone_matrix(states[1].render_transform);
        let flip = Mat4::from_scale(Vec3::new(1.0, 1.0, -1.0));
        let expected = flip * expected * flip;
        let transforms = physics.get_dynamic_bone_transforms(&[animated; 3]).to_vec();
        assert_eq!(transforms.len(), 2);
        assert!(transforms[0].1.abs_diff_eq(expected, 1e-5));
        assert!(transforms[1].1.abs_diff_eq(expected, 1e-5));
        assert_eq!(bodies[2].mode, RigidBodyMode::DynamicWithBonePosition);
        let old_after = mmd_engine::physics::bullet_ffi::get_alloc_stats();
        assert_eq!(
            old_before.worlds, old_after.worlds,
            "legacy world must not be instantiated"
        );
        assert_eq!(old_before.rigid_bodies, old_after.rigid_bodies);
        assert_eq!(
            mmd_engine::physics::get_alloc_stats().worlds,
            old_before.worlds + 1
        );
    }
    assert_eq!(
        mmd_engine::physics::get_alloc_stats().worlds,
        old_before.worlds
    );
}

#[test]
fn model_keeps_authored_topology_and_resyncs_after_animation_pose_is_ready() {
    let mut model = MmdModel::new();
    for name in ["頭", "后长发_0_1", "M-后长发_0_1"] {
        let mut bone = BoneLink::new(name.into());
        bone.initial_position = Vec3::new(0.0, 3.0, 0.0);
        model.bone_manager.add_bone(bone);
    }
    model.bone_manager.build_hierarchy();
    model.rigid_bodies = vec![
        body(0, RigidBodyMode::Static),
        body(1, RigidBodyMode::Dynamic),
        body(2, RigidBodyMode::DynamicWithBonePosition),
    ];
    assert!(model.init_physics());
    for _ in 0..10 {
        model.update_physics(1.0 / 60.0);
    }
    let before = model.physics_body_snapshots_matching("");
    assert_eq!(before.len(), 3);
    assert!(before[1].simulation_transform.w_axis.y < before[1].initial_transform.w_axis.y);
    model.reset_physics();
    model.reset_physics();
    let requested = model.physics_body_snapshots_matching("");
    assert!(requested[1]
        .simulation_transform
        .abs_diff_eq(before[1].simulation_transform, 1e-5));
    assert!(requested[1]
        .linear_velocity
        .abs_diff_eq(before[1].linear_velocity, 1e-5));

    // 第一人称的零步长求值不能消耗请求，也不能把动态刚体摆回 PMX 原位。
    model.update_physics(0.0);
    let zero_step = model.physics_body_snapshots_matching("");
    assert!(zero_step[1]
        .simulation_transform
        .abs_diff_eq(before[1].simulation_transform, 1e-5));

    // 动画后才提交的新姿态是唯一一次重同步的目标。
    let animated = Mat4::from_translation(Vec3::new(2.0, 3.0, 0.0));
    model.bone_manager.set_global_transform(1, animated);
    model.update_physics(1.0 / 60.0);
    let applied = model.physics_body_snapshots_matching("");
    let dynamic = &applied[1];
    assert!(!dynamic
        .simulation_transform
        .abs_diff_eq(dynamic.initial_transform, 1e-3));
    assert!(dynamic.linear_velocity.length() < 1e-5);
    assert!((model.bone_manager.get_global_transform(1).w_axis.x - 2.0).abs() < 1e-4);
    assert!(
        dynamic.simulation_transform.w_axis.x > before[1].simulation_transform.w_axis.x + 1.0
    );
    model.update_physics(1.0 / 60.0);
    let next = model.physics_body_snapshots_matching("");
    assert!(next[1].simulation_transform.w_axis.y < dynamic.simulation_transform.w_axis.y);
    let info: serde_json::Value = serde_json::from_str(&model.get_physics_debug_info()).unwrap();
    assert_eq!(info["stats"]["total_rb"], 3);
    assert_eq!(info["stats"]["joints"], 0);
    assert_eq!(info["stats"]["dynamic_bone"], 0);
    assert!(model
        .issue_debug_diagnostic(4)
        .contains("backend=upstream1201"));
}

#[test]
fn inertia_uses_block_velocity_without_frame_time_amplification() {
    use mmd_engine::physics::{get_config, set_config, PhysicsConfig};

    let original = get_config();
    set_config(PhysicsConfig {
        gravity_y: 0.0,
        inertia_strength: 0.3,
        physics_fps: 60.0,
        ..PhysicsConfig::default()
    });
    let mut physics = MMDPhysics::new().unwrap();
    let bodies = [body(1, RigidBodyMode::Dynamic)];
    let bind = [Mat4::IDENTITY; 2];
    physics.build_physics(&bodies, &[], &bind);
    physics.initialize(&bind);
    let dt = 1.0 / 60.0;
    physics.sync_bodies_with_model_velocity(&bind, dt, Mat4::IDENTITY);
    // 单步隔离上游额外阻尼，检查 1 方块/秒输入产生的力，而非长期阻尼平衡值。
    let position = Mat4::from_translation(Vec3::new(0.0, 0.0, dt));
    physics.sync_bodies_with_model_velocity(&bind, dt, position);
    physics.step_simulation(dt);
    let velocity = physics.body_snapshots_matching("")[0].linear_velocity;
    set_config(original);
    assert!((velocity.z - 0.3 * dt).abs() < 1e-5, "velocity={velocity:?}");
    assert!(velocity.x.abs() < 1e-5 && velocity.y.abs() < 1e-5);
}
