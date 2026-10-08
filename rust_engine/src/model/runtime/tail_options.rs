use super::*;

impl MmdModel {
    /// 设置当前模型实例的尾巴受力选项。
    pub fn set_tail_physics_options(&mut self, idle_lift: bool, movement_boost: bool) {
        self.tail_idle_lift = idle_lift;
        self.tail_movement_boost = movement_boost;
        if let Some(physics) = self.physics.as_mut() {
            physics.set_tail_physics_options(idle_lift, movement_boost);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_models_enable_tail_effects_and_allow_explicit_disable() {
        let mut model = MmdModel::new();
        assert!(model.tail_idle_lift && model.tail_movement_boost);
        model.set_tail_physics_options(false, false);
        assert!(!model.tail_idle_lift && !model.tail_movement_boost);
    }
}
