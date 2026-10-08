use super::*;

impl MmdModel {
    pub fn set_vr_enabled(&mut self, enabled: bool) {
        self.vr_enabled = enabled;
    }

    pub fn is_vr_enabled(&self) -> bool {
        self.vr_enabled
    }

    pub(crate) fn set_vr_tracking_frame(&mut self, frame: Option<VrTrackingFrame>) {
        self.vr_tracking_frame = frame;
        if let Some(frame) = frame {
            self.vr_tracking_data = frame.to_tracking_packet();
        } else {
            self.vr_tracking_data = [0.0; 21];
        }
    }

    /// 设置 VR 追踪数据（长度必须为 21）
    pub fn set_vr_tracking_data(&mut self, data: &[f32]) {
        if data.len() == 21 {
            self.vr_tracking_data.copy_from_slice(data);
            let arm_ik_calibration = self
                .vr_tracking_frame
                .map(|frame| frame.arm_ik_calibration)
                .unwrap_or_else(ArmIkCalibration::default);
            let body_calibration = self
                .vr_tracking_frame
                .map(|frame| frame.body_calibration)
                .unwrap_or_default();
            self.vr_tracking_frame = Some(VrTrackingFrame::from_tracking_packet(
                &self.vr_tracking_data,
                arm_ik_calibration,
                body_calibration,
            ));
        }
    }

    pub fn set_vr_ik_strength(&mut self, strength: f32) {
        self.vr_ik_strength = strength.clamp(0.0, 1.0);
    }

    pub fn vr_tracking_data(&self) -> &[f32; 21] {
        &self.vr_tracking_data
    }

    pub fn vr_ik_strength(&self) -> f32 {
        self.vr_ik_strength
    }

    pub fn vr_debug_snapshot(&self) -> ModelVrDebugSnapshot {
        ModelVrDebugSnapshot {
            head_local_model: self.vr_debug_state.head_local_model,
            body_anchor_model: self.vr_debug_state.body_anchor_model,
            left_palm_target_model: self.vr_debug_state.left_palm_target_model,
            right_palm_target_model: self.vr_debug_state.right_palm_target_model,
            left_auto_wrist_offset_model: self.vr_debug_state.left_auto_wrist_offset_model,
            right_auto_wrist_offset_model: self.vr_debug_state.right_auto_wrist_offset_model,
            left_wrist_solved_model: self.vr_debug_state.left_wrist_solved_model,
            right_wrist_solved_model: self.vr_debug_state.right_wrist_solved_model,
            left_wrist_error_cm: self.vr_debug_state.left_wrist_error_cm,
            right_wrist_error_cm: self.vr_debug_state.right_wrist_error_cm,
        }
    }

    pub(crate) fn vr_debug_state(&self) -> VrDebugState {
        self.vr_debug_state
    }

    // ======== VR 手部模式 ========

    /// 初始化手部子网格检测（基于骨骼权重判断左/右手归属）
    pub(super) fn init_hand_detection(&mut self) {
        if self.hand_detection_initialized {
            return;
        }
        self.hand_detection_initialized = true;

        // 收集左/右手骨骼索引
        let left_names = [
            "左腕",
            "左手首",
            "左親指０",
            "左親指１",
            "左親指２",
            "左人指１",
            "左人指２",
            "左人指３",
            "左中指１",
            "左中指２",
            "左中指３",
            "左薬指１",
            "左薬指２",
            "左薬指３",
            "左小指１",
            "左小指２",
            "左小指３",
        ];
        let right_names = [
            "右腕",
            "右手首",
            "右親指０",
            "右親指１",
            "右親指２",
            "右人指１",
            "右人指２",
            "右人指３",
            "右中指１",
            "右中指２",
            "右中指３",
            "右薬指１",
            "右薬指２",
            "右薬指３",
            "右小指１",
            "右小指２",
            "右小指３",
        ];

        let mut left_bones = std::collections::HashSet::new();
        let mut right_bones = std::collections::HashSet::new();

        for name in &left_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                left_bones.insert(idx as i32);
            }
        }
        for name in &right_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                right_bones.insert(idx as i32);
            }
        }

        log::info!(
            "VR 手部检测: 左手骨骼={}, 右手骨骼={}",
            left_bones.len(),
            right_bones.len()
        );

        if left_bones.is_empty() && right_bones.is_empty() {
            self.hand_submesh_flags = vec![0u8; self.submeshes.len()];
            log::warn!("未找到手部骨骼，手部模式不可用");
            return;
        }

        // 对每个子网格，统计顶点的骨骼权重归属
        self.hand_submesh_flags = Vec::with_capacity(self.submeshes.len());

        for submesh in &self.submeshes {
            let begin = submesh.begin_index as usize;
            let count = submesh.index_count as usize;
            let mut left_weight_sum = 0.0f32;
            let mut right_weight_sum = 0.0f32;
            let mut total_weight_sum = 0.0f32;

            for idx_offset in 0..count {
                let index_pos = begin + idx_offset;
                if index_pos >= self.indices.len() {
                    break;
                }
                let vi = self.indices[index_pos] as usize;
                if vi >= self.weights.len() {
                    continue;
                }

                // 提取该顶点的骨骼索引和权重
                let pairs: Vec<(i32, f32)> = match &self.weights[vi] {
                    VertexWeight::Bdef1 { bone } => vec![(*bone, 1.0)],
                    VertexWeight::Bdef2 { bones, weight } => {
                        vec![(bones[0], *weight), (bones[1], 1.0 - *weight)]
                    }
                    VertexWeight::Bdef4 { bones, weights } => {
                        (0..4).map(|j| (bones[j], weights[j])).collect()
                    }
                    VertexWeight::Sdef { bones, weight, .. } => {
                        vec![(bones[0], *weight), (bones[1], 1.0 - *weight)]
                    }
                    VertexWeight::Qdef { bones, weights } => {
                        (0..4).map(|j| (bones[j], weights[j])).collect()
                    }
                };

                for (bone_id, w) in &pairs {
                    total_weight_sum += w;
                    if left_bones.contains(bone_id) {
                        left_weight_sum += w;
                    }
                    if right_bones.contains(bone_id) {
                        right_weight_sum += w;
                    }
                }
            }

            // 阈值：超过 60% 权重属于某只手则标记
            let flag = if total_weight_sum > 0.0 {
                let left_ratio = left_weight_sum / total_weight_sum;
                let right_ratio = right_weight_sum / total_weight_sum;
                if left_ratio > 0.6 {
                    1u8
                } else if right_ratio > 0.6 {
                    2u8
                } else {
                    0u8
                }
            } else {
                0u8
            };

            self.hand_submesh_flags.push(flag);

            if flag != 0 {
                let mat_name = self
                    .materials
                    .get(submesh.material_id as usize)
                    .map(|m| m.name.as_str())
                    .unwrap_or("?");
                let side = if flag == 1 { "左手" } else { "右手" };
                log::info!(
                    "  [{}] submesh={}, material={}",
                    side,
                    self.hand_submesh_flags.len() - 1,
                    mat_name
                );
            }
        }
    }

    /// 设置 VR 手部渲染模式
    /// mode: 0=全身（恢复）, 1=仅左手, 2=仅右手
    pub fn set_vr_hand_mode(&mut self, mode: u8) {
        if !self.hand_detection_initialized {
            self.init_hand_detection();
        }

        let old_mode = self.vr_hand_mode;
        self.vr_hand_mode = mode;

        if mode == old_mode {
            return;
        }

        self.refresh_effective_material_visibility();
    }

    pub fn memory_usage(&self) -> u64 {
        use std::mem::size_of;
        let mut total: u64 = 0;

        // 静态数据
        total += (self.vertices.capacity() * size_of::<RuntimeVertex>()) as u64;
        total += (self.indices.capacity() * size_of::<u32>()) as u64;
        total += (self.weights.capacity() * size_of::<VertexWeight>()) as u64;
        total += (self.materials.capacity() * size_of::<MmdMaterial>()) as u64;
        total += (self.submeshes.capacity() * size_of::<SubMesh>()) as u64;
        if let Some(mesh) = &self.first_person_mesh {
            total += (mesh.indices.capacity() * size_of::<u32>()) as u64;
            total += (mesh.submeshes.capacity() * size_of::<SubMesh>()) as u64;
        }
        // texture_paths: 每个 String 有堆分配
        for s in &self.texture_paths {
            total += s.capacity() as u64;
        }
        total += (self.texture_paths.capacity() * size_of::<String>()) as u64;

        // PMX 原始数据（刚体/关节）
        total +=
            (self.rigid_bodies.capacity() * size_of::<mmd::pmx::rigid_body::RigidBody>()) as u64;
        total += (self.joints.capacity() * size_of::<mmd::pmx::joint::Joint>()) as u64;

        // 运行时更新缓冲区
        total += (self.update_positions.capacity() * size_of::<Vec3>()) as u64;
        total += (self.update_normals.capacity() * size_of::<Vec3>()) as u64;
        total += (self.update_uvs.capacity() * size_of::<Vec2>()) as u64;
        total += (self.update_positions_raw.capacity() * size_of::<f32>()) as u64;
        total += (self.update_normals_raw.capacity() * size_of::<f32>()) as u64;
        total += (self.update_uvs_raw.capacity() * size_of::<f32>()) as u64;

        // GPU 蒙皮缓冲区
        total += (self.bone_indices.capacity() * size_of::<i32>()) as u64;
        total += (self.bone_weights.capacity() * size_of::<f32>()) as u64;
        total += (self.original_positions.capacity() * size_of::<f32>()) as u64;
        total += (self.original_normals.capacity() * size_of::<f32>()) as u64;

        // GPU Morph 缓冲区（可能非常大）
        total += (self.gpu_morph_offsets.capacity() * size_of::<f32>()) as u64;
        total += (self.gpu_morph_weights.capacity() * size_of::<f32>()) as u64;
        total += (self.vertex_morph_indices.capacity() * size_of::<usize>()) as u64;

        // GPU UV Morph 缓冲区
        total += (self.gpu_uv_morph_offsets.capacity() * size_of::<f32>()) as u64;
        total += (self.gpu_uv_morph_weights.capacity() * size_of::<f32>()) as u64;
        total += (self.uv_morph_indices.capacity() * size_of::<usize>()) as u64;

        // 材质 Morph 结果缓存
        total += (self.material_morph_results_flat_cache.capacity() * size_of::<f32>()) as u64;

        // 物理缓冲区
        total += (self.physics_bone_transforms_buf.capacity() * size_of::<Mat4>()) as u64;
        total += (self.transition_matrices.capacity() * size_of::<Mat4>()) as u64;

        // 材质可见性
        total += (self.material_visible.capacity() * size_of::<bool>()) as u64;
        total += (self.head_submesh_flags.capacity() * size_of::<bool>()) as u64;

        // 子系统估算
        total += self.bone_manager.memory_usage();
        total += self.morph_manager.memory_usage();

        // VPD 骨骼覆盖
        total += (self.vpd_bone_overrides.capacity()
            * (size_of::<usize>() + size_of::<(Vec3, Quat)>())) as u64;

        total
    }
}
