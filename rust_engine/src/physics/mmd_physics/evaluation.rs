use super::*;

impl MMDPhysics {
    /// 步进物理模拟（Bullet3 stepSimulation）+ 速度钳制
    ///
    /// Bullet3 没有内置全局速度限制，需在每步后手动截断超速刚体，
    /// 防止卡顿帧或极端力导致的物理爆炸。
    pub fn step_simulation(&mut self, delta_time: f32) -> bool {
        if !delta_time.is_finite() || delta_time <= 0.0 {
            if get_config().debug_log {
                self.debug_telemetry.invalid_step_count += 1;
            }
            return true;
        }
        let fixed_dt = 1.0 / self.fps;
        let timer = crate::evaluation_profile::timer();
        let policy = crate::evaluation_parallelism::current_policy();
        let bullet_width = if policy.physics_parallel
            && policy.thread_budget > 1
            && BulletWorld::parallel_available()
        {
            let Some(width) = self.world.step_parallel(
                delta_time,
                self.max_substep_count,
                fixed_dt,
                policy.thread_budget,
            ) else {
                crate::evaluation_profile::mark_failed();
                crate::evaluation_profile::detail_stage(timer, 2);
                return false;
            };
            width
        } else {
            self.world
                .step(delta_time, self.max_substep_count, fixed_dt);
            1
        };
        if crate::evaluation_profile::enabled() {
            let (_, peak, jobs, islands, max_bodies, max_constraints) = if bullet_width > 1 {
                self.world.parallel_stats()
            } else {
                (1, 0, 0, 0, 0, 0)
            };
            crate::evaluation_profile::physics_parallel_stats(
                bullet_width,
                peak,
                jobs,
                islands,
                max_bodies,
                max_constraints,
            );
        }
        crate::evaluation_profile::detail_stage(timer, 2);

        // 速度钳制
        let config = get_config();
        let max_lin = config.max_linear_velocity;
        let max_ang = config.max_angular_velocity;
        let max_lin_sq = max_lin * max_lin;
        let max_ang_sq = max_ang * max_ang;

        let timer = crate::evaluation_profile::timer();
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
                    self.hair_debug.observe_velocity(index, lin_vel, ang_vel);
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

        crate::evaluation_profile::detail_stage(timer, 3);

        if config.debug_log {
            self.observe_hair_contacts();
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
                self.reset_hair_debug_window();
            }
        } else if self.debug_telemetry.step_count > 0 {
            // 关日志丢弃未完成窗口，重开后不混入旧峰值。
            self.debug_telemetry.reset_window();
            self.reset_hair_debug_window();
            self.pending_debug_diagnostic = None;
        }
        true
    }
}
