//! 只读导出碰撞配置、接触和约束锚点快照。

use super::MMDPhysics;
use std::fmt::Write as _;

const SAMPLE_LIMIT: usize = 24;
const JOINT_SCAN_LIMIT: usize = 8192;

impl MMDPhysics {
    pub(crate) fn write_issue_diagnostic(
        &self,
        out: &mut String,
        model_name: &str,
        enabled: bool,
        rebuild_pending: bool,
    ) {
        let requested = crate::physics::config::get_config();
        let _ = writeln!(out,
            "[MMD诊断][COLLISION] model={} model_physics_enabled={} global_physics_enabled={} rebuild_pending={} active_collision={} active_joints={} active_stability={} active_gravity_y={:.4} active_static_collider_scale={:.4} requested_collision={} requested_joints={} requested_stability={} requested_gravity_y={:.4} requested_static_collider_scale={:.4} requested_filters={} applied_filters={} rejected_filters={} largest_component={} garment_extended={} garment_forced_ignore={} embedded_filtered={} sample=instantaneous",
            escape(model_name), enabled, requested.enabled, rebuild_pending, self.active_debug_config.collision_enabled,
            self.active_debug_config.joints_enabled, self.collision_stability_mode.as_str(),
            self.active_debug_config.gravity_y.get(), self.active_debug_config.static_collider_scale,
            requested.collision_enabled, requested.joints_enabled, requested.collision_stability_mode.as_str(),
            requested.gravity_y, requested.static_collider_scale,
            self.collision_filter_plan.pairs.len(), self.collision_filter_applied_pairs,
            self.collision_filter_rejected_pairs, self.collision_filter_plan.largest_dynamic_component,
            self.garment_extension_pairs, self.garment_rejected_pairs, self.embedded_body_filtered_pairs);

        let _ = writeln!(out, "[MMD诊断][COLLISION] velocity_caps linear={:.4} angular={:.4} clamp_phase=after_step sample_phase=instantaneous_read sample=current_read_not_pre_clamp_peak",
            requested.max_linear_velocity, requested.max_angular_velocity);

        let filter_total = self.collision_filter_plan.pairs.len();
        for &(a, b) in self.collision_filter_plan.pairs.iter().take(SAMPLE_LIMIT) {
            let name_a = self
                .rigid_bodies
                .get(a)
                .map_or("<missing>", |body| body.name.as_str());
            let name_b = self
                .rigid_bodies
                .get(b)
                .map_or("<missing>", |body| body.name.as_str());
            let applied = self
                .rigid_bodies
                .get(a)
                .and_then(|body| body.bullet_body.as_ref())
                .zip(
                    self.rigid_bodies
                        .get(b)
                        .and_then(|body| body.bullet_body.as_ref()),
                )
                .is_some_and(|(a, b)| !a.check_collide_with(b) && !b.check_collide_with(a));
            let _ = writeln!(
                out,
                "[MMD诊断][COLLISION] filtered_pair a={} name_a={} b={} name_b={} status={}",
                a,
                escape(name_a),
                b,
                escape(name_b),
                if applied {
                    "applied"
                } else {
                    "rejected_or_unavailable"
                }
            );
        }
        if filter_total > SAMPLE_LIMIT {
            let _ = writeln!(
                out,
                "[MMD诊断][COLLISION] filtered_pairs_truncated={} total={}",
                filter_total - SAMPLE_LIMIT,
                filter_total
            );
        }
        for &(a, b) in &self.garment_forced_ignore_samples {
            let name_a = self
                .rigid_bodies
                .get(a)
                .map_or("<missing>", |body| body.name.as_str());
            let name_b = self
                .rigid_bodies
                .get(b)
                .map_or("<missing>", |body| body.name.as_str());
            let _ = writeln!(
                out,
                "[MMD诊断][COLLISION] garment_forced_ignore_pair a={} name_a={} b={} name_b={}",
                a,
                escape(name_a),
                b,
                escape(name_b)
            );
        }

        let manifolds_truncated = self.world.contact_manifold_count() > 4096;
        let mut contacts = Vec::new();
        for manifold in self.world.contact_manifolds_bounded(4096) {
            if manifold.contact_count <= 0 || !manifold.max_penetration_depth.is_finite() {
                continue;
            }
            let (Some(&a), Some(&b)) = (
                self.debug_body_pointer_indices.get(&manifold.body_a),
                self.debug_body_pointer_indices.get(&manifold.body_b),
            ) else {
                continue;
            };
            contacts.push((manifold, a, b));
        }
        contacts.sort_by(|left, right| {
            right
                .0
                .max_penetration_depth
                .total_cmp(&left.0.max_penetration_depth)
        });
        let contact_total = contacts.len();
        for (rank, (contact, a, b)) in contacts.into_iter().take(5).enumerate() {
            let name_a = self
                .rigid_bodies
                .get(a)
                .map_or("<missing>", |body| body.name.as_str());
            let name_b = self
                .rigid_bodies
                .get(b)
                .map_or("<missing>", |body| body.name.as_str());
            let _ = writeln!(out,
                "[MMD诊断][COLLISION] contact rank={} a={} name_a={} b={} name_b={} points={} penetration={:.5} impulse={:.5} mode_a={:?} mass_a={:.4} group_a={} mask_a=0x{:04X} mode_b={:?} mass_b={:.4} group_b={} mask_b=0x{:04X} snapshot=current_manifold space=bullet_left_model impulse_kind=max_point_applied_impulse",
                rank + 1, a, escape(name_a), b, escape(name_b), contact.contact_count,
                contact.max_penetration_depth, contact.max_applied_impulse,
                self.rigid_bodies[a].physics_mode, self.rigid_bodies[a].mass, self.rigid_bodies[a].group, self.rigid_bodies[a].collision_mask,
                self.rigid_bodies[b].physics_mode, self.rigid_bodies[b].mass, self.rigid_bodies[b].group, self.rigid_bodies[b].collision_mask);
        }
        let _ = writeln!(out, "[MMD诊断][COLLISION] contacts_sampled={} contacts_truncated_at_scan_limit={} note=current_manifold_not_window_peak", contact_total, manifolds_truncated);

        let mut worst: Option<(
            f32,
            &str,
            &str,
            &str,
            glam::Vec3,
            glam::Vec3,
            Option<glam::Vec3>,
        )> = None;
        let mut scanned = 0usize;
        for joint in self.joints.iter().take(JOINT_SCAN_LIMIT) {
            scanned += 1;
            let (Ok(a), Ok(b)) = (
                usize::try_from(joint.rigid_body_a_index),
                usize::try_from(joint.rigid_body_b_index),
            ) else {
                continue;
            };
            let (Some(body_a), Some(body_b)) = (self.rigid_bodies.get(a), self.rigid_bodies.get(b))
            else {
                continue;
            };
            let (Some(a), Some(b), Some(_constraint)) = (
                body_a.bullet_body.as_ref(),
                body_b.bullet_body.as_ref(),
                joint.constraint.as_ref(),
            ) else {
                continue;
            };
            let (error, anchor_a, anchor_b) = super::super::mmd_joint::joint_anchor_position_error(
                a.get_simulation_transform(),
                joint.frame_a,
                b.get_simulation_transform(),
                joint.frame_b,
            );
            if error.is_finite() && worst.as_ref().map_or(true, |current| error > current.0) {
                worst = Some((
                    error,
                    &joint.name,
                    &body_a.name,
                    &body_b.name,
                    anchor_a,
                    anchor_b,
                    joint
                        .constraint
                        .as_ref()
                        .and_then(|constraint| constraint.diagnostic())
                        .map(|diagnostic| diagnostic.linear_violation),
                ));
            }
        }
        if let Some((error, joint, body_a, body_b, anchor_a, anchor_b, violation)) = worst {
            let _ = writeln!(out, "[MMD诊断][COLLISION] worst_anchor joint={} body_a={} body_b={} error={:.5} anchor_a={} anchor_b={} scanned_joints={} total_joints={} truncated={} metric=world_anchor_separation space=bullet_left_model linear_limit_violation={}",
                escape(joint), escape(body_a), escape(body_b), error, vec3(anchor_a), vec3(anchor_b), scanned, self.joints.len(), self.joints.len() > scanned,
                violation.map_or_else(|| "unavailable".to_owned(), vec3));
        } else {
            let _ = writeln!(
                out,
                "[MMD诊断][COLLISION] worst_anchor=unavailable scanned_joints={} total_joints={}",
                scanned,
                self.joints.len()
            );
        }
    }
}

fn vec3(value: glam::Vec3) -> String {
    format!("{:.4},{:.4},{:.4}", value.x, value.y, value.z)
}

fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::physics::collision_topology::CollisionStabilityMode;
    use mmd::pmx::joint::{Joint as PmxJoint, JointType};
    use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyMode, RigidBodyShape};

    fn body(name: &str, group: u8, mode: RigidBodyMode, x: f32) -> PmxRigidBody {
        PmxRigidBody {
            local_name: name.to_owned(),
            universal_name: String::new(),
            bone_index: -1,
            group,
            un_collision_group_flag: u16::MAX,
            shape: RigidBodyShape::Sphere,
            size: [0.35, 0.0, 0.0],
            position: [x, 0.0, 0.0],
            rotation: [0.0; 3],
            mass: if mode == RigidBodyMode::Static {
                0.0
            } else {
                1.0
            },
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode,
        }
    }

    fn link(a: i32, b: i32) -> PmxJoint {
        PmxJoint {
            local_name: format!("link_{a}_{b}"),
            universal_name: String::new(),
            type_: JointType::Spring6DOF,
            rigid_body_a_index: a,
            rigid_body_b_index: b,
            position: [0.0; 3],
            rotation: [0.0; 3],
            position_min: [0.0; 3],
            position_max: [0.0; 3],
            rotation_min: [0.0; 3],
            rotation_max: [0.0; 3],
            position_spring: [0.0; 3],
            rotation_spring: [0.0; 3],
        }
    }

    #[test]
    fn snapshot_reports_applied_filter_and_real_bullet_contact() {
        let mut physics = MMDPhysics::new().expect("Bullet should be available");
        let bodies = [
            body("upper body", 1, RigidBodyMode::Static, 0.0),
            body("skirt_a", 0, RigidBodyMode::Dynamic, 0.0),
            body("skirt_b", 0, RigidBodyMode::Dynamic, 0.25),
            body("skirt_c", 0, RigidBodyMode::Dynamic, 0.5),
        ];
        let joints = [link(1, 2), link(2, 3)];
        physics.build_physics(&bodies, &joints, &[]);
        physics.set_gravity(0.0, 0.0, 0.0);
        physics.world.detect_collisions();
        let mut output = String::new();
        physics.write_issue_diagnostic(&mut output, "test.pmx", true, false);
        assert!(output.contains("active_stability=Stable"));
        assert!(output.contains("model_physics_enabled=true"));
        assert!(output.contains("global_physics_enabled=true"));
        assert!(output.contains("active_gravity_y=0.0000"));
        assert!(output.contains("requested_gravity_y=-98.0000"));
        assert!(output.contains("active_static_collider_scale="));
        assert!(output.contains("status=applied"));
        assert!(output.contains("contact rank=1"));
        assert!(output.contains("note=current_manifold_not_window_peak"));
        assert!(output.contains("mode_a="));
        assert!(output.contains("mask_a=0x"));
        assert!(output.contains("impulse=0.00000"));
        assert!(output.contains("impulse_kind=max_point_applied_impulse"));
        assert!(output.contains("clamp_phase=after_step sample_phase=instantaneous_read"));
        assert!(output.contains("linear_limit_violation="));
        assert!(physics.collision_stability_mode == CollisionStabilityMode::Stable);
    }
}
