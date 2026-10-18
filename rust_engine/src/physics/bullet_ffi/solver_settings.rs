use super::{ffi, BulletWorld};

/// Bullet 世界级求解参数快照。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BulletSolverSettings {
    pub iterations: i32,
    pub contact_erp: f32,
    pub split_impulse: bool,
    pub split_impulse_penetration_threshold: f32,
}

impl BulletWorld {
    /// 读取当前 Bullet 求解器的真实参数。
    pub fn solver_settings(&self) -> Option<BulletSolverSettings> {
        let mut raw = ffi::BW_SolverSettings::default();
        let ok = unsafe { ffi::bw_world_get_solver_settings(self.ptr, &mut raw) };
        ok.then_some(BulletSolverSettings {
            iterations: raw.iterations,
            contact_erp: raw.contact_erp,
            split_impulse: raw.split_impulse,
            split_impulse_penetration_threshold: raw.split_impulse_penetration_threshold,
        })
    }

    /// 校验后一次性写入所有求解参数。
    pub fn set_solver_settings(&self, settings: BulletSolverSettings) -> bool {
        if !(1..=256).contains(&settings.iterations)
            || !settings.contact_erp.is_finite()
            || !(0.0..=1.0).contains(&settings.contact_erp)
            || !settings.split_impulse_penetration_threshold.is_finite()
            || settings.split_impulse_penetration_threshold > 0.0
        {
            return false;
        }
        let raw = ffi::BW_SolverSettings {
            iterations: settings.iterations,
            contact_erp: settings.contact_erp,
            split_impulse: settings.split_impulse,
            split_impulse_penetration_threshold:
                settings.split_impulse_penetration_threshold,
        };
        unsafe { ffi::bw_world_set_solver_settings(self.ptr, &raw) }
    }
}
