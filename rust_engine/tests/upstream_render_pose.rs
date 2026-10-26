#![cfg(not(feature = "legacy-physics"))]

use glam::{Mat4, Vec2, Vec3};
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};
use mmd_engine::{
    model::{RuntimeVertex, VertexWeight},
    physics::{get_config, set_config, PhysicsConfig},
    skeleton::BoneLink,
    MmdModel,
};

fn model() -> MmdModel {
    let mut model = MmdModel::new();
    for index in 0..3 {
        let mut bone = BoneLink::new(format!("Cloth{index}"));
        bone.initial_position = Vec3::new(0.0, 3.0 + index as f32, 0.0);
        if index == 2 {
            bone.parent_index = 1;
        }
        model.bone_manager.add_bone(bone);
        model.vertices.push(RuntimeVertex {
            position: Vec3::new(0.0, 3.0 + index as f32, 0.0),
            normal: Vec3::Y,
            uv: Vec2::ZERO,
        });
        model.weights.push(VertexWeight::Bdef1 { bone: index as i32 });
    }
    model.bone_manager.build_hierarchy();
    model.update_positions = model.vertices.iter().map(|v| v.position).collect();
    model.update_normals = model.vertices.iter().map(|v| v.normal).collect();
    model.update_uvs = model.vertices.iter().map(|v| v.uv).collect();
    model.update_node_animation(false);
    model.rigid_bodies = [RigidBodyMode::Dynamic, RigidBodyMode::DynamicWithBonePosition]
        .into_iter()
        .enumerate()
        .map(|(index, mode)| RigidBody {
            local_name: format!("Cloth{index}"),
            universal_name: String::new(),
            bone_index: index as i32,
            group: 0,
            un_collision_group_flag: u16::MAX,
            shape: RigidBodyShape::Sphere,
            size: [0.2, 0.0, 0.0],
            position: [0.0, 3.0 + index as f32, 0.0],
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode,
        })
        .collect();
    assert!(model.init_physics());
    model
}

#[test]
fn skipped_steps_publish_bullet_pose_to_cpu_and_gpu_skinning() {
    let original = get_config();
    for fps in [60.0, 120.0] {
        set_config(PhysicsConfig { physics_fps: fps, ..PhysicsConfig::default() });
        for cpu in [false, true] {
            // 50 ms is below the old 60 Hz guard but above the 120 Hz guard.
            for delta in [0.0, -0.01, 0.05, 0.1] {
                let mut model = model();
                for _ in 0..10 {
                    model.tick_animation(1.0 / 60.0);
                }
                let matrices = model.bone_manager.get_skinning_matrices().to_vec();
                let positions = model.update_positions.clone();
                assert!(matrices[0].w_axis.y < -0.01, "fixture must leave bind pose");
                let states = model.physics_body_snapshots_matching("");
                if cpu { model.tick_animation(delta); }
                else { model.tick_animation_no_skinning(delta); }
                if delta <= 0.0 || delta > 5.0 / fps {
                    for (index, expected) in matrices.iter().enumerate() {
                        assert!(model.bone_manager.get_skinning_matrices()[index]
                            .abs_diff_eq(*expected, 1e-4),
                            "fps={fps} cpu={cpu} delta={delta} bone={index}: skipped step lost Bullet pose");
                        if cpu {
                            assert!(model.update_positions[index].abs_diff_eq(positions[index], 1e-4));
                        }
                    }
                    let after = model.physics_body_snapshots_matching("");
                    for (before, after) in states.iter().zip(after) {
                        assert!(before.simulation_transform.abs_diff_eq(after.simulation_transform, 1e-5));
                    }
                }
                // Resuming normal time must keep a valid physical pose too.
                model.tick_animation_no_skinning(1.0 / 60.0);
                assert!(model.bone_manager.get_skinning_matrices()[0].w_axis.y < -0.01);
            }
        }
    }
    set_config(original);
}

#[test]
fn invalid_steps_keep_the_rendered_pose_and_deferred_reset() {
    for delta in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        let mut model = model();
        for _ in 0..10 { model.tick_animation_no_skinning(1.0 / 60.0); }
        let before: Vec<Mat4> = model.bone_manager.get_skinning_matrices().to_vec();
        let states = model.physics_body_snapshots_matching("");
        model.reset_physics();
        model.begin_animation();
        model.update_node_animation(false);
        model.update_physics(delta);
        model.update_node_animation(true);
        model.bone_manager.clear_physics_bone_indices();
        model.end_animation();
        for (actual, expected) in model.bone_manager.get_skinning_matrices().iter().zip(&before) {
            assert!(actual.abs_diff_eq(*expected, 1e-4), "invalid step lost Bullet pose");
        }
        for (before, after) in states.iter().zip(model.physics_body_snapshots_matching("")) {
            assert!(before.simulation_transform.abs_diff_eq(after.simulation_transform, 1e-5));
        }
        // The valid frame still consumes the pending reset into animation pose.
        model.tick_animation_no_skinning(1.0 / 60.0);
        assert!(model.bone_manager.get_skinning_matrices()[0].abs_diff_eq(Mat4::IDENTITY, 1e-4));
    }
}
