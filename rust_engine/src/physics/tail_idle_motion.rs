//! 全部输入/输出采用方块、秒和方块/秒²。
//! 仅在闲置波形内用附加力接近尾段位移目标，不改写刚体或骨骼变换。
use glam::Vec3;

#[derive(Clone, Default)]
pub(crate) struct TailIdleMotion {
    rest_position: Option<Vec3>,
    pub acceleration: Vec3,
}

impl TailIdleMotion {
    /// 仅在 Bullet 边界换算一次；内部目标、位置、速度和反馈均为方块单位。
    pub fn update_in_model_space(
        &mut self,
        position: Vec3,
        velocity: Vec3,
        offset_blocks: Vec3,
        influence: f32,
        gravity: f32,
        blocks_per_model_unit: f32,
    ) -> Vec3 {
        self.update(
            position * blocks_per_model_unit,
            velocity * blocks_per_model_unit,
            offset_blocks,
            influence,
            gravity * blocks_per_model_unit,
        ) / blocks_per_model_unit
    }
    pub fn observe_rest(&mut self, position: Vec3) {
        self.rest_position = Some(position);
        self.acceleration = Vec3::ZERO;
    }

    pub fn update(
        &mut self,
        position: Vec3,
        velocity: Vec3,
        offset: Vec3,
        influence: f32,
        gravity: f32,
    ) -> Vec3 {
        if !position.is_finite() || !velocity.is_finite() || !offset.is_finite() {
            self.acceleration = Vec3::ZERO;
            return Vec3::ZERO;
        }
        if offset.length_squared() < 1e-10 {
            self.acceleration = Vec3::ZERO;
            return Vec3::ZERO;
        }
        let rest = *self.rest_position.get_or_insert(position);
        let blend = (offset.y * 8.0).clamp(0.0, 1.0);
        // 只在当前包络内叠加弹簧、阻尼和重力补偿，让关节继续决定实际姿态。
        let error = rest + offset * influence - position;
        self.acceleration = ((error * Vec3::new(400.0, 500.0, 1000.0)
            - velocity * Vec3::new(25.0, 30.0, 45.0))
            * blend
            + Vec3::Y * gravity * offset.y * influence)
            .clamp_length_max(72.0);
        self.acceleration
    }
}

#[cfg(test)]
mod tests {
    use super::TailIdleMotion;
    use glam::Vec3;

    #[test]
    fn different_render_scales_preserve_the_same_world_acceleration() {
        let position_blocks = Vec3::new(0.0, 0.2, 0.1);
        let velocity_blocks = Vec3::new(0.0, 0.1, 0.2);
        let offset_blocks = Vec3::new(0.0, 1.0, 0.5);
        let mut reference = TailIdleMotion::default();
        reference.observe_rest(Vec3::ZERO);
        let expected = reference.update(position_blocks, velocity_blocks, offset_blocks, 1.0, 9.8);
        for scale in [0.045, 0.09, 0.18] {
            let mut actual = TailIdleMotion::default();
            actual.observe_rest(Vec3::ZERO);
            let model_acceleration = actual.update_in_model_space(
                position_blocks / scale,
                velocity_blocks / scale,
                offset_blocks,
                1.0,
                9.8 / scale,
                scale,
            );
            assert!((model_acceleration * scale).abs_diff_eq(expected, 1e-4));
            assert!(actual.acceleration.abs_diff_eq(expected, 1e-4));
        }
    }

    #[test]
    fn delayed_segment_keeps_the_pre_wave_reference_until_its_turn() {
        let mut state = TailIdleMotion::default();
        state.observe_rest(Vec3::ZERO);
        // 前段已开始带动尾尖，但尾尖自己的波形尚未到达。
        assert_eq!(
            state.update(Vec3::Z, Vec3::ZERO, Vec3::ZERO, 1.0, 98.0),
            Vec3::ZERO
        );
        let offset = Vec3::new(0.0, 1.0, 0.5);
        let actual = state.update(Vec3::ZERO, Vec3::ZERO, offset, 1.0, 98.0);
        let mut reference = TailIdleMotion::default();
        reference.observe_rest(Vec3::ZERO);
        assert_eq!(
            actual,
            reference.update(Vec3::ZERO, Vec3::ZERO, offset, 1.0, 98.0)
        );
    }

    #[test]
    fn feedback_opposes_overshoot_and_stops_when_the_pulse_ends() {
        let mut state = TailIdleMotion::default();
        state.observe_rest(Vec3::ZERO);
        let offset = Vec3::new(0.0, 1.0, 0.5);
        let force = state.update(Vec3::new(0.0, 3.0, 3.0), Vec3::ONE, offset, 1.0, 98.0);
        assert!(force.y < 0.0 && force.z < 0.0);
        assert!(force.length() <= 72.01);
        assert_eq!(
            state.update(Vec3::ONE, Vec3::ONE, Vec3::ZERO, 1.0, 98.0),
            Vec3::ZERO
        );
    }
}
