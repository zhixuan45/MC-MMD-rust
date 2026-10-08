use super::*;

impl MmdModel {
    /// 设置模型全局变换
    pub fn set_model_transform(&mut self, transform: Mat4) {
        self.model_transform = transform;
    }

    /// 设置模型位置和朝向（用于惯性计算）
    /// x, y, z: Minecraft 世界坐标（方块/米），自动按 1 方块 = 10 MMD 局部单位换算速度与惯性
    pub fn set_model_position_and_yaw(&mut self, x: f32, y: f32, z: f32, yaw: f32) {
        let cos_y = yaw.cos();
        let sin_y = yaw.sin();
        let mmd_scale = 10.0_f32;
        self.model_transform = Mat4::from_cols(
            Vec4::new(cos_y, 0.0, sin_y, 0.0),
            Vec4::new(0.0, 1.0, 0.0, 0.0),
            Vec4::new(-sin_y, 0.0, cos_y, 0.0),
            Vec4::new(x * mmd_scale, y * mmd_scale, z * mmd_scale, 1.0),
        );
    }

    /// 获取模型全局变换
    pub fn model_transform(&self) -> Mat4 {
        self.model_transform
    }

    /// 设置 VRM 模型标志
    pub fn set_vrm(&mut self, is_vrm: bool) {
        self.is_vrm = is_vrm;
    }

    /// 是否为 VRM 模型
    pub fn is_vrm(&self) -> bool {
        self.is_vrm
    }

    /// 获取右手矩阵
    pub fn get_right_hand_matrix(&self) -> Mat4 {
        self.get_hand_attachment_matrix(
            "Hand_Attach_R",
            'R',
            &["右手首", "右腕", "right_hand", "RightHand"],
        )
    }

    /// 获取左手矩阵
    pub fn get_left_hand_matrix(&self) -> Mat4 {
        self.get_hand_attachment_matrix(
            "Hand_Attach_L",
            'L',
            &["左手首", "左腕", "left_hand", "LeftHand"],
        )
    }

    fn get_hand_attachment_matrix(
        &self,
        explicit_name: &str,
        dummy_side: char,
        fallback_names: &[&str],
    ) -> Mat4 {
        // 显式挂点优先于录制时期存在多种分隔符的 MMD Dummy 命名。
        if let Some(index) = find_hand_attachment(&self.bone_manager, explicit_name, dummy_side) {
            return self.bone_manager.get_global_transform(index);
        }
        for name in fallback_names {
            if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                return self.bone_manager.get_global_transform(idx);
            }
        }
        Mat4::IDENTITY
    }

    /// 获取更新后的顶点位置数据指针
    pub fn get_positions_ptr(&self) -> *const f32 {
        self.update_positions_raw.as_ptr()
    }

    /// 获取更新后的法线数据指针
    pub fn get_normals_ptr(&self) -> *const f32 {
        self.update_normals_raw.as_ptr()
    }

    /// 获取 UV 数据指针
    pub fn get_uvs_ptr(&self) -> *const f32 {
        self.update_uvs_raw.as_ptr()
    }

    /// 获取索引数据指针
    pub fn get_indices_ptr(&self) -> *const u32 {
        self.indices.as_ptr()
    }

    /// 获取第一人称索引指针；无专用布局时返回空指针。
    pub fn get_first_person_indices_ptr(&mut self) -> *const u32 {
        self.ensure_first_person_mesh();
        self.first_person_mesh
            .as_ref()
            .map_or(std::ptr::null(), |mesh| mesh.indices.as_ptr())
    }

    // ========== 批量子网格元数据（G3 优化）==========

    /// 批量获取所有子网格的渲染元数据，避免 Java 侧逐子网格 JNI 调用
    ///
    /// 输出布局（每子网格 20 字节）：
    /// - offset  0: i32 materialID
    /// - offset  4: i32 beginIndex
    /// - offset  8: i32 vertexCount
    /// - offset 12: f32 alpha（基础材质 alpha，Java 侧再叠加 morph）
    /// - offset 16: u8  isVisible (0/1)
    /// - offset 17: u8  bothFace  (0/1)
    /// - offset 18: u8  hasEdge   (0/1)
    /// - offset 19: u8  padding
    ///
    /// 返回写入的子网格数量
    pub fn batch_get_sub_mesh_data(&self, output: &mut [u8]) -> usize {
        self.write_sub_mesh_data(output, &self.submeshes)
    }

    /// 按单次 Draw 的视图选择原始或第一人称子网格范围。
    pub fn batch_get_sub_mesh_data_for_view(
        &mut self,
        output: &mut [u8],
        first_person_view: bool,
    ) -> usize {
        if first_person_view {
            self.ensure_first_person_mesh();
        }
        let submeshes = if first_person_view {
            self.first_person_mesh
                .as_ref()
                .map_or(self.submeshes.as_slice(), |mesh| mesh.submeshes.as_slice())
        } else {
            self.submeshes.as_slice()
        };
        self.write_sub_mesh_data(output, submeshes)
    }

    fn write_sub_mesh_data(&self, output: &mut [u8], submeshes: &[SubMesh]) -> usize {
        const STRIDE: usize = 20;
        let count = submeshes.len();
        if output.len() < count * STRIDE {
            return 0;
        }

        for (i, submesh) in submeshes.iter().enumerate() {
            let mat_id = submesh.material_id as i32;
            let begin = submesh.begin_index as i32;
            let vert_count = submesh.index_count as i32;
            let alpha = self
                .materials
                .get(submesh.material_id as usize)
                .map(|m| m.diffuse.w)
                .unwrap_or(1.0f32);
            let visible: u8 = if self.is_material_visible(submesh.material_id as usize) {
                1
            } else {
                0
            };
            let mat = self.materials.get(submesh.material_id as usize);
            let both_face: u8 = mat
                .map(|m| if m.is_double_sided() { 1u8 } else { 0u8 })
                .unwrap_or(0u8);
            let has_edge: u8 = mat
                .map(|m| if m.has_edge() { 1u8 } else { 0u8 })
                .unwrap_or(0u8);

            unsafe {
                let p = output.as_mut_ptr().add(i * STRIDE);
                (p as *mut i32).write_unaligned(mat_id);
                (p.add(4) as *mut i32).write_unaligned(begin);
                (p.add(8) as *mut i32).write_unaligned(vert_count);
                (p.add(12) as *mut f32).write_unaligned(alpha);
                *p.add(16) = visible;
                *p.add(17) = both_face;
                *p.add(18) = has_edge;
            }
        }

        count
    }

    // ========== GPU 蒙皮相关方法 ==========

    /// 初始化 GPU 蒙皮数据（模型加载后调用）
    pub fn init_gpu_skinning_data(&mut self) {
        let vertex_count = self.vertices.len();

        // 初始化骨骼索引和权重缓冲区（每顶点 4 个）
        self.bone_indices = vec![-1; vertex_count * 4];
        self.bone_weights = vec![0.0; vertex_count * 4];

        // 从权重数据填充
        for (i, weight) in self.weights.iter().enumerate() {
            let base = i * 4;
            match weight {
                VertexWeight::Bdef1 { bone } => {
                    self.bone_indices[base] = *bone;
                    self.bone_weights[base] = 1.0;
                }
                VertexWeight::Bdef2 { bones, weight } => {
                    self.bone_indices[base] = bones[0];
                    self.bone_indices[base + 1] = bones[1];
                    self.bone_weights[base] = *weight;
                    self.bone_weights[base + 1] = 1.0 - *weight;
                }
                VertexWeight::Bdef4 { bones, weights } => {
                    for j in 0..4 {
                        self.bone_indices[base + j] = bones[j];
                        self.bone_weights[base + j] = weights[j];
                    }
                }
                VertexWeight::Sdef { bones, weight, .. } => {
                    // SDEF 退化为 BDEF2
                    self.bone_indices[base] = bones[0];
                    self.bone_indices[base + 1] = bones[1];
                    self.bone_weights[base] = *weight;
                    self.bone_weights[base + 1] = 1.0 - *weight;
                }
                VertexWeight::Qdef { bones, weights } => {
                    for j in 0..4 {
                        self.bone_indices[base + j] = bones[j];
                        self.bone_weights[base + j] = weights[j];
                    }
                }
            }
        }

        // 初始化原始顶点数据（未蒙皮）
        self.original_positions = Vec::with_capacity(vertex_count * 3);
        self.original_normals = Vec::with_capacity(vertex_count * 3);

        for vertex in &self.vertices {
            self.original_positions.push(vertex.position.x);
            self.original_positions.push(vertex.position.y);
            self.original_positions.push(vertex.position.z);
            self.original_normals.push(vertex.normal.x);
            self.original_normals.push(vertex.normal.y);
            self.original_normals.push(vertex.normal.z);
        }

        // 调试：检查骨骼索引范围和权重
        let bone_count = self.bone_manager.bone_count();
        let mut max_bone_idx = -1i32;
        let mut invalid_idx_count = 0usize;
        let mut zero_weight_count = 0usize;

        for i in 0..vertex_count {
            let base = i * 4;
            let mut total_weight = 0.0f32;
            let mut valid_bones = 0;

            for j in 0..4 {
                let idx = self.bone_indices[base + j];
                let weight = self.bone_weights[base + j];

                if idx > max_bone_idx {
                    max_bone_idx = idx;
                }
                if idx >= 0 && idx < bone_count as i32 {
                    valid_bones += 1;
                    total_weight += weight;
                } else if idx >= bone_count as i32 {
                    invalid_idx_count += 1;
                }
            }

            if valid_bones > 0 && total_weight < 0.001 {
                zero_weight_count += 1;
            }
        }

        if invalid_idx_count > 0 {
            log::warn!(
                "GPU 蒙皮: 发现 {} 个无效骨骼索引 (>= {})",
                invalid_idx_count,
                bone_count
            );
        }
        if zero_weight_count > 0 {
            log::warn!("GPU 蒙皮: 发现 {} 个顶点权重为0", zero_weight_count);
        }

        log::info!(
            "GPU 蒙皮数据初始化完成: {} 顶点, {} 骨骼, 最大骨骼索引: {}",
            vertex_count,
            bone_count,
            max_bone_idx
        );
    }

    /// 获取骨骼索引数据指针
    pub fn get_bone_indices_ptr(&self) -> *const i32 {
        self.bone_indices.as_ptr()
    }

    /// 获取骨骼索引数据引用
    pub fn get_bone_indices(&self) -> &[i32] {
        &self.bone_indices
    }

    /// 获取骨骼权重数据指针
    pub fn get_bone_weights_ptr(&self) -> *const f32 {
        self.bone_weights.as_ptr()
    }

    /// 获取骨骼权重数据引用
    pub fn get_bone_weights(&self) -> &[f32] {
        &self.bone_weights
    }

    /// 获取物理系统动态骨骼数量
    pub fn get_dynamic_bone_count(&self) -> usize {
        if let Some(ref physics) = self.physics {
            physics.get_dynamic_bone_indices().len()
        } else {
            0
        }
    }

    /// 获取原始顶点位置数据指针
    pub fn get_original_positions_ptr(&self) -> *const f32 {
        self.original_positions.as_ptr()
    }

    /// 获取原始法线数据指针
    pub fn get_original_normals_ptr(&self) -> *const f32 {
        self.original_normals.as_ptr()
    }

    // ========== GPU Morph ==========

    /// 初始化 GPU 顶点 Morph 数据（稀疏→密集格式）
    pub fn init_gpu_morph_data(&mut self) {
        if self.gpu_morph_initialized {
            return;
        }

        let vertex_count = self.vertices.len();

        // 收集所有顶点类型的 Morph 索引
        self.vertex_morph_indices = (0..self.morph_manager.morph_count())
            .filter_map(|i| {
                let morph = self.morph_manager.get_morph(i)?;
                if morph.morph_type == crate::morph::MorphType::Vertex
                    && !morph.vertex_offsets.is_empty()
                {
                    Some(i)
                } else {
                    None
                }
            })
            .collect();

        self.vertex_morph_count = self.vertex_morph_indices.len();

        if self.vertex_morph_count == 0 {
            log::info!("模型没有顶点 Morph，跳过 GPU Morph 初始化");
            self.gpu_morph_initialized = true;
            return;
        }

        // 分配密集格式的偏移数据：morph_count * vertex_count * 3 (xyz)
        let total_floats = self.vertex_morph_count * vertex_count * 3;
        self.gpu_morph_offsets = vec![0.0f32; total_floats];
        self.gpu_morph_weights = vec![0.0f32; self.vertex_morph_count];

        // 填充稀疏数据到密集格式
        for (morph_idx, &global_morph_idx) in self.vertex_morph_indices.iter().enumerate() {
            if let Some(morph) = self.morph_manager.get_morph(global_morph_idx) {
                let base_offset = morph_idx * vertex_count * 3;
                for offset in &morph.vertex_offsets {
                    let vid = offset.vertex_index as usize;
                    if vid < vertex_count {
                        let idx = base_offset + vid * 3;
                        self.gpu_morph_offsets[idx] = offset.offset.x;
                        self.gpu_morph_offsets[idx + 1] = offset.offset.y;
                        self.gpu_morph_offsets[idx + 2] = offset.offset.z;
                    }
                }
            }
        }

        self.gpu_morph_initialized = true;
        log::info!(
            "GPU Morph 数据初始化完成: {} 个顶点 Morph, 数据大小 {:.2} MB",
            self.vertex_morph_count,
            (total_floats * 4) as f64 / 1024.0 / 1024.0
        );
    }

    /// 计算并缓存所有 Morph 的有效权重（递归展开 Group/Flip）
    pub(super) fn compute_and_cache_effective_weights(&mut self) {
        let morph_count = self.morph_manager.morph_count();
        if morph_count == 0 {
            return;
        }
        self.effective_weights_buf.resize(morph_count, 0.0);
        for w in self.effective_weights_buf.iter_mut() {
            *w = 0.0;
        }
        self.morph_manager
            .compute_effective_weights_into(&mut self.effective_weights_buf);
    }

    /// 同步 GPU Morph 权重（公共接口，供 JNI 调用）
    pub fn sync_gpu_morph_weights(&mut self) {
        self.compute_and_cache_effective_weights();
        self.sync_gpu_morph_weights_from_cache();
        self.sync_gpu_uv_morph_weights_from_cache();
    }

    /// 同步 GPU 顶点 Morph 有效权重（从已缓存的有效权重读取）
    pub(super) fn sync_gpu_morph_weights_from_cache(&mut self) {
        if !self.gpu_morph_initialized || self.vertex_morph_count == 0 {
            return;
        }
        for (gpu_idx, &morph_idx) in self.vertex_morph_indices.iter().enumerate() {
            if gpu_idx < self.gpu_morph_weights.len()
                && morph_idx < self.effective_weights_buf.len()
            {
                self.gpu_morph_weights[gpu_idx] = self.effective_weights_buf[morph_idx];
            }
        }
    }

    pub fn get_vertex_morph_count(&self) -> usize {
        self.vertex_morph_count
    }

    pub fn get_gpu_morph_offsets_ptr(&self) -> *const f32 {
        self.gpu_morph_offsets.as_ptr()
    }

    pub fn get_gpu_morph_offsets_size(&self) -> usize {
        self.gpu_morph_offsets.len() * 4
    }

    pub fn get_gpu_morph_weights_ptr(&self) -> *const f32 {
        self.gpu_morph_weights.as_ptr()
    }

    pub fn is_gpu_morph_initialized(&self) -> bool {
        self.gpu_morph_initialized
    }

    // ========== GPU UV Morph ==========

    /// 初始化 GPU UV Morph 数据（稀疏→密集格式）
    pub fn init_gpu_uv_morph_data(&mut self) {
        if self.gpu_uv_morph_initialized {
            return;
        }

        let vertex_count = self.vertices.len();

        // 收集所有 UV 类型的 Morph 索引
        self.uv_morph_indices = (0..self.morph_manager.morph_count())
            .filter_map(|i| {
                let morph = self.morph_manager.get_morph(i)?;
                if (morph.morph_type == crate::morph::MorphType::Uv
                    || morph.morph_type == crate::morph::MorphType::AdditionalUv1)
                    && !morph.uv_offsets.is_empty()
                {
                    Some(i)
                } else {
                    None
                }
            })
            .collect();

        self.uv_morph_count = self.uv_morph_indices.len();

        if self.uv_morph_count == 0 {
            log::info!("模型没有 UV Morph，跳过 GPU UV Morph 初始化");
            self.gpu_uv_morph_initialized = true;
            return;
        }

        // 分配密集格式的偏移数据：uv_morph_count * vertex_count * 2 (uv)
        let total_floats = self.uv_morph_count * vertex_count * 2;
        self.gpu_uv_morph_offsets = vec![0.0f32; total_floats];
        self.gpu_uv_morph_weights = vec![0.0f32; self.uv_morph_count];

        // 填充稀疏数据到密集格式
        for (morph_idx, &global_morph_idx) in self.uv_morph_indices.iter().enumerate() {
            if let Some(morph) = self.morph_manager.get_morph(global_morph_idx) {
                let base_offset = morph_idx * vertex_count * 2;
                for offset in &morph.uv_offsets {
                    let vid = offset.vertex_index as usize;
                    if vid < vertex_count {
                        let idx = base_offset + vid * 2;
                        self.gpu_uv_morph_offsets[idx] = offset.offset.x;
                        self.gpu_uv_morph_offsets[idx + 1] = offset.offset.y;
                    }
                }
            }
        }

        self.gpu_uv_morph_initialized = true;
        log::info!(
            "GPU UV Morph 数据初始化完成: {} 个 UV Morph, 数据大小 {:.2} KB",
            self.uv_morph_count,
            (total_floats * 4) as f64 / 1024.0
        );
    }

    /// 同步 GPU UV Morph 有效权重（从已缓存的有效权重读取）
    pub(super) fn sync_gpu_uv_morph_weights_from_cache(&mut self) {
        if !self.gpu_uv_morph_initialized || self.uv_morph_count == 0 {
            return;
        }
        for (gpu_idx, &morph_idx) in self.uv_morph_indices.iter().enumerate() {
            if gpu_idx < self.gpu_uv_morph_weights.len()
                && morph_idx < self.effective_weights_buf.len()
            {
                self.gpu_uv_morph_weights[gpu_idx] = self.effective_weights_buf[morph_idx];
            }
        }
    }

    /// 获取 UV Morph 数量
    pub fn get_uv_morph_count(&self) -> usize {
        self.uv_morph_count
    }

    /// 获取 GPU UV Morph 偏移数据指针
    pub fn get_gpu_uv_morph_offsets_ptr(&self) -> *const f32 {
        self.gpu_uv_morph_offsets.as_ptr()
    }

    /// 获取 GPU UV Morph 偏移数据大小（字节）
    pub fn get_gpu_uv_morph_offsets_size(&self) -> usize {
        self.gpu_uv_morph_offsets.len() * 4
    }

    /// 获取 GPU UV Morph 权重数据指针
    pub fn get_gpu_uv_morph_weights_ptr(&self) -> *const f32 {
        self.gpu_uv_morph_weights.as_ptr()
    }

    /// GPU UV Morph 是否已初始化
    pub fn is_gpu_uv_morph_initialized(&self) -> bool {
        self.gpu_uv_morph_initialized
    }

    // ========== 材质 Morph 结果访问 ==========

    /// 获取材质 Morph 结果数量
    pub fn get_material_morph_result_count(&self) -> usize {
        self.morph_manager.get_material_morph_results().len()
    }

    /// 获取材质 Morph 结果展平数据（每材质 56 个 float）
    /// 布局：mul[diffuse(4) + specular(3) + specular_strength(1) +
    ///        ambient(3) + edge_color(4) + edge_size(1) + texture_tint(4) +
    ///        environment_tint(4) + toon_tint(4)] = 28 floats
    ///     + add[同上布局] = 28 floats
    /// 渲染时：final = base * mul + add
    pub fn get_material_morph_results_flat(&mut self) -> &[f32] {
        let results = self.morph_manager.get_material_morph_results();
        let expected_len = results.len() * 56;
        self.material_morph_results_flat_cache.clear();
        self.material_morph_results_flat_cache.reserve(expected_len);
        for r in results {
            // 乘算部分 (28 floats)
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.mul.diffuse.x,
                r.mul.diffuse.y,
                r.mul.diffuse.z,
                r.mul.diffuse.w,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.mul.specular.x,
                r.mul.specular.y,
                r.mul.specular.z,
            ]);
            self.material_morph_results_flat_cache
                .push(r.mul.specular_strength);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.mul.ambient.x,
                r.mul.ambient.y,
                r.mul.ambient.z,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.mul.edge_color.x,
                r.mul.edge_color.y,
                r.mul.edge_color.z,
                r.mul.edge_color.w,
            ]);
            self.material_morph_results_flat_cache.push(r.mul.edge_size);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.mul.texture_tint.x,
                r.mul.texture_tint.y,
                r.mul.texture_tint.z,
                r.mul.texture_tint.w,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.mul.environment_tint.x,
                r.mul.environment_tint.y,
                r.mul.environment_tint.z,
                r.mul.environment_tint.w,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.mul.toon_tint.x,
                r.mul.toon_tint.y,
                r.mul.toon_tint.z,
                r.mul.toon_tint.w,
            ]);
            // 加算部分 (28 floats)
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.add.diffuse.x,
                r.add.diffuse.y,
                r.add.diffuse.z,
                r.add.diffuse.w,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.add.specular.x,
                r.add.specular.y,
                r.add.specular.z,
            ]);
            self.material_morph_results_flat_cache
                .push(r.add.specular_strength);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.add.ambient.x,
                r.add.ambient.y,
                r.add.ambient.z,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.add.edge_color.x,
                r.add.edge_color.y,
                r.add.edge_color.z,
                r.add.edge_color.w,
            ]);
            self.material_morph_results_flat_cache.push(r.add.edge_size);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.add.texture_tint.x,
                r.add.texture_tint.y,
                r.add.texture_tint.z,
                r.add.texture_tint.w,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.add.environment_tint.x,
                r.add.environment_tint.y,
                r.add.environment_tint.z,
                r.add.environment_tint.w,
            ]);
            self.material_morph_results_flat_cache.extend_from_slice(&[
                r.add.toon_tint.x,
                r.add.toon_tint.y,
                r.add.toon_tint.z,
                r.add.toon_tint.w,
            ]);
        }
        &self.material_morph_results_flat_cache
    }
}
