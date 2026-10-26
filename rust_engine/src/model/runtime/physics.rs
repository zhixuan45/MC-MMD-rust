use super::*;
#[cfg(not(feature = "legacy-physics"))]
use crate::physics::upstream_adapter::sync_config;

impl MmdModel {
    pub fn init_physics(&mut self) -> bool {
        if self.rigid_bodies.is_empty() {
            log::debug!("模型没有刚体数据，跳过物理初始化");
            return false;
        }

        #[cfg(not(feature = "legacy-physics"))]
        sync_config();
        let mut physics = match MMDPhysics::new() {
            Some(p) => p,
            None => {
                log::error!("[Bullet3] 物理世界创建失败，跳过物理初始化");
                return false;
            }
        };

        // 收集骨骼变换（复用缓冲区）
        let bone_count = self.bone_manager.bone_count();
        self.physics_bone_transforms_buf
            .resize(bone_count, Mat4::IDENTITY);
        for i in 0..bone_count {
            self.physics_bone_transforms_buf[i] = self.bone_manager.get_global_transform(i);
        }

        physics.build_physics(
            &self.rigid_bodies,
            &self.joints,
            &self.physics_bone_transforms_buf,
        );
        let bone_names: Vec<String> = self.bone_manager.links().map(|b| b.name.clone()).collect();
        let bone_parents: Vec<i32> = self.bone_manager.links().map(|b| b.parent_index).collect();
        physics.set_tail_physics_scale(self.tail_blocks_per_model_unit);
        physics.set_tail_bone_names(&bone_names, &bone_parents);
        physics.set_tail_physics_options(self.tail_idle_lift, self.tail_movement_boost);
        physics.initialize(&self.physics_bone_transforms_buf);

        self.physics_position_aligned_bones.clear();
        self.physics_resync_pending = false;
        self.physics_rebuild_pending = false;
        self.physics = Some(physics);
        self.physics_enabled = true;
        true
    }

    /// 读取尾部闲置受力：(骨骼索引、波形延迟秒数、当前加速度(方块/秒²)、目标抬高(方块))。
    #[cfg(not(feature = "legacy-physics"))]
    pub fn physics_tail_idle_samples(&self) -> Vec<(i32, f32, f32, f32)> {
        self.physics.as_ref().map(|p| p.tail_idle_samples()).unwrap_or_default()
    }

    /// 重置物理系统
    pub fn reset_physics(&mut self) {
        if self.physics.is_some() {
            // 动画切换可能先于新姿态求值；合并请求，等物理更新读取当帧骨骼。
            self.physics_resync_pending = true;
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
            let enabled = self.physics_enabled;
            self.physics = None;
            self.init_physics();
            self.physics_enabled = enabled;
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

    /// 配置当前模型的求解器诊断参数，不影响全局默认值。
    pub fn configure_physics_solver_diagnostic(
        &mut self,
        iterations: Option<i32>,
        contact_erp: Option<f32>,
        split_impulse: Option<bool>,
        joint_stop_erp: Option<f32>,
    ) -> bool {
        self.physics.as_mut().is_some_and(|physics| {
            physics.configure_solver_diagnostic(
                iterations,
                contact_erp,
                split_impulse,
                joint_stop_erp,
            )
        })
    }

    pub fn physics_solver_settings(
        &self,
    ) -> Option<crate::physics::bullet_ffi::BulletSolverSettings> {
        self.physics.as_ref()?.solver_settings()
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

        // 收集骨骼变换（复用缓冲区，resize + 索引赋值避免 push 分支开销）
        let bone_count = self.bone_manager.bone_count();
        self.physics_bone_transforms_buf
            .resize(bone_count, Mat4::IDENTITY);
        for i in 0..bone_count {
            self.physics_bone_transforms_buf[i] = self.bone_manager.get_global_transform(i);
        }

        let model_transform = self.model_transform;

        // 拆分借用：先取出 physics 避免同时借用 self
        let mut physics = self.physics.take().unwrap();

        let max_step_delta = physics.max_step_delta_time();
        if !delta_time.is_finite() || delta_time <= 0.0 {
            // 零步长常用于第一人称预求值；只同步骨骼，不向 Bullet 累积时间或惯性。
            physics.sync_bodies(&self.physics_bone_transforms_buf);
            // 动画求值已覆盖全局矩阵；不步进也必须发布现有物理姿态供蒙皮使用。
            self.writeback_physics_bones(&mut physics);
            self.physics = Some(physics);
            return;
        }
        if self.physics_resync_pending {
            // 清除旧运动状态，再用动画后姿态摆放刚体；不经过 PMX 初始位置。
            physics.recover_after_large_delta();
            physics.initialize(&self.physics_bone_transforms_buf);
            self.physics_resync_pending = false;
            self.writeback_physics_bones(&mut physics);
            self.physics = Some(physics);
            return;
        }
        if delta_time > max_step_delta * 5.0 {
            // 卡顿/传送后的大步长会让约束跨越过远距离，丢弃该步以免拉坏动态链。
            physics.recover_after_large_delta();
            // 保留刚体还不够：本帧显示骨骼也必须继续使用它们，不能闪回纯动画。
            self.writeback_physics_bones(&mut physics);
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

    /// 将当前 Bullet 姿态写回骨骼，并更新非物理子骨骼。
    fn writeback_physics_bones(&mut self, physics: &mut MMDPhysics) {
        let dynamic_bone_transforms =
            physics.get_dynamic_bone_transforms(&self.physics_bone_transforms_buf);

        for &(bone_idx, transform) in dynamic_bone_transforms {
            self.bone_manager
                .set_global_transform_physics(bone_idx, transform);
        }

        let physics_bone_indices = physics.get_dynamic_bone_indices();
        self.bone_manager
            .set_physics_bone_indices(physics_bone_indices);
        self.bone_manager
            .update_non_physics_children(physics_bone_indices);
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

#[cfg(all(test, feature = "legacy-physics"))]
#[path = "physics_tests.rs"]
mod physics_tests;
