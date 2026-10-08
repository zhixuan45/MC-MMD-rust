use super::*;

impl MmdModel {
    pub fn initialize_vrm_runtime(&mut self, extensions: VrmExtensions) {
        if !self.is_vrm {
            return;
        }

        let mut runtime_state = VrmModelRuntimeState::new(self, extensions);
        runtime_state.initialize_output(self);
        self.vrm_runtime_state = Some(Box::new(runtime_state));
    }

    pub fn set_vrm_runtime_input(&mut self, input: VrmRuntimeInput) {
        self.first_person_enabled = input.first_person;
        if let Some(runtime_state) = self.vrm_runtime_state.as_mut() {
            runtime_state.input = input;
        }
    }

    pub fn vrm_runtime_output(&self) -> Option<&VrmRuntimeOutput> {
        self.vrm_runtime_state
            .as_deref()
            .map(|runtime_state| &runtime_state.output)
    }

    pub(super) fn with_vrm_runtime_state<R>(
        &mut self,
        f: impl FnOnce(&mut Self, &mut VrmModelRuntimeState) -> R,
    ) -> Option<R> {
        let mut runtime_state = self.vrm_runtime_state.take()?;
        let result = f(self, &mut runtime_state);
        self.vrm_runtime_state = Some(runtime_state);
        Some(result)
    }

    pub fn apply_vr_tracking_input(
        &mut self,
        tracking: Option<VrmTrackingInput>,
        hand_calibration: HandTrackingCalibration,
        arm_ik_calibration: ArmIkCalibration,
        body_calibration: BodyTrackingCalibration,
    ) {
        let Some(frame) = resolve_tracking_frame_for_model(
            self,
            tracking,
            hand_calibration,
            arm_ik_calibration,
            body_calibration,
        ) else {
            self.set_vr_enabled(false);
            self.set_vr_tracking_frame(None);
            return;
        };

        self.set_vr_enabled(true);
        self.set_vr_tracking_frame(Some(frame));
    }

    pub fn apply_java_vr_tracking_input_packet(&mut self, tracking_packet: &[f32]) {
        if tracking_packet.len() != 21 {
            return;
        }

        let mut packet = [0.0f32; 21];
        packet.copy_from_slice(tracking_packet);

        let current_strength = self.vr_ik_strength;
        let hand_calibration = if self.is_vrm {
            vrm_controller_hand_tracking_calibration()
        } else {
            pmx_controller_hand_tracking_calibration()
        };
        let tracking = java_tracking_input_from_packet(&packet);
        let Some(mut frame) = resolve_java_tracking_frame_for_model(
            self,
            Some(tracking),
            hand_calibration,
            ArmIkCalibration::default(),
            BodyTrackingCalibration::default(),
        ) else {
            self.set_vr_enabled(false);
            self.set_vr_tracking_frame(None);
            return;
        };

        let defaults = frame.body_calibration;
        let calibration = vivecraft_body_tracking_calibration();
        frame.body_calibration = BodyTrackingCalibration {
            head_rest_anchor_model: defaults.head_rest_anchor_model,
            shoulder_width_model: defaults.shoulder_width_model,
            shoulder_depth_model: defaults.shoulder_depth_model,
            body_yaw_follow_gain: calibration.body_yaw_follow_gain,
            horizontal_translation_follow_gain: calibration.horizontal_translation_follow_gain,
            vertical_translation_follow_gain: calibration.vertical_translation_follow_gain,
            body_translation_clamp_model: calibration.body_translation_clamp_model,
            shoulder_follow_gain: calibration.shoulder_follow_gain,
        };

        self.set_vr_enabled(true);
        self.set_vr_tracking_frame(Some(frame));
        self.set_vr_ik_strength(current_strength);
    }

    // ========== 材质可见性控制 ==========

    /// 初始化材质可见性（默认全部可见）
    pub fn set_head_angle(&mut self, x: f32, y: f32, z: f32) {
        self.head_angle_x = x;
        self.head_angle_y = y;
        self.head_angle_z = z;
    }

    /// 应用头部旋转到骨骼
    pub(super) fn apply_head_rotation(&mut self) {
        // 延迟搜索 + 缓存头部骨骼索引（只搜索一次）
        if !self.head_bone_searched {
            self.head_bone_searched = true;
            let head_names = ["頭", "head", "Head", "あたま"];
            for name in &head_names {
                if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                    self.head_bone_cached = Some(idx);
                    break;
                }
            }
        }

        if let Some(bone_idx) = self.head_bone_cached {
            let rotation = glam::Quat::from_euler(
                glam::EulerRot::XYZ,
                self.head_angle_x,
                self.head_angle_y,
                self.head_angle_z,
            );
            self.bone_manager.add_bone_rotation(bone_idx, rotation);
        }

        // 应用眼球追踪
        self.apply_eye_rotation();
    }

    /// 设置眼球追踪角度（会自动限制在最大角度内）
    pub fn set_eye_angle(&mut self, x: f32, y: f32) {
        // 限制在最大角度范围内
        self.eye_angle_x = x.clamp(-self.eye_max_angle, self.eye_max_angle);
        self.eye_angle_y = y.clamp(-self.eye_max_angle, self.eye_max_angle);
    }

    /// 设置眼球最大转动角度（弧度）
    pub fn set_eye_max_angle(&mut self, max_angle: f32) {
        self.eye_max_angle = max_angle.clamp(0.1, 1.0); // 约 5.7° - 57°
    }

    /// 启用/禁用眼球追踪
    pub fn set_eye_tracking_enabled(&mut self, enabled: bool) {
        self.eye_tracking_enabled = enabled;
        if enabled && self.eye_bone_left.is_none() {
            // 首次启用时查找眼睛骨骼
            self.find_eye_bones();
        }
    }

    /// 获取眼球追踪是否启用
    pub fn is_eye_tracking_enabled(&self) -> bool {
        self.eye_tracking_enabled
    }

    /// 查找眼睛骨骼并缓存索引
    fn find_eye_bones(&mut self) {
        // 扩展的眼睛骨骼名称列表
        let left_eye_names = [
            "左目", "eye_L", "Eye_L", "LeftEye", "left_eye", "Left_Eye", "eyeL", "EyeL", "左眼",
            "L_Eye", "eye.L", "Eye.L",
        ];
        let right_eye_names = [
            "右目",
            "eye_R",
            "Eye_R",
            "RightEye",
            "right_eye",
            "Right_Eye",
            "eyeR",
            "EyeR",
            "右眼",
            "R_Eye",
            "eye.R",
            "Eye.R",
        ];

        // 查找左眼
        self.eye_bone_left = None;
        for name in &left_eye_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                self.eye_bone_left = Some(idx);
                break;
            }
        }

        // 查找右眼
        self.eye_bone_right = None;
        for name in &right_eye_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                self.eye_bone_right = Some(idx);
                break;
            }
        }
    }

    /// 应用眼球旋转到骨骼（左目、右目）
    fn apply_eye_rotation(&mut self) {
        if !self.eye_tracking_enabled {
            return;
        }

        // 使用缓存的骨骼索引
        let left_idx = self.eye_bone_left;
        let right_idx = self.eye_bone_right;

        if left_idx.is_none() && right_idx.is_none() {
            return;
        }

        // 眼球旋转（上下左右看）
        // 直接使用眼球追踪角度覆盖动画旋转，确保实时响应
        let rotation =
            glam::Quat::from_euler(glam::EulerRot::XYZ, self.eye_angle_x, self.eye_angle_y, 0.0);

        // 在动画旋转基础上叠加眼球追踪旋转
        if let Some(idx) = left_idx {
            self.bone_manager.add_bone_rotation(idx, rotation);
        }

        // 应用到右眼
        if let Some(idx) = right_idx {
            self.bone_manager.add_bone_rotation(idx, rotation);
        }
    }

    // ========== 自动眨眼 ==========

    /// 启用/禁用自动眨眼
    pub fn set_auto_blink_enabled(&mut self, enabled: bool) {
        self.auto_blink_enabled = enabled;
        if enabled {
            // 初始化眨眼 Morph 索引缓存
            self.find_blink_morph();
            // 随机初始计时器，避免所有模型同时眨眼
            self.blink_timer = rand_float() * self.blink_interval;
        }
    }

    /// 获取自动眨眼是否启用
    pub fn is_auto_blink_enabled(&self) -> bool {
        self.auto_blink_enabled
    }

    /// 设置眨眼参数
    pub fn set_blink_params(&mut self, interval: f32, duration: f32) {
        self.blink_interval = interval.max(0.5); // 最小 0.5 秒间隔
        self.blink_duration = duration.clamp(0.05, 0.5); // 0.05-0.5 秒
    }

    /// 查找眨眼 Morph 索引
    fn find_blink_morph(&mut self) {
        // 常见眨眼 Morph 名称
        let blink_names = [
            "まばたき",
            "眨眼",
            "blink",
            "Blink",
            "まばたき両目",
            "ウィンク",
            "wink",
        ];

        for name in &blink_names {
            if let Some(idx) = self.morph_manager.find_morph_by_name(name) {
                self.blink_morph_index = Some(idx);
                return;
            }
        }
        self.blink_morph_index = None;
    }

    /// 更新自动眨眼（每帧调用）
    /// 返回是否需要同步 GPU Morph 权重
    pub(super) fn update_auto_blink(&mut self, delta_time: f32) -> bool {
        if !self.auto_blink_enabled {
            return false;
        }

        let morph_idx = match self.blink_morph_index {
            Some(idx) => idx,
            None => return false,
        };

        let mut needs_sync = false;

        if self.is_blinking {
            // 正在眨眼，更新进度
            self.blink_phase += delta_time / self.blink_duration;

            if self.blink_phase >= 1.0 {
                // 眨眼结束
                self.is_blinking = false;
                self.blink_phase = 0.0;
                self.morph_manager.set_morph_weight(morph_idx, 0.0);
                // 添加随机变化到下次眨眼间隔
                self.blink_timer = self.blink_interval * (0.7 + rand_float() * 0.6);
                needs_sync = true;
            } else {
                // 计算眨眼权重：0 -> 1 -> 0 (使用 sin 曲线)
                let weight = (self.blink_phase * std::f32::consts::PI).sin();
                self.morph_manager.set_morph_weight(morph_idx, weight);
                needs_sync = true;
            }
        } else {
            // 等待下次眨眼
            self.blink_timer -= delta_time;

            if self.blink_timer <= 0.0 {
                // 开始眨眼
                self.is_blinking = true;
                self.blink_phase = 0.0;
            }
        }

        needs_sync
    }
}
