use super::*;
use crate::skeleton::BoneLink;
use crate::vr::{VrTrackedPose, XR_TO_MODEL_SCALE};
use crate::vrm_runtime::{ArmIkHandCalibration, BodyTrackingCalibration};

#[test]
fn hand_matrix_should_prefer_explicit_attachment_over_dummy_and_wrist() {
    let mut model = MmdModel::new();
    add_test_bone(&mut model, "右手首", Vec3::new(1.0, 0.0, 0.0));
    add_test_bone(&mut model, "ダミー.R", Vec3::new(2.0, 0.0, 0.0));
    add_test_bone(&mut model, "Hand_Attach_R", Vec3::new(3.0, 0.0, 0.0));
    model.bone_manager.build_hierarchy();

    assert_eq!(
        model.get_right_hand_matrix().transform_point3(Vec3::ZERO),
        Vec3::new(3.0, 0.0, 0.0)
    );
}

#[test]
fn hand_matrix_should_fall_back_to_dummy_then_wrist() {
    let mut dummy_model = MmdModel::new();
    add_test_bone(&mut dummy_model, "右手首", Vec3::new(1.0, 0.0, 0.0));
    add_test_bone(&mut dummy_model, "ダミー.R", Vec3::new(2.0, 0.0, 0.0));
    dummy_model.bone_manager.build_hierarchy();

    let mut wrist_model = MmdModel::new();
    add_test_bone(&mut wrist_model, "右手首", Vec3::new(1.0, 0.0, 0.0));
    wrist_model.bone_manager.build_hierarchy();

    assert_eq!(
        dummy_model
            .get_right_hand_matrix()
            .transform_point3(Vec3::ZERO),
        Vec3::new(2.0, 0.0, 0.0)
    );
    assert_eq!(
        wrist_model
            .get_right_hand_matrix()
            .transform_point3(Vec3::ZERO),
        Vec3::new(1.0, 0.0, 0.0)
    );
}



fn add_test_bone(model: &mut MmdModel, name: &str, position: Vec3) {
    let mut bone = BoneLink::new(name.to_string());
    bone.initial_position = position;
    model.bone_manager.add_bone(bone);
}

#[test]
fn set_first_person_mode_should_restore_user_material_visibility() {
    let mut model = make_material_visibility_test_model();
    model.set_material_visible(2, false);

    model.set_first_person_mode(true);

    assert!(model.is_material_visible(0));
    assert!(!model.is_material_visible(1));
    assert!(!model.is_material_visible(2));
    assert_eq!(
        model.user_material_visibility_snapshot(),
        vec![true, true, false]
    );

    model.set_first_person_mode(false);

    assert!(model.is_material_visible(0));
    assert!(model.is_material_visible(1));
    assert!(!model.is_material_visible(2));
    assert_eq!(
        model.user_material_visibility_snapshot(),
        vec![true, true, false]
    );
}



#[test]
fn camera_anchor_should_follow_rendered_skinning_transition() {
    let mut model = make_camera_anchor_test_model(false, true, true);
    model
        .bone_manager
        .get_bone_mut(1)
        .unwrap()
        .animation_translate = Vec3::new(0.0, 0.0, -1.0);
    model
        .bone_manager
        .get_bone_mut(2)
        .unwrap()
        .animation_translate = Vec3::new(0.0, 0.0, -0.6);
    model.bone_manager.update_transforms(false);

    // 模拟 Sprint -> Idle 过渡中，网格仍只完成部分回正。
    model
        .bone_manager
        .set_skinning_matrix(1, Mat4::from_translation(Vec3::new(0.0, 0.0, -0.25)));
    model
        .bone_manager
        .set_skinning_matrix(2, Mat4::from_translation(Vec3::new(0.0, 0.0, -0.15)));

    let anchor = model.get_first_person_camera_anchor_position();
    let unblended_eye = model.get_eye_bone_animated_position();

    assert_eq!(anchor, Vec3::new(0.0, 17.6, 0.1));
    assert_eq!(unblended_eye, Vec3::new(0.0, 17.6, -0.5));
}


fn make_material_visibility_test_model() -> MmdModel {
    let mut model = MmdModel::new();
    model.materials = vec![
        MmdMaterial::default(),
        MmdMaterial::default(),
        MmdMaterial::default(),
    ];
    model.submeshes = vec![
        SubMesh::new(0, 3, 0),
        SubMesh::new(3, 3, 1),
        SubMesh::new(6, 3, 2),
    ];
    model.head_submesh_flags = vec![false, true, false];
    model.head_detection_initialized = true;
    model
        .bone_manager
        .add_bone(BoneLink::new("Head".to_string()));
    model.init_material_visibility();
    model
}

fn make_camera_anchor_test_model(
    has_combined_eye: bool,
    has_left_eye: bool,
    has_right_eye: bool,
) -> MmdModel {
    let mut model = MmdModel::new();

    let mut head = BoneLink::new("Head".to_string());
    head.initial_position = Vec3::new(0.0, 16.0, 0.0);
    model.bone_manager.add_bone(head);

    if has_combined_eye {
        let mut eye = BoneLink::new("両目".to_string());
        eye.initial_position = Vec3::new(4.0, 18.0, -2.0);
        model.bone_manager.add_bone(eye);
    }
    if has_left_eye {
        let mut left_eye = BoneLink::new("左目".to_string());
        left_eye.initial_position = Vec3::new(-0.3, 17.5, 0.2);
        model.bone_manager.add_bone(left_eye);
    }
    if has_right_eye {
        let mut right_eye = BoneLink::new("右目".to_string());
        right_eye.initial_position = Vec3::new(0.3, 17.7, 0.4);
        model.bone_manager.add_bone(right_eye);
    }

    model.bone_manager.build_hierarchy();
    model
}

#[test]
fn minecraft_world_position_is_converted_once_to_mmd_physics_units() {
    let mut model = MmdModel::new();
    model.set_model_position_and_yaw(1.0, 2.0, 3.0, std::f32::consts::FRAC_PI_2);
    assert_eq!(model.model_transform.w_axis.truncate(), Vec3::new(10.0, 20.0, 30.0));
    assert!((model.model_transform.x_axis.truncate().length() - 1.0).abs() < 1e-6);
}
