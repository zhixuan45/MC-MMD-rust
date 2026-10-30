use super::*;
use crate::animation::VpdFile;

impl MmdModel {
    /// Apply a parsed pose to the same persistent inputs consumed by CPU/GPU ticks.
    pub fn apply_vpd_pose(&mut self, pose: &VpdFile) -> (usize, usize) {
        let bones: Vec<_> = pose.bones.iter().filter_map(|bone| {
            self.bone_manager.find_bone_by_name(&bone.name)
                .or_else(|| bone.alternate_name.as_deref().and_then(|name| self.bone_manager.find_bone_by_name(name)))
                .map(|index| (index, bone.translation, bone.rotation))
        }).collect();
        let morphs: Vec<_> = pose.morphs.iter().filter_map(|morph| {
            self.morph_manager.find_morph_by_name(&morph.name)
                .or_else(|| morph.alternate_name.as_deref().and_then(|name| self.morph_manager.find_morph_by_name(name)))
                .map(|index| (index, morph.weight))
        }).collect();
        if bones.is_empty() && morphs.is_empty() { return (0, 0); }
        self.clear_vpd_morphs();
        self.clear_vpd_bone_overrides();
        for &(index, translation, rotation) in &bones {
            self.set_vpd_bone_override(index, translation, rotation);
        }
        for &(index, weight) in &morphs {
            self.morph_manager.set_morph_weight(index, weight);
            self.track_vpd_morph(index);
        }
        self.sync_gpu_morph_weights();
        if !bones.is_empty() { self.reset_physics(); }
        (bones.len(), morphs.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::animation::{BoneKeyframe, MorphKeyframe, Motion};
    use crate::morph::{Morph, MorphType};
    use crate::skeleton::BoneLink;

    const POSE: &str = "Vocaloid Pose Data file\nmodel.osm;\n1;\nBone0{Root\n3,2,1;\n0,0.7071068,0,0.7071068;\n}\nMorph0{Smile\n0.75;\n}\n";

    fn animated_model() -> MmdModel {
        let mut model = MmdModel::new();
        model.bone_manager.add_bone(BoneLink::new("Root".into()));
        model.bone_manager.build_hierarchy();
        model.morph_manager.add_morph(Morph::new("Smile".into(), MorphType::Vertex));
        let mut motion = Motion::new();
        motion.insert_bone_keyframe("Root", BoneKeyframe::with_transform(0, Vec3::NEG_X, Quat::IDENTITY));
        motion.insert_morph_keyframe("Smile", MorphKeyframe::new(0, 0.0));
        model.set_layer_animation(0, Some(Arc::new(VmdAnimation::from_motion(motion))));
        model.play_layer(0);
        model
    }

    #[test]
    fn parsed_vpd_bones_and_morphs_survive_live_animation_in_cpu_and_gpu_paths() {
        let pose = VpdFile::load_from_bytes(POSE.as_bytes()).unwrap();
        for cpu in [true, false] {
            let mut model = animated_model();
            assert_eq!(model.apply_vpd_pose(&pose), (1, 1));
            for _ in 0..4 {
                if cpu { model.tick_animation(1.0 / 60.0); }
                else { model.tick_animation_no_skinning(1.0 / 60.0); }
                let expected = Mat4::from_rotation_translation(pose.bones[0].rotation, pose.bones[0].translation);
                assert!(model.bone_manager.get_global_transform(0).abs_diff_eq(expected, 1e-5));
                assert!(model.bone_manager.get_skinning_matrices()[0].abs_diff_eq(expected, 1e-5));
                assert!((model.morph_manager.get_morph_weight(0) - 0.75).abs() < 1e-5,
                        "A VMD morph track must not overwrite a selected VPD after one frame");
            }
        }
    }

    #[test]
    fn unmatched_pose_keeps_current_pose_and_explicit_changes_can_release_the_overlay() {
        let pose = VpdFile::load_from_bytes(POSE.as_bytes()).unwrap();
        let mut model = animated_model();
        model.apply_vpd_pose(&pose);
        let missing = VpdFile::load_from_bytes(POSE.replace("Root", "Missing").replace("Smile", "Absent").as_bytes()).unwrap();
        assert_eq!(model.apply_vpd_pose(&missing), (0, 0));
        model.tick_animation_no_skinning(0.02);
        assert!((model.bone_manager.get_global_transform(0).w_axis.x - 3.0).abs() < 1e-5);
        model.clear_vpd_bone_overrides();
        model.tick_animation_no_skinning(0.02);
        assert!((model.bone_manager.get_global_transform(0).w_axis.x + 1.0).abs() < 1e-5);
        model.set_user_morph_weight(0, 0.2);
        model.stop_layer(0);
        model.tick_animation_no_skinning(0.02);
        assert!((model.morph_manager.get_morph_weight(0) - 0.2).abs() < 1e-5);
        model.apply_vpd_pose(&pose);
        model.clear_vpd_morphs();
        assert_eq!(model.morph_manager.get_morph_weight(0), 0.0);
    }

    #[test]
    fn ambiguous_legacy_names_match_the_models_actual_names_in_cpu_and_gpu_paths() {
        let mut bytes = b"Vocaloid Pose Data file\nmodel.osm;\n3;\n".to_vec();
        for (name, encoding) in [("右腕", encoding_rs::SHIFT_JIS),
                                  ("腰部系绳指定", encoding_rs::GBK),
                                  ("M-后长发_3_1", encoding_rs::GBK)] {
            bytes.extend_from_slice(b"Bone0{");
            bytes.extend_from_slice(&encoding.encode(name).0);
            bytes.extend_from_slice(b"\n1,2,3;\n0,0,0,1;\n}\n");
        }
        bytes.extend_from_slice(b"Morph0{");
        bytes.extend_from_slice(&encoding_rs::GBK.encode("鞋 坠饰").0);
        bytes.extend_from_slice(b"\n0.75;\n}\n");
        let pose = VpdFile::load_from_bytes(&bytes).unwrap();
        for cpu in [true, false] {
            let mut model = MmdModel::new();
            for name in ["右腕", "腰部系绳指定", "M-后长发_3_1"] {
                model.bone_manager.add_bone(BoneLink::new(name.into()));
            }
            model.bone_manager.build_hierarchy();
            model.morph_manager.add_morph(Morph::new("鞋 坠饰".into(), MorphType::Vertex));
            assert_eq!(model.apply_vpd_pose(&pose), (3, 1));
            for _ in 0..3 {
                if cpu { model.tick_animation(0.02); }
                else { model.tick_animation_no_skinning(0.02); }
                for index in 0..3 {
                    assert!(model.bone_manager.get_global_transform(index).w_axis.truncate()
                            .abs_diff_eq(Vec3::new(1.0, 2.0, -3.0), 1e-5));
                }
                assert_eq!(model.morph_manager.get_morph_weight(0), 0.75);
            }
        }
    }
}
