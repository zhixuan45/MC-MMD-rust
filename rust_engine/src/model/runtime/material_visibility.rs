use super::*;

impl MmdModel {
    pub(crate) fn tracked_head_bone_index(&self) -> Option<usize> {
        self.head_bone_index
    }

    pub(crate) fn replace_material_visibility(&mut self, visible: Vec<bool>) {
        self.material_visible = visible;
    }

    pub(crate) fn user_material_visibility_snapshot(&self) -> Vec<bool> {
        normalized_material_visibility(&self.user_material_visible, self.material_count())
    }

    pub(super) fn refresh_effective_material_visibility(&mut self) {
        let user_visible = self.user_material_visibility_snapshot();
        self.replace_material_visibility(user_visible);

        // 专用 EBO 已经按三角形裁掉头部前侧，此时不能再隐藏整个头部材质，
        // 否则为遮挡动作错位而保留的后脑外壳也会一起消失。
        if self.first_person_enabled && !self.has_first_person_mesh() {
            self.apply_first_person_material_mask();
        }
        if self.vr_hand_mode != 0 {
            self.apply_vr_hand_material_mask();
        }
    }

    fn set_user_material_visibility(&mut self, visible: Vec<bool>) {
        self.user_material_visible =
            normalized_material_visibility(&visible, self.material_count());
        self.refresh_effective_material_visibility();
    }

    pub(crate) fn build_first_person_heuristic_masks(
        &mut self,
        baseline_visible: &[bool],
    ) -> (Vec<bool>, Vec<bool>) {
        let (head_materials, body_materials) = self.classify_head_materials();
        let mut mirror_visible_materials = baseline_visible.to_vec();
        if mirror_visible_materials.len() != self.material_count() {
            mirror_visible_materials = vec![true; self.material_count()];
        }

        let mut hmd_visible_materials = mirror_visible_materials.clone();
        for material_id in head_materials {
            if !body_materials.contains(&material_id) && material_id < hmd_visible_materials.len() {
                hmd_visible_materials[material_id] = false;
            }
        }

        (hmd_visible_materials, mirror_visible_materials)
    }

    fn apply_first_person_material_mask(&mut self) {
        let (head_materials, body_materials) = self.classify_head_materials();
        let mut hidden_count = 0;
        for material_id in head_materials {
            if body_materials.contains(&material_id) || material_id >= self.material_visible.len() {
                continue;
            }
            self.material_visible[material_id] = false;
            hidden_count += 1;
        }
        log::info!(
            "First person material mask applied: hidden_materials={}, head_submeshes={}",
            hidden_count,
            self.head_submesh_flags.iter().filter(|&&x| x).count()
        );
    }

    fn apply_vr_hand_material_mask(&mut self) {
        if self.vr_hand_mode == 0 {
            return;
        }

        if !self.hand_detection_initialized {
            self.init_hand_detection();
        }

        let mut hand_mat_ids = std::collections::HashSet::new();
        for (i, &flag) in self.hand_submesh_flags.iter().enumerate() {
            if flag == self.vr_hand_mode && i < self.submeshes.len() {
                hand_mat_ids.insert(self.submeshes[i].material_id as usize);
            }
        }

        for (i, vis) in self.material_visible.iter_mut().enumerate() {
            *vis = *vis && hand_mat_ids.contains(&i);
        }
    }

    pub(crate) fn classify_head_materials(&mut self) -> (HashSet<usize>, HashSet<usize>) {
        if !self.head_detection_initialized {
            self.init_head_detection();
        }

        let mut head_materials = HashSet::new();
        let mut body_materials = HashSet::new();
        for (index, submesh) in self.submeshes.iter().enumerate() {
            let material_id = submesh.material_id.max(0) as usize;
            if index < self.head_submesh_flags.len() && self.head_submesh_flags[index] {
                head_materials.insert(material_id);
            } else {
                body_materials.insert(material_id);
            }
        }

        (head_materials, body_materials)
    }

    pub fn init_material_visibility(&mut self) {
        let visible = vec![true; self.materials.len()];
        self.user_material_visible = visible.clone();
        self.material_visible = visible;
    }

    /// 获取材质是否可见
    pub fn is_material_visible(&self, index: usize) -> bool {
        self.material_visible.get(index).copied().unwrap_or(true)
    }

    /// 设置材质可见性
    pub fn set_material_visible(&mut self, index: usize, visible: bool) {
        self.user_material_visible =
            normalized_material_visibility(&self.user_material_visible, self.material_count());
        if index < self.user_material_visible.len() {
            self.user_material_visible[index] = visible;
            self.refresh_effective_material_visibility();
        }
    }

    /// 根据材质名称设置可见性（支持部分匹配）
    pub fn set_material_visible_by_name(&mut self, name: &str, visible: bool) -> usize {
        let mut count = 0;
        self.user_material_visible =
            normalized_material_visibility(&self.user_material_visible, self.material_count());
        for (i, mat) in self.materials.iter().enumerate() {
            if mat.name.contains(name) {
                if i < self.user_material_visible.len() {
                    self.user_material_visible[i] = visible;
                    count += 1;
                }
            }
        }
        if count > 0 {
            self.refresh_effective_material_visibility();
        }
        count
    }

    /// 设置所有材质可见性
    pub fn set_all_materials_visible(&mut self, visible: bool) {
        self.set_user_material_visibility(vec![visible; self.material_count()]);
    }

    /// 获取材质名称
    pub fn get_material_name(&self, index: usize) -> Option<&str> {
        self.materials.get(index).map(|m| m.name.as_str())
    }

    /// 获取所有材质名称列表
    pub fn get_material_names(&self) -> Vec<String> {
        self.materials.iter().map(|m| m.name.clone()).collect()
    }

    // ========== 第一人称模式 ==========

    /// 初始化头部检测（模型加载后调用一次）
    /// 基于顶点位置判断：颈部骨骼 Y 坐标以上的子网格标记为头部
    pub fn init_head_detection(&mut self) {
        if self.head_detection_initialized {
            return;
        }
        self.head_detection_initialized = true;

        // 1. 查找头部骨骼（眼睛骨骼 fallback 用）
        let head_names = ["頭", "head", "Head", "あたま"];
        self.head_bone_index = None;
        for name in &head_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                self.head_bone_index = Some(idx);
                break;
            }
        }

        // 2. 确定颈部分界线 Y 坐标（优先颈部骨骼，fallback 到头部骨骼）
        let neck_names = ["首", "neck", "Neck"];
        let mut neck_y: Option<f32> = None;
        for name in &neck_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                if let Some(bone) = self.bone_manager.get_bone(idx) {
                    neck_y = Some(bone.initial_position.y);
                    log::info!(
                        "第一人称分界线: 使用颈部骨骼 '{}' Y={:.2}",
                        name,
                        bone.initial_position.y
                    );
                }
                break;
            }
        }
        if neck_y.is_none() {
            if let Some(idx) = self.head_bone_index {
                if let Some(bone) = self.bone_manager.get_bone(idx) {
                    neck_y = Some(bone.initial_position.y);
                    log::info!(
                        "第一人称分界线: 颈部骨骼未找到，fallback 到头部骨骼 Y={:.2}",
                        bone.initial_position.y
                    );
                }
            }
        }

        let cutoff_y = match neck_y {
            Some(y) => y,
            None => {
                log::warn!("颈部/头部骨骼均未找到，第一人称头部隐藏不可用");
                self.head_submesh_flags = vec![false; self.submeshes.len()];
                return;
            }
        };

        // 3. 对每个子网格，按顶点位置判断是否在脖子以上
        self.head_submesh_flags = Vec::with_capacity(self.submeshes.len());

        for submesh in &self.submeshes {
            let begin = submesh.begin_index as usize;
            let count = submesh.index_count as usize;

            if count == 0 {
                self.head_submesh_flags.push(false);
                continue;
            }

            let mut above_count: usize = 0;
            let mut total_count: usize = 0;

            for idx_offset in 0..count {
                let index_pos = begin + idx_offset;
                if index_pos >= self.indices.len() {
                    break;
                }
                let vertex_idx = self.indices[index_pos] as usize;
                if vertex_idx >= self.vertices.len() {
                    continue;
                }

                total_count += 1;
                if self.vertices[vertex_idx].position.y >= cutoff_y {
                    above_count += 1;
                }
            }

            let ratio = if total_count > 0 {
                above_count as f32 / total_count as f32
            } else {
                0.0
            };
            let is_head = ratio > 0.5;
            self.head_submesh_flags.push(is_head);

            let mat_name = self
                .materials
                .get(submesh.material_id as usize)
                .map(|m| m.name.as_str())
                .unwrap_or("?");
            if is_head {
                log::info!(
                    "  [HEAD] submesh={}, material={}, 脖子以上顶点={:.1}%",
                    self.head_submesh_flags.len() - 1,
                    mat_name,
                    ratio * 100.0
                );
            } else if ratio > 0.1 {
                log::info!(
                    "  [BODY] submesh={}, material={}, 脖子以上顶点={:.1}%",
                    self.head_submesh_flags.len() - 1,
                    mat_name,
                    ratio * 100.0
                );
            }
        }

        // 4. 查找眼睛骨骼。复合眼骨保留旧接口语义，左右眼另行缓存给桌面相机。
        let single_eye_names = ["両目", "目", "eye", "Eye", "Eyes", "eyes"];
        self.eye_bone_index = None;
        self.eye_bone_pair = None;

        for name in &single_eye_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                self.eye_bone_index = Some(idx);
                log::info!("眼睛骨骼检测: 使用 '{}' (索引={})", name, idx);
                break;
            }
        }

        let left_names = ["左目", "eye_L", "Eye_L", "LeftEye"];
        let right_names = ["右目", "eye_R", "Eye_R", "RightEye"];
        let mut left_idx = None;
        let mut right_idx = None;
        for name in &left_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                left_idx = Some(idx);
                break;
            }
        }
        for name in &right_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                right_idx = Some(idx);
                break;
            }
        }

        if let (Some(l), Some(r)) = (left_idx, right_idx) {
            self.eye_bone_pair = Some((l, r));
            log::info!("眼睛骨骼检测: 缓存左目({})+右目({})中点", l, r);
        } else if self.eye_bone_index.is_none() {
            if let Some(l) = left_idx {
                self.eye_bone_index = Some(l);
                log::info!("眼睛骨骼检测: 仅找到左目({})", l);
            } else if let Some(r) = right_idx {
                self.eye_bone_index = Some(r);
                log::info!("眼睛骨骼检测: 仅找到右目({})", r);
            } else {
                log::info!("眼睛骨骼检测: 未找到可用眼睛骨骼");
            }
        }
    }

    pub(super) fn ensure_first_person_mesh(&mut self) {
        if !self.head_detection_initialized {
            self.init_head_detection();
        }
        if self.first_person_mesh.is_some() {
            return;
        }
        let Some(head_index) = self.head_bone_index else {
            return;
        };
        let head_bones = self.bone_manager.collect_descendants(head_index);
        self.first_person_mesh = build_first_person_mesh(
            &self.vertices,
            &self.indices,
            &self.weights,
            &self.submeshes,
            &head_bones,
        );
        if let Some(mesh) = &self.first_person_mesh {
            log::info!(
                "第一人称几何裁切: 保留 {}/{} 个索引（完整移除头部主体）",
                mesh.indices.len(),
                self.indices.len()
            );
        } else {
            log::warn!("第一人称几何裁切未产生安全布局，回退到原始索引");
        }
    }

    /// 第一人称专用几何是否可用；失败时调用方应继续使用材质遮罩回退。
    pub(crate) fn has_first_person_mesh(&mut self) -> bool {
        self.ensure_first_person_mesh();
        self.first_person_mesh.is_some()
    }

    /// 用当前姿态和实际绘制矩阵刷新第一人称索引，并复制到调用方缓冲区。
    pub(crate) fn refresh_first_person_indices(
        &mut self,
        model_view: Mat4,
        projection: Mat4,
        gpu_skinning: bool,
        output: &mut [u8],
    ) -> usize {
        self.ensure_first_person_mesh();
        let Some(mut mesh) = self.first_person_mesh.take() else {
            return 0;
        };

        if self.first_person_position_scratch.len() != self.vertices.len() {
            self.first_person_position_scratch
                .resize(self.vertices.len(), Vec3::ZERO);
        }

        let matrices = self.bone_manager.get_skinning_matrices();
        for &vertex_index in mesh.dynamic_vertices() {
            let index = vertex_index as usize;
            let (Some(vertex), Some(weight), Some(morph_position), Some(position)) = (
                self.vertices.get(index),
                self.weights.get(index),
                self.update_positions.get(index),
                self.first_person_position_scratch.get_mut(index),
            ) else {
                continue;
            };

            // CPU 路径已经完成蒙皮；GPU 路径只对有限的头颈候选做局部 CPU 蒙皮。
            *position = if gpu_skinning {
                compute_vertex_skinning(*morph_position, vertex.normal, weight, matrices).0
            } else {
                *morph_position
            };
        }

        let _ = refresh_first_person_mesh(
            &mut mesh,
            &self.first_person_position_scratch,
            &self.indices,
            &self.submeshes,
            model_view,
            projection,
        );
        let byte_len = mesh
            .indices
            .len()
            .saturating_mul(std::mem::size_of::<u32>());
        let written = if byte_len <= output.len() {
            unsafe {
                std::ptr::copy_nonoverlapping(
                    mesh.indices.as_ptr() as *const u8,
                    output.as_mut_ptr(),
                    byte_len,
                );
            }
            mesh.indices.len()
        } else {
            0
        };
        self.first_person_mesh = Some(mesh);
        written
    }

    /// 设置第一人称模式
    /// 启用时只生成临时有效遮罩，用户材质配置不被覆盖。
    pub fn set_first_person_mode(&mut self, enabled: bool) {
        if self.first_person_enabled == enabled {
            return;
        }

        self.first_person_enabled = enabled;
        if let Some(mut runtime_state) = self.vrm_runtime_state.take() {
            runtime_state.input.first_person = enabled;
            runtime_state.refresh_output(self);
            self.vrm_runtime_state = Some(runtime_state);
            return;
        }

        self.refresh_effective_material_visibility();
    }

    /// 获取第一人称模式是否启用
    pub fn is_first_person_enabled(&self) -> bool {
        self.first_person_enabled
    }

    /// 获取头部骨骼的初始 Y 坐标（静态休息姿势，用于相机高度）
    /// 返回值为模型局部空间的 Y 坐标
    pub fn get_head_bone_rest_position_y(&mut self) -> f32 {
        // 确保头部检测已初始化
        if !self.head_detection_initialized {
            self.init_head_detection();
        }

        match self.head_bone_index {
            Some(idx) => {
                if let Some(bone) = self.bone_manager.get_bone(idx) {
                    bone.initial_position.y
                } else {
                    0.0
                }
            }
            None => 0.0,
        }
    }

    /// 获取眼睛骨骼的当前动画位置（模型局部空间）
    /// 每帧调用，返回经过动画/物理更新后的实时位置 [x, y, z]
    /// 用于第一人称模式下的相机跟踪
    pub fn get_eye_bone_animated_position(&mut self) -> Vec3 {
        if !self.head_detection_initialized {
            self.init_head_detection();
        }

        // 保持旧接口语义：模型提供両目/目时继续优先返回该骨骼的真实动画位置。
        if let Some(idx) = self.eye_bone_index {
            return self
                .bone_manager
                .get_bone(idx)
                .map(|b| b.position())
                .unwrap_or(Vec3::ZERO);
        }

        if let Some(midpoint) = self.animated_eye_pair_midpoint() {
            return midpoint;
        }

        Vec3::ZERO
    }

    /// 获取桌面第一人称相机锚点。
    /// 使用最终蒙皮矩阵计算双眼位置，保证相机与动画过渡后的实际网格同步。
    pub fn get_first_person_camera_anchor_position(&mut self) -> Vec3 {
        if !self.head_detection_initialized {
            self.init_head_detection();
        }

        self.rendered_eye_pair_midpoint()
            .or_else(|| {
                self.eye_bone_index
                    .and_then(|index| self.rendered_bone_position(index))
            })
            .unwrap_or(Vec3::ZERO)
    }

    fn animated_eye_pair_midpoint(&self) -> Option<Vec3> {
        let (left, right) = self.eye_bone_pair?;
        let left_pos = self.bone_manager.get_bone(left)?.position();
        let right_pos = self.bone_manager.get_bone(right)?.position();
        Some((left_pos + right_pos) * 0.5)
    }

    fn rendered_eye_pair_midpoint(&self) -> Option<Vec3> {
        let (left, right) = self.eye_bone_pair?;
        let left_pos = self.rendered_bone_position(left)?;
        let right_pos = self.rendered_bone_position(right)?;
        Some((left_pos + right_pos) * 0.5)
    }

    fn rendered_bone_position(&self, index: usize) -> Option<Vec3> {
        let bone = self.bone_manager.get_bone(index)?;
        let skinning_matrix = self.bone_manager.get_skinning_matrices().get(index)?;
        Some(skinning_matrix.transform_point3(bone.initial_position))
    }

    pub fn current_head_rotation(&mut self) -> Quat {
        if !self.head_detection_initialized {
            self.init_head_detection();
        }

        self.head_bone_index
            .map(|index| Quat::from_mat4(&self.bone_manager.get_global_transform(index)))
            .map(|rotation| {
                if rotation.length_squared() > 1e-6 {
                    rotation.normalize()
                } else {
                    Quat::IDENTITY
                }
            })
            .unwrap_or(Quat::IDENTITY)
    }
}
