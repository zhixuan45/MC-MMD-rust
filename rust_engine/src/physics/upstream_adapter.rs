//! Host API compatibility; all simulation belongs to the imported 1.20.1 backend.
use super::{
    bullet_ffi, upstream1201, GarmentPhysicsSnapshot, PhysicsBodySnapshot,
    PhysicsContactPointSnapshotBatch, PhysicsJointSnapshot,
};
use glam::Mat4;
use mmd::pmx::{
    joint::Joint,
    rigid_body::{RigidBody, RigidBodyShape},
};
use std::ops::{Deref, DerefMut};

pub(crate) fn sync_config() {
    let c = super::config::get_config();
    upstream1201::config::set_config(upstream1201::config::PhysicsConfig {
        enabled: c.enabled,
        gravity_y: c.gravity_y,
        physics_fps: c.physics_fps,
        max_substep_count: c.max_substep_count,
        inertia_strength: c.inertia_strength,
        max_linear_velocity: c.max_linear_velocity,
        max_angular_velocity: c.max_angular_velocity,
        joints_enabled: c.joints_enabled,
        kinematic_filter: c.kinematic_filter,
        debug_log: c.debug_log,
    });
}

pub struct MMDPhysics {
    inner: upstream1201::MMDPhysics,
    authored_bodies: Vec<RigidBody>,
}

impl Deref for MMDPhysics {
    type Target = upstream1201::MMDPhysics;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}
impl DerefMut for MMDPhysics {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl MMDPhysics {
    pub fn alloc_stats() -> bullet_ffi::BulletAllocStats {
        super::get_alloc_stats()
    }

    pub fn new() -> Option<Self> {
        sync_config();
        let inner = upstream1201::MMDPhysics::new()?;
        log::info!("[PhysicsBackend] upstream1201 commit=5fe18f8 type2_as_type1={} legacy_policies=false solver_iterations=10 advanced_contact_diagnostics=unavailable", !cfg!(feature="upstream-native-mode2"));
        Some(Self {
            inner,
            authored_bodies: Vec::new(),
        })
    }

    pub fn build_physics(&mut self, bodies: &[RigidBody], joints: &[Joint], bones: &[Mat4]) {
        self.authored_bodies = bodies.to_vec();
        if super::config::get_config().collision_enabled {
            self.inner.build_physics(bodies, joints, bones);
        } else {
            let mut disabled = bodies.to_vec();
            for body in &mut disabled {
                body.un_collision_group_flag = 0;
            }
            self.inner.build_physics(&disabled, joints, bones);
        }
    }

    pub fn set_tail_physics_options(&mut self, _idle_lift: bool, _movement_boost: bool) {
        log::debug!("[PhysicsBackend] tail force enhancements inactive in upstream1201 baseline");
    }

    pub fn configure_solver_diagnostic(
        &mut self,
        iterations: Option<i32>,
        contact_erp: Option<f32>,
        split_impulse: Option<bool>,
        joint_stop_erp: Option<f32>,
    ) -> bool {
        iterations.is_none_or(|v| v == 10)
            && contact_erp.is_none()
            && split_impulse.is_none()
            && joint_stop_erp.is_none()
    }
    pub fn solver_settings(&self) -> Option<bullet_ffi::BulletSolverSettings> {
        None
    }
    pub fn joint_snapshots_matching(&self, _needle: &str) -> Vec<PhysicsJointSnapshot> {
        Vec::new()
    }
    pub fn garment_snapshot(&self) -> GarmentPhysicsSnapshot {
        GarmentPhysicsSnapshot::default()
    }
    pub fn take_debug_diagnostic(&mut self) -> Option<String> {
        None
    }
    pub fn contact_points_matching(
        &self,
        _needle: &str,
        _limit: usize,
    ) -> PhysicsContactPointSnapshotBatch {
        PhysicsContactPointSnapshotBatch::default()
    }
    pub fn body_snapshots_matching(&self, needle: &str) -> Vec<PhysicsBodySnapshot> {
        self.inner
            .rigid_bodies
            .iter()
            .enumerate()
            .filter_map(|(index, data)| {
                if !data.name.contains(needle) {
                    return None;
                }
                let body = data.bullet_body.as_ref()?;
                let raw = self.authored_bodies.get(index)?;
                let shape_name = match raw.shape {
                    RigidBodyShape::Sphere => "sphere",
                    RigidBodyShape::Box => "box",
                    RigidBodyShape::Capsule => "capsule_y",
                };
                Some(PhysicsBodySnapshot {
                    index,
                    name: data.name.clone(),
                    bone_index: data.bone_index,
                    mode: data.physics_mode,
                    mass: raw.mass,
                    shape_name,
                    raw_shape_name: shape_name,
                    shape_size: raw.size,
                    raw_shape_size: raw.size,
                    proxy_source: "upstream1201_pmx",
                    initial_transform: data.initial_transform,
                    simulation_transform: body.get_simulation_transform(),
                    render_transform: body.get_transform(),
                    linear_velocity: body.get_linear_velocity(),
                    angular_velocity: body.get_angular_velocity(),
                })
            })
            .collect()
    }
    pub fn write_hair_issue_diagnostic(&self, output: &mut String) {
        use std::fmt::Write;
        let _ = writeln!(output, "[MMD诊断][HAIR] backend=upstream1201 advanced_constraint_diagnostics=unavailable legacy_hair_limits=false");
    }
    pub fn write_issue_diagnostic(
        &self,
        output: &mut String,
        model: &str,
        enabled: bool,
        rebuild: bool,
    ) {
        use std::fmt::Write;
        let _ = writeln!(output, "[MMD诊断][COLLISION] model={model:?} backend=upstream1201 model_physics_enabled={enabled} global_physics_enabled={} rebuild_pending={rebuild} raw_bodies={} runtime_bodies={} runtime_joints={} authored_masks=true synthetic_bodies=0 synthetic_joints=0 advanced_contact_diagnostics=unavailable", super::config::get_config().enabled, self.authored_bodies.len(), self.inner.rigid_bodies.len(), self.inner.joint_count());
    }
}
