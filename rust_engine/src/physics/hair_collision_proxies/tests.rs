use super::*;
use crate::model::{RuntimeVertex, VertexWeight};
use crate::skeleton::BoneLink;
use mmd::pmx::joint::{Joint, JointType};
use mmd::pmx::rigid_body::RigidBody;

fn fixture() -> MmdModel {
    let mut model = MmdModel::new();
    model
        .bone_manager
        .add_bone(BoneLink::new("HairRoot".into()));
    model.bone_manager.add_bone(BoneLink::new("HairTip".into()));
    model.bone_manager.get_bone_mut(1).unwrap().parent_index = 0;
    model.bone_manager.build_hierarchy();
    model.vertices = [
        Vec3::new(-2.0, 0.0, 0.0),
        Vec3::new(0.0, 0.1, 0.0),
        Vec3::new(2.0, 0.0, 0.0),
    ]
    .into_iter()
    .map(|position| RuntimeVertex {
        position,
        normal: Vec3::Y,
        uv: glam::Vec2::ZERO,
    })
    .collect();
    model.indices = vec![0, 1, 2];
    model.weights = vec![VertexWeight::Bdef1 { bone: 1 }; 3];
    model.rigid_bodies = vec![body("HairRoot", 0, 0.5), body("HairTip", 1, 1.0)];
    model.joints.push(joint(0, 1));
    model
}

fn body(name: &str, bone_index: i32, radius: f32) -> RigidBody {
    RigidBody {
        local_name: name.into(),
        universal_name: name.into(),
        bone_index,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [radius, 0.0, 0.0],
        position: [0.0; 3],
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode: RigidBodyMode::Dynamic,
    }
}

fn joint(a: i32, b: i32) -> Joint {
    Joint {
        local_name: "hair-joint".into(),
        universal_name: "hair-joint".into(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: a,
        rigid_body_b_index: b,
        position: [0.0; 3],
        rotation: [0.0; 3],
        position_min: [0.0; 3],
        position_max: [0.0; 3],
        rotation_min: [0.0; 3],
        rotation_max: [0.0; 3],
        position_spring: [0.0; 3],
        rotation_spring: [0.0; 3],
    }
}

#[test]
fn plans_only_the_reliable_jointed_hair_terminal_and_preserves_raw_data() {
    let model = fixture();
    let raw = model.rigid_bodies[1].clone();
    let plan = build_hair_collision_proxies(&model);
    assert_eq!(plan.proxies.len(), 1);
    assert_eq!(plan.proxies[0].body_index, 1);
    assert_eq!(plan.proxies[0].effective_shape, RigidBodyShape::Capsule);
    assert_eq!(plan.proxies[0].raw_shape, raw.shape);
    assert_eq!(plan.proxies[0].raw_size, raw.size);
    assert_eq!(plan.proxies[0].covered_point_count, 3);
    assert!(plan.proxies[0].volume_ratio.unwrap() < 1.0);
    assert_eq!(model.rigid_bodies[1].shape, raw.shape);
    assert_eq!(model.rigid_bodies[1].size, raw.size);
    assert_eq!(model.rigid_bodies[1].position, raw.position);
    assert_eq!(model.rigid_bodies[1].rotation, raw.rotation);

    let proxy = &plan.proxies[0];
    let expected_center = proxy.raw_bind.transform_point3(proxy.fitted_center);
    assert!(proxy
        .bind_transform
        .transform_point3(Vec3::ZERO)
        .abs_diff_eq(expected_center, 1e-5));
    assert!(proxy
        .bind_transform
        .transform_vector3(Vec3::Y)
        .normalize()
        .abs_diff_eq(Vec3::X, 1e-5));
}

#[test]
fn mirror_and_scale_keep_the_same_volume_ratio_and_scale_proxy_size() {
    let base = build_hair_collision_proxies(&fixture()).proxies.remove(0);
    let mut scaled = fixture();
    for vertex in &mut scaled.vertices {
        vertex.position *= 3.0;
    }
    for body in &mut scaled.rigid_bodies {
        body.size[0] *= 3.0;
        for value in &mut body.position {
            *value *= 3.0;
        }
    }
    let scaled_proxy = build_hair_collision_proxies(&scaled).proxies.remove(0);
    assert!((scaled_proxy.effective_size[0] / base.effective_size[0] - 3.0).abs() < 1e-4);
    assert!((scaled_proxy.effective_size[1] / base.effective_size[1] - 3.0).abs() < 1e-4);
    assert!((scaled_proxy.volume_ratio.unwrap() - base.volume_ratio.unwrap()).abs() < 1e-4);

    let mut mirrored = fixture();
    for vertex in &mut mirrored.vertices {
        vertex.position.z *= -1.0;
    }
    for body in &mut mirrored.rigid_bodies {
        body.position[2] *= -1.0;
    }
    let mirrored_proxy = build_hair_collision_proxies(&mirrored).proxies.remove(0);
    assert!((mirrored_proxy.effective_size[0] - base.effective_size[0]).abs() < 1e-5);
    assert!((mirrored_proxy.effective_size[1] - base.effective_size[1]).abs() < 1e-5);
    assert!((mirrored_proxy.volume_ratio.unwrap() - base.volume_ratio.unwrap()).abs() < 1e-5);
}

#[test]
fn rejects_a_capsule_that_no_longer_covers_every_bound_point() {
    let geometry = audit_hair_geometry(&fixture())
        .into_iter()
        .find(|audit| audit.body_index == 1)
        .unwrap()
        .geometry
        .unwrap();
    assert!(capsule_covers_points(&geometry));
    let mut too_small = geometry;
    too_small.fitted_capsule_radius *= 0.5;
    assert!(!capsule_covers_points(&too_small));
}

#[test]
fn does_not_shrink_a_large_nonterminal_sphere() {
    let mut model = fixture();
    model.bone_manager.add_bone(BoneLink::new("HairEnd".into()));
    model.bone_manager.get_bone_mut(2).unwrap().parent_index = 1;
    model.bone_manager.build_hierarchy();
    model.rigid_bodies.push(body("HairEnd", 2, 0.5));
    model.joints.clear();
    model.joints.push(joint(0, 1));
    model.rigid_bodies[1].size[0] = 5.0;
    let plan = build_hair_collision_proxies(&model);
    assert!(plan.proxies.is_empty());
    assert!(plan
        .skipped
        .iter()
        .any(|item| item.body_index == 1 && item.reason == "骨骼存在动态头发后续"));
}
