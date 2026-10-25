use super::*;

impl MmdModel {
    /// 尾巴位移使用方块单位；比例与实际渲染保持一致。
    pub fn set_tail_physics_scale(&mut self, blocks_per_model_unit: f32) {
        if !blocks_per_model_unit.is_finite() || blocks_per_model_unit <= 1e-6 {
            return;
        }
        self.tail_blocks_per_model_unit = blocks_per_model_unit;
        #[cfg(not(feature = "legacy-physics"))]
        if let Some(physics) = self.physics.as_mut() {
            physics.set_tail_physics_scale(blocks_per_model_unit);
        }
    }

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
    fn render_scale_changes_do_not_rescale_world_position_or_accept_invalid_values() {
        let mut model = MmdModel::new();
        model.set_model_position_and_yaw(2.0, 3.0, 4.0, 0.0);
        model.set_tail_physics_scale(0.18);
        assert_eq!(model.tail_blocks_per_model_unit, 0.18);
        assert_eq!(
            model.model_transform().w_axis.truncate(),
            Vec3::new(2.0, 3.0, 4.0)
        );
        for scale in [0.0, -0.1, f32::NAN, f32::INFINITY] {
            model.set_tail_physics_scale(scale);
            assert_eq!(model.tail_blocks_per_model_unit, 0.18);
        }
    }

    #[test]
    fn new_models_enable_tail_effects_and_allow_explicit_disable() {
        let mut model = MmdModel::new();
        assert!(model.tail_idle_lift && model.tail_movement_boost);
        model.set_tail_physics_options(false, false);
        assert!(!model.tail_idle_lift && !model.tail_movement_boost);
    }
}
