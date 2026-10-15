//! 探针与碰撞线框共用真实刚体状态，统一标明时间相位。
use super::{MMDPhysics, PhysicsMode};
use crate::physics::bullet_ffi::{ContactSamplePhase, MAX_CONTACT_QUERY_TARGETS};
use glam::{Mat4, Vec3};

#[derive(Debug, Clone)]
pub struct PhysicsContactPointSnapshot {
    pub a_index: usize,
    pub b_index: usize,
    pub a_name: String,
    pub b_name: String,
    pub point_a: Vec3,
    pub point_b: Vec3,
    pub normal_on_b: Vec3,
    pub normal_impulse: f32,
    pub penetration: f32,
}

#[derive(Debug, Clone)]
pub struct PhysicsContactPointSnapshotBatch {
    pub points: Vec<PhysicsContactPointSnapshot>,
    pub total_points: usize,
    pub truncated: bool,
    pub targets_truncated: bool,
    pub step_serial: u64,
    pub substeps: usize,
    pub requested_substeps: usize,
    pub phase: ContactSamplePhase,
}

impl Default for PhysicsContactPointSnapshotBatch {
    fn default() -> Self {
        Self {
            points: Vec::new(),
            total_points: 0,
            truncated: false,
            targets_truncated: false,
            step_serial: 0,
            substeps: 0,
            requested_substeps: 0,
            phase: ContactSamplePhase::Unavailable,
        }
    }
}

#[derive(Debug, Clone)]
pub struct PhysicsBodySnapshot {
    pub index: usize,
    pub name: String,
    pub bone_index: i32,
    pub mode: PhysicsMode,
    pub mass: f32,
    pub shape_name: &'static str,
    pub shape_size: [f32; 3],
    pub raw_shape_size: [f32; 3],
    pub raw_shape_name: &'static str,
    pub proxy_source: &'static str,
    pub initial_transform: Mat4,
    pub simulation_transform: Mat4,
    pub render_transform: Mat4,
    pub linear_velocity: Vec3,
    pub angular_velocity: Vec3,
}

impl MMDPhysics {
    pub fn contact_points_matching(
        &self,
        needle: &str,
        limit: usize,
    ) -> PhysicsContactPointSnapshotBatch {
        let indices: Vec<_> = self
            .rigid_bodies
            .iter()
            .enumerate()
            .filter_map(|(i, body)| body.name.contains(needle).then_some(i))
            .collect();
        self.contact_points_for_indices(&indices, limit)
    }

    pub(super) fn contact_points_for_indices(
        &self,
        indices: &[usize],
        limit: usize,
    ) -> PhysicsContactPointSnapshotBatch {
        let mut targets = Vec::new();
        let mut targets_truncated = false;
        for &index in indices {
            let Some(body) = self
                .rigid_bodies
                .get(index)
                .and_then(|data| data.bullet_body.as_ref())
            else {
                continue;
            };
            if targets.len() == MAX_CONTACT_QUERY_TARGETS {
                targets_truncated = true;
                break;
            }
            targets.push(body);
        }
        // 底层空目标表示全世界，模型层空集合必须表示无匹配。
        if targets.is_empty() {
            return PhysicsContactPointSnapshotBatch::default();
        }
        let Ok(query) = self.world.contact_points_for(&targets, limit) else {
            return PhysicsContactPointSnapshotBatch {
                truncated: true,
                targets_truncated: true,
                ..Default::default()
            };
        };
        let points: Vec<_> = query
            .points
            .iter()
            .filter_map(|point| {
                let &a_index = self.debug_body_pointer_indices.get(&point.body_a)?;
                let &b_index = self.debug_body_pointer_indices.get(&point.body_b)?;
                Some(PhysicsContactPointSnapshot {
                    a_index,
                    b_index,
                    a_name: self.rigid_bodies[a_index].name.clone(),
                    b_name: self.rigid_bodies[b_index].name.clone(),
                    point_a: point.point_a,
                    point_b: point.point_b,
                    normal_on_b: point.normal_on_b,
                    normal_impulse: point.applied_normal_impulse,
                    penetration: (-point.distance).max(0.0),
                })
            })
            .collect();
        PhysicsContactPointSnapshotBatch {
            truncated: query.truncated || targets_truncated || points.len() < query.points.len(),
            targets_truncated,
            total_points: query.total_hits,
            points,
            step_serial: query.step_serial,
            substeps: query.substeps,
            requested_substeps: query.requested_substeps,
            phase: query.phase,
        }
    }

    /// 同时返回求解和 MotionState 状态，读取不会同步或推进世界。
    pub fn body_snapshots_matching(&self, needle: &str) -> Vec<PhysicsBodySnapshot> {
        self.rigid_bodies
            .iter()
            .enumerate()
            .filter_map(|(index, data)| {
                if !data.name.contains(needle) {
                    return None;
                }
                let body = data.bullet_body.as_ref()?;
                Some(PhysicsBodySnapshot {
                    index,
                    name: data.name.clone(),
                    bone_index: data.bone_index,
                    mode: data.physics_mode,
                    mass: data.mass,
                    shape_name: data.shape_name,
                    shape_size: data.shape_size,
                    raw_shape_name: data.raw_shape_name,
                    raw_shape_size: data.raw_shape_size,
                    proxy_source: data.proxy_source,
                    initial_transform: data.initial_transform,
                    simulation_transform: body.get_simulation_transform(),
                    render_transform: body.get_transform(),
                    linear_velocity: body.get_linear_velocity(),
                    angular_velocity: body.get_angular_velocity(),
                })
            })
            .collect()
    }
}
