//! MMD 物理世界管理器
//!
//! 移植自 babylon-mmd，使用 Bullet3 引擎。
//! 实现 commitBodyStates / syncBodies / stepSimulation / syncBones 全套流程。

use std::collections::{HashMap, HashSet};

use glam::{Mat4, Vec3};

use mmd::pmx::joint::Joint as PmxJoint;
use mmd::pmx::rigid_body::RigidBody as PmxRigidBody;

use self::diagnostics::{ActivePhysicsDebugConfig, PhysicsDebugTelemetry};
use super::bullet_ffi::{self, BulletWorld};
use super::collision_topology::{
    build_filter_plan, CollisionAabb, CollisionBody, CollisionFilterPlan, CollisionStabilityMode,
};
use super::config::get_config;
use super::garment_contacts::build_garment_contact_plan;
use super::kinematic_target_filter::KinematicTargetFilter;
use super::mmd_joint::MmdJointData;
use super::mmd_rigid_body::{
    body_collider_scale_flags, classify_tail_dynamic,
    effective_collision_shape_size_with_static_scale, garment_contact_body_flags,
    is_skirt_or_lower_garment, is_tail_dynamic_part, MmdRigidBodyData, PhysicsMode,
};
use super::physics_diagnostics::model_topology_signature;
use super::tail_forces::TailForceState;

mod body_contacts;
mod diagnostics;
mod garment_diagnostics;
mod motion_forces;

pub use garment_diagnostics::{GarmentContactSnapshot, GarmentPhysicsSnapshot};

/// MMD 物理世界管理器（Bullet3 引擎）
///
/// 移植自 babylon-mmd，管理 Bullet3 世界、刚体、关节。
/// 物理在模型局部空间运行（Minecraft 仅绕 Y 轴旋转，重力方向不变）。
/// 流程：build_physics → 每帧 [sync_bodies → stepSimulation → sync_bones]
pub struct MMDPhysics {
    /// MMD 关节数据列表（Drop 顺序：约束先于刚体先于世界）
    joints: Vec<MmdJointData>,
    /// MMD 刚体数据列表
    pub rigid_bodies: Vec<MmdRigidBodyData>,
    /// Bullet3 物理世界
    world: BulletWorld,
    /// 物理 FPS
    fps: f32,
    /// 最大子步数
    max_substep_count: i32,

    // --- 预分配缓冲区（避免每帧堆分配） ---
    /// 动态刚体关联的骨骼索引集合（构建时计算一次）
    dynamic_bone_indices: HashSet<usize>,
    /// 动态骨骼变换结果缓冲区（复用内存）
    dynamic_bone_buf: Vec<(usize, Mat4)>,
    /// 调试时缓存本帧骨骼所对应的刚体目标位置。
    debug_body_target_positions: Vec<Option<Vec3>>,
    /// 上一帧提交给 FollowBone 刚体的目标矩阵，用于确认静止姿态是否仍在驱动运动学速度。
    debug_kinematic_target_transforms: Vec<Option<Mat4>>,
    /// 抑制 FollowBone 静止姿态的亚毫米级逐帧漂移，避免 Bullet 将其放大为运动学速度。
    kinematic_target_filter: KinematicTargetFilter,
    /// Bullet 刚体地址到模型刚体索引的映射，仅用于接触诊断。
    debug_body_pointer_indices: HashMap<usize, usize>,

    /// 上一帧模型世界位置（用于计算参考系速度）
    prev_model_position: Option<Vec3>,
    /// 平滑后的模型世界速度，消除逐帧离散渲染跳步
    smoothed_model_velocity: Vec3,
    /// 调试遥测按模拟时间聚合，避免开启日志后逐帧刷屏。
    debug_telemetry: PhysicsDebugTelemetry,
    /// 等待 Java Log4j 消费的诊断消息；每个聚合窗口只构造一次。
    pending_debug_diagnostic: Option<String>,
    /// 当前 Bullet 世界构建时实际采用的配置，避免日志读取到尚未重建的新配置。
    active_debug_config: ActivePhysicsDebugConfig,
    /// 构建阶段使用的拓扑稳定模式。
    collision_stability_mode: CollisionStabilityMode,
    /// 本次构建的动态自碰撞过滤计划统计。
    collision_filter_plan: CollisionFilterPlan,
    /// Bullet 回读确认已禁用碰撞的计划对数量。
    collision_filter_applied_pairs: usize,
    /// Bullet 回读仍允许碰撞的计划对数量。
    collision_filter_rejected_pairs: usize,
    /// 初始身体深嵌入触发的祖先躯干禁碰对数量。
    embedded_body_filtered_pairs: usize,
    /// 当前模型刚体与关节拓扑的稳定签名。
    model_topology_signature: String,
    /// 每个物理实例独立的尾巴选项与闲置包络。
    tail_forces: TailForceState,
}

/// 供命令行诊断工具读取的关节瞬时状态。
///
/// 内容完全来自当前 Bullet 世界；构造快照不会写入刚体或约束参数。
#[derive(Debug, Clone)]
pub struct PhysicsJointSnapshot {
    pub name: String,
    pub body_a_name: String,
    pub body_b_name: String,
    pub anchor_error: f32,
    pub anchor_a: Vec3,
    pub anchor_b: Vec3,
    pub diagnostic: bullet_ffi::ConstraintDiagnostic,
}

impl MMDPhysics {
    /// 创建新的物理世界（C++ OOM 时返回 None）
    pub fn new() -> Option<Self> {
        let config = get_config();
        let world = BulletWorld::new(0.0, config.gravity_y, 0.0)?;

        if config.debug_log {
            log::info!(
                "[Bullet3] 物理世界创建: FPS={}, 重力Y={}",
                config.physics_fps,
                config.gravity_y
            );
        }

        Some(Self {
            joints: Vec::new(),
            rigid_bodies: Vec::new(),
            world,
            fps: config.physics_fps,
            max_substep_count: config.max_substep_count,
            dynamic_bone_indices: HashSet::new(),
            dynamic_bone_buf: Vec::new(),
            debug_body_target_positions: Vec::new(),
            debug_kinematic_target_transforms: Vec::new(),
            kinematic_target_filter: KinematicTargetFilter::default(),
            debug_body_pointer_indices: HashMap::new(),
            prev_model_position: None,
            smoothed_model_velocity: Vec3::ZERO,
            debug_telemetry: PhysicsDebugTelemetry::default(),
            pending_debug_diagnostic: None,
            active_debug_config: ActivePhysicsDebugConfig::from_config(&config),
            collision_stability_mode: config.collision_stability_mode,
            collision_filter_plan: CollisionFilterPlan::default(),
            collision_filter_applied_pairs: 0,
            collision_filter_rejected_pairs: 0,
            embedded_body_filtered_pairs: 0,
            model_topology_signature: "fnv1a64:UNBUILT".to_owned(),
            tail_forces: TailForceState::default(),
        })
    }

    /// 构建物理系统（移植自 babylon-mmd buildPhysics）
    ///
    /// 一次性创建所有刚体和关节，并添加到 Bullet3 世界。
    /// 先将对象存入 Vec（所有权转移），再添加到世界，保证 panic 安全。
    pub fn build_physics(
        &mut self,
        pmx_rigid_bodies: &[PmxRigidBody],
        pmx_joints: &[PmxJoint],
        bone_transforms: &[Mat4],
    ) {
        let config = get_config();
        self.active_debug_config = ActivePhysicsDebugConfig::from_config(&config);
        self.collision_stability_mode = config.collision_stability_mode;
        self.model_topology_signature = model_topology_signature(pmx_rigid_bodies, pmx_joints);

        // 按整个模型的碰撞用途分类，避免把动态链的静态关节锚点误当成人体碰撞壳。
        let body_collider_flags = body_collider_scale_flags(pmx_rigid_bodies, pmx_joints);
        // 兼容裙摆的资格独立于 PMX 原始掩码，避免漏配掩码导致补碰撞永远无法生效。
        let garment_body_flags = garment_contact_body_flags(pmx_rigid_bodies, pmx_joints);
        let garment_contact_plan = build_garment_contact_plan(
            pmx_rigid_bodies,
            &garment_body_flags,
            config.collision_enabled,
            self.collision_stability_mode,
        );

        // 预分配容量
        self.rigid_bodies.reserve(pmx_rigid_bodies.len());

        // 第一步：创建所有刚体并存入 Vec（还未加入世界）
        for (body_index, pmx_rb) in pmx_rigid_bodies.iter().enumerate() {
            let bone_transform =
                if pmx_rb.bone_index >= 0 && (pmx_rb.bone_index as usize) < bone_transforms.len() {
                    Some(bone_transforms[pmx_rb.bone_index as usize])
                } else {
                    None
                };

            let shape_size = effective_collision_shape_size_with_static_scale(
                pmx_rb,
                config.static_collider_scale,
                body_collider_flags[body_index],
            );
            let mut rb_data = MmdRigidBodyData::from_pmx(pmx_rb, bone_transform, shape_size);
            let shape = MmdRigidBodyData::create_shape(pmx_rb, shape_size);
            let body = shape
                .as_ref()
                .and_then(|s| rb_data.create_rigid_body(pmx_rb, s));

            if shape.is_none() || body.is_none() {
                log::warn!("[Bullet3] 刚体 '{}' 创建失败，跳过", rb_data.name);
            }

            // 先转移所有权到 rb_data，再 push 到 Vec
            rb_data.bullet_body = body;
            rb_data.bullet_shape = shape;
            self.rigid_bodies.push(rb_data);
        }

        // 刚体入场前安装过滤规则，避免 broadphase 按旧规则缓存碰撞对。
        self.world.set_kinematic_filter(config.kinematic_filter);
        for &(a_index, b_index) in &garment_contact_plan.forced_ignore_pairs {
            let (Some(body_a), Some(body_b)) = (
                self.rigid_bodies
                    .get(a_index)
                    .and_then(|body| body.bullet_body.as_ref()),
                self.rigid_bodies
                    .get(b_index)
                    .and_then(|body| body.bullet_body.as_ref()),
            ) else {
                continue;
            };
            body_a.set_ignore_collision_check(body_b, true);
        }

        // 第二步：统一将已存储的刚体添加到世界
        // 此时所有权已在 self.rigid_bodies 中，panic 时 Drop 链会正确清理
        for (rb_data_index, rb_data) in self.rigid_bodies.iter().enumerate() {
            if let Some(ref body) = rb_data.bullet_body {
                let group = 1i32 << (rb_data.group.min(15) as i32);
                // 只应用计划中为裙摆与骨盆/大腿补齐的组位。
                let mask = effective_collision_mask(
                    config.collision_enabled,
                    garment_contact_plan.effective_masks[rb_data_index],
                );
                self.world.add_rigid_body(body, group, mask);
            }
        }

        self.collision_filter_plan = CollisionFilterPlan::default();
        self.collision_filter_applied_pairs = 0;
        self.collision_filter_rejected_pairs = 0;
        if config.collision_enabled && config.joints_enabled {
            // 只构建同组动态子图，保留衣物与跟骨身体刚体之间的碰撞。
            let topology_bodies: Vec<CollisionBody> = self
                .rigid_bodies
                .iter()
                .zip(pmx_rigid_bodies.iter())
                .map(|(body, pmx_body)| CollisionBody {
                    group: body.group,
                    collision_mask: body.collision_mask,
                    is_dynamic: body.physics_mode != PhysicsMode::FollowBone,
                    is_skirt: is_skirt_or_lower_garment(pmx_body),
                    is_tail: is_tail_dynamic_part(pmx_body),
                    is_hair: super::hair_parameters::is_hair_body(pmx_body),
                    is_active: body.bullet_body.is_some(),
                    initial_aabb: body.collision_half_extents.and_then(|extents| {
                        CollisionAabb::from_transform(body.initial_transform, extents)
                    }),
                })
                .collect();
            let topology_edges: Vec<(i32, i32)> = pmx_joints
                .iter()
                .map(|joint| (joint.rigid_body_a_index, joint.rigid_body_b_index))
                .collect();
            self.collision_filter_plan = build_filter_plan(
                &topology_bodies,
                &topology_edges,
                self.collision_stability_mode,
            );
            // 仅屏蔽稳定计划中的同组动态邻域，保留动态与跟骨身体的碰撞。
            for &(a_index, b_index) in &self.collision_filter_plan.pairs {
                let Some(body_a) = self
                    .rigid_bodies
                    .get(a_index)
                    .and_then(|body| body.bullet_body.as_ref())
                else {
                    self.collision_filter_rejected_pairs += 1;
                    log::warn!(
                        "[Bullet3] 碰撞过滤对无效: A 索引 {} 不存在可用刚体，B 索引 {}",
                        a_index,
                        b_index
                    );
                    continue;
                };
                let Some(body_b) = self
                    .rigid_bodies
                    .get(b_index)
                    .and_then(|body| body.bullet_body.as_ref())
                else {
                    self.collision_filter_rejected_pairs += 1;
                    log::warn!(
                        "[Bullet3] 碰撞过滤对无效: A 索引 {}，B 索引 {} 不存在可用刚体",
                        a_index,
                        b_index
                    );
                    continue;
                };

                body_a.set_ignore_collision_check(body_b, true);
                if !body_a.check_collide_with(body_b) && !body_b.check_collide_with(body_a) {
                    self.collision_filter_applied_pairs += 1;
                } else {
                    self.collision_filter_rejected_pairs += 1;
                    log::warn!(
                        "[Bullet3] 碰撞过滤回读失败: A[{}]='{}' B[{}]='{}'",
                        a_index,
                        self.rigid_bodies[a_index].name,
                        b_index,
                        self.rigid_bodies[b_index].name
                    );
                }
            }
        }

        // 第三步：创建关节并存入 Vec
        if config.joints_enabled {
            self.joints.reserve(pmx_joints.len());

            for pmx_joint in pmx_joints {
                let rb_a_idx = pmx_joint.rigid_body_a_index as usize;
                let rb_b_idx = pmx_joint.rigid_body_b_index as usize;

                if rb_a_idx >= self.rigid_bodies.len()
                    || rb_b_idx >= self.rigid_bodies.len()
                    || rb_a_idx == rb_b_idx
                {
                    continue;
                }

                let (rb_a_body, rb_b_body, rb_a_init, rb_b_init) = {
                    let rb_a = &self.rigid_bodies[rb_a_idx];
                    let rb_b = &self.rigid_bodies[rb_b_idx];
                    match (&rb_a.bullet_body, &rb_b.bullet_body) {
                        (Some(a), Some(b)) => {
                            (a, b, rb_a.initial_transform, rb_b.initial_transform)
                        }
                        _ => continue,
                    }
                };

                let joint_data = MmdJointData::from_pmx(
                    pmx_joint,
                    rb_a_body,
                    rb_b_body,
                    &pmx_rigid_bodies[rb_a_idx],
                    &pmx_rigid_bodies[rb_b_idx],
                    rb_a_init,
                    rb_b_init,
                );

                // 先存入 Vec，再添加到世界
                self.joints.push(joint_data);
            }

            // 统一添加约束到世界
            for joint_data in &self.joints {
                if let Some(ref constraint) = joint_data.constraint {
                    self.world.add_constraint(constraint, true);
                }
            }
        }

        // 第四步：预计算动态骨骼索引集合（一次性，避免每帧重算）
        self.dynamic_bone_indices = self
            .rigid_bodies
            .iter()
            .filter(|rb| rb.physics_mode != PhysicsMode::FollowBone && rb.bone_index >= 0)
            .map(|rb| rb.bone_index as usize)
            .collect();

        // 预分配动态骨骼缓冲区
        self.dynamic_bone_buf
            .reserve(self.dynamic_bone_indices.len());
        self.debug_body_target_positions
            .resize(self.rigid_bodies.len(), None);
        self.debug_kinematic_target_transforms
            .resize(self.rigid_bodies.len(), None);
        self.kinematic_target_filter.resize(self.rigid_bodies.len());
        self.debug_body_pointer_indices = self
            .rigid_bodies
            .iter()
            .enumerate()
            .filter_map(|(index, body)| {
                body.bullet_body
                    .as_ref()
                    .map(|bullet| (bullet.as_ptr() as usize, index))
            })
            .collect();

        let kinematic_count = self
            .rigid_bodies
            .iter()
            .filter(|rb| rb.physics_mode == PhysicsMode::FollowBone)
            .count();
        let dynamic_count = self
            .rigid_bodies
            .iter()
            .filter(|rb| rb.physics_mode == PhysicsMode::Physics)
            .count();
        let dynamic_bone_count = self
            .rigid_bodies
            .iter()
            .filter(|rb| rb.physics_mode == PhysicsMode::PhysicsWithBone)
            .count();

        log::info!(
            "Bullet3 物理构建完成: {} 刚体 ({}跟骨 + {}物理 + {}物理跟骨), {} 关节",
            self.rigid_bodies.len(),
            kinematic_count,
            dynamic_count,
            dynamic_bone_count,
            self.joints.len()
        );
    }

    /// 根据关联骨骼名补充尾巴分类，并缓存到每个刚体。
    pub fn set_tail_bone_names(&mut self, bone_names: &[String], bone_parents: &[i32]) {
        for body in &mut self.rigid_bodies {
            let bone_name = usize::try_from(body.bone_index)
                .ok()
                .and_then(|index| bone_names.get(index))
                .map(String::as_str)
                .unwrap_or("");
            body.is_tail_dynamic = classify_tail_dynamic(
                body.physics_mode != PhysicsMode::FollowBone,
                [&body.name, &body.universal_name, bone_name],
            );
        }
        self.tail_forces.set_wave_delays(super::tail_wave::tail_wave_delays(
            &self.rigid_bodies, bone_parents,
        ));
    }

    /// 设置当前物理实例的尾巴闲置与移动效果。
    pub fn set_tail_physics_options(&mut self, idle_lift: bool, movement_boost: bool) {
        self.tail_forces.set_options(idle_lift, movement_boost);
    }

    /// 同步运动学刚体位置（babylon-mmd syncBodies）
    ///
    /// 在每帧物理步进前调用。将 FollowBone 模式的刚体位置
    /// 同步为骨骼变换推导的物理空间（左手）变换。
    pub fn sync_bodies(&mut self, bone_transforms: &[Mat4]) {
        for (body_index, rb_data) in self.rigid_bodies.iter().enumerate() {
            if rb_data.physics_mode != PhysicsMode::FollowBone {
                continue;
            }
            let bone_idx = rb_data.bone_index;
            if bone_idx < 0 || (bone_idx as usize) >= bone_transforms.len() {
                continue;
            }
            if let Some(ref body) = rb_data.bullet_body {
                // 骨骼(右手) → inv_z → 左手，再计算刚体位置
                let bone_left = super::inv_z(bone_transforms[bone_idx as usize]);
                let body_matrix = rb_data.compute_body_matrix(bone_left);
                let filtered = self.kinematic_target_filter.filter(body_index, body_matrix);
                if filtered.suppressed && get_config().debug_log {
                    self.debug_telemetry.observe_kinematic_suppression(
                        filtered.translation_delta,
                        filtered.rotation_delta,
                    );
                }
                if filtered.suppressed {
                    // 精确跟随当前姿态，但不要让静止噪声被 Bullet 差分成周期性速度脉冲。
                    body.set_transform(filtered.transform);
                    body.set_linear_velocity(0.0, 0.0, 0.0);
                    body.set_angular_velocity(0.0, 0.0, 0.0);
                } else {
                    // 明显动作保留上一物理姿态，让 Bullet 生成真实运动学碰撞速度。
                    body.set_kinematic_target(filtered.transform);
                }
            }
        }
    }

    /// 缓存当前骨骼姿态对应的刚体目标位置，仅供碰撞异常诊断使用。
    fn update_debug_body_targets(&mut self, bone_transforms: &[Mat4], delta_time: f32) {
        for (index, rb_data) in self.rigid_bodies.iter().enumerate() {
            let bone_index = rb_data.bone_index;
            let target = if bone_index >= 0 && (bone_index as usize) < bone_transforms.len() {
                let bone_left = super::inv_z(bone_transforms[bone_index as usize]);
                Some(rb_data.compute_body_matrix(bone_left))
            } else {
                None
            };
            self.debug_body_target_positions[index] = target.map(|matrix| matrix.w_axis.truncate());

            // 只有 FollowBone 的目标会被 Bullet 解释为运动学碰撞体的驱动输入。
            if rb_data.physics_mode == PhysicsMode::FollowBone {
                if let (Some(previous), Some(current)) =
                    (self.debug_kinematic_target_transforms[index], target)
                {
                    self.debug_telemetry
                        .observe_kinematic_target(index, previous, current, delta_time);
                }
                self.debug_kinematic_target_transforms[index] = target;
            }
        }
    }

    /// 步进物理模拟（Bullet3 stepSimulation）+ 速度钳制
    ///
    /// Bullet3 没有内置全局速度限制，需在每步后手动截断超速刚体，
    /// 防止卡顿帧或极端力导致的物理爆炸。
    pub fn step_simulation(&mut self, delta_time: f32) {
        if !delta_time.is_finite() || delta_time <= 0.0 {
            if get_config().debug_log {
                self.debug_telemetry.invalid_step_count += 1;
            }
            return;
        }
        let fixed_dt = 1.0 / self.fps;
        self.world
            .step(delta_time, self.max_substep_count, fixed_dt);

        // 速度钳制
        let config = get_config();
        let max_lin = config.max_linear_velocity;
        let max_ang = config.max_angular_velocity;
        let max_lin_sq = max_lin * max_lin;
        let max_ang_sq = max_ang * max_ang;

        for (index, rb_data) in self.rigid_bodies.iter().enumerate() {
            if let Some(ref body) = rb_data.bullet_body {
                let lin_vel = body.get_linear_velocity();
                let ang_vel = body.get_angular_velocity();
                if config.debug_log && rb_data.physics_mode == PhysicsMode::FollowBone {
                    self.debug_telemetry
                        .observe_kinematic_body(index, lin_vel, ang_vel);
                    if is_skirt_body_name(&rb_data.name) {
                        self.debug_telemetry
                            .observe_skirt_kinematic_body(index, lin_vel, ang_vel);
                    }
                }
                if rb_data.physics_mode == PhysicsMode::FollowBone {
                    continue;
                }
                let lin_sq = lin_vel.length_squared();
                if config.debug_log {
                    // 峰值必须与同一次求解后的速度、位置和目标偏差配套记录。
                    let position = body.get_simulation_transform().w_axis.truncate();
                    self.debug_telemetry.observe_body(
                        index,
                        lin_vel,
                        ang_vel,
                        position,
                        self.debug_body_target_positions[index],
                    );
                }
                if lin_sq > max_lin_sq {
                    let clamped = lin_vel * (max_lin / lin_sq.sqrt());
                    body.set_linear_velocity(clamped.x, clamped.y, clamped.z);
                }

                let ang_sq = ang_vel.length_squared();
                if ang_sq > max_ang_sq {
                    let clamped = ang_vel * (max_ang / ang_sq.sqrt());
                    body.set_angular_velocity(clamped.x, clamped.y, clamped.z);
                }
            }
        }

        if config.debug_log {
            // 接触与关节数据均在求解后读取，不参与 Bullet 状态更新。
            for manifold in self.world.contact_manifolds() {
                let (Some(&body_a), Some(&body_b)) = (
                    self.debug_body_pointer_indices.get(&manifold.body_a),
                    self.debug_body_pointer_indices.get(&manifold.body_b),
                ) else {
                    continue;
                };
                // 跟骨壳之间不会驱动动态链，避免掩盖有冲量的真实接触。
                if self.rigid_bodies[body_a].physics_mode == PhysicsMode::FollowBone
                    && self.rigid_bodies[body_b].physics_mode == PhysicsMode::FollowBone
                {
                    continue;
                }
                self.debug_telemetry
                    .contacts
                    .observe(manifold, body_a, body_b);
            }
            for (joint_index, joint) in self.joints.iter().enumerate() {
                if let Some(diagnostic) = joint
                    .constraint
                    .as_ref()
                    .and_then(|constraint| constraint.diagnostic())
                {
                    self.debug_telemetry
                        .joint_limit_peak
                        .observe(joint_index, diagnostic);
                }
            }

            self.debug_telemetry.elapsed += delta_time;
            self.debug_telemetry.step_count += 1;
            self.debug_telemetry.max_delta_time =
                self.debug_telemetry.max_delta_time.max(delta_time);
            if self.debug_telemetry.elapsed >= 1.0 {
                self.pending_debug_diagnostic = Some(self.build_debug_diagnostic());
                self.debug_telemetry.reset_window();
            }
        }
    }

    /// 将动态刚体变换同步回骨骼（babylon-mmd syncBones）
    ///
    /// 从 Bullet3 读取左手空间变换，通过 inv_z 转回右手空间写入骨骼。
    pub fn sync_bones(&self, bone_transforms: &mut [Mat4]) {
        for rb_data in &self.rigid_bodies {
            if rb_data.physics_mode == PhysicsMode::FollowBone {
                continue;
            }
            let bone_idx = rb_data.bone_index;
            if bone_idx < 0 || (bone_idx as usize) >= bone_transforms.len() {
                continue;
            }
            if let Some(ref body) = rb_data.bullet_body {
                let rb_matrix = body.get_transform();
                let new_bone_left = match rb_data.physics_mode {
                    PhysicsMode::Physics => rb_data.compute_bone_matrix(rb_matrix),
                    PhysicsMode::PhysicsWithBone => {
                        // 骨骼位置(右手) → inv_z → 左手
                        let bone_right = bone_transforms[bone_idx as usize];
                        let pos_left = Vec3::new(
                            bone_right.w_axis.x,
                            bone_right.w_axis.y,
                            -bone_right.w_axis.z,
                        );
                        rb_data.compute_bone_matrix_rotation_only(rb_matrix, pos_left)
                    }
                    PhysicsMode::FollowBone => unreachable!(),
                };
                // 左手 → inv_z → 右手
                bone_transforms[bone_idx as usize] = super::inv_z(new_bone_left);
            }
        }
    }

    /// 初始化物理（commitBodyStates 的初始版本）
    ///
    /// 在骨骼初始姿态确定后调用，将所有刚体设置到正确的初始位置（左手空间）。
    pub fn initialize(&mut self, bone_transforms: &[Mat4]) {
        self.reset_motion_history();
        for (body_index, rb_data) in self.rigid_bodies.iter().enumerate() {
            let bone_idx = rb_data.bone_index;
            if bone_idx < 0 || (bone_idx as usize) >= bone_transforms.len() {
                continue;
            }
            if let Some(ref body) = rb_data.bullet_body {
                // 骨骼(右手) → inv_z → 左手
                let bone_left = super::inv_z(bone_transforms[bone_idx as usize]);
                let body_matrix = rb_data.compute_body_matrix(bone_left);
                body.set_transform(body_matrix);
                if rb_data.physics_mode == PhysicsMode::FollowBone {
                    self.kinematic_target_filter.seed(body_index, body_matrix);
                }
                body.set_linear_velocity(0.0, 0.0, 0.0);
                body.set_angular_velocity(0.0, 0.0, 0.0);
                body.clear_forces();
            }
        }

        // 所有刚体到达同一运行姿态后再记录弹簧零点。约束在 build_physics 阶段
        // 创建，若沿用当时的 equilibrium，首步会把当前无预载姿态拉回旧基准。
        for joint in &self.joints {
            joint.rebase_equilibrium();
        }

        if get_config().debug_log {
            super::initialization_diagnostics::log_initialized_bodies(
                &self.rigid_bodies,
                bone_transforms,
            );
            super::initialization_diagnostics::log_joint_anchor_baseline(
                &self.rigid_bodies,
                &self.joints,
            );
            super::initialization_diagnostics::log_joint_constraints_pre_step(
                &self.rigid_bodies,
                &self.joints,
            );
            super::initialization_diagnostics::log_initial_contacts_pre_step(
                &self.world,
                &self.rigid_bodies,
                &self.debug_body_pointer_indices,
            );
        }
    }

    /// 跳帧后恢复连续模拟状态。
    ///
    /// 大时间步并不等同于动画或模型被重置。若把每个动态刚体重新从已回写的
    /// 骨骼推导，会让同一关节两端落在不同锚点，随后被约束强行拉开。这里只把
    /// 跟骨刚体提交到当前动画姿态，并清除动态体速度与累积力，保留关节拓扑。
    pub fn recover_after_large_delta(&mut self, bone_transforms: &[Mat4]) {
        self.reset_motion_history();
        for (body_index, rb_data) in self.rigid_bodies.iter().enumerate() {
            let Some(ref body) = rb_data.bullet_body else {
                continue;
            };

            if rb_data.physics_mode == PhysicsMode::FollowBone {
                let bone_idx = rb_data.bone_index;
                if bone_idx < 0 || (bone_idx as usize) >= bone_transforms.len() {
                    continue;
                }
                // 直接提交避免 Bullet 将暂停期间的位移解释为运动学碰撞速度。
                let bone_left = super::inv_z(bone_transforms[bone_idx as usize]);
                let body_matrix = rb_data.compute_body_matrix(bone_left);
                body.set_transform(body_matrix);
                self.kinematic_target_filter.seed(body_index, body_matrix);
            } else {
                body.set_linear_velocity(0.0, 0.0, 0.0);
                body.set_angular_velocity(0.0, 0.0, 0.0);
                body.clear_forces();
            }
        }
    }

    /// 重置物理系统
    pub fn reset(&mut self, bone_transforms: &[Mat4]) {
        self.initialize(bone_transforms);
    }

    /// 清除模型参考系运动历史，防止暂停、传送或重置后产生虚假惯性。
    pub fn reset_motion_history(&mut self) {
        self.prev_model_position = None;
        self.smoothed_model_velocity = Vec3::ZERO;
        self.tail_forces.reset();
        // 重同步后的首帧没有连续目标历史，避免诊断把姿态切换误报为静止跳变。
        self.debug_kinematic_target_transforms.fill(None);
        self.debug_telemetry = PhysicsDebugTelemetry::default();
        self.pending_debug_diagnostic = None;
    }

    /// 当前配置允许一次调用实际消化的最大时间。
    pub fn max_step_delta_time(&self) -> f32 {
        self.max_substep_count.max(1) as f32 / self.fps.max(1.0)
    }

    /// 设置重力
    pub fn set_gravity(&self, x: f32, y: f32, z: f32) {
        self.world.set_gravity(x, y, z);
    }

    pub fn rigid_body_count(&self) -> usize {
        self.rigid_bodies.len()
    }
    pub fn joint_count(&self) -> usize {
        self.joints.len()
    }

    /// 返回名称包含 `needle` 的关节当前求解状态，供独立命令行探针使用。
    pub fn joint_snapshots_matching(&self, needle: &str) -> Vec<PhysicsJointSnapshot> {
        self.joints
            .iter()
            .filter(|joint| joint.name.contains(needle))
            .filter_map(|joint| {
                let body_a_index = usize::try_from(joint.rigid_body_a_index).ok()?;
                let body_b_index = usize::try_from(joint.rigid_body_b_index).ok()?;
                let body_a = self.rigid_bodies.get(body_a_index)?.bullet_body.as_ref()?;
                let body_b = self.rigid_bodies.get(body_b_index)?.bullet_body.as_ref()?;
                let diagnostic = joint.constraint.as_ref()?.diagnostic()?;
                let (anchor_error, anchor_a, anchor_b) =
                    super::mmd_joint::joint_anchor_position_error(
                        body_a.get_simulation_transform(),
                        joint.frame_a,
                        body_b.get_simulation_transform(),
                        joint.frame_b,
                    );
                Some(PhysicsJointSnapshot {
                    name: joint.name.clone(),
                    body_a_name: self.rigid_bodies.get(body_a_index)?.name.clone(),
                    body_b_name: self.rigid_bodies.get(body_b_index)?.name.clone(),
                    anchor_error,
                    anchor_a,
                    anchor_b,
                    diagnostic,
                })
            })
            .collect()
    }

    /// 获取动态刚体关联的骨骼变换（复用内部缓冲区，零堆分配）
    ///
    /// 从 Bullet3 读取左手空间变换，通过 inv_z 转回右手空间返回。
    pub fn get_dynamic_bone_transforms(
        &mut self,
        current_bone_transforms: &[Mat4],
    ) -> &[(usize, Mat4)] {
        self.dynamic_bone_buf.clear();
        // 从最终求解姿态预测余下不足一个固定步的时间，接触面限制朝内预测。
        self.world.sync_render_states();

        for rb_data in &self.rigid_bodies {
            if rb_data.physics_mode == PhysicsMode::FollowBone {
                continue;
            }
            let bone_idx = rb_data.bone_index;
            if bone_idx < 0 || (bone_idx as usize) >= current_bone_transforms.len() {
                continue;
            }
            if let Some(ref body) = rb_data.bullet_body {
                let rb_matrix = body.get_transform();
                let new_bone_left = match rb_data.physics_mode {
                    PhysicsMode::Physics => rb_data.compute_bone_matrix(rb_matrix),
                    PhysicsMode::PhysicsWithBone => {
                        let bone_right = current_bone_transforms[bone_idx as usize];
                        let pos_left = Vec3::new(
                            bone_right.w_axis.x,
                            bone_right.w_axis.y,
                            -bone_right.w_axis.z,
                        );
                        rb_data.compute_bone_matrix_rotation_only(rb_matrix, pos_left)
                    }
                    PhysicsMode::FollowBone => unreachable!(),
                };
                let bone_right = super::inv_z(new_bone_left);
                self.dynamic_bone_buf.push((bone_idx as usize, bone_right));
            }
        }
        &self.dynamic_bone_buf
    }

    /// 获取动态骨骼索引集合的引用（构建时已预计算，零分配）
    pub fn get_dynamic_bone_indices(&self) -> &HashSet<usize> {
        &self.dynamic_bone_indices
    }

    /// 一次性取走已完成的诊断窗口，交给 Java Log4j 写入 Minecraft 日志。
    pub fn take_debug_diagnostic(&mut self) -> Option<String> {
        self.pending_debug_diagnostic.take()
    }

    /// 获取 C++ 侧存活对象计数（调试用）
    pub fn alloc_stats() -> bullet_ffi::BulletAllocStats {
        bullet_ffi::get_alloc_stats()
    }
}

fn is_skirt_body_name(name: &str) -> bool {
    const SKIRT_PARTS: &[&str] = &[
        "裙",
        "スカート",
        "skirt",
        "petticoat",
        "下装",
        "下衣",
        "裾",
        "摆",
        "衣摆",
        "后摆",
        "下摆",
        "cloak",
        "cape",
    ];
    let lower = name.to_ascii_lowercase();
    SKIRT_PARTS.iter().any(|p| lower.contains(p))
}

/// 根据实验性总开关选择 Bullet 的允许碰撞掩码。
fn effective_collision_mask(collision_enabled: bool, pmx_mask: u16) -> i32 {
    if collision_enabled {
        pmx_mask as i32
    } else {
        0
    }
}

#[cfg(test)]
#[path = "mmd_physics_tests.rs"]
mod tests;

impl Drop for MMDPhysics {
    fn drop(&mut self) {
        // Bullet3 要求：必须在 destroy 对象前先从世界中移除。
        // Rust 默认按声明顺序 drop 字段（joints → rigid_bodies → world），
        // 如果不先移除，bw_world_destroy 会访问已释放的指针导致崩溃。
        for joint in &self.joints {
            if let Some(ref constraint) = joint.constraint {
                self.world.remove_constraint(constraint);
            }
        }
        for rb in &self.rigid_bodies {
            if let Some(ref body) = rb.bullet_body {
                self.world.remove_rigid_body(body);
            }
        }
        // 之后 Rust 自动 drop 各字段（约束/刚体/世界），此时世界已为空，安全释放

        // 泄漏检测日志（仅在当前实例释放后检查全局计数）
        let stats = bullet_ffi::get_alloc_stats();
        if !stats.is_clean() {
            log::warn!(
                "[Bullet3] C++ 侧仍有存活对象: worlds={}, shapes={}, bodies={}, constraints={}, motionStates={}",
                stats.worlds, stats.shapes, stats.rigid_bodies,
                stats.constraints, stats.motion_states
            );
        }
    }
}
