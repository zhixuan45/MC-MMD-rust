use super::*;

use glam::{Mat4, Quat, Vec3};
use mmd::pmx::{
    joint::{Joint as PmxJoint, JointType},
    rigid_body::{RigidBodyMode, RigidBodyShape},
};

use crate::physics::bullet_ffi::{BulletRigidBody, BulletShape, RigidBodyInfo};
use crate::physics::mmd_joint::joint_anchor_position_error;
use crate::physics::{inv_z, MmdJointData, MmdRigidBodyData};

fn pmx_body(name: &str, position: [f32; 3]) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.to_owned(),
        universal_name: String::new(),
        bone_index: 0,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [0.2; 3],
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

fn pmx_joint(position: [f32; 3], rotation: [f32; 3]) -> PmxJoint {
    PmxJoint {
        local_name: "Synthesized_Skirt_Cross_Test".to_owned(),
        universal_name: String::new(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: 0,
        rigid_body_b_index: 1,
        position,
        rotation,
        position_min: [0.0; 3],
        position_max: [0.0; 3],
        rotation_min: [0.0; 3],
        rotation_max: [0.0; 3],
        position_spring: [0.0; 3],
        rotation_spring: [0.0; 3],
    }
}

struct NativeBody {
    body: BulletRigidBody,
    // Bullet 刚体不拥有 shape，保持到 body 之后释放。
    _shape: BulletShape,
}

fn native_body(initial_transform: Mat4) -> NativeBody {
    let shape = BulletShape::sphere(0.2).expect("测试球体应能创建");
    let body = BulletRigidBody::new(
        &RigidBodyInfo {
            mass: 1.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            friction: 0.5,
            restitution: 0.0,
            additional_damping: false,
            is_kinematic: false,
            disable_deactivation: true,
            no_contact_response: false,
            initial_transform,
        },
        &shape,
    )
    .expect("测试刚体应能创建");
    NativeBody {
        body,
        _shape: shape,
    }
}

// 字段顺序让约束先于其引用的 Bullet 刚体释放。
struct ConstraintFixture {
    data: MmdJointData,
    _body_a: NativeBody,
    _body_b: NativeBody,
}

fn production_joint(
    joint: &PmxJoint,
    pmx_a: &PmxRigidBody,
    pmx_b: &PmxRigidBody,
    initial_a: Mat4,
    initial_b: Mat4,
) -> ConstraintFixture {
    let body_a = native_body(initial_a);
    let body_b = native_body(initial_b);
    let data = MmdJointData::from_pmx(
        joint,
        &body_a.body,
        &body_b.body,
        pmx_a,
        pmx_b,
        initial_a,
        initial_b,
    );
    ConstraintFixture {
        data,
        _body_a: body_a,
        _body_b: body_b,
    }
}

fn pmx_initial_transform(body: &PmxRigidBody) -> Mat4 {
    MmdRigidBodyData::from_pmx(body, None, body.size).initial_transform
}

#[test]
fn tilted_chord_rotation_aligns_actual_joint_frame_x_with_model_space_chord() {
    let names = vec!["pelvis".to_owned(), "skirt root".to_owned()];
    let parents = vec![-1, 0];
    // 数字编号表示同一裙层的三列。
    let bodies = vec![
        pmx_body("Skirt_0", [1.0, 2.0, 0.0]),
        pmx_body("Skirt_1", [-0.5, 0.8, 0.8660254]),
        pmx_body("Skirt_2", [-0.5, -0.7, -0.8660254]),
    ];
    let synthesized =
        synthesize_missing_skirt_cross_joints_with_bones(&bodies, &[], &names, &parents);
    assert_eq!(synthesized.len(), 3);

    let joint = &synthesized[0];
    let pmx_a = &bodies[joint.rigid_body_a_index as usize];
    let pmx_b = &bodies[joint.rigid_body_b_index as usize];
    let initial_a = pmx_initial_transform(pmx_a);
    let initial_b = pmx_initial_transform(pmx_b);
    let fixture = production_joint(joint, pmx_a, pmx_b, initial_a, initial_b);
    let chord = Vec3::from_array(pmx_b.position) - Vec3::from_array(pmx_a.position);
    let frame_world = initial_a * fixture.data.frame_a;
    let actual_x = frame_world.transform_vector3(Vec3::X).normalize();
    let (error, _, _) = joint_anchor_position_error(
        initial_a,
        fixture.data.frame_a,
        initial_b,
        fixture.data.frame_b,
    );

    assert!(
        actual_x.dot(chord.normalize()) > 0.999,
        "实际 Bullet frame X 应沿弦"
    );
    assert!(error < 1e-4, "PMX 初始姿态的生产 frame 应共锚: {error}");
    assert!(fixture.data.constraint.is_some());
}

#[test]
fn common_large_initial_rotation_and_translation_keep_production_anchors_coincident() {
    let pmx_a = pmx_body("Skirt_A", [-1.0, 2.0, 0.5]);
    let pmx_b = pmx_body("Skirt_B", [2.0, 0.5, -1.0]);
    let initial_a = Mat4::from_rotation_translation(
        Quat::from_rotation_y(0.4),
        Vec3::from_array(pmx_a.position),
    );
    let initial_b = Mat4::from_rotation_translation(
        Quat::from_rotation_x(-0.7),
        Vec3::from_array(pmx_b.position),
    );
    let joint = pmx_joint([0.5, 1.25, -0.25], [0.3, -0.2, 0.6]);
    let fixture = production_joint(&joint, &pmx_a, &pmx_b, initial_a, initial_b);
    let common_pose = Mat4::from_rotation_translation(
        Quat::from_euler(glam::EulerRot::YXZ, 1.1, -0.8, 0.65),
        Vec3::new(17.0, -9.0, 23.0),
    );

    let error = joint_anchor_position_error(
        common_pose * initial_a,
        fixture.data.frame_a,
        common_pose * initial_b,
        fixture.data.frame_b,
    )
    .0;

    assert!(error < 1e-4, "共同大变换后生产锚点仍应重合: {error}");
}

#[test]
fn independent_body_transforms_have_predictable_production_anchor_error() {
    let pmx_a = pmx_body("Skirt_A", [2.0, 0.0, 0.0]);
    let pmx_b = pmx_body("Skirt_B", [-2.0, 0.0, 0.0]);
    let initial_a = pmx_initial_transform(&pmx_a);
    let initial_b = pmx_initial_transform(&pmx_b);
    let joint = pmx_joint([0.0, 1.0, 0.0], [0.0, 0.0, 0.0]);
    let fixture = production_joint(&joint, &pmx_a, &pmx_b, initial_a, initial_b);
    let body_a_pose = Mat4::from_translation(Vec3::new(3.0, 0.0, 0.0)) * initial_a;

    let error = joint_anchor_position_error(
        body_a_pose,
        fixture.data.frame_a,
        initial_b,
        fixture.data.frame_b,
    )
    .0;

    assert!(
        (error - 3.0).abs() < 1e-4,
        "独立刚体位移误差应可预测: {error}"
    );
}

#[test]
fn non_uniform_display_scale_stays_outside_bullet_joint_transforms() {
    let pmx_a = pmx_body("Skirt_A", [-1.0, 0.0, 0.0]);
    let pmx_b = pmx_body("Skirt_B", [1.0, 0.0, 0.5]);
    let initial_a = pmx_initial_transform(&pmx_a);
    let initial_b = pmx_initial_transform(&pmx_b);
    let joint = pmx_joint([0.0, 0.0, 0.25], [0.0, 0.4, 0.0]);
    let fixture = production_joint(&joint, &pmx_a, &pmx_b, initial_a, initial_b);
    let model_local_error = joint_anchor_position_error(
        initial_a,
        fixture.data.frame_a,
        initial_b,
        fixture.data.frame_b,
    )
    .0;
    let display_root = Mat4::from_scale_rotation_translation(
        Vec3::new(1.7, 0.6, 2.3),
        Quat::from_rotation_y(0.9),
        Vec3::new(4.0, -3.0, 8.0),
    );
    let local_a = (initial_a * fixture.data.frame_a).w_axis.truncate();
    let local_b = (initial_b * fixture.data.frame_b).w_axis.truncate();
    let display_error = display_root
        .transform_point3(local_a)
        .distance(display_root.transform_point3(local_b));

    assert!(model_local_error < 1e-4);
    assert!(display_error < 1e-4);
    // 根缩放只在 Bullet 锚点算完后作为显示矩阵使用。
}

#[test]
fn inv_z_bind_offset_roundtrip_matches_production_rigid_body_data() {
    let mut body = pmx_body("Skirt_A", [1.2, -0.4, 2.1]);
    body.rotation = [0.17, -0.52, 0.91];
    let bind_bone_right = Mat4::from_rotation_translation(
        Quat::from_euler(glam::EulerRot::XYZ, -0.3, 0.8, 0.45),
        Vec3::new(-2.0, 3.5, 1.25),
    );
    let data = MmdRigidBodyData::from_pmx(&body, Some(bind_bone_right), body.size);
    let bind_bone_left = inv_z(bind_bone_right);

    let body_left = data.compute_body_matrix(bind_bone_left);
    let recovered_bone_left = data.compute_bone_matrix(body_left);
    let recovered_bone_right = inv_z(recovered_bone_left);

    assert!(body_left.abs_diff_eq(data.initial_transform, 1e-4));
    assert!(recovered_bone_right.abs_diff_eq(bind_bone_right, 1e-4));
}
