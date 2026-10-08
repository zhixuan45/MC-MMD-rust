use super::*;

impl MmdModel {
    pub fn init_physics(&mut self) -> bool {
        if self.rigid_bodies.is_empty() {
            log::debug!("模型没有刚体数据，跳过物理初始化");
            return false;
        }

        let mut physics = match MMDPhysics::new() {
            Some(p) => p,
            None => {
                log::error!("[Bullet3] 物理世界创建失败，跳过物理初始化");
                return false;
            }
        };

        // 当前姿态只用于把新 Bullet 世界同步到动画帧，不能参与永久 offset 的构建。
        let bone_count = self.bone_manager.bone_count();
        self.physics_bone_transforms_buf
            .resize(bone_count, Mat4::IDENTITY);
        for i in 0..bone_count {
            self.physics_bone_transforms_buf[i] = self.bone_manager.get_global_transform(i);
        }

        // 参考 CySpring 的初始化时序：静态参数始终来自绑定姿态，运行姿态单独提交。
        // PMX 绑定骨骼没有初始旋转，initial_position 已是右手模型空间的全局位置。
        let bind_bone_transforms: Vec<Mat4> = self
            .bone_manager
            .links()
            .map(|bone| Mat4::from_translation(bone.initial_position))
            .collect();

        // 自动合成缺失的上身跟骨碰撞体（如 Grass Wonder 等模型），提供真实的物理阻挡面
        let bone_names: Vec<String> = self.bone_manager.links().map(|b| b.name.clone()).collect();
        let bone_positions: Vec<[f32; 3]> = self
            .bone_manager
            .links()
            .map(|b| b.initial_position.to_array())
            .collect();
        let synthesized_colliders =
            crate::physics::body_collider_synthesis::synthesize_missing_body_colliders(
                &self.rigid_bodies,
                &bone_names,
                &bone_positions,
            );
        let all_rigid_bodies: Vec<mmd::pmx::rigid_body::RigidBody> =
            if synthesized_colliders.is_empty() {
                self.rigid_bodies.clone()
            } else {
                self.rigid_bodies
                    .iter()
                    .cloned()
                    .chain(synthesized_colliders)
                    .collect()
            };

        // 自动合成裙摆缺失的横向环形保形弹簧关节（方案 B）
        let synthesized_cross_joints =
            crate::physics::skirt_cross_joints::synthesize_missing_skirt_cross_joints(
                &all_rigid_bodies,
                &self.joints,
            );
        let all_joints: Vec<mmd::pmx::joint::Joint> = if synthesized_cross_joints.is_empty() {
            self.joints.clone()
        } else {
            self.joints
                .iter()
                .cloned()
                .chain(synthesized_cross_joints)
                .collect()
        };

        physics.build_physics(&all_rigid_bodies, &all_joints, &bind_bone_transforms);
        let bone_parents: Vec<_> = self.bone_manager.links().map(|b| b.parent_index).collect();
        physics.configure_embedded_body_contacts(&bone_names, &bone_parents);
        physics.set_tail_bone_names(&bone_names, &bone_parents);
        physics.set_tail_physics_options(self.tail_idle_lift, self.tail_movement_boost);
        self.physics_position_aligned_bones = physics
            .rigid_bodies
            .iter()
            .filter(|body| {
                body.physics_mode == crate::physics::PhysicsMode::PhysicsWithBone
                    && body.bullet_body.is_some()
            })
            .filter_map(|body| usize::try_from(body.bone_index).ok())
            .filter(|&index| index < bone_count)
            .collect();
        if crate::physics::config::get_config().debug_log {
            log::info!(
                "[Bullet3][诊断][参数基准] offset_source=pmx_bind_pose runtime_pose_separate=true bones={} rigid_bodies={} joints={}",
                bone_count,
                all_rigid_bodies.len(),
                all_joints.len(),
            );
        }
        physics.initialize(&self.physics_bone_transforms_buf);

        self.physics = Some(physics);
        self.physics_enabled = true;
        self.physics_resync_pending = false;
        self.physics_rebuild_pending = false;
        true
    }

    /// 重置物理系统
    pub fn reset_physics(&mut self) {
        if self.physics.is_some() {
            // 动画切换接口通常在新动画求值前调用；延迟到下一次物理更新，
            // 避免把上一帧物理回写姿态当成新链条的初始化姿态。
            self.physics_resync_pending = true;
            if crate::physics::config::get_config().debug_log {
                log::info!("[Bullet3][PHYSICS_RESYNC_REQUEST] deferred_until_post_animation=true");
            }
        }
    }

    /// 启用/禁用物理
    pub fn set_physics_enabled(&mut self, enabled: bool) {
        if enabled && !self.physics_enabled {
            self.physics_resync_pending = true;
        }
        self.physics_enabled = enabled;
    }

    /// 请求在下一次更新时按最新全局配置重建物理世界。
    pub fn request_physics_rebuild(&mut self) {
        if self.physics.is_some() {
            self.physics_rebuild_pending = true;
        }
    }

    /// 获取物理是否启用
    pub fn is_physics_enabled(&self) -> bool {
        self.physics_enabled && self.physics.is_some()
    }

    /// 获取物理系统是否已初始化
    pub fn has_physics(&self) -> bool {
        self.physics.is_some()
    }

    /// 读取匹配名称的 Bullet 关节快照，仅供命令行物理诊断工具使用。
    pub fn physics_joint_snapshots_matching(
        &self,
        needle: &str,
    ) -> Vec<crate::physics::PhysicsJointSnapshot> {
        self.physics
            .as_ref()
            .map_or_else(Vec::new, |physics| physics.joint_snapshots_matching(needle))
    }

    /// 读取腿部与裙摆的 Bullet 接触诊断快照。
    pub fn garment_physics_snapshot(&self) -> crate::physics::GarmentPhysicsSnapshot {
        self.physics
            .as_ref()
            .map_or_else(Default::default, MMDPhysics::garment_snapshot)
    }

    /// 更新物理模拟（Bullet3）
    ///
    /// 流程：sync_bodies → stepSimulation → sync_bones
    /// 所有中间数据复用预分配缓冲区，零堆分配。
    pub fn update_physics(&mut self, delta_time: f32) {
        // 全局开关 + per-model 开关双重检查
        if !crate::physics::config::get_config().enabled
            || !self.physics_enabled
            || self.physics.is_none()
        {
            return;
        }

        if self.physics_rebuild_pending {
            self.physics = None;
            if !self.init_physics() {
                return;
            }
        }

        self.collect_physics_bone_transforms();

        let model_transform = self.model_transform;

        // 拆分借用：先取出 physics 避免同时借用 self
        let mut physics = self.physics.take().unwrap();

        let max_step_delta = physics.max_step_delta_time();
        if !delta_time.is_finite() {
            // 时间参数异常时不重摆动态链，避免破坏已建立的关节锚点。
            physics.recover_after_large_delta(&self.physics_bone_transforms_buf);
            self.writeback_physics_bones(&mut physics);
            self.physics = Some(physics);
            return;
        }

        if delta_time <= 0.0 {
            // 第一人称会执行零步长求值；只同步运动学刚体，不清空动态物理链。
            physics.sync_bodies(&self.physics_bone_transforms_buf);
            self.writeback_physics_bones(&mut physics);
            self.physics = Some(physics);
            return;
        }

        if self.physics_resync_pending {
            // 动画切换是显式重置：按新动画姿态重新建立动态链的初始状态。
            if crate::physics::config::get_config().debug_log {
                log::info!(
                    "[Bullet3][PHYSICS_RESYNC_APPLY] reason=requested post_animation_pose=true delta_time={:.6}",
                    delta_time,
                );
            }
            physics.initialize(&self.physics_bone_transforms_buf);
            self.physics_resync_pending = false;
            self.writeback_physics_bones(&mut physics);
            self.physics = Some(physics);
            return;
        }

        if delta_time > max_step_delta {
            // 卡顿帧只清除旧速度；重新按骨骼摆放动态体会制造关节锚点失配。
            physics.recover_after_large_delta(&self.physics_bone_transforms_buf);
            self.writeback_physics_bones(&mut physics);
            if crate::physics::config::get_config().debug_log {
                log::info!(
                    "[Bullet3][PHYSICS_GAP_RECOVER] reason=large_delta preserve_dynamic_constraints=true physical_bones_written=true delta_time={:.6}",
                    delta_time,
                );
            }
            self.physics = Some(physics);
            return;
        }

        // 1. 同步运动学刚体
        physics.sync_bodies_with_model_velocity(
            &self.physics_bone_transforms_buf,
            delta_time,
            model_transform,
        );

        // 2. Bullet3 步进
        physics.step_simulation(delta_time);

        // 3. 同步物理结果回骨骼（复用内部缓冲区）
        self.writeback_physics_bones(&mut physics);

        // 归还所有权
        self.physics = Some(physics);
    }

    /// 每条启用物理的路径都将当前 Bullet 姿态写回骨骼，避免跳帧时回退到动画姿态。
    fn writeback_physics_bones(&mut self, physics: &mut MMDPhysics) {
        let dynamic_bone_transforms =
            physics.get_dynamic_bone_transforms(&self.physics_bone_transforms_buf);

        // 模式 2 必须沿父骨骼的本帧物理姿态传播位置。
        self.bone_manager.apply_physics_transforms(
            dynamic_bone_transforms,
            &self.physics_position_aligned_bones,
        );
    }

    /// 收集当前骨骼全局矩阵，供初始化、重置和每帧物理同步复用。
    fn collect_physics_bone_transforms(&mut self) {
        let bone_count = self.bone_manager.bone_count();
        self.physics_bone_transforms_buf
            .resize(bone_count, Mat4::IDENTITY);
        for i in 0..bone_count {
            self.physics_bone_transforms_buf[i] = self.bone_manager.get_global_transform(i);
        }
    }

    /// 结束物理更新，清除物理骨骼保护
    /// 在 tick_animation 结束时调用
    pub(super) fn end_physics_update(&mut self) {
        self.bone_manager.clear_physics_bone_indices();
    }

    /// 获取物理调试信息（JSON 格式）
    pub fn get_physics_debug_info(&self) -> String {
        use crate::physics::PhysicsMode;

        if let Some(ref physics) = self.physics {
            let mut info = String::from("{\n");

            // 刚体信息
            info.push_str("  \"rigid_bodies\": [\n");
            for (i, rb) in physics.rigid_bodies.iter().enumerate() {
                let type_str = match rb.physics_mode {
                    PhysicsMode::FollowBone => "FollowBone",
                    PhysicsMode::Physics => "Physics",
                    PhysicsMode::PhysicsWithBone => "PhysicsWithBone",
                };
                let escaped_name = rb.name.replace('\\', "\\\\").replace('"', "\\\"");
                info.push_str(&format!(
                    "    {{\"index\": {}, \"name\": \"{}\", \"type\": \"{}\", \"bone\": {}, \"mass\": {:.3}}}",
                    i, escaped_name, type_str, rb.bone_index, rb.mass
                ));
                if i < physics.rigid_bodies.len() - 1 {
                    info.push_str(",\n");
                } else {
                    info.push_str("\n");
                }
            }
            info.push_str("  ],\n");

            // 统计信息
            let kinematic_count = physics
                .rigid_bodies
                .iter()
                .filter(|rb| rb.physics_mode == PhysicsMode::FollowBone)
                .count();
            let dynamic_count = physics
                .rigid_bodies
                .iter()
                .filter(|rb| rb.physics_mode == PhysicsMode::Physics)
                .count();
            let dynamic_bone_count = physics
                .rigid_bodies
                .iter()
                .filter(|rb| rb.physics_mode == PhysicsMode::PhysicsWithBone)
                .count();

            info.push_str(&format!(
                "  \"stats\": {{\"total_rb\": {}, \"kinematic\": {}, \"dynamic\": {}, \"dynamic_bone\": {}, \"joints\": {}}}\n",
                physics.rigid_bodies.len(), kinematic_count, dynamic_count, dynamic_bone_count, physics.joint_count()
            ));

            info.push_str("}");
            info
        } else {
            String::from("{\"error\": \"no physics\"}")
        }
    }

    /// 一次性获取已聚合完成的物理诊断，避免 Java 重复输出同一窗口。
    pub fn take_physics_debug_diagnostic(&mut self) -> Option<String> {
        self.physics
            .as_mut()
            .and_then(MMDPhysics::take_debug_diagnostic)
    }

    // ======== VR 联动 ========
}

#[cfg(test)]
#[path = "physics_tests.rs"]
mod physics_tests;
