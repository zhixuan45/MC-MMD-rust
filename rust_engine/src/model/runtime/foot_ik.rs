//! 台阶双足自适应 IK（Foot Placement IK）

use crate::skeleton::BoneManager;

const MAX_HEIGHT_DELTA_METERS: f32 = 0.65;
const SMOOTH_SPEED: f32 = 15.0;
const EPSILON_SNAP: f32 = 1e-5;

/// 运行时双足地形自适应 IK 状态
#[derive(Clone, Debug, Default)]
pub struct FootTerrainIkState {
    pub smoothed_pelvis_y: f32,
    pub smoothed_left_y: f32,
    pub smoothed_right_y: f32,
}

impl FootTerrainIkState {
    /// 更新平滑偏移并叠加到骨盆与双足 IK 骨骼（需在 `update_node_animation(false)` 之前调用）
    #[allow(clippy::too_many_arguments)]
    pub fn update_and_apply(
        &mut self,
        bone_manager: &mut BoneManager,
        dt: f32,
        foot_ik_enabled: bool,
        allow_foot_ik: bool,
        model_pos_y: f32,
        total_scale: f32,
        left_surface_y: Option<f32>,
        right_surface_y: Option<f32>,
    ) {
        let valid_scale = total_scale.is_finite() && total_scale > 1e-4;
        let valid_model_y = model_pos_y.is_finite();
        let left_valid = left_surface_y.filter(|y| y.is_finite());
        let right_valid = right_surface_y.filter(|y| y.is_finite());
        let active = foot_ik_enabled
            && allow_foot_ik
            && valid_scale
            && valid_model_y
            && (left_valid.is_some() || right_valid.is_some());

        let (target_pelvis, target_left, target_right) = if active {
            let delta_l = left_valid
                .map(|y| (y - model_pos_y).clamp(-MAX_HEIGHT_DELTA_METERS, MAX_HEIGHT_DELTA_METERS) / total_scale)
                .unwrap_or(0.0);
            let delta_r = right_valid
                .map(|y| (y - model_pos_y).clamp(-MAX_HEIGHT_DELTA_METERS, MAX_HEIGHT_DELTA_METERS) / total_scale)
                .unwrap_or(0.0);
            let pelvis = 0.0_f32.min(delta_l.min(delta_r));
            (pelvis, delta_l, delta_r)
        } else {
            (0.0, 0.0, 0.0)
        };

        let alpha = if dt.is_finite() && dt > 0.0 {
            (1.0 - (-SMOOTH_SPEED * dt).exp()).clamp(0.0, 1.0)
        } else {
            0.0
        };

        self.smoothed_pelvis_y += (target_pelvis - self.smoothed_pelvis_y) * alpha;
        self.smoothed_left_y += (target_left - self.smoothed_left_y) * alpha;
        self.smoothed_right_y += (target_right - self.smoothed_right_y) * alpha;

        if !active {
            if self.smoothed_pelvis_y.abs() < EPSILON_SNAP {
                self.smoothed_pelvis_y = 0.0;
            }
            if self.smoothed_left_y.abs() < EPSILON_SNAP {
                self.smoothed_left_y = 0.0;
            }
            if self.smoothed_right_y.abs() < EPSILON_SNAP {
                self.smoothed_right_y = 0.0;
            }
        }

        if self.smoothed_pelvis_y.abs() < EPSILON_SNAP
            && self.smoothed_left_y.abs() < EPSILON_SNAP
            && self.smoothed_right_y.abs() < EPSILON_SNAP
        {
            return;
        }

        let pelvis_idx = bone_manager
            .find_bone_by_name("センター")
            .or_else(|| bone_manager.find_bone_by_name("グルーブ"));

        if let Some(idx) = pelvis_idx {
            if let Some(bone) = bone_manager.get_bone_mut(idx) {
                bone.animation_translate.y += self.smoothed_pelvis_y;
            }
        }

        apply_foot_side(
            bone_manager,
            pelvis_idx,
            "左足ＩＫ",
            "左つま先ＩＫ",
            self.smoothed_left_y,
            self.smoothed_pelvis_y,
        );
        apply_foot_side(
            bone_manager,
            pelvis_idx,
            "右足ＩＫ",
            "右つま先ＩＫ",
            self.smoothed_right_y,
            self.smoothed_pelvis_y,
        );
    }
}

fn apply_foot_side(
    bone_manager: &mut BoneManager,
    pelvis_idx: Option<usize>,
    foot_name: &str,
    toe_name: &str,
    foot_offset_y: f32,
    pelvis_offset_y: f32,
) {
    let Some(foot_idx) = bone_manager.find_bone_by_name(foot_name) else {
        return;
    };

    // 若骨盆是足部 IK 的祖先节点，足部已继承骨盆下沉，需扣除以免双重叠加
    let pelvis_is_foot_ancestor = pelvis_idx.is_some_and(|p| is_ancestor(bone_manager, p, foot_idx));
    let applied_foot_y = if pelvis_is_foot_ancestor {
        foot_offset_y - pelvis_offset_y
    } else {
        foot_offset_y
    };

    if let Some(bone) = bone_manager.get_bone_mut(foot_idx) {
        bone.animation_translate.y += applied_foot_y;
    }
    bone_manager.set_ik_enabled_by_name(foot_name, true);

    if let Some(toe_idx) = bone_manager.find_bone_by_name(toe_name) {
        // 仅当脚趾 IK 不是足部 IK 的子孙时才补充偏移，避免子骨骼双重叠加
        if !is_ancestor(bone_manager, foot_idx, toe_idx) {
            let pelvis_is_toe_ancestor =
                pelvis_idx.is_some_and(|p| is_ancestor(bone_manager, p, toe_idx));
            let applied_toe_y = if pelvis_is_toe_ancestor {
                foot_offset_y - pelvis_offset_y
            } else {
                foot_offset_y
            };
            if let Some(toe_bone) = bone_manager.get_bone_mut(toe_idx) {
                toe_bone.animation_translate.y += applied_toe_y;
            }
        }
        bone_manager.set_ik_enabled_by_name(toe_name, true);
    }
}

fn is_ancestor(bone_manager: &BoneManager, ancestor_idx: usize, node_idx: usize) -> bool {
    let max_hops = bone_manager.bone_count();
    let mut current = node_idx;
    for _ in 0..max_hops {
        let Some(bone) = bone_manager.get_bone(current) else {
            return false;
        };
        if bone.parent_index < 0 {
            return false;
        }
        let parent = bone.parent_index as usize;
        if parent == ancestor_idx {
            return true;
        }
        current = parent;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::skeleton::{BoneLink, IkConfig};
    use glam::Vec3;

    fn make_bone(name: &str, parent_index: i32, pos: Vec3) -> BoneLink {
        let mut bone = BoneLink::new(name.to_string());
        bone.parent_index = parent_index;
        bone.initial_position = pos;
        bone
    }

    fn build_test_skeleton(center_is_ik_ancestor: bool) -> BoneManager {
        let mut bm = BoneManager::new();
        // 0: 全ての親
        bm.add_bone(make_bone("全ての親", -1, Vec3::ZERO));
        // 1: センター
        bm.add_bone(make_bone("センター", 0, Vec3::new(0.0, 8.0, 0.0)));
        let ik_parent = if center_is_ik_ancestor { 1 } else { 0 };
        // 2: 左足ＩＫ
        let mut left_ik = make_bone("左足ＩＫ", ik_parent, Vec3::new(1.0, 0.0, 0.0));
        left_ik.ik_config = Some(IkConfig {
            target_bone: 2,
            iterations: 10,
            limit_angle: 1.0,
            links: vec![],
        });
        bm.add_bone(left_ik);
        // 3: 右足ＩＫ
        let mut right_ik = make_bone("右足ＩＫ", ik_parent, Vec3::new(-1.0, 0.0, 0.0));
        right_ik.ik_config = Some(IkConfig {
            target_bone: 3,
            iterations: 10,
            limit_angle: 1.0,
            links: vec![],
        });
        bm.add_bone(right_ik);
        // 4: 左つま先ＩＫ (child of 左足ＩＫ)
        bm.add_bone(make_bone("左つま先ＩＫ", 2, Vec3::new(1.0, 0.0, -1.0)));
        // 5: 右つま先ＩＫ (sibling of 右足ＩＫ)
        bm.add_bone(make_bone("右つま先ＩＫ", ik_parent, Vec3::new(-1.0, 0.0, -1.0)));
        bm.build_hierarchy();
        bm
    }

    #[test]
    fn stair_step_offset_and_pelvis_sink_without_center_ancestry() {
        let mut bm = build_test_skeleton(false);
        bm.set_ik_enabled_by_name("左足ＩＫ", false);
        let mut state = FootTerrainIkState::default();

        // 左脚踩上 +0.45m 台阶，右脚在 0.0m 地面，total_scale = 0.1
        state.update_and_apply(
            &mut bm,
            1.0,
            true,
            true,
            10.0,
            0.1,
            Some(10.45),
            Some(10.0),
        );

        let center_y = bm.get_bone(1).unwrap().animation_translate.y;
        let left_y = bm.get_bone(2).unwrap().animation_translate.y;
        let right_y = bm.get_bone(3).unwrap().animation_translate.y;
        let left_toe_y = bm.get_bone(4).unwrap().animation_translate.y;
        let right_toe_y = bm.get_bone(5).unwrap().animation_translate.y;

        assert!((center_y - 0.0).abs() < 1e-3);
        assert!((left_y - 4.5).abs() < 1e-2);
        assert!(right_y.abs() < 1e-3);
        // 左つま先ＩＫ 是 左足ＩＫ 子骨骼，不应重复叠加
        assert!(left_toe_y.abs() < 1e-4);
        assert!(right_toe_y.abs() < 1e-4);
    }

    #[test]
    fn downward_step_sinks_pelvis_and_avoids_double_offset_when_center_is_ancestor() {
        let mut bm = build_test_skeleton(true);
        let mut state = FootTerrainIkState::default();

        // 左脚在 -0.5m 下台阶（夹紧 [-0.65, +0.65]），右脚在 +0.2m，scale = 0.1
        // target_L = -5.0, target_R = +2.0, pelvis = min(0, -5, 2) = -5.0
        state.update_and_apply(
            &mut bm,
            1.0,
            true,
            true,
            10.0,
            0.1,
            Some(9.5),
            Some(10.2),
        );

        let center_y = bm.get_bone(1).unwrap().animation_translate.y;
        let left_y = bm.get_bone(2).unwrap().animation_translate.y;
        let right_y = bm.get_bone(3).unwrap().animation_translate.y;
        let right_toe_y = bm.get_bone(5).unwrap().animation_translate.y;

        assert!((center_y - (-5.0)).abs() < 1e-2);
        // センター 是 左足ＩＫ 祖先：applied_L = -5.0 - (-5.0) = 0.0
        assert!(left_y.abs() < 1e-2);
        // センター 是 右足ＩＫ 祖先：applied_R = 2.0 - (-5.0) = 7.0
        assert!((right_y - 7.0).abs() < 1e-2);
        assert!((right_toe_y - 7.0).abs() < 1e-2);
    }

    #[test]
    fn clamps_to_065m_and_decays_to_zero_when_disabled() {
        let mut bm = build_test_skeleton(false);
        let mut state = FootTerrainIkState::default();

        state.update_and_apply(
            &mut bm,
            1.0,
            true,
            true,
            0.0,
            1.0,
            Some(2.0),
            Some(-2.0),
        );
        assert!((state.smoothed_left_y - 0.65).abs() < 1e-3);
        assert!((state.smoothed_right_y - (-0.65)).abs() < 1e-3);
        assert!((state.smoothed_pelvis_y - (-0.65)).abs() < 1e-3);

        // 禁用后按 1 - exp(-15 * dt) 指数平滑归零
        let dt: f32 = 0.05;
        let expected_factor = (-15.0 * dt).exp();
        let prev_left = state.smoothed_left_y;
        state.update_and_apply(&mut bm, dt, false, true, 0.0, 1.0, Some(2.0), Some(-2.0));
        assert!((state.smoothed_left_y - prev_left * expected_factor).abs() < 1e-4);

        // 多帧后归零
        for _ in 0..30 {
            state.update_and_apply(&mut bm, 0.05, false, true, 0.0, 1.0, None, None);
        }
        assert_eq!(state.smoothed_left_y, 0.0);
        assert_eq!(state.smoothed_right_y, 0.0);
        assert_eq!(state.smoothed_pelvis_y, 0.0);
    }
}
