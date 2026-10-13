//! Isolated process for admission regressions that change the global physics scale.
use glam::Vec3;
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};
use mmd_engine::{
    model::MmdModel,
    physics::{reset_config, set_config, PhysicsConfig},
    skeleton::BoneLink,
};

fn model_with_nearby_dynamic(name: &str, distance: f32) -> MmdModel {
    let mut model = MmdModel::new();
    let mut bone = BoneLink::new("腰".into());
    bone.initial_position = Vec3::new(0.0, 8.0, 2.0);
    model.bone_manager.add_bone(bone);
    model.bone_manager.build_hierarchy();
    model.rigid_bodies.push(RigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: -1,
        group: 1,
        un_collision_group_flag: u16::MAX,
        shape: RigidBodyShape::Sphere,
        size: [0.1, 0.0, 0.0],
        position: [0.0, 8.0, -2.0 - distance],
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.5,
        mode: RigidBodyMode::Dynamic,
    });
    model
}

#[test]
fn pelvis_admission_covers_unscaled_and_enlarged_runtime_shapes() {
    for (name, distance, scale, expected) in [
        // No garment qualifies this pelvis for scaling: its runtime radius is 0.70.
        ("tail", 0.75, 0.5, 1),
        ("accessory", 0.75, 0.8, 1),
        // A garment can enlarge the runtime radius to 1.05; full-size alone is insufficient.
        ("skirt_0_0", 1.05, 1.5, 1),
        // Disjoint tail and garment fixtures still allow the missing pelvis to be filled.
        ("tail", 1.0, 0.5, 2),
        ("skirt_0_0", 1.3, 1.5, 2),
    ] {
        set_config(PhysicsConfig {
            gravity_y: 0.0,
            static_collider_scale: scale,
            ..PhysicsConfig::default()
        });
        let mut model = model_with_nearby_dynamic(name, distance);
        assert!(model.init_physics());
        let info: serde_json::Value =
            serde_json::from_str(&model.get_physics_debug_info()).unwrap();
        assert_eq!(
            info["rigid_bodies"].as_array().unwrap().len(),
            expected,
            "admission mismatch for {name}, distance {distance}, scale {scale}"
        );
        assert_eq!(
            model.rigid_bodies.len(),
            1,
            "authored bodies must be unchanged"
        );
    }
    reset_config();
}
