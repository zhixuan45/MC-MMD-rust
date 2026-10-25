//! 头发专用接触窗口；不参与受力和过滤，关闭物理日志时不采样。
use super::{MMDPhysics, PhysicsContactPointSnapshotBatch, PhysicsMode};
use glam::Vec3;
use std::{collections::BTreeMap, fmt::Write};

const POINT_LIMIT: usize = 512;
const PAIR_LIMIT: usize = 128;

#[derive(Default)]
pub(super) struct HairDebugTelemetry {
    indices: Vec<usize>,
    flags: Vec<bool>,
    samples: u64,
    sampled_substeps: usize,
    unsampled_substeps: usize,
    truncated_samples: usize,
    dropped_pairs: usize,
    non_finite_samples: usize,
    peak_linear: f32,
    peak_angular: f32,
    pairs: BTreeMap<(usize, usize), ContactPeak>,
    joints: Vec<HairJointBaseline>,
    worst_joint: Option<(usize, f32, f32)>,
}

struct HairJointBaseline {
    index: usize,
    lower: [f32; 3],
    upper: [f32; 3],
    spring: [f32; 3],
}

#[derive(Default)]
struct ContactPeak {
    samples: usize,
    max_depth: f32,
    max_upward: f32,
    impulse: f32,
    direction: Vec3,
    point: Vec3,
    step: u64,
}

impl HairDebugTelemetry {
    fn clear_window(&mut self) {
        let indices = std::mem::take(&mut self.indices);
        let flags = std::mem::take(&mut self.flags);
        let joints = std::mem::take(&mut self.joints);
        *self = Self {
            indices,
            flags,
            joints,
            ..Default::default()
        };
    }

    pub(super) fn observe_velocity(&mut self, index: usize, linear: Vec3, angular: Vec3) {
        if !self.flags.get(index).copied().unwrap_or(false) {
            return;
        }
        if !linear.is_finite()
            || !angular.is_finite()
            || !linear.length().is_finite()
            || !angular.length().is_finite()
        {
            self.non_finite_samples += 1;
            return;
        }
        self.peak_linear = self.peak_linear.max(linear.length());
        self.peak_angular = self.peak_angular.max(angular.length());
    }

    fn observe(&mut self, batch: &PhysicsContactPointSnapshotBatch) {
        // 零子步仍可能留下旧冲量；禁止重复计入当前时间窗。
        if batch.substeps == 0 {
            return;
        }
        self.samples += 1;
        self.sampled_substeps += 1;
        self.unsampled_substeps += batch.substeps.saturating_sub(1);
        self.truncated_samples += usize::from(batch.truncated);
        for point in &batch.points {
            if !point.normal_on_b.is_finite()
                || !point.point_a.is_finite()
                || !point.point_b.is_finite()
                || !point.penetration.is_finite()
                || !point.normal_impulse.is_finite()
            {
                self.non_finite_samples += 1;
                continue;
            }
            for (hair, other, direction, position) in [
                (
                    point.a_index,
                    point.b_index,
                    point.normal_on_b,
                    point.point_a,
                ),
                (
                    point.b_index,
                    point.a_index,
                    -point.normal_on_b,
                    point.point_b,
                ),
            ] {
                if !self.flags.get(hair).copied().unwrap_or(false) {
                    continue;
                }
                let key = (hair, other);
                if self.pairs.len() >= PAIR_LIMIT && !self.pairs.contains_key(&key) {
                    self.dropped_pairs += 1;
                    continue;
                }
                let peak = self.pairs.entry(key).or_default();
                peak.samples += 1;
                peak.max_depth = peak.max_depth.max(point.penetration);
                let upward = (direction.y * point.normal_impulse).max(0.0);
                if upward > peak.max_upward || peak.samples == 1 {
                    peak.max_upward = upward;
                    peak.impulse = point.normal_impulse;
                    peak.direction = direction;
                    peak.point = position;
                    peak.step = batch.step_serial;
                }
            }
        }
    }
}

impl MMDPhysics {
    pub(super) fn initialize_hair_diagnostics(
        &mut self,
        raw: &[mmd::pmx::rigid_body::RigidBody],
        raw_joints: &[mmd::pmx::joint::Joint],
    ) {
        // 诊断候选可宽于修正资格；发饰也能被观察，但不能据此改形状。
        let indices: Vec<_> = raw
            .iter()
            .enumerate()
            .filter_map(|(i, body)| {
                (self.rigid_bodies[i].physics_mode != PhysicsMode::FollowBone
                    && super::super::hair_parameters::is_hair_body(body))
                .then_some(i)
            })
            .collect();
        let mut flags = vec![false; self.rigid_bodies.len()];
        for &index in &indices {
            flags[index] = true;
        }
        let joints = self
            .joints
            .iter()
            .enumerate()
            .filter_map(|(index, joint)| {
                let touches = [joint.rigid_body_a_index, joint.rigid_body_b_index]
                    .iter()
                    .any(|i| {
                        usize::try_from(*i)
                            .ok()
                            .and_then(|i| flags.get(i))
                            .copied()
                            .unwrap_or(false)
                    });
                if !touches {
                    return None;
                }
                // 构建跳过坏关节时按端点和名称找回作者记录，不假设索引一致。
                let source = raw_joints.iter().find(|j| {
                    j.rigid_body_a_index == joint.rigid_body_a_index
                        && j.rigid_body_b_index == joint.rigid_body_b_index
                        && j.local_name == joint.name
                })?;
                Some(HairJointBaseline {
                    index,
                    lower: source.rotation_min,
                    upper: source.rotation_max,
                    spring: source.rotation_spring,
                })
            })
            .collect();
        self.hair_debug = HairDebugTelemetry {
            indices,
            flags,
            joints,
            ..Default::default()
        };
    }

    pub(super) fn observe_hair_contacts(&mut self) {
        if self.hair_debug.indices.is_empty() {
            return;
        }
        let batch = self.contact_points_for_indices(&self.hair_debug.indices, POINT_LIMIT);
        self.hair_debug.observe(&batch);
        for source in &self.hair_debug.joints {
            let joint = &self.joints[source.index];
            let Some(diagnostic) = joint.constraint.as_ref().and_then(|c| c.diagnostic()) else {
                continue;
            };
            let a = self.rigid_bodies[joint.rigid_body_a_index as usize]
                .bullet_body
                .as_ref()
                .unwrap();
            let b = self.rigid_bodies[joint.rigid_body_b_index as usize]
                .bullet_body
                .as_ref()
                .unwrap();
            let (anchor, _, _) = super::super::mmd_joint::joint_anchor_position_error(
                a.get_simulation_transform(),
                joint.frame_a,
                b.get_simulation_transform(),
                joint.frame_b,
            );
            let violation = diagnostic.angular_violation.length();
            if !anchor.is_finite() || !violation.is_finite() {
                self.hair_debug.non_finite_samples += 1;
                continue;
            }
            if self
                .hair_debug
                .worst_joint
                .is_none_or(|(_, old_anchor, old_violation)| {
                    anchor + violation > old_anchor + old_violation
                })
            {
                self.hair_debug.worst_joint = Some((source.index, anchor, violation));
            }
        }
    }

    pub(super) fn reset_hair_debug_window(&mut self) {
        self.hair_debug.clear_window();
    }

    pub(super) fn hair_debug_lines(&self) -> Vec<String> {
        let state = &self.hair_debug;
        let mut lines = vec![format!(
            "[Bullet3][诊断][HAIR_WINDOW] targets={} samples={} sampled_final_substeps={} unsampled_intermediate_substeps={} truncated_samples={} dropped_pair_samples={} non_finite_samples={} linear_pre_cap={:.5} angular_pre_cap={:.5} impulse=normal_only space=bullet_left_model",
            state.indices.len(), state.samples, state.sampled_substeps, state.unsampled_substeps,
            state.truncated_samples, state.dropped_pairs, state.non_finite_samples, state.peak_linear, state.peak_angular,
        )];
        for (&(hair, other), peak) in &state.pairs {
            lines.push(format!(
                "[Bullet3][诊断][HAIR_CONTACT] hair={} name={:?} other={} name_other={:?} point_samples={} peak_upward_normal_impulse={:.6} same_point_impulse={:.6} same_point_direction={:?} same_point_position={:?} peak_step={} max_depth={:.6}",
                hair, self.rigid_bodies[hair].name, other, self.rigid_bodies[other].name,
                peak.samples, peak.max_upward, peak.impulse, peak.direction, peak.point, peak.step, peak.max_depth,
            ));
        }
        if let Some((index, anchor, violation)) = state.worst_joint {
            lines.push(format!("[Bullet3][诊断][HAIR_JOINT] name={:?} anchor_error={anchor:.6} angular_violation={violation:.6}", self.joints[index].name));
        }
        lines
    }

    /// 两侧日志即时读取，完整窗口需开启已有“物理调试日志”。
    pub(crate) fn write_hair_issue_diagnostic(&self, out: &mut String) {
        for &index in self.hair_debug.indices.iter().take(24) {
            let body = &self.rigid_bodies[index];
            let _ = writeln!(out,
                "[MMD诊断][HAIR_PROXY] body={} name={:?} source={} raw_shape={} raw_size={:?} effective_shape={} effective_size={:?} bind_transform={:?}",
                index, body.name, body.proxy_source, body.raw_shape_name, body.raw_shape_size, body.shape_name, body.shape_size, body.initial_transform);
        }
        let batch = self.contact_points_for_indices(&self.hair_debug.indices, POINT_LIMIT);
        let _ = writeln!(out,
            "[MMD诊断][HAIR_CONTACT] targets={} sampled={} total_points={} truncated={} targets_truncated={} step={} substeps={} requested_substeps={} phase={:?} sample=instantaneous_final_state impulse=normal_only",
            self.hair_debug.indices.len(), batch.points.len(), batch.total_points, batch.truncated,
            batch.targets_truncated, batch.step_serial, batch.substeps, batch.requested_substeps, batch.phase);
        for point in batch.points.iter().take(24) {
            let _ = writeln!(out,
                "[MMD诊断][HAIR_POINT] a={} name_a={:?} b={} name_b={:?} impulse={:.6} normal_on_b={:?} point_a={:?} point_b={:?} penetration={:.6} same_point=true",
                point.a_index, point.a_name, point.b_index, point.b_name, point.normal_impulse,
                point.normal_on_b, point.point_a, point.point_b, point.penetration);
        }
        if batch.points.len() > 24 {
            let _ = writeln!(
                out,
                "[MMD诊断][HAIR_CONTACT] output_points_omitted={}",
                batch.points.len() - 24
            );
        }
        for source in self.hair_debug.joints.iter().take(24) {
            let joint = &self.joints[source.index];
            let Some(actual) = joint.constraint.as_ref().and_then(|c| c.diagnostic()) else {
                continue;
            };
            let _ = writeln!(out,
                "[MMD诊断][HAIR_JOINT] name={:?} raw_lower={:?} raw_upper={:?} raw_spring={:?} effective_lower={:?} effective_upper={:?} effective_stiffness={:?} angular={:?} equilibrium={:?} violation={:?}",
                joint.name, source.lower, source.upper, source.spring, actual.angular_lower, actual.angular_upper,
                actual.stiffness, actual.angular_position, actual.equilibrium, actual.angular_violation);
        }
        if self.hair_debug.joints.len() > 24 {
            let _ = writeln!(
                out,
                "[MMD诊断][HAIR_JOINT] output_joints_omitted={}",
                self.hair_debug.joints.len() - 24
            );
        }
    }
}

#[cfg(all(test, feature = "legacy-physics"))]
mod tests {
    use super::*;
    use crate::physics::PhysicsContactPointSnapshot;

    #[test]
    fn same_point_impulse_is_signed_for_hair_side_and_zero_steps_are_not_counted() {
        let mut state = HairDebugTelemetry {
            flags: vec![false, true],
            ..Default::default()
        };
        let mut batch = PhysicsContactPointSnapshotBatch {
            substeps: 1,
            step_serial: 7,
            points: vec![PhysicsContactPointSnapshot {
                a_index: 0,
                b_index: 1,
                a_name: "肩".into(),
                b_name: "发".into(),
                point_a: Vec3::ZERO,
                point_b: Vec3::ONE,
                normal_on_b: -Vec3::Y,
                normal_impulse: 3.0,
                penetration: 0.1,
            }],
            ..Default::default()
        };
        state.observe(&batch);
        let peak = &state.pairs[&(1, 0)];
        assert_eq!(peak.max_upward, 3.0);
        assert_eq!(peak.point, Vec3::ONE);
        assert_eq!(peak.step, 7);
        batch.substeps = 0;
        state.observe(&batch);
        assert_eq!(state.pairs[&(1, 0)].samples, 1);
        // 更深的另一点不应替换真正向上冲量峰值的点位和方向。
        batch.substeps = 1;
        batch.points[0].normal_on_b = Vec3::Y;
        batch.points[0].penetration = 1.0;
        batch.points[0].normal_impulse = 10.0;
        state.observe(&batch);
        assert_eq!(state.pairs[&(1, 0)].max_upward, 3.0);
        assert_eq!(state.pairs[&(1, 0)].impulse, 3.0);
        assert_eq!(state.pairs[&(1, 0)].max_depth, 1.0);
    }
}
