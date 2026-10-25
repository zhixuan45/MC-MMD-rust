#![cfg(feature = "legacy-physics")]
//! One integration-test process owns its global config; library tests run separately.
use glam::Vec3;
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};
use mmd_engine::{
    model::MmdModel,
    physics::{reset_config, set_config, PhysicsConfig},
    skeleton::BoneLink,
};

#[test]
fn upper_and_lower_fill_rebuild_independently_and_keep_authored_body() {
    let mut model = MmdModel::new();
    for (name, y) in [("下半身", 8.0), ("上半身", 12.0), ("首", 15.0)] {
        let mut bone = BoneLink::new(name.into());
        bone.initial_position = Vec3::new(0.0, y, 2.0);
        model.bone_manager.add_bone(bone);
    }
    model.bone_manager.build_hierarchy();
    model.rigid_bodies.push(RigidBody {
        local_name: "authored_anchor".into(),
        universal_name: String::new(),
        bone_index: 0,
        group: 0,
        un_collision_group_flag: u16::MAX,
        shape: RigidBodyShape::Sphere,
        size: [0.1, 0.0, 0.0],
        position: [0.0, 8.0, -2.0],
        rotation: [0.0; 3],
        mass: 0.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.5,
        mode: RigidBodyMode::Static,
    });
    let mut config = PhysicsConfig::default();
    set_config(config.clone());
    assert!(model.init_physics());
    let count = |model: &MmdModel| -> usize {
        let info: serde_json::Value =
            serde_json::from_str(&model.get_physics_debug_info()).unwrap();
        info["rigid_bodies"].as_array().unwrap().len()
    };
    assert_eq!(count(&model), 4);
    for (upper, lower, expected) in [
        (false, true, 2),
        (true, false, 3),
        (false, false, 1),
        (true, true, 4),
    ] {
        config.auto_upper_body_colliders = upper;
        config.auto_lower_body_colliders = lower;
        set_config(config.clone());
        model.request_physics_rebuild();
        model.tick_animation_no_skinning(1.0 / 60.0);
        assert_eq!(count(&model), expected);
        assert!(model.get_physics_debug_info().contains("authored_anchor"));
        assert_eq!(model.rigid_bodies.len(), 1);
    }
    reset_config();
}
