//! 物理诊断快照与遥测日志构建。

use super::super::physics_diagnostics::{ContactWindow, JointLimitPeak};
use super::MMDPhysics;
use crate::physics::config::PhysicsConfig;
use glam::{Mat3, Mat4, Vec3};

#[derive(Clone, Copy)]
pub(super) struct ActivePhysicsDebugConfig {
    pub(super) collision_enabled: bool,
    pub(super) joints_enabled: bool,
    pub(super) kinematic_filter: bool,
    pub(super) inertia_strength: f32,
    pub(super) static_collider_scale: f32,
}

impl ActivePhysicsDebugConfig {
    pub(super) fn from_config(config: &PhysicsConfig) -> Self {
        Self {
            collision_enabled: config.collision_enabled,
            joints_enabled: config.joints_enabled,
            kinematic_filter: config.kinematic_filter,
            inertia_strength: config.inertia_strength,
            static_collider_scale: config.static_collider_scale,
        }
    }
}

#[derive(Default)]
pub(super) struct PhysicsDebugTelemetry {
    pub(super) elapsed: f32,
    pub(super) step_count: u32,
    pub(super) invalid_step_count: u32,
    pub(super) max_delta_time: f32,
    pub(super) max_linear_speed: f32,
    pub(super) max_angular_speed: f32,
    pub(super) peak_linear_body_index: Option<usize>,
    pub(super) peak_angular_body_index: Option<usize>,
    pub(super) peak_linear_velocity: Vec3,
    pub(super) peak_angular_velocity: Vec3,
    pub(super) peak_linear_position: Vec3,
    pub(super) peak_angular_position: Vec3,
    pub(super) peak_linear_body_target_error: f32,
    pub(super) peak_angular_body_target_error: f32,
    pub(super) max_body_target_error: f32,
    pub(super) max_body_target_delta: Vec3,
    pub(super) max_body_target_actual: Vec3,
    pub(super) max_body_target_expected: Vec3,
    pub(super) peak_body_target_index: Option<usize>,
    pub(super) max_kinematic_target_delta: f32,
    pub(super) max_kinematic_target_speed: f32,
    pub(super) max_kinematic_target_angle: f32,
    pub(super) max_kinematic_target_angular_speed: f32,
    pub(super) peak_kinematic_target_index: Option<usize>,
    pub(super) kinematic_suppressed_count: u32,
    pub(super) max_suppressed_translation_error: f32,
    pub(super) max_suppressed_rotation_error: f32,
    pub(super) max_kinematic_linear_speed: f32,
    pub(super) max_kinematic_angular_speed: f32,
    pub(super) peak_kinematic_linear_body_index: Option<usize>,
    pub(super) peak_kinematic_angular_body_index: Option<usize>,
    pub(super) peak_kinematic_linear_velocity: Vec3,
    pub(super) peak_kinematic_angular_velocity: Vec3,
    pub(super) max_skirt_kinematic_linear_speed: f32,
    pub(super) max_skirt_kinematic_angular_speed: f32,
    pub(super) peak_skirt_kinematic_linear_body_index: Option<usize>,
    pub(super) peak_skirt_kinematic_angular_body_index: Option<usize>,
    pub(super) peak_skirt_kinematic_linear_velocity: Vec3,
    pub(super) peak_skirt_kinematic_angular_velocity: Vec3,
    pub(super) contacts: ContactWindow,
    pub(super) joint_limit_peak: JointLimitPeak,
}

impl PhysicsDebugTelemetry {
    pub(super) fn observe_kinematic_suppression(
        &mut self,
        translation_error: f32,
        rotation_error: f32,
    ) {
        self.kinematic_suppressed_count = self.kinematic_suppressed_count.saturating_add(1);
        self.max_suppressed_translation_error =
            self.max_suppressed_translation_error.max(translation_error);
        self.max_suppressed_rotation_error = self.max_suppressed_rotation_error.max(rotation_error);
    }

    pub(super) fn observe_kinematic_target(
        &mut self,
        body_index: usize,
        previous: Mat4,
        current: Mat4,
        delta_time: f32,
    ) {
        let dt = delta_time.max(0.001);
        let position_delta =
            finite_length_or_infinity(current.w_axis.truncate() - previous.w_axis.truncate());
        let previous_rotation = glam::Quat::from_mat3(&Mat3::from_mat4(previous));
        let current_rotation = glam::Quat::from_mat3(&Mat3::from_mat4(current));
        let angle_delta = (2.0
            * previous_rotation
                .dot(current_rotation)
                .abs()
                .clamp(0.0, 1.0)
                .acos())
        .min(std::f32::consts::PI);
        let target_speed = position_delta / dt;
        let angular_speed = angle_delta / dt;

        if position_delta > self.max_kinematic_target_delta {
            self.max_kinematic_target_delta = position_delta;
            self.max_kinematic_target_speed = target_speed;
            self.max_kinematic_target_angle = angle_delta;
            self.max_kinematic_target_angular_speed = angular_speed;
            self.peak_kinematic_target_index = Some(body_index);
        }
    }

    pub(super) fn observe_kinematic_body(
        &mut self,
        body_index: usize,
        linear: Vec3,
        angular: Vec3,
    ) {
        let linear_speed = finite_length_or_infinity(linear);
        let angular_speed = finite_length_or_infinity(angular);
        // 首个样本即使恰为零也要保留，避免日志把“已观测且静止”误写成 none。
        if self.peak_kinematic_linear_body_index.is_none() {
            self.peak_kinematic_linear_body_index = Some(body_index);
            self.peak_kinematic_linear_velocity = linear;
        }
        if self.peak_kinematic_angular_body_index.is_none() {
            self.peak_kinematic_angular_body_index = Some(body_index);
            self.peak_kinematic_angular_velocity = angular;
        }
        if linear_speed > self.max_kinematic_linear_speed {
            self.max_kinematic_linear_speed = linear_speed;
            self.peak_kinematic_linear_body_index = Some(body_index);
            self.peak_kinematic_linear_velocity = linear;
        }
        if angular_speed > self.max_kinematic_angular_speed {
            self.max_kinematic_angular_speed = angular_speed;
            self.peak_kinematic_angular_body_index = Some(body_index);
            self.peak_kinematic_angular_velocity = angular;
        }
    }

    pub(super) fn observe_skirt_kinematic_body(
        &mut self,
        body_index: usize,
        linear: Vec3,
        angular: Vec3,
    ) {
        let linear_speed = finite_length_or_infinity(linear);
        let angular_speed = finite_length_or_infinity(angular);
        if self.peak_skirt_kinematic_linear_body_index.is_none() {
            self.peak_skirt_kinematic_linear_body_index = Some(body_index);
            self.peak_skirt_kinematic_linear_velocity = linear;
        }
        if self.peak_skirt_kinematic_angular_body_index.is_none() {
            self.peak_skirt_kinematic_angular_body_index = Some(body_index);
            self.peak_skirt_kinematic_angular_velocity = angular;
        }
        if linear_speed > self.max_skirt_kinematic_linear_speed {
            self.max_skirt_kinematic_linear_speed = linear_speed;
            self.peak_skirt_kinematic_linear_body_index = Some(body_index);
            self.peak_skirt_kinematic_linear_velocity = linear;
        }
        if angular_speed > self.max_skirt_kinematic_angular_speed {
            self.max_skirt_kinematic_angular_speed = angular_speed;
            self.peak_skirt_kinematic_angular_body_index = Some(body_index);
            self.peak_skirt_kinematic_angular_velocity = angular;
        }
    }

    pub(super) fn observe_body(
        &mut self,
        body_index: usize,
        linear: Vec3,
        angular: Vec3,
        actual_position: Vec3,
        target_position: Option<Vec3>,
    ) {
        let linear_speed = finite_length_or_infinity(linear);
        let angular_speed = finite_length_or_infinity(angular);
        let target_error = target_position
            .map(|target| finite_length_or_infinity(actual_position - target))
            .unwrap_or(0.0);
        if linear_speed > self.max_linear_speed {
            self.max_linear_speed = linear_speed;
            self.peak_linear_body_index = Some(body_index);
            self.peak_linear_velocity = linear;
            self.peak_linear_position = actual_position;
            self.peak_linear_body_target_error = target_error;
        }
        if angular_speed > self.max_angular_speed {
            self.max_angular_speed = angular_speed;
            self.peak_angular_body_index = Some(body_index);
            self.peak_angular_velocity = angular;
            self.peak_angular_position = actual_position;
            self.peak_angular_body_target_error = target_error;
        }
        if let Some(target) = target_position {
            if target_error > self.max_body_target_error {
                self.max_body_target_error = target_error;
                self.max_body_target_delta = actual_position - target;
                self.max_body_target_actual = actual_position;
                self.max_body_target_expected = target;
                self.peak_body_target_index = Some(body_index);
            }
        }
    }

    pub(super) fn reset_window(&mut self) {
        *self = Self::default();
    }
}

fn finite_length_or_infinity(value: Vec3) -> f32 {
    let length = value.length();
    if length.is_finite() {
        length
    } else {
        f32::INFINITY
    }
}

fn format_vec3(value: Vec3) -> String {
    format!("({:.4},{:.4},{:.4})", value.x, value.y, value.z)
}

impl MMDPhysics {
    /// 构造当前聚合窗口中的峰值刚体信息，便于定位接触导致的高频振荡。
    pub(super) fn build_debug_diagnostic(&self) -> String {
        let mut lines = vec![format!(
            "[Bullet3][诊断][窗口] model_signature={} cfg(collision={} joints={} kinematic_filter={} inertia={:.3} static_scale={:.3} stability_mode={}) requested={} applied={} rejected={} embedded_body_filtered_pairs={} largest_dynamic_component={} space=bullet_left steps={} max_dt={:.5}s invalid={}",
            self.model_topology_signature,
            self.active_debug_config.collision_enabled,
            self.active_debug_config.joints_enabled,
            self.active_debug_config.kinematic_filter,
            self.active_debug_config.inertia_strength,
            self.active_debug_config.static_collider_scale,
            self.collision_stability_mode.as_str(),
            self.collision_filter_plan.pairs.len(),
            self.collision_filter_applied_pairs,
            self.collision_filter_rejected_pairs,
            self.embedded_body_filtered_pairs,
            self.collision_filter_plan.largest_dynamic_component,
            self.debug_telemetry.step_count,
            self.debug_telemetry.max_delta_time,
            self.debug_telemetry.invalid_step_count,
        )];
        lines.push(format!(
            "[Bullet3][诊断][初始重叠] dynamic_dynamic={} filtered_dynamic_dynamic={} dynamic_kinematic={} filtered_tail_anchor_skirt={} preserved_dynamic_kinematic={}",
            self.collision_filter_plan.initial_overlap_dynamic_dynamic_pairs,
            self.collision_filter_plan.filtered_initial_overlap_pairs,
            self.collision_filter_plan.initial_overlap_dynamic_kinematic_pairs,
            self.collision_filter_plan.filtered_tail_anchor_skirt_pairs,
            self.collision_filter_plan.preserved_dynamic_kinematic_pairs,
        ));

        if let (Some(linear), Some(angular)) = (
            self.debug_telemetry
                .peak_linear_body_index
                .and_then(|index| self.rigid_bodies.get(index)),
            self.debug_telemetry
                .peak_angular_body_index
                .and_then(|index| self.rigid_bodies.get(index)),
        ) {
            lines.push(format!(
                "[Bullet3][诊断][刚体] linear='{}' mode={:?} speed={:.3} vel={} pos={} target_error={:.5} angular='{}' mode={:?} speed={:.3} vel={} pos={} target_error={:.5}",
                linear.name,
                linear.physics_mode,
                self.debug_telemetry.max_linear_speed,
                format_vec3(self.debug_telemetry.peak_linear_velocity),
                format_vec3(self.debug_telemetry.peak_linear_position),
                self.debug_telemetry.peak_linear_body_target_error,
                angular.name,
                angular.physics_mode,
                self.debug_telemetry.max_angular_speed,
                format_vec3(self.debug_telemetry.peak_angular_velocity),
                format_vec3(self.debug_telemetry.peak_angular_position),
                self.debug_telemetry.peak_angular_body_target_error,
            ));
        }

        if let Some(body) = self
            .debug_telemetry
            .peak_kinematic_target_index
            .and_then(|index| self.rigid_bodies.get(index))
        {
            lines.push(format!(
                "[Bullet3][诊断][运动学目标] body='{}' target_delta={:.6} target_speed={:.3} target_angle={:.5}rad target_angular_speed={:.3}",
                body.name,
                self.debug_telemetry.max_kinematic_target_delta,
                self.debug_telemetry.max_kinematic_target_speed,
                self.debug_telemetry.max_kinematic_target_angle,
                self.debug_telemetry.max_kinematic_target_angular_speed,
            ));
        } else {
            lines.push("[Bullet3][诊断][运动学目标] none".to_owned());
        }

        lines.push(format!(
            "[Bullet3][诊断][运动学过滤] suppressed={} frame_translation_peak={:.6} frame_rotation_peak={:.6}rad",
            self.debug_telemetry.kinematic_suppressed_count,
            self.debug_telemetry.max_suppressed_translation_error,
            self.debug_telemetry.max_suppressed_rotation_error,
        ));

        if let (Some(linear), Some(angular)) = (
            self.debug_telemetry
                .peak_kinematic_linear_body_index
                .and_then(|index| self.rigid_bodies.get(index)),
            self.debug_telemetry
                .peak_kinematic_angular_body_index
                .and_then(|index| self.rigid_bodies.get(index)),
        ) {
            lines.push(format!(
                "[Bullet3][诊断][运动学速度] linear='{}' speed={:.3} vel={} angular='{}' speed={:.3} vel={}",
                linear.name,
                self.debug_telemetry.max_kinematic_linear_speed,
                format_vec3(self.debug_telemetry.peak_kinematic_linear_velocity),
                angular.name,
                self.debug_telemetry.max_kinematic_angular_speed,
                format_vec3(self.debug_telemetry.peak_kinematic_angular_velocity),
            ));
        } else {
            lines.push("[Bullet3][诊断][运动学速度] none".to_owned());
        }

        if let (Some(linear), Some(angular)) = (
            self.debug_telemetry
                .peak_skirt_kinematic_linear_body_index
                .and_then(|index| self.rigid_bodies.get(index)),
            self.debug_telemetry
                .peak_skirt_kinematic_angular_body_index
                .and_then(|index| self.rigid_bodies.get(index)),
        ) {
            lines.push(format!(
                "[Bullet3][诊断][裙摆运动学速度] linear='{}' speed={:.3} vel={} angular='{}' speed={:.3} vel={}",
                linear.name,
                self.debug_telemetry.max_skirt_kinematic_linear_speed,
                format_vec3(self.debug_telemetry.peak_skirt_kinematic_linear_velocity),
                angular.name,
                self.debug_telemetry.max_skirt_kinematic_angular_speed,
                format_vec3(self.debug_telemetry.peak_skirt_kinematic_angular_velocity),
            ));
        } else {
            lines.push(
                "[Bullet3][诊断][裙摆运动学速度] not_applicable(no FollowBone skirt collider)"
                    .to_owned(),
            );
        }

        if let Some(body) = self
            .debug_telemetry
            .peak_body_target_index
            .and_then(|index| self.rigid_bodies.get(index))
        {
            // 这里的 expected 是同骨骼当前动画姿态对应的刚体目标，不代表动态体必须贴住目标。
            lines.push(format!(
                "[Bullet3][诊断][刚体偏差] body='{}' mode={:?} error={:.5} actual={} expected={} delta={}",
                body.name,
                body.physics_mode,
                self.debug_telemetry.max_body_target_error,
                format_vec3(self.debug_telemetry.max_body_target_actual),
                format_vec3(self.debug_telemetry.max_body_target_expected),
                format_vec3(self.debug_telemetry.max_body_target_delta),
            ));
        } else {
            lines.push("[Bullet3][诊断][刚体偏差] none".to_owned());
        }

        let contacts = self.debug_telemetry.contacts.top();
        if contacts.is_empty() {
            lines.push("[Bullet3][诊断][接触] none".to_owned());
        }
        for (rank, contact) in contacts.iter().enumerate() {
            let (Some(a), Some(b)) = (
                self.rigid_bodies.get(contact.body_a_index),
                self.rigid_bodies.get(contact.body_b_index),
            ) else {
                continue;
            };
            lines.push(format!(
                "[Bullet3][诊断][接触#{rank}] A='{}' mode={:?} group={} mask=0x{:04X} B='{}' mode={:?} group={} mask=0x{:04X} points={} depth={:.5} impulse_peak={:.5} impulse_sum={:.5} point_a={} point_b={} normal_on_b={}",
                a.name, a.physics_mode, a.group, a.collision_mask,
                b.name, b.physics_mode, b.group, b.collision_mask,
                contact.contact_count,
                contact.max_penetration_depth,
                contact.max_applied_impulse,
                contact.total_applied_impulse,
                format_vec3(contact.point_a),
                format_vec3(contact.point_b),
                format_vec3(contact.normal_on_b),
                rank = rank + 1,
            ));
        }

        let peak = self.debug_telemetry.joint_limit_peak;
        if peak.max_violation > 0.0 {
            if let Some(joint) = self.joints.get(peak.joint_index) {
                let body_a = self
                    .rigid_bodies
                    .get(joint.rigid_body_a_index as usize)
                    .map_or("?", |body| body.name.as_str());
                let body_b = self
                    .rigid_bodies
                    .get(joint.rigid_body_b_index as usize)
                    .map_or("?", |body| body.name.as_str());
                lines.push(format!(
                    "[Bullet3][诊断][关节限位] joint='{}' bodies='{}'/'{}' max_violation={:.5} linear_pos={} linear_violation={} angular_pos={} angular_violation={}",
                    joint.name, body_a, body_b, peak.max_violation,
                    format_vec3(peak.linear_position),
                    format_vec3(peak.linear_violation),
                    format_vec3(peak.angular_position),
                    format_vec3(peak.angular_violation),
                ));
            }
        } else {
            lines.push("[Bullet3][诊断][关节限位] none".to_owned());
        }
        lines.join("\n")
    }
}
