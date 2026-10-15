use super::*;
use crate::model::RuntimeVertex;
use crate::skeleton::BoneLink;
use mmd::pmx::joint::{Joint, JointType};
use mmd::pmx::rigid_body::RigidBody;

fn fixture() -> MmdModel {
    let mut model = MmdModel::new();
    for name in ["HairRoot", "HairMid", "HairTip"] {
        model.bone_manager.add_bone(BoneLink::new(name.into()));
    }
    model.bone_manager.get_bone_mut(1).unwrap().parent_index = 0;
    model.bone_manager.get_bone_mut(2).unwrap().parent_index = 1;
    model.bone_manager.build_hierarchy();
    model.vertices = (0..6)
        .map(|i| RuntimeVertex {
            position: Vec3::new(i as f32, 0.0, 2.0),
            normal: Vec3::Y,
            uv: glam::Vec2::ZERO,
        })
        .collect();
    model.indices = vec![0, 1, 2, 3, 4, 5];
    model.weights = vec![
        VertexWeight::Bdef1 { bone: 2 },
        VertexWeight::Bdef2 {
            bones: [2, 1],
            weight: 0.25,
        },
        VertexWeight::Bdef4 {
            bones: [2, 1, 0, 2],
            weights: [0.1, 0.2, 0.0, 0.7],
        },
        VertexWeight::Sdef {
            bones: [2, 1],
            weight: 0.5,
            c: Vec3::ZERO,
            r0: Vec3::ZERO,
            r1: Vec3::ZERO,
        },
        VertexWeight::Qdef {
            bones: [2, 1, 0, 2],
            weights: [0.25, 0.25, 0.0, 0.5],
        },
        VertexWeight::Bdef1 { bone: 2 },
    ];
    model
        .rigid_bodies
        .push(body("HairTip", 2, RigidBodyShape::Sphere, [0.5, 0.0, 0.0]));
    model
}

fn body(name: &str, bone_index: i32, shape: RigidBodyShape, position: [f32; 3]) -> RigidBody {
    RigidBody {
        local_name: name.into(),
        universal_name: name.into(),
        bone_index,
        group: 0,
        un_collision_group_flag: 0,
        shape,
        size: [0.5, 0.5, 0.5],
        position,
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
        local_name: "hair-link".into(),
        universal_name: "hair-link".into(),
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
fn includes_positive_mixed_weights_and_all_weight_kinds() {
    let audit = audit_hair_geometry(&fixture()).remove(0);
    assert_eq!(audit.status, AuditStatus::Candidate);
    assert_eq!(audit.weights.influenced_vertices, 6);
    assert_eq!(audit.weights.mixed_boundary_vertices, 4);
    assert!(audit.weights.total_positive_weight > 4.0);
    assert_eq!(audit.bound_vertices, vec![0, 1, 2, 3, 4, 5]);
}

#[test]
fn tolerates_f32_roundoff_in_bdef2_and_bdef4_weight_sums() {
    let mut model = fixture();
    model.weights[1] = VertexWeight::Bdef2 {
        bones: [2, 1],
        weight: 0.3,
    };
    model.weights[2] = VertexWeight::Bdef4 {
        bones: [2, 1, 0, 2],
        weights: [0.1, 0.2, 0.3, 0.4],
    };
    assert_eq!(
        audit_hair_geometry(&model)[0].status,
        AuditStatus::Candidate
    );
}

fn assert_capsule_covers(geometry: &GeometrySummary) {
    let half = geometry.fitted_capsule_segment_length * 0.5;
    for point in &geometry.body_local_points {
        let delta = *point - geometry.fitted_capsule_center;
        let axial = (delta[geometry.capsule_axis].abs() - half).max(0.0);
        let distance = (delta.length_squared()
            - delta[geometry.capsule_axis] * delta[geometry.capsule_axis]
            + axial * axial)
            .sqrt();
        assert!(
            distance <= geometry.fitted_capsule_radius + 1e-5,
            "capsule gap {distance} exceeds radius {}",
            geometry.fitted_capsule_radius
        );
    }
}

#[test]
fn mirror_and_scale_preserve_dimensionless_fit_ratios() {
    let base = fixture();
    let mut scaled = fixture();
    for v in &mut scaled.vertices {
        v.position *= 3.0;
    }
    for b in &mut scaled.rigid_bodies {
        for x in &mut b.position {
            *x *= 3.0;
        }
    }
    let a = audit_hair_geometry(&base).remove(0).geometry.unwrap();
    let b = audit_hair_geometry(&scaled).remove(0).geometry.unwrap();
    assert_capsule_covers(&a);
    assert_capsule_covers(&b);
    assert!((b.required_sphere_radius / a.required_sphere_radius - 3.0).abs() < 1e-5);
    let mut mirrored = base;
    for v in &mut mirrored.vertices {
        v.position.z = -v.position.z;
    }
    for body in &mut mirrored.rigid_bodies {
        body.position[2] *= -1.0;
    }
    let c = audit_hair_geometry(&mirrored).remove(0).geometry.unwrap();
    assert_capsule_covers(&c);
    assert!((c.required_sphere_radius - a.required_sphere_radius).abs() < 1e-5);
    assert!((c.fitted_capsule_radius - a.fitted_capsule_radius).abs() < 1e-5);
}

#[test]
fn original_capsule_coverage_uses_bullet_cylinder_segment_height() {
    let points = [
        Vec3::new(0.0, -2.0, 0.0),
        Vec3::ZERO,
        Vec3::new(0.0, 2.0, 0.0),
    ];
    let geometry = summarize_geometry(&points, RigidBodyShape::Capsule, [0.5, 2.0, 0.0]);
    assert!((geometry.original_shape_coverage_ratio.unwrap() - 2.0).abs() < 1e-5);
}

#[test]
fn fits_non_degenerate_capsule_for_thin_long_mesh_and_reports_volumes() {
    let mut points = Vec::new();
    for y in [-5.0, 0.0, 5.0] {
        for step in 0..8 {
            let angle = step as f32 * std::f32::consts::TAU / 8.0;
            points.push(Vec3::new(angle.cos(), y, angle.sin()));
        }
    }
    let geometry = summarize_geometry(&points, RigidBodyShape::Sphere, [6.0, 0.0, 0.0]);
    assert_eq!(geometry.capsule_axis, 1);
    assert!(geometry.fitted_capsule_segment_length > 0.0);
    assert_capsule_covers(&geometry);
    let enclosing_sphere_volume =
        4.0 / 3.0 * std::f32::consts::PI * geometry.required_sphere_radius.powi(3);
    assert!(geometry.fitted_capsule_volume < enclosing_sphere_volume);
    assert_eq!(
        geometry.original_shape_volume,
        Some(4.0 / 3.0 * std::f32::consts::PI * 216.0)
    );
    assert!(geometry.fitted_to_original_volume_ratio.unwrap() < 1.0);
}

#[test]
fn skips_shared_body_branch_bad_coordinates_and_accessory_names() {
    let mut m = fixture();
    m.rigid_bodies
        .push(body("second", 2, RigidBodyShape::Sphere, [0.0; 3]));
    assert!(audit_hair_geometry(&m)[0]
        .skip_reason
        .as_deref()
        .unwrap()
        .contains("多个刚体"));
    m.rigid_bodies.pop();
    m.vertices[0].position.x = f32::NAN;
    assert!(audit_hair_geometry(&m)[0]
        .skip_reason
        .as_deref()
        .unwrap()
        .contains("非有限"));
    let mut accessory = fixture();
    accessory.bone_manager.get_bone_mut(2).unwrap().name = "HairOrnament".into();
    assert!(audit_hair_geometry(&accessory).is_empty());
    let mut branched = fixture();
    branched
        .rigid_bodies
        .push(body("HairMid", 1, RigidBodyShape::Sphere, [0.0; 3]));
    branched
        .rigid_bodies
        .push(body("HairRoot", 0, RigidBodyShape::Sphere, [0.0; 3]));
    branched
        .bone_manager
        .add_bone(BoneLink::new("HairFork".into()));
    branched.bone_manager.get_bone_mut(3).unwrap().parent_index = 1;
    branched.bone_manager.build_hierarchy();
    branched
        .rigid_bodies
        .push(body("HairFork", 3, RigidBodyShape::Sphere, [0.0; 3]));
    let topology = inspect_topology(&branched, &HashSet::from([0, 1, 2, 3]));
    assert!(
        topology
            .values()
            .any(|reason| reason == "头发候选骨骼存在分支"),
        "{topology:?}"
    );
    assert!(audit_hair_geometry(&branched)
        .iter()
        .any(|r| r.skip_reason.as_deref() == Some("头发候选骨骼存在分支")));
}

#[test]
fn accepts_ponytail_hair_but_rejects_tail_and_bare_fur_terms() {
    assert!(is_hair_candidate("TwinTail_L", "", ""));
    assert!(is_hair_candidate("後髮", "", ""));
    assert!(is_hair_candidate("頭髮_ponyTail", "", ""));
    assert!(!is_hair_candidate("尾巴_1", "", ""));
    assert!(!is_hair_candidate("fur_毛_01", "", ""));
    assert!(!is_hair_candidate("髮飾", "hair ornament", ""));
    let bow = classify_hair_name("长发蝴蝶结", "", "");
    assert!(!bow.candidate);
    assert!(bow.excluded_terms.iter().any(|term| term == "蝴蝶结"));
    let template_alias = classify_hair_name("后长发", "SkirtTemplate", "Skirt");
    assert!(template_alias.candidate);
    assert!(template_alias
        .soft_excluded_terms
        .iter()
        .any(|term| term == "skirt"));
    assert!(!classify_hair_name("", "Skirt", "Skirt").candidate);
}

#[test]
fn shared_nonhair_parent_does_not_create_a_hair_branch() {
    let mut model = fixture();
    model.bone_manager.add_bone(BoneLink::new("Head".into()));
    model
        .bone_manager
        .add_bone(BoneLink::new("HairLeft".into()));
    model
        .bone_manager
        .add_bone(BoneLink::new("HairRight".into()));
    model.bone_manager.get_bone_mut(3).unwrap().parent_index = -1;
    model.bone_manager.get_bone_mut(4).unwrap().parent_index = 3;
    model.bone_manager.get_bone_mut(5).unwrap().parent_index = 3;
    model.bone_manager.build_hierarchy();
    let reasons = inspect_topology(&model, &HashSet::from([4, 5]));
    assert!(reasons.is_empty(), "{reasons:?}");
}

#[test]
fn skips_parent_cycles_and_unconnected_or_invalid_meshes() {
    let mut cyclic = fixture();
    cyclic.bone_manager.get_bone_mut(1).unwrap().parent_index = 2;
    cyclic.bone_manager.get_bone_mut(2).unwrap().parent_index = 1;
    assert!(audit_hair_geometry(&cyclic)[0]
        .skip_reason
        .as_deref()
        .unwrap()
        .contains("环"));

    let mut no_faces = fixture();
    no_faces.indices.clear();
    assert!(audit_hair_geometry(&no_faces)[0]
        .skip_reason
        .as_deref()
        .unwrap()
        .contains("没有有效面片"));

    let mut orphan = fixture();
    orphan.vertices.push(RuntimeVertex {
        position: Vec3::splat(100.0),
        normal: Vec3::Y,
        uv: glam::Vec2::ZERO,
    });
    orphan.weights.push(VertexWeight::Bdef1 { bone: 2 });
    assert!(audit_hair_geometry(&orphan)[0]
        .skip_reason
        .as_deref()
        .unwrap()
        .contains("没有有效面片"));

    let mut invalid = fixture();
    invalid.indices[0] = 999;
    assert!(audit_hair_geometry(&invalid)[0]
        .skip_reason
        .as_deref()
        .unwrap()
        .contains("索引越界"));
}

#[test]
fn reports_named_hair_body_with_invalid_bone_instead_of_dropping_it() {
    let mut model = fixture();
    model.rigid_bodies[0].bone_index = 999;
    model.rigid_bodies[0].local_name = "hair-body".into();
    let audit = audit_hair_geometry(&model);
    assert_eq!(audit.len(), 1);
    assert_eq!(audit[0].bone_index, 999);
    assert_eq!(audit[0].status, AuditStatus::Skipped);
    assert_eq!(audit[0].skip_reason.as_deref(), Some("骨骼索引无效"));
}

#[test]
fn skips_joint_cycles_and_skeleton_aligned_joint_branches() {
    let mut cycle = fixture();
    cycle
        .rigid_bodies
        .push(body("HairMid", 1, RigidBodyShape::Sphere, [0.0; 3]));
    cycle
        .rigid_bodies
        .push(body("HairRoot", 0, RigidBodyShape::Sphere, [0.0; 3]));
    cycle.joints = vec![joint(0, 1), joint(1, 2), joint(2, 0)];
    assert!(audit_hair_geometry(&cycle).iter().all(|item| {
        item.skip_reason.as_deref() == Some("头发刚体关节图存在环")
            || item.skip_reason.as_deref() == Some("同一骨骼绑定了多个刚体")
    }));

    let mut branch = fixture();
    branch
        .rigid_bodies
        .push(body("HairMid", 1, RigidBodyShape::Sphere, [0.0; 3]));
    branch
        .rigid_bodies
        .push(body("HairRoot", 0, RigidBodyShape::Sphere, [0.0; 3]));
    branch
        .bone_manager
        .add_bone(BoneLink::new("HairFork".into()));
    branch.bone_manager.get_bone_mut(3).unwrap().parent_index = 2;
    branch.bone_manager.build_hierarchy();
    branch
        .rigid_bodies
        .push(body("HairFork", 3, RigidBodyShape::Sphere, [0.0; 3]));
    // Root body 2 (bone 0) is the center, aligned with the bone parent graph.
    branch.joints = vec![joint(2, 0), joint(2, 1), joint(2, 3)];
    let body_graph = inspect_body_graph(
        &branch,
        &[(0, Some(2)), (1, Some(1)), (2, Some(0)), (3, Some(3))],
    );
    assert_eq!(
        body_graph.get(&2).map(String::as_str),
        Some("头发刚体关节图存在分支"),
        "{body_graph:?}"
    );
    assert!(audit_hair_geometry(&branch)
        .iter()
        .any(|item| { item.skip_reason.as_deref() == Some("头发刚体关节图存在分支") }));
}
