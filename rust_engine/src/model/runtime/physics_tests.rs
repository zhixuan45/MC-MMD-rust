use super::*;
use crate::skeleton::BoneLink;
use glam::{Mat4, Quat, Vec3};
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

fn test_body(name: &str, bone_index: i32, position: [f32; 3], mode: RigidBodyMode) -> RigidBody {
    RigidBody {
        local_name: name.to_owned(),
        universal_name: String::new(),
        bone_index,
        group: 0,
        un_collision_group_flag: u16::MAX,
        shape: RigidBodyShape::Sphere,
        size: [0.2, 0.0, 0.0],
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

fn physics_test_model() -> MmdModel {
    let mut model = MmdModel::new();
    for (name, position) in [
        ("Dynamic", Vec3::ZERO),
        ("WithBone", Vec3::new(5.0, 0.0, 0.0)),
    ] {
        let mut bone = BoneLink::new(name.to_owned());
        bone.initial_position = position;
        model.bone_manager.add_bone(bone);
    }
    model.bone_manager.build_hierarchy();
    model.rigid_bodies = vec![
        test_body("Dynamic", 0, [0.0; 3], RigidBodyMode::Dynamic),
        test_body(
            "WithBone",
            1,
            [5.0, 0.0, 0.0],
            RigidBodyMode::DynamicWithBonePosition,
        ),
    ];
    assert!(model.init_physics(), "test Bullet world should initialize");
    let physics = model.physics.as_mut().unwrap();
    physics.set_gravity(0.0, 0.0, 0.0);
    physics.rigid_bodies[0]
        .bullet_body
        .as_ref()
        .unwrap()
        .set_linear_velocity(4.0, 0.0, 0.0);
    physics.rigid_bodies[1]
        .bullet_body
        .as_ref()
        .unwrap()
        .set_angular_velocity(0.0, 0.0, 4.0);
    model
}

fn run_animation_physics_frame(model: &mut MmdModel, delta_time: f32, root_translation: Vec3) {
    model.begin_animation();
    model.bone_manager.set_bone_translation(0, root_translation);
    model.update_node_animation(false);
    model.update_physics(delta_time);
    model.update_node_animation(true);
    model.end_physics_update();
    model.end_animation();
}

fn body_pose(model: &MmdModel, index: usize) -> Mat4 {
    model.physics.as_ref().unwrap().rigid_bodies[index]
        .bullet_body
        .as_ref()
        .unwrap()
        .get_simulation_transform()
}

#[test]
fn skipped_and_zero_steps_keep_bullet_poses_in_the_rendered_skeleton() {
    for delta_time in [0.1, 0.0, -0.01] {
        let mut model = physics_test_model();
        for _ in 0..4 {
            run_animation_physics_frame(&mut model, 1.0 / 60.0, Vec3::ZERO);
        }

        let dynamic_pose = model.bone_manager.get_global_transform(0);
        let with_bone_pose = model.bone_manager.get_global_transform(1);
        let skinning_poses = model.bone_manager.get_skinning_matrices().to_vec();
        assert!(
            dynamic_pose.w_axis.x > 0.01,
            "Bullet should move the dynamic bone"
        );
        assert!(Quat::from_mat4(&with_bone_pose).z.abs() > 0.01);
        let body_pose_before = [body_pose(&model, 0), body_pose(&model, 1)];

        // 大时间步和零/负时间步不能让蒙皮矩阵退回纯动画姿态。
        run_animation_physics_frame(&mut model, delta_time, Vec3::ZERO);
        assert!(model
            .bone_manager
            .get_global_transform(0)
            .abs_diff_eq(dynamic_pose, 1e-4));
        assert!(model
            .bone_manager
            .get_global_transform(1)
            .abs_diff_eq(with_bone_pose, 1e-4));
        for (actual, expected) in model
            .bone_manager
            .get_skinning_matrices()
            .iter()
            .zip(&skinning_poses)
        {
            assert!(actual.abs_diff_eq(*expected, 1e-4));
        }
        for index in 0..2 {
            assert!(body_pose(&model, index).abs_diff_eq(body_pose_before[index], 1e-5));
        }

        // 大步长回收速度；零步长保留速度，下一帧应继续运动。
        run_animation_physics_frame(&mut model, 1.0 / 60.0, Vec3::ZERO);
        let next_dynamic_pose = model.bone_manager.get_global_transform(0);
        let next_with_bone_pose = model.bone_manager.get_global_transform(1);
        if delta_time > 1.0 / 12.0 {
            assert!(next_dynamic_pose.abs_diff_eq(dynamic_pose, 1e-4));
            assert!(next_with_bone_pose.abs_diff_eq(with_bone_pose, 1e-4));
            for (actual, expected) in model
                .bone_manager
                .get_skinning_matrices()
                .iter()
                .zip(&skinning_poses)
            {
                assert!(actual.abs_diff_eq(*expected, 1e-4));
            }
        } else {
            assert!(next_dynamic_pose.w_axis.x > dynamic_pose.w_axis.x);
            assert!(!Quat::from_mat4(&next_with_bone_pose)
                .abs_diff_eq(Quat::from_mat4(&with_bone_pose), 1e-4));
        }
    }
}

#[test]
fn zero_and_nonfinite_steps_preserve_explicit_resync_until_a_valid_frame() {
    for invalid_delta in [0.0, f32::NAN] {
        let mut model = physics_test_model();
        for _ in 0..4 {
            run_animation_physics_frame(&mut model, 1.0 / 60.0, Vec3::ZERO);
        }
        let old_pose = model.bone_manager.get_global_transform(0);
        let old_body_pose = body_pose(&model, 0);
        model.reset_physics();

        run_animation_physics_frame(&mut model, invalid_delta, Vec3::new(2.0, 0.0, 0.0));
        assert!(model.physics_resync_pending);
        assert!(model
            .bone_manager
            .get_global_transform(0)
            .abs_diff_eq(old_pose, 1e-4));
        assert!(body_pose(&model, 0).abs_diff_eq(old_body_pose, 1e-5));

        run_animation_physics_frame(&mut model, 1.0 / 60.0, Vec3::new(3.0, 0.0, 0.0));
        assert!(!model.physics_resync_pending);
        assert!((model.bone_manager.get_global_transform(0).w_axis.x - 3.0).abs() < 1e-3);
    }
}
