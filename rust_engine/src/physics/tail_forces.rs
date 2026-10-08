//! 尾巴闲置轻抬与全向移动增强。

use glam::Vec3;
use std::sync::atomic::{AtomicU64, Ordering};

const LIFT_GRAVITY_MIN: f32 = 0.08;
const LIFT_GRAVITY_MAX: f32 = 0.14;
// 在原有首版 2 倍力度上再乘 4。
const IDLE_LIFT_MULTIPLIER: f32 = 8.0;
const MOVEMENT_FORCE_MULTIPLIER: f32 = 1.5;
const RESTORE_SECONDS: f32 = 0.55;
static NEXT_TAIL_SEED: AtomicU64 = AtomicU64::new(0x9E37_79B9_7F4A_7C15);

#[derive(Debug, Clone)]
pub(crate) struct TailForceState {
    pub idle_lift: bool,
    pub movement_boost: bool,
    rng: u64,
    time_to_next_lift: f32,
    lift_age: Option<f32>,
    lift_duration: f32,
    lift_accel: f32,
    movement_fade: f32,
    wave_delays: Vec<f32>,
    max_wave_delay: f32,
}

impl Default for TailForceState {
    fn default() -> Self {
        Self::with_seed(NEXT_TAIL_SEED.fetch_add(0xA076_1D64_78BD_642F, Ordering::Relaxed))
    }
}

impl TailForceState {
    fn with_seed(seed: u64) -> Self {
        Self {
            idle_lift: true,
            movement_boost: true,
            rng: seed.max(1),
            time_to_next_lift: 1.2,
            lift_age: None,
            lift_duration: 0.7,
            lift_accel: 0.0,
            movement_fade: 0.0,
            wave_delays: Vec::new(),
            max_wave_delay: 0.0,
        }
    }

    pub fn set_options(&mut self, idle_lift: bool, movement_boost: bool) {
        self.idle_lift = idle_lift;
        self.movement_boost = movement_boost;
        if !idle_lift {
            self.lift_age = None;
            // 关闭整个闲置力，清掉正在播放的抬起包络。
            self.lift_accel = 0.0;
            self.time_to_next_lift = 1.2;
        }
    }

    pub fn set_wave_delays(&mut self, mut delays: Vec<f32>) {
        for delay in &mut delays {
            if !delay.is_finite() || *delay < 0.0 {
                *delay = 0.0;
            }
        }
        self.max_wave_delay = delays.iter().copied().fold(0.0_f32, f32::max);
        self.wave_delays = delays;
        self.reset_envelope();
    }

    pub fn update(&mut self, delta_time: f32, local_velocity: Vec3, gravity_magnitude: f32) -> f32 {
        if !delta_time.is_finite() || delta_time <= 0.0 {
            self.reset_envelope();
            return 0.0;
        }

        let dt = delta_time;
        let speed = local_velocity.length().min(200.0);
        let target_fade = (speed / 2.0).clamp(0.0, 1.0);
        let fade_step = (dt
            / if target_fade > self.movement_fade {
                0.2
            } else {
                RESTORE_SECONDS
            })
        .clamp(0.0, 1.0);
        self.movement_fade += (target_fade - self.movement_fade) * fade_step;

        if !self.idle_lift {
            return 0.0;
        }

        let mut remaining = dt;
        while remaining > 0.0 {
            if let Some(age) = self.lift_age {
                // 等尾尖的延迟脉冲播完，再开始下一次等待。
                let until_end = (self.lift_duration + self.max_wave_delay - age).max(0.0);
                let consumed = remaining.min(until_end);
                self.lift_age = Some(age + consumed);
                remaining -= consumed;
                if consumed < until_end {
                    break;
                }
                self.lift_age = None;
                self.time_to_next_lift = self.next_wait();
            } else if remaining >= self.time_to_next_lift {
                remaining -= self.time_to_next_lift;
                self.lift_duration = 0.45 + self.random_unit() * 0.4;
                self.lift_accel = gravity_magnitude.abs()
                    * (LIFT_GRAVITY_MIN
                        + self.random_unit() * (LIFT_GRAVITY_MAX - LIFT_GRAVITY_MIN));
                self.lift_age = Some(0.0);
            } else {
                self.time_to_next_lift -= remaining;
                break;
            }
        }

        self.delayed_lift(0.0)
    }

    fn delayed_lift(&self, delay: f32) -> f32 {
        if !self.idle_lift {
            return 0.0;
        }
        let Some(age) = self.lift_age else {
            return 0.0;
        };
        let phase = ((age - delay) / self.lift_duration).clamp(0.0, 1.0);
        let envelope = (std::f32::consts::PI * phase).sin().max(0.0);
        self.lift_accel * envelope * (1.0 - self.movement_fade)
    }

    pub fn idle_lift_acceleration(
        &mut self,
        delta_time: f32,
        local_velocity: Vec3,
        gravity_magnitude: f32,
        inertia_strength: f32,
    ) -> f32 {
        self.update(delta_time, local_velocity, gravity_magnitude)
            * inertia_strength.max(0.0)
            * IDLE_LIFT_MULTIPLIER
    }

    /// 每帧只推进一次，返回全链当前最大抬起力。
    pub fn advance_idle_wave(
        &mut self,
        delta_time: f32,
        local_velocity: Vec3,
        gravity_magnitude: f32,
        inertia_strength: f32,
    ) -> f32 {
        let root = self.idle_lift_acceleration(
            delta_time,
            local_velocity,
            gravity_magnitude,
            inertia_strength,
        );
        self.wave_delays
            .iter()
            .copied()
            .map(|delay| {
                self.delayed_lift(delay) * inertia_strength.max(0.0) * IDLE_LIFT_MULTIPLIER
            })
            .fold(root, f32::max)
    }

    pub fn idle_lift_for_body(&self, body_index: usize, inertia_strength: f32) -> f32 {
        let delay = self.wave_delays.get(body_index).copied().unwrap_or(0.0);
        self.delayed_lift(delay) * inertia_strength.max(0.0) * IDLE_LIFT_MULTIPLIER
    }

    pub fn boost_movement_acceleration(&self, acceleration: Vec3) -> Vec3 {
        if self.movement_boost {
            acceleration * MOVEMENT_FORCE_MULTIPLIER
        } else {
            acceleration
        }
    }

    pub fn reset(&mut self) {
        self.rng = self.rng.rotate_left(17) ^ 0xA076_1D64_78BD_642F;
        self.reset_envelope();
    }

    fn reset_envelope(&mut self) {
        self.time_to_next_lift = 1.2;
        self.lift_age = None;
        self.movement_fade = 0.0;
    }

    fn next_wait(&mut self) -> f32 {
        1.6 + self.random_unit() * 2.2
    }

    fn random_unit(&mut self) -> f32 {
        self.rng ^= self.rng << 13;
        self.rng ^= self.rng >> 7;
        self.rng ^= self.rng << 17;
        (self.rng as u32) as f32 / u32::MAX as f32
    }
}

#[cfg(test)]
mod tests {
    use super::TailForceState;
    use glam::Vec3;

    #[test]
    fn idle_wave_reaches_each_segment_after_its_delay_and_finishes_at_the_tip() {
        let mut state = TailForceState::with_seed(17);
        state.set_options(true, false);
        state.set_wave_delays(vec![0.0, 0.3, 0.6]);
        let mut samples = vec![Vec::new(); 3];
        let mut saw_tip_after_root = false;
        for _ in 0..180 {
            let active = state.advance_idle_wave(1.0 / 60.0, Vec3::ZERO, 98.0, 0.5);
            for (index, track) in samples.iter_mut().enumerate() {
                let lift = state.idle_lift_for_body(index, 0.5);
                assert!(lift >= 0.0 && lift <= 98.0 * 0.14 * 0.5 * 8.0);
                track.push(lift);
            }
            if samples[0].last() == Some(&0.0) && *samples[2].last().unwrap() > 0.0 {
                assert!(active > 0.0, "尾根播完后仍需给尾尖施力");
                saw_tip_after_root = true;
            }
        }
        assert!(saw_tip_after_root);
        assert!(samples[0].iter().any(|force| *force > 0.0));
        // 三段播放同一个平滑脉冲，分别错开 18 帧和 36 帧。
        for index in 0..144 {
            assert!((samples[0][index] - samples[1][index + 18]).abs() < 0.002);
            assert!((samples[0][index] - samples[2][index + 36]).abs() < 0.002);
        }
    }

    #[test]
    fn disabling_idle_clears_delayed_segments_and_reset_preserves_the_switch() {
        let mut state = TailForceState::with_seed(17);
        state.set_wave_delays(vec![0.0, 0.3, 0.6]);
        for _ in 0..120 {
            state.advance_idle_wave(1.0 / 60.0, Vec3::ZERO, 98.0, 0.5);
        }
        assert!(state.idle_lift_for_body(2, 0.5) > 0.0);
        state.set_options(false, true);
        for _ in 0..240 {
            assert_eq!(
                state.advance_idle_wave(1.0 / 60.0, Vec3::ZERO, 98.0, 0.5),
                0.0
            );
            for index in 0..3 {
                assert_eq!(state.idle_lift_for_body(index, 0.5), 0.0);
            }
        }
        state.reset();
        assert_eq!(state.advance_idle_wave(2.0, Vec3::ZERO, 98.0, 0.5), 0.0);
    }

    #[test]
    fn wave_pulse_integral_is_stable_at_different_frame_rates() {
        fn integral(fps: usize) -> [f32; 3] {
            let mut state = TailForceState::with_seed(17);
            state.set_wave_delays(vec![0.0, 0.3, 0.6]);
            let dt = 1.0 / fps as f32;
            let mut total = [0.0; 3];
            for _ in 0..fps * 3 {
                state.advance_idle_wave(dt, Vec3::ZERO, 98.0, 0.5);
                for (index, sum) in total.iter_mut().enumerate() {
                    *sum += state.idle_lift_for_body(index, 0.5) * dt;
                }
            }
            total
        }
        let reference = integral(120);
        for fps in [30, 60] {
            for (actual, expected) in integral(fps).into_iter().zip(reference) {
                assert!(expected > 0.0);
                assert!((actual - expected).abs() < expected * 0.03);
            }
        }
    }

    #[test]
    fn idle_lift_is_shared_deterministic_and_stops_cleanly() {
        let mut a = TailForceState::with_seed(17);
        let mut b = TailForceState::with_seed(17);
        a.set_options(true, false);
        b.set_options(true, false);
        let samples_a: Vec<_> = (0..240)
            .map(|_| a.update(1.0 / 60.0, Vec3::ZERO, 98.0))
            .collect();
        let samples_b: Vec<_> = (0..240)
            .map(|_| b.update(1.0 / 60.0, Vec3::ZERO, 98.0))
            .collect();
        assert_eq!(samples_a, samples_b);
        assert!(samples_a.iter().any(|v| *v > 0.0));
        assert!(samples_a.iter().any(|v| *v == 0.0));
    }

    #[test]
    fn disabling_idle_lift_stops_the_force_even_with_movement_boost() {
        for boost in [false, true] {
            let mut state = TailForceState::with_seed(17);
            state.set_options(true, boost);
            assert!((0..180).any(|_| state.idle_lift_acceleration(
                1.0 / 60.0,
                Vec3::ZERO,
                98.0,
                0.5
            ) > 0.0));
            state.set_options(false, boost);
            assert!(state.lift_age.is_none());
            assert_eq!(state.lift_accel, 0.0);
            for _ in 0..600 {
                assert_eq!(
                    state.idle_lift_acceleration(1.0 / 60.0, Vec3::ZERO, 98.0, 0.5),
                    0.0
                );
            }
            // 重置不能把已关闭的开关重新打开。
            state.reset();
            assert_eq!(
                state.idle_lift_acceleration(1.5, Vec3::ZERO, 98.0, 0.5),
                0.0
            );
        }
    }

    #[test]
    fn idle_lift_is_four_times_the_prior_force_without_changing_the_pulse() {
        for boost in [false, true] {
            let mut envelope = TailForceState::with_seed(17);
            let mut raised = TailForceState::with_seed(17);
            envelope.set_options(true, boost);
            raised.set_options(true, boost);
            let mut saw_pulse = false;
            for _ in 0..720 {
                let prior = envelope.update(1.0 / 60.0, Vec3::ZERO, 98.0) * 0.5 * 2.0;
                let current = raised.idle_lift_acceleration(1.0 / 60.0, Vec3::ZERO, 98.0, 0.5);
                assert_eq!(current, prior * 4.0);
                saw_pulse |= current > 0.0;
            }
            assert!(saw_pulse, "必须实际覆盖抬起脉冲");
        }
    }
}
