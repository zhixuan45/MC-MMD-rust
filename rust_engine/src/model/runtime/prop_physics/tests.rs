use super::*;
use crate::animation::{BoneKeyframe, Motion};
use crate::skeleton::BoneLink;
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

fn prop() -> MmdModel {
    let mut model = MmdModel::new();
    model.bone_manager.add_bone(BoneLink::new("Root".into()));
    let mut tassel = BoneLink::new("Tassel".into());
    tassel.parent_index = 0;
    tassel.initial_position = Vec3::NEG_Y;
    model.bone_manager.add_bone(tassel);
    model.bone_manager.build_hierarchy();
    model.update_node_animation(false);
    model.rigid_bodies = vec![RigidBody {
        local_name: "Tassel".into(), universal_name: String::new(),
        bone_index: 1, group: 0, un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere, size: [0.1, 0.0, 0.0],
        position: [0.0, -1.0, 0.0], rotation: [0.0; 3], mass: 1.0,
        move_attenuation: 0.0, rotation_attenuation: 0.0,
        repulsion: 0.0, friction: 0.0, mode: RigidBodyMode::Dynamic,
    }];
    assert!(model.init_physics());
    assert!(model.set_prop_attachment_transform(Mat4::from_scale(Vec3::splat(0.1))));
    model
}

fn body(model: &MmdModel) -> Mat4 {
    inv_z(model.physics.as_ref().unwrap().rigid_bodies[0]
        .bullet_body.as_ref().unwrap().get_simulation_transform())
}

#[test]
fn externally_seeked_vmd_keeps_advancing_physics_in_cpu_and_gpu_paths() {
    for cpu in [false, true] {
        let mut model = prop();
        let mut motion = Motion::new();
        motion.insert_bone_keyframe("Root", BoneKeyframe::with_transform(0, Vec3::ZERO, Quat::IDENTITY));
        motion.insert_bone_keyframe("Root", BoneKeyframe::with_transform(60, Vec3::X * 6.0, Quat::IDENTITY));
        model.set_layer_animation(0, Some(Arc::new(VmdAnimation::from_motion(motion))));
        model.play_layer(0);
        model.set_layer_loop(0, false);
        model.set_layer_speed(0, 0.0);
        model.reset_physics();
        model.tick_animation_no_skinning(1.0 / 60.0);
        let opening = body(&model).w_axis.y;
        for frame in 1..=30 {
            model.seek_layer(0, frame as f32);
            if cpu { model.tick_animation(1.0 / 60.0); }
            else { model.tick_animation_no_skinning(1.0 / 60.0); }
        }
        assert!((model.bone_manager.get_global_transform(0).w_axis.x - 3.0).abs() < 1e-3);
        assert!(body(&model).w_axis.y < opening - 0.1, "VMD seeking must not freeze or reset the tassel");
        assert!(model.bone_manager.get_global_transform(1).w_axis.y < opening - 0.05,
                "The continuously simulated pose must reach rendered bones");
    }
}

#[test]
fn moving_and_rotating_attachment_preserves_dynamic_world_pose_and_world_down_gravity() {
    let mut model = prop();
    model.tick_animation_no_skinning(1.0 / 60.0);
    model.tick_animation_no_skinning(1.0 / 60.0);
    let old_world = Mat4::from_scale(Vec3::splat(0.1));
    let before = old_world * body(&model);
    let new_world = Mat4::from_scale_rotation_translation(
        Vec3::splat(0.1), Quat::from_rotation_z(std::f32::consts::FRAC_PI_2),
        Vec3::new(0.3, 0.4, 0.2));
    assert!(model.set_prop_attachment_transform(new_world));
    let mut physics = model.physics.take().unwrap();
    assert!(model.prepare_prop_physics(&mut physics));
    model.physics = Some(physics);
    assert!((new_world * body(&model)).abs_diff_eq(before, 1e-4),
            "An attachment move must not rigidly drag the dynamic body");
    let start = (new_world * body(&model)).w_axis.truncate();
    for _ in 0..4 { model.tick_animation_no_skinning(1.0 / 60.0); }
    let end = (new_world * body(&model)).w_axis.truncate();
    assert!(end.y < start.y - 0.001, "Gravity must continue pointing down in world space");
    assert!((end.x - start.x).abs() < 1e-4);
    assert!((end.z - start.z).abs() < 1e-4);
}

#[test]
fn teleports_and_size_edits_resync_and_invalid_reference_frames_are_rejected() {
    let mut model = prop();
    for _ in 0..8 { model.tick_animation_no_skinning(1.0 / 60.0); }
    assert!(body(&model).w_axis.y < -1.01);
    let teleported = Mat4::from_scale_rotation_translation(Vec3::splat(0.1), Quat::IDENTITY, Vec3::X * 20.0);
    assert!(model.set_prop_attachment_transform(teleported));
    model.tick_animation_no_skinning(1.0 / 60.0);
    assert!((body(&model).w_axis.y + 1.0).abs() < 1e-4);
    let resized = Mat4::from_scale_rotation_translation(Vec3::splat(0.2), Quat::IDENTITY, Vec3::X * 20.0);
    assert!(model.set_prop_attachment_transform(resized));
    model.tick_animation_no_skinning(1.0 / 60.0);
    assert!((body(&model).w_axis.y + 1.0).abs() < 1e-4);
    for invalid in [
        Mat4::ZERO, Mat4::from_scale(Vec3::new(1.0, 2.0, 1.0)),
        Mat4::from_scale(Vec3::new(-1.0, 1.0, 1.0)),
        Mat4::from_translation(Vec3::new(f32::NAN, 0.0, 0.0)),
    ] { assert!(!model.set_prop_attachment_transform(invalid)); }
    assert!(model.prop_physics_frame.unwrap().current.abs_diff_eq(resized, 0.0));
}
