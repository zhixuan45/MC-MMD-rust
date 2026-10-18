//! 求解器单因子对照仅修改探针实例，不写入游戏配置。
use super::*;

#[derive(Debug, Default)]
pub(super) struct SolverOverrides {
    iterations: Option<i32>,
    contact_erp: Option<f32>,
    split_impulse: Option<bool>,
    joint_stop_erp: Option<f32>,
}

impl SolverOverrides {
    pub fn parse(
        &mut self,
        key: &str,
        args: &mut std::iter::Skip<std::env::Args>,
    ) -> Result<(), String> {
        let raw = args.next().ok_or_else(|| format!("{key} 缺少值"))?;
        match key {
            "--solver-iterations" => self.iterations = Some(raw.parse().map_err(|_| "迭代次数必须为整数")?),
            "--contact-erp" => self.contact_erp = Some(raw.parse().map_err(|_| "接触 ERP 必须为数字")?),
            "--joint-stop-erp" => self.joint_stop_erp = Some(raw.parse().map_err(|_| "限位 ERP 必须为数字")?),
            "--split-impulse" => self.split_impulse = Some(raw.parse().map_err(|_| "split-impulse 必须为 true/false")?),
            _ => return Err(format!("未知求解器参数：{key}")),
        }
        Ok(())
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.iterations.is_some_and(|v| !(1..=256).contains(&v)) {
            return Err("求解器迭代次数必须在 1～256 之间".to_owned());
        }
        if [self.contact_erp, self.joint_stop_erp].into_iter().flatten()
            .any(|v| !v.is_finite() || !(0.0..=1.0).contains(&v)) {
            return Err("ERP 必须为 0～1 的有限数".to_owned());
        }
        Ok(())
    }

    pub fn active(&self) -> bool {
        self.iterations.is_some() || self.contact_erp.is_some()
            || self.split_impulse.is_some() || self.joint_stop_erp.is_some()
    }

    pub fn apply(&self, model: &mut MmdModel) -> Result<Value, String> {
        if !self.active() {
            return Ok(Value::Null);
        }
        if !model.configure_physics_solver_diagnostic(
            self.iterations, self.contact_erp, self.split_impulse, self.joint_stop_erp,
        ) {
            return Err("探针求解器参数应用失败".to_owned());
        }
        Ok(json!({"diagnostic_only":true,"iterations":self.iterations,
            "contact_erp":self.contact_erp,"split_impulse":self.split_impulse,
            "joint_stop_erp":self.joint_stop_erp,"unspecified_fields":"unchanged"}))
    }
}
