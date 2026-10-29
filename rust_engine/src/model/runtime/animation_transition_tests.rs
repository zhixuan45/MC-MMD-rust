use super::*;
use crate::animation::{BoneKeyframe, Motion};
use crate::skeleton::BoneLink;
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

fn model() -> MmdModel {
    let mut model = MmdModel::new();
    model.bone_manager.add_bone(BoneLink::new("Root".into()));
    let mut anchor = BoneLink::new("Anchor".into());
    anchor.parent_index = 0;
    anchor.initial_position = Vec3::Y * 2.0;
    model.bone_manager.add_bone(anchor);
    model.bone_manager.build_hierarchy();
    model
}

fn swim() -> Arc<VmdAnimation> {
    let mut motion = Motion::new();
    motion.insert_bone_keyframe(
        "Root",
        BoneKeyframe::with_transform(
            0,
            Vec3::Z * 15.0,
            Quat::from_rotation_x(71.0_f32.to_radians()),
        ),
    );
    Arc::new(VmdAnimation::from_motion(motion))
}

fn idle() -> Arc<VmdAnimation> {
    Arc::new(VmdAnimation::from_motion(Motion::new()))
}

fn tick(model: &mut MmdModel, cpu: bool, dt: f32) {
    if cpu {
        model.tick_animation(dt);
    } else {
        model.tick_animation_no_skinning(dt);
    }
}

#[test]
fn animation_transition_blends_physics_input_and_rigid_skinning_in_both_paths() {
    for cpu in [false, true] {
        let mut model = model();
        model.transition_layer_to(0, Some(swim()), 0.0);
        tick(&mut model, cpu, 0.01);
        let start = model.bone_manager.get_global_transform(0);
        model.transition_layer_to(0, Some(idle()), 0.25);
        tick(&mut model, cpu, 0.125);
        let (_, rotation, position) = start.to_scale_rotation_translation();
        let expected =
            Mat4::from_rotation_translation(rotation.slerp(Quat::IDENTITY, 0.5), position * 0.5);
        assert!(model
            .bone_manager
            .get_global_transform(0)
            .abs_diff_eq(expected, 1e-5));
        let skinning = model.bone_manager.get_skinning_matrices()[0];
        assert!(skinning.abs_diff_eq(expected, 1e-5));
        assert!((skinning.x_axis.truncate().length() - 1.0).abs() < 1e-5);
        tick(&mut model, cpu, 0.125);
        assert!(model
            .bone_manager
            .get_global_transform(0)
            .abs_diff_eq(Mat4::IDENTITY, 1e-5));
    }
}

#[test]
fn animation_transition_interrupt_and_immediate_switch_are_continuous() {
    let mut model = model();
    model.transition_layer_to(0, Some(swim()), 0.0);
    model.tick_animation_no_skinning(0.01);
    model.transition_layer_to(0, Some(idle()), 0.25);
    model.tick_animation_no_skinning(0.1);
    let previous = model.bone_manager.get_global_transform(0);
    model.transition_layer_to(0, Some(swim()), 0.25);
    model.tick_animation_no_skinning(0.0);
    assert!(model
        .bone_manager
        .get_global_transform(0)
        .abs_diff_eq(previous, 1e-5));
    model.transition_layer_to(0, Some(idle()), 0.0);
    model.tick_animation_no_skinning(0.01);
    assert!(model.bone_manager.get_skinning_matrices()[0].abs_diff_eq(Mat4::IDENTITY, 1e-5));
}

#[test]
fn animation_transition_sends_blended_pose_to_bullet_without_blending_dynamic_output() {
    let mut model = model();
    model.transition_layer_to(0, Some(idle()), 0.0);
    model.tick_animation_no_skinning(0.01);
    model.rigid_bodies.push(RigidBody {
        local_name: "Anchor".into(),
        universal_name: String::new(),
        bone_index: 1,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [0.1, 0.0, 0.0],
        position: [0.0, 2.0, 0.0],
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode: RigidBodyMode::Static,
    });
    let mut dynamic_bone = BoneLink::new("Cloth".into());
    dynamic_bone.initial_position = Vec3::X * 5.0;
    model.bone_manager.add_bone(dynamic_bone);
    model.bone_manager.build_hierarchy();
    let mut dynamic = model.rigid_bodies[0].clone();
    dynamic.local_name = "Cloth".into();
    dynamic.bone_index = 2;
    dynamic.position = [5.0, 0.0, 0.0];
    dynamic.mode = RigidBodyMode::Dynamic;
    model.rigid_bodies.push(dynamic);
    assert!(model.init_physics());
    model.physics.as_ref().unwrap().set_gravity(0.0, 0.0, 0.0);
    model.transition_layer_to(0, Some(swim()), 0.0);
    model.tick_animation_no_skinning(1.0 / 60.0);
    model.physics.as_ref().unwrap().rigid_bodies[1]
        .bullet_body
        .as_ref()
        .unwrap()
        .set_linear_velocity(4.0, 0.0, 0.0);
    model.transition_layer_to(0, Some(idle()), 0.25);
    for _ in 0..8 {
        model.tick_animation_no_skinning(1.0 / 60.0);
        let physics = model.physics.as_ref().unwrap();
        let anchor = &physics.rigid_bodies[0];
        let expected = anchor.compute_body_matrix(crate::physics::inv_z(
            model.bone_manager.get_global_transform(1),
        ));
        assert!(anchor
            .bullet_body
            .as_ref()
            .unwrap()
            .get_simulation_transform()
            .abs_diff_eq(expected, 1e-4));
        // 独立动态骨骼的最终显示应直接使用物理结果，不再混合切换前矩阵。
        let dynamic = &physics.rigid_bodies[1];
        let expected_dynamic = crate::physics::inv_z(
            dynamic.compute_bone_matrix(dynamic.bullet_body.as_ref().unwrap().get_transform()),
        ) * Mat4::from_translation(-Vec3::X * 5.0);
        assert!(model.bone_manager.get_skinning_matrices()[2].abs_diff_eq(expected_dynamic, 1e-4));
    }
}

#[test]
fn resetting_vpd_pose_preserves_external_bone_inputs_in_both_evaluation_paths() {
    for cpu in [true, false] {
        let mut model = model();
        model.set_vpd_bone_override(0, Vec3::X, Quat::IDENTITY);
        model.set_external_bone_override(0, Vec3::X * 2.0, Quat::IDENTITY);
        tick(&mut model, cpu, 0.01);
        assert!((model.bone_manager.get_global_transform(0).w_axis.x - 2.0).abs() < 1e-5);
        model.clear_vpd_bone_overrides();
        tick(&mut model, cpu, 0.01);
        assert!((model.bone_manager.get_global_transform(0).w_axis.x - 2.0).abs() < 1e-5);
        model.clear_external_bone_overrides();
        tick(&mut model, cpu, 0.01);
        assert!(model.bone_manager.get_global_transform(0).abs_diff_eq(Mat4::IDENTITY, 1e-5));
    }
}
