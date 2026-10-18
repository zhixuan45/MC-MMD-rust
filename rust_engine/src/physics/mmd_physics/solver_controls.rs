use super::bullet_ffi::BulletSolverSettings;
use super::MMDPhysics;

impl MMDPhysics {
    pub fn solver_settings(&self) -> Option<BulletSolverSettings> {
        self.world.solver_settings()
    }

    pub(super) fn sync_default_solver_iterations(&mut self) {
        let iterations = crate::physics::solver_quality::default_iterations();
        if iterations == self.solver_iterations_snapshot {
            return;
        }
        let Some(mut settings) = self.world.solver_settings() else {
            return;
        };
        settings.iterations = iterations;
        if self.world.set_solver_settings(settings) {
            self.solver_iterations_snapshot = iterations;
        }
    }

    /// 显式诊断入口：只覆盖提供的求解参数，不写全局配置。
    pub fn configure_solver_diagnostic(
        &mut self,
        iterations: Option<i32>,
        contact_erp: Option<f32>,
        split_impulse: Option<bool>,
        joint_stop_erp: Option<f32>,
    ) -> bool {
        let invalid_erp = |value: f32| !value.is_finite() || !(0.0..=1.0).contains(&value);
        if iterations.map_or(false, |value| !(1..=256).contains(&value))
            || contact_erp.map_or(false, invalid_erp)
            || joint_stop_erp.map_or(false, invalid_erp)
        {
            return false;
        }
        if iterations.is_none()
            && contact_erp.is_none()
            && split_impulse.is_none()
            && joint_stop_erp.is_none()
        {
            return true;
        }

        if iterations.is_some() || contact_erp.is_some() || split_impulse.is_some() {
            let Some(mut settings) = self.world.solver_settings() else {
                return false;
            };
            if let Some(value) = iterations {
                settings.iterations = value;
            }
            if let Some(value) = contact_erp {
                settings.contact_erp = value;
            }
            if let Some(value) = split_impulse {
                settings.split_impulse = value;
            }
            if !self.world.set_solver_settings(settings) {
                return false;
            }
        }

        if let Some(value) = joint_stop_erp {
            for joint in &self.joints {
                if let Some(constraint) = joint.constraint.as_ref() {
                    for axis in 0..6 {
                        constraint.set_param(
                            super::bullet_ffi::BT_CONSTRAINT_STOP_ERP,
                            value,
                            axis,
                        );
                    }
                }
            }
        }
        true
    }
}

