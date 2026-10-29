use super::*;
use crate::physics::{inv_z, PhysicsMode};

#[derive(Clone, Copy)]
pub(super) struct PropPhysicsFrame {
    current: Mat4,
    previous: Option<Mat4>,
}

impl MmdModel {
    /// PMX units stay local, while this reference frame follows the character attachment.
    pub fn set_prop_attachment_transform(&mut self, world: Mat4) -> bool {
        let scale = world.x_axis.truncate().length();
        if !world.is_finite() || !scale.is_finite() || !(1e-6..1e3).contains(&scale)
            || world.determinant() <= 0.0
            || world.x_axis.w.abs() > 1e-6 || world.y_axis.w.abs() > 1e-6
            || world.z_axis.w.abs() > 1e-6 || (world.w_axis.w - 1.0).abs() > 1e-6
        {
            return false;
        }
        let rotation = glam::Mat3::from_mat4(world) / scale;
        if !(rotation.transpose() * rotation).abs_diff_eq(glam::Mat3::IDENTITY, 1e-3) {
            return false;
        }
        match &mut self.prop_physics_frame {
            Some(frame) => frame.current = world,
            None => self.prop_physics_frame = Some(PropPhysicsFrame { current: world, previous: None }),
        }
        true
    }

    /// Preserve dynamic bodies in world space when the attachment moves, before Bullet steps.
    /// A render-only parent transform cannot rotate gravity or produce attachment inertia.
    pub(super) fn prepare_prop_physics(&mut self, physics: &mut MMDPhysics) -> bool {
        let Some(frame) = self.prop_physics_frame.as_mut() else { return false; };
        let current = frame.current;
        let scale = current.x_axis.truncate().length();
        let rotation = glam::Mat3::from_mat4(current) / scale;
        let gravity = rotation.transpose() * Vec3::new(0.0, crate::physics::get_config().gravity_y, 0.0);
        physics.set_gravity(gravity.x, gravity.y, -gravity.z);

        if let Some(previous) = frame.previous {
            let old_scale = previous.x_axis.truncate().length();
            if current.w_axis.truncate().distance(previous.w_axis.truncate()) > 8.0
                || (scale / old_scale - 1.0).abs() > 1e-3
            {
                // Teleports and size edits align the chain instead of launching it.
                self.physics_resync_pending = true;
            } else if !self.physics_resync_pending && !current.abs_diff_eq(previous, 1e-7) {
                let change = inv_z(current.inverse() * previous);
                let velocity_rotation = glam::Mat3::from_mat4(change);
                for data in &physics.rigid_bodies {
                    if data.physics_mode == PhysicsMode::FollowBone { continue; }
                    if let Some(body) = &data.bullet_body {
                        body.set_transform(change * body.get_simulation_transform());
                        let linear = velocity_rotation * body.get_linear_velocity();
                        let angular = velocity_rotation * body.get_angular_velocity();
                        body.set_linear_velocity(linear.x, linear.y, linear.z);
                        body.set_angular_velocity(angular.x, angular.y, angular.z);
                    }
                }
            }
        }
        frame.previous = Some(current);
        true
    }
}

#[cfg(test)]
mod tests;
