use super::*;

type SkinningItem<'a> = (
    (
        (
            ((&'a mut glam::Vec3, &'a mut glam::Vec3), &'a mut [f32]),
            &'a mut [f32],
        ),
        &'a RuntimeVertex,
    ),
    &'a VertexWeight,
);

impl MmdModel {
    /// 初始化动画状态
    pub fn initialize_animation(&mut self) {
        self.bone_manager.reset_all_transforms();
        self.morph_manager.reset_all_weights();
    }

    /// 开始动画帧
    pub fn begin_animation(&mut self) {
        self.bone_manager.begin_update();
    }

    /// 结束动画帧
    pub fn end_animation(&mut self) {
        self.bone_manager.end_update();
    }

    /// 更新 Morph 动画
    pub fn update_morph_animation(&mut self) {
        // 先将 update_positions 重置为原始顶点位置（因为 apply_morphs 是累加操作）
        for (i, vertex) in self.vertices.iter().enumerate() {
            if i < self.update_positions.len() {
                self.update_positions[i] = vertex.position;
            }
        }
        // 应用所有 Morph 变形（顶点/骨骼/材质/UV/Group）
        self.morph_manager
            .apply_morphs(&mut self.bone_manager, &mut self.update_positions);

        // 将 UV Morph 偏移应用到 UV 缓冲区
        let uv_deltas = self.morph_manager.get_uv_morph_deltas();
        if !uv_deltas.is_empty() {
            for (i, vertex) in self.vertices.iter().enumerate() {
                if i < self.update_uvs.len() && i < uv_deltas.len() {
                    self.update_uvs[i] = vertex.uv + uv_deltas[i];
                }
            }
        }
    }

    /// 更新骨骼动画（物理前/后）
    pub fn update_node_animation(&mut self, after_physics: bool) {
        self.bone_manager.update_transforms(after_physics);
    }

    /// 更新顶点（蒙皮计算）- 使用 rayon 并行加速
    pub fn update(&mut self) {
        let bone_matrices = self.bone_manager.get_skinning_matrices();
        let vertex_count = self.vertices.len();
        let budget = crate::evaluation_parallelism::current_policy().thread_budget;
        let raw_len = vertex_count * 3;

        if self.update_positions_raw.len() != raw_len {
            self.update_positions_raw.resize(raw_len, 0.0);
        }
        if self.update_normals_raw.len() != raw_len {
            self.update_normals_raw.resize(raw_len, 0.0);
        }
        if self.update_uvs_raw.len() != self.update_uvs.len() * 2 {
            self.update_uvs_raw.resize(self.update_uvs.len() * 2, 0.0);
        }

        let sample_parallelism = crate::evaluation_profile::enabled();
        let parallel_sample = if vertex_count >= 2048 {
            crate::evaluation_parallelism::with_pool_for_budget(budget, || {
                self.update_uvs_raw
                    .par_chunks_mut(2)
                    .zip(self.update_uvs.par_iter())
                    .for_each(|(chunk, uv)| {
                        chunk[0] = uv.x;
                        chunk[1] = uv.y;
                    });
                let sampled = sample_parallelism;
                let metrics = sampled.then(|| {
                    (
                        std::sync::atomic::AtomicUsize::new(0),
                        std::sync::atomic::AtomicUsize::new(0),
                        std::sync::atomic::AtomicUsize::new(0),
                    )
                });
                let vertices = &self.vertices;
                let weights = &self.weights;
                let matrices = &bone_matrices;
                let output = self
                    .update_positions
                    .par_iter_mut()
                    .zip(self.update_normals.par_iter_mut())
                    .zip(self.update_positions_raw.par_chunks_mut(3))
                    .zip(self.update_normals_raw.par_chunks_mut(3))
                    .zip(vertices.par_iter())
                    .zip(weights.par_iter());
                let apply = |item: SkinningItem<'_>| {
                    let (((((pos_out, norm_out), pos_chunk), norm_chunk), vertex), weight) = item;
                    let (pos, norm) =
                        compute_vertex_skinning(*pos_out, vertex.normal, weight, matrices);
                    *pos_out = pos;
                    *norm_out = norm;
                    pos_chunk.copy_from_slice(&pos.to_array());
                    norm_chunk.copy_from_slice(&norm.to_array());
                };
                if sampled {
                    use std::sync::atomic::Ordering;
                    let (tasks, active, peak) = metrics.as_ref().unwrap();
                    output.for_each_init(
                        || {
                            tasks.fetch_add(1, Ordering::Relaxed);
                            let now = active.fetch_add(1, Ordering::Relaxed) + 1;
                            peak.fetch_max(now, Ordering::Relaxed);
                            MatrixWorkerGuard(active)
                        },
                        |_, value| apply(value),
                    );
                    let (tasks, _, peak) = metrics.as_ref().unwrap();
                    Some((
                        tasks.load(std::sync::atomic::Ordering::Relaxed),
                        peak.load(std::sync::atomic::Ordering::Relaxed),
                    ))
                } else {
                    output.for_each(apply);
                    None
                }
            })
        } else {
            None
        };
        if let Some(Some((tasks, peak))) = parallel_sample {
            crate::evaluation_profile::matrix_parallel(
                crate::evaluation_parallelism::bounded_thread_budget(budget),
                tasks,
                peak,
            );
        }
        if parallel_sample.is_none() {
            for (chunk, uv) in self
                .update_uvs_raw
                .chunks_exact_mut(2)
                .zip(&self.update_uvs)
            {
                chunk[0] = uv.x;
                chunk[1] = uv.y;
            }
            for index in 0..vertex_count {
                let vertex = &self.vertices[index];
                let weight = &self.weights[index];
                let (pos, norm) = compute_vertex_skinning(
                    self.update_positions[index],
                    vertex.normal,
                    weight,
                    &bone_matrices,
                );
                self.update_positions[index] = pos;
                self.update_normals[index] = norm;
                self.update_positions_raw[index * 3..index * 3 + 3]
                    .copy_from_slice(&pos.to_array());
                self.update_normals_raw[index * 3..index * 3 + 3].copy_from_slice(&norm.to_array());
            }
        }

        // 调试日志（只在首次执行）
        if !self.debug_logged {
            self.debug_logged = true;
            log::info!(
                "MMD Debug: vertex_count={}, pos_raw_len={}, uv_raw_len={} (rayon并行蒙皮)",
                vertex_count,
                self.update_positions_raw.len(),
                self.update_uvs_raw.len(),
            );
        }
    }

    /// 完整动画更新流程
    pub fn update_all_animation(&mut self, vmd: Option<&VmdAnimation>, frame: f32, _elapsed: f32) {
        self.begin_animation();

        if let Some(animation) = vmd {
            animation.evaluate(frame, &mut self.bone_manager, &mut self.morph_manager);
        }

        // 应用头部旋转
        self.apply_head_rotation();

        self.update_morph_animation();
        self.update_node_animation(false);
        self.update_node_animation(true);

        self.end_animation();
        self.update();
    }

    /// 设置指定层的动画（新版多动画层接口）
    pub fn set_layer_animation(&mut self, layer_id: usize, animation: Option<Arc<VmdAnimation>>) {
        // 如果这是第一个层且设置了动画，先评估第一帧
        // 避免骨骼瞬间回到 T-pose 导致物理系统异常
        if layer_id == 0 {
            if let Some(ref anim) = animation {
                self.initialize_animation();
                anim.evaluate(0.0, &mut self.bone_manager, &mut self.morph_manager);
                self.begin_animation();
                self.update_node_animation(false);
                self.update_node_animation(true);
                self.end_animation();
            }
        }

        self.animation_layer_manager
            .set_layer_animation(layer_id, animation);
    }

    /// 播放指定层的动画
    pub fn play_layer(&mut self, layer_id: usize) {
        self.animation_layer_manager.play_layer(layer_id);
    }

    /// 停止指定层的动画
    pub fn stop_layer(&mut self, layer_id: usize) {
        self.animation_layer_manager.stop_layer(layer_id);
    }

    /// 暂停指定层的动画
    pub fn pause_layer(&mut self, layer_id: usize) {
        self.animation_layer_manager.pause_layer(layer_id);
    }

    /// 恢复指定层的动画
    pub fn resume_layer(&mut self, layer_id: usize) {
        self.animation_layer_manager.resume_layer(layer_id);
    }

    /// 设置层权重
    pub fn set_layer_weight(&mut self, layer_id: usize, weight: f32) {
        self.animation_layer_manager
            .set_layer_weight(layer_id, weight);
    }

    /// 设置层播放速度
    pub fn set_layer_speed(&mut self, layer_id: usize, speed: f32) {
        self.animation_layer_manager
            .set_layer_speed(layer_id, speed);
    }

    /// 设置层是否循环播放
    pub fn set_layer_loop(&mut self, layer_id: usize, loop_play: bool) {
        self.animation_layer_manager
            .set_layer_loop(layer_id, loop_play);
    }

    /// 跳转到指定帧
    pub fn seek_layer(&mut self, layer_id: usize, frame: f32) {
        self.animation_layer_manager.seek_layer(layer_id, frame);
    }

    /// 设置层淡入淡出时间
    pub fn set_layer_fade_times(&mut self, layer_id: usize, fade_in: f32, fade_out: f32) {
        self.animation_layer_manager
            .set_layer_fade_times(layer_id, fade_in, fade_out);
    }

    /// 带过渡地切换层动画，物理和蒙皮共用插值后的动画输入。
    ///
    /// 从当前骨骼姿态平滑过渡到新动画的第一帧，避免动作切换时的突兀感。
    ///
    /// # 参数
    /// - `layer_id`: 层 ID
    /// - `animation`: 新动画
    /// - `transition_time`: 过渡时间（秒），推荐 0.2 ~ 0.5 秒
    pub fn transition_layer_to(
        &mut self,
        layer_id: usize,
        animation: Option<Arc<VmdAnimation>>,
        transition_time: f32,
    ) {
        if transition_time.is_finite() && transition_time > 0.0 {
            self.transition_pose.clone_from(&self.animation_input_pose);
            self.transition_duration = transition_time;
            self.transition_progress = 0.0;
            self.is_transitioning = !self.transition_pose.is_empty();
        } else {
            self.is_transitioning = false;
            self.transition_pose.clear();
        }

        self.animation_layer_manager
            .set_layer_animation(layer_id, animation);
        self.animation_layer_manager.play_layer(layer_id);
    }

    /// 获取指定层的最大帧数
    pub fn get_layer_max_frame(&self, layer_id: usize) -> u32 {
        self.animation_layer_manager
            .get_layer(layer_id)
            .map(|l| l.max_frame())
            .unwrap_or(0)
    }

    /// 检测层动画是否播放完毕
    pub fn is_layer_finished(&self, layer_id: usize) -> bool {
        self.animation_layer_manager.is_layer_finished(layer_id)
    }

    /// 按根骨骼名设置层的骨骼遮罩（仅影响该骨骼及其子孙）
    pub fn set_layer_bone_mask_by_name(
        &mut self,
        layer_id: usize,
        root_bone_name: Option<&str>,
    ) -> bool {
        let mask = match root_bone_name {
            Some(name) => {
                if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                    Some(self.bone_manager.collect_descendants(idx))
                } else {
                    return false;
                }
            }
            None => None,
        };
        if let Some(layer) = self.animation_layer_manager.get_layer_mut(layer_id) {
            layer.set_bone_mask(mask);
            true
        } else {
            false
        }
    }

    /// 按根骨骼名设置层的骨骼排除集（该骨骼及其子孙不受动画影响）
    pub fn set_layer_bone_exclude_by_name(
        &mut self,
        layer_id: usize,
        root_bone_name: Option<&str>,
    ) -> bool {
        let exclude = match root_bone_name {
            Some(name) => {
                if let Some(idx) = self.bone_manager.find_bone_by_name(name) {
                    Some(self.bone_manager.collect_descendants(idx))
                } else {
                    return false;
                }
            }
            None => None,
        };
        if let Some(layer) = self.animation_layer_manager.get_layer_mut(layer_id) {
            layer.set_bone_exclude(exclude);
            true
        } else {
            false
        }
    }

    /// 获取所有活跃层中的最大帧数
    pub fn get_max_frame(&self) -> u32 {
        (0..self.animation_layer_manager.layer_count())
            .map(|i| self.get_layer_max_frame(i))
            .max()
            .unwrap_or(0)
    }

    /// 更新动画（每帧调用）- 多动画层版本（CPU蒙皮模式）
    #[allow(unreachable_code)]
    pub fn tick_animation(&mut self, elapsed: f32) {
        self.tick_animation_internal(elapsed, true, None);
        return;
        // 更新所有动画层
        self.animation_layer_manager.update(elapsed);

        // 执行动画更新
        self.begin_animation();

        // 评估所有层并混合结果
        self.animation_layer_manager
            .evaluate_normalized(&mut self.bone_manager, &mut self.morph_manager);
        self.apply_transition_blend(elapsed);

        // 应用 VPD 骨骼姿势覆盖（在动画评估后）
        self.apply_vpd_bone_overrides();

        // 自动眨眼
        self.update_auto_blink(elapsed);

        // VR 模式下跳过普通头部旋转，由 VR IK 接管
        if !self.vr_enabled {
            self.apply_head_rotation();
        }
        self.update_morph_animation();

        // 骨骼更新（物理前）— 先计算当前帧全局变换
        self.update_node_animation(false);

        // VR IK 求解（在全局变换计算之后，确保用当前帧的骨骼位置）
        if self.vr_enabled {
            let strength = self.vr_ik_strength;
            if let Some(frame) = self.vr_tracking_frame {
                self.vr_debug_state = self.vr_ik_solver.solve_tracking_frame(
                    &mut self.bone_manager,
                    &frame,
                    strength,
                );
            } else {
                let tracking = self.vr_tracking_data;
                self.vr_ik_solver
                    .solve(&mut self.bone_manager, &tracking, strength);
                self.vr_debug_state = VrDebugState::default();
            }
        }

        // 物理更新
        self.update_physics(elapsed);

        // 骨骼更新（物理后）
        self.update_node_animation(true);

        // 清除物理骨骼保护（允许下一帧重新计算）
        self.end_physics_update();

        self.end_animation();

        self.update();
    }

    /// 在物理前混合局部动画输入，不能混入上一帧的动态刚体结果。
    fn apply_transition_blend(&mut self, elapsed: f32) {
        if self.is_transitioning {
            self.transition_progress =
                (self.transition_progress + elapsed.max(0.0) / self.transition_duration).min(1.0);
            let t = self.transition_progress;
            let smooth_t = t * t * (3.0 - 2.0 * t);
            for (i, start) in self.transition_pose.iter().enumerate() {
                if let Some(bone) = self.bone_manager.get_bone_mut(i) {
                    bone.animation_translate = start.translation.lerp(bone.animation_translate, smooth_t);
                    bone.animation_rotate = start.rotation.slerp(bone.animation_rotate, smooth_t);
                }
            }
            if t >= 1.0 {
                self.is_transitioning = false;
                self.transition_pose.clear();
            }
        }

        self.animation_input_pose.resize(self.bone_manager.bone_count(), BonePose::default());
        for (i, pose) in self.animation_input_pose.iter_mut().enumerate() {
            if let Some(bone) = self.bone_manager.get_bone(i) {
                pose.translation = bone.animation_translate;
                pose.rotation = bone.animation_rotate;
            }
        }
    }

    /// 设置头部角度
    // ========== VPD 骨骼姿势覆盖 ==========

    /// 设置 VPD 骨骼姿势覆盖
    pub fn set_vpd_bone_override(&mut self, bone_index: usize, translation: Vec3, rotation: Quat) {
        self.vpd_bone_overrides
            .insert(bone_index, (translation, rotation));
    }

    pub fn set_external_bone_override(&mut self, bone_index: usize, translation: Vec3, rotation: Quat) {
        self.external_bone_overrides.insert(bone_index, (translation, rotation));
    }

    pub fn clear_external_bone_overrides(&mut self) {
        self.external_bone_overrides.clear();
    }

    pub fn clear_vpd_morphs(&mut self) {
        for index in self.vpd_morph_indices.drain(..) {
            self.morph_manager.set_morph_weight(index, 0.0);
        }
    }

    pub fn track_vpd_morph(&mut self, index: usize) { self.vpd_morph_indices.push(index); }

    /// 清除所有 VPD 骨骼姿势覆盖
    pub fn clear_vpd_bone_overrides(&mut self) {
        self.vpd_bone_overrides.clear();
    }

    /// 应用 VPD 骨骼姿势覆盖到 BoneManager
    fn apply_vpd_bone_overrides(&mut self) {
        for (&bone_index, &(translation, rotation)) in self.vpd_bone_overrides.iter().chain(self.external_bone_overrides.iter()) {
            let t = self.bone_manager.convert_vmd_translation(translation);
            let r = self.bone_manager.convert_vmd_rotation(rotation);
            self.bone_manager.set_bone_translation(bone_index, t);
            self.bone_manager.set_bone_rotation(bone_index, r);
        }
    }

    pub fn set_external_ik_override(&mut self, ik_name: String, enabled: bool) {
        if ik_name.is_empty() {
            return;
        }
        self.external_ik_overrides.insert(ik_name, enabled);
    }

    pub fn clear_external_ik_overrides(&mut self) {
        self.external_ik_overrides.clear();
    }

    fn apply_external_ik_overrides(&mut self) {
        for (ik_name, enabled) in &self.external_ik_overrides {
            self.bone_manager.set_ik_enabled_by_name(ik_name, *enabled);
        }
    }

    /// 仅更新动画（不执行 CPU 蒙皮，用于 GPU 蒙皮模式）
    #[allow(unreachable_code)]
    pub fn tick_animation_no_skinning(&mut self, elapsed: f32) {
        self.tick_animation_internal(elapsed, false, None);
        return;
        self.animation_layer_manager.update(elapsed);
        self.begin_animation();

        self.animation_layer_manager
            .evaluate_normalized(&mut self.bone_manager, &mut self.morph_manager);
        self.apply_transition_blend(elapsed);

        // 应用 VPD 骨骼姿势覆盖（在动画评估后）
        self.apply_vpd_bone_overrides();

        // 自动眨眼
        self.update_auto_blink(elapsed);

        // VR 模式下跳过普通头部旋转，由 VR IK 接管
        if !self.vr_enabled {
            self.apply_head_rotation();
        }
        self.update_morph_animation();

        // 一次性计算所有 Morph 有效权重，供顶点和 UV Morph 同步使用
        self.compute_and_cache_effective_weights();
        self.sync_gpu_morph_weights_from_cache();
        self.sync_gpu_uv_morph_weights_from_cache();

        // 骨骼更新（物理前）— 先计算当前帧全局变换
        self.update_node_animation(false);

        // VR IK 求解（在全局变换计算之后，确保用当前帧的骨骼位置）
        if self.vr_enabled {
            let strength = self.vr_ik_strength;
            if let Some(frame) = self.vr_tracking_frame {
                self.vr_debug_state = self.vr_ik_solver.solve_tracking_frame(
                    &mut self.bone_manager,
                    &frame,
                    strength,
                );
            } else {
                let tracking = self.vr_tracking_data;
                self.vr_ik_solver
                    .solve(&mut self.bone_manager, &tracking, strength);
                self.vr_debug_state = VrDebugState::default();
            }
        } else {
            self.vr_debug_state = VrDebugState::default();
        }

        // 记录物理更新前的动态骨骼数量
        let physics_enabled = self.physics_enabled && self.physics.is_some();

        self.update_physics(elapsed);
        self.update_node_animation(true);
        self.end_physics_update();
        self.end_animation();

        // 调试日志（仅首次）
        if !self.debug_logged && physics_enabled {
            self.debug_logged = true;
            if let Some(ref physics) = self.physics {
                let dynamic_count = physics.get_dynamic_bone_indices().len();
                log::info!("GPU蒙皮物理调试: 物理已启用, {} 个动态骨骼", dynamic_count);
            }
        }
        // 注意：不调用 self.update()，跳过 CPU 蒙皮
    }

    // ========== 物理系统方法 ==========

    /// 初始化物理系统（Bullet3）
    /// 更新动画并消费一次性 TaCZ 双臂目标；目标不会保存到模型状态中。
    pub fn tick_animation_with_tacz_targets(
        &mut self,
        elapsed: f32,
        cpu_skinning: bool,
        targets: Option<TaczArmTargets>,
    ) -> TaczArmApplyOutcome {
        self.tick_animation_internal(elapsed, cpu_skinning, targets)
    }

    /// 按次接收内部并行预算，不修改全局执行策略。
    pub(crate) fn tick_animation_with_policy(
        &mut self,
        elapsed: f32,
        cpu_skinning: bool,
        targets: Option<TaczArmTargets>,
        policy: crate::evaluation_parallelism::EvaluationPolicy,
    ) -> TaczArmApplyOutcome {
        crate::evaluation_parallelism::with_policy(policy, || {
            self.tick_animation_internal(elapsed, cpu_skinning, targets)
        })
    }

    fn tick_animation_internal(
        &mut self,
        elapsed: f32,
        cpu_skinning: bool,
        targets: Option<TaczArmTargets>,
    ) -> TaczArmApplyOutcome {
        let animation_timer = crate::evaluation_profile::timer();
        self.with_vrm_runtime_state(|model, runtime_state| {
            runtime_state.apply_inputs(model);
        });

        self.animation_layer_manager.update(elapsed);
        self.begin_animation();
        self.bone_manager.reset_all_ik_enabled();

        self.animation_layer_manager
            .evaluate_normalized(&mut self.bone_manager, &mut self.morph_manager);

        self.apply_transition_blend(elapsed);
        self.apply_vpd_bone_overrides();
        self.apply_external_ik_overrides();
        self.update_auto_blink(elapsed);

        if !self.vr_enabled {
            self.apply_head_rotation();
        }

        self.with_vrm_runtime_state(|model, runtime_state| {
            runtime_state.process_expressions(model);
        });

        self.update_morph_animation();

        if !cpu_skinning {
            self.compute_and_cache_effective_weights();
            self.sync_gpu_morph_weights_from_cache();
            self.sync_gpu_uv_morph_weights_from_cache();
        }

        crate::evaluation_profile::stage(animation_timer, 0);
        let ik_timer = crate::evaluation_profile::timer();
        self.update_node_animation(false);

        if self.vr_enabled {
            let strength = self.vr_ik_strength;
            if let Some(frame) = self.vr_tracking_frame {
                self.vr_debug_state = self.vr_ik_solver.solve_tracking_frame(
                    &mut self.bone_manager,
                    &frame,
                    strength,
                );
            } else {
                let tracking = self.vr_tracking_data;
                self.vr_ik_solver
                    .solve(&mut self.bone_manager, &tracking, strength);
                self.vr_debug_state = VrDebugState::default();
            }
        } else {
            self.vr_debug_state = VrDebugState::default();
        }

        self.with_vrm_runtime_state(|model, runtime_state| {
            runtime_state.process_post_ik(model, elapsed);
        });

        crate::evaluation_profile::stage(ik_timer, 1);
        let physics_timer = crate::evaluation_profile::timer();
        let physics_enabled = !self.is_vrm && self.physics_enabled && self.physics.is_some();
        if physics_enabled {
            self.update_physics(elapsed);
            if crate::evaluation_profile::has_failed() {
                return TaczArmApplyOutcome::default();
            }
            self.update_node_animation(true);
            self.end_physics_update();
        }

        crate::evaluation_profile::stage(physics_timer, 2);
        let finishing_timer = crate::evaluation_profile::timer();
        // 第一人称 TaCZ 真实锚点必须晚于物理与 VR 分支，避免手臂在同帧被再次覆盖。
        // 第三人称不提交骨骼目标，完整保留本帧 VMD 姿态。
        let mut tacz_outcome = TaczArmApplyOutcome::default();
        if !self.vr_enabled {
            if let Some(targets) = targets {
                tacz_outcome = apply_tacz_arm_targets(
                    &mut self.bone_manager,
                    &mut self.tacz_arm_solver_cache,
                    targets,
                );
            }
        }

        self.end_animation();

        self.with_vrm_runtime_state(|model, runtime_state| {
            runtime_state.refresh_output(model);
        });

        crate::evaluation_profile::stage(finishing_timer, 0);
        if cpu_skinning {
            let skinning_timer = crate::evaluation_profile::timer();
            self.update();
            crate::evaluation_profile::stage(skinning_timer, 3);
        } else if !self.debug_logged && physics_enabled {
            self.debug_logged = true;
            if let Some(ref physics) = self.physics {
                let dynamic_count = physics.get_dynamic_bone_indices().len();
                log::info!(
                    "GPU skinning physics debug: {} dynamic bones",
                    dynamic_count
                );
            }
            /*
            if let Some(ref physics) = self.physics {
                let dynamic_count = physics.get_dynamic_bone_indices().len();
                log::info!("GPU钂欑毊鐗╃悊璋冭瘯: 鐗╃悊宸插惎鐢? {} 涓姩鎬侀楠?, dynamic_count);
            }
            */
        }
        tacz_outcome
    }
}

struct MatrixWorkerGuard<'a>(&'a std::sync::atomic::AtomicUsize);
impl Drop for MatrixWorkerGuard<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
    }
}
