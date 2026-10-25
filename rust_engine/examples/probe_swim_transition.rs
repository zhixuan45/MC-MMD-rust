//! 用原始 PMX 和游戏 VMD 对比起身期间的物理输入连续性，不修改模型参数。
use std::{env, fs, path::Path, sync::Arc};

use glam::{Mat4, Vec3};
use mmd_engine::{
    animation::{VmdAnimation, VmdFile},
    model::{load_pmx, MmdModel},
    physics::{set_config, CollisionStabilityMode, PhysicsConfig},
};
use serde_json::json;

fn animation(path: &Path) -> Arc<VmdAnimation> {
    Arc::new(VmdAnimation::from_vmd_file(VmdFile::load(path).unwrap()))
}

fn pelvis(model: &MmdModel) -> Mat4 {
    model
        .physics_body_snapshots_matching("下半身")
        .into_iter()
        .find(|b| b.name == "下半身")
        .unwrap()
        .simulation_transform
}

fn delta(a: Mat4, b: Mat4) -> (f32, f32) {
    let (_, qa, pa) = a.to_scale_rotation_translation();
    let (_, qb, pb) = b.to_scale_rotation_translation();
    (
        pa.distance(pb),
        2.0 * qa.dot(qb).abs().min(1.0).acos().to_degrees(),
    )
}

fn tick(model: &mut MmdModel, dt: f32) {
    model.set_model_position_and_yaw(0.0, 0.0, 0.0, 0.0);
    model.tick_animation_no_skinning(dt);
}

fn main() {
    let args: Vec<_> = env::args().collect();
    assert_eq!(
        args.len(),
        4,
        "usage: probe_swim_transition <model.pmx> <DefaultAnim> <output.json>"
    );
    let anim_dir = Path::new(&args[2]);
    let idle = animation(&anim_dir.join("idle.vmd"));
    let swim = animation(&anim_dir.join("swim.vmd"));
    set_config(PhysicsConfig {
        inertia_strength: 0.3,
        collision_stability_mode: CollisionStabilityMode::Strict,
        auto_upper_body_colliders: false,
        auto_lower_body_colliders: false,
        ..PhysicsConfig::default()
    });
    let mut cases = Vec::new();
    for fps in [30, 60, 144] {
        for phase in [0.0, 0.5] {
            let mut model = load_pmx(&args[1]).unwrap();
            model.set_auto_blink_enabled(false);
            model.transition_layer_to(0, Some(idle.clone()), 0.0);
            if !model.has_physics() {
                assert!(model.init_physics());
            }
            let dt = 1.0 / fps as f32;
            for _ in 0..fps * 2 {
                tick(&mut model, dt);
            }
            let reference: Vec<_> = model
                .physics_body_snapshots_matching("スカート")
                .into_iter()
                .map(|b| {
                    pelvis(&model)
                        .inverse()
                        .transform_point3(b.simulation_transform.w_axis.truncate())
                })
                .collect();
            model.transition_layer_to(0, Some(swim.clone()), 0.25);
            for _ in 0..((3.0 + phase) * fps as f32) as usize {
                tick(&mut model, dt);
            }
            let mut previous = pelvis(&model);
            model.transition_layer_to(0, Some(idle.clone()), 0.25);
            let mut frames = Vec::new();
            let mut max_jump = 0.0_f32;
            let mut max_angle = 0.0_f32;
            let mut invalid = 0;
            for frame in 0..fps * 3 {
                tick(&mut model, dt);
                let current = pelvis(&model);
                let (jump, angle) = delta(previous, current);
                max_jump = max_jump.max(jump);
                max_angle = max_angle.max(angle);
                let cloth = model.physics_body_snapshots_matching("スカート");
                invalid += cloth
                    .iter()
                    .filter(|b| {
                        !b.simulation_transform.is_finite()
                            || !b.linear_velocity.is_finite()
                            || !b.angular_velocity.is_finite()
                    })
                    .count();
                if frame < (fps as f32 * 0.3) as i32 || frame == fps - 1 || frame == fps * 3 - 1 {
                    let inv = current.inverse();
                    let displacement: Vec<_> = cloth
                        .iter()
                        .zip(&reference)
                        .map(|(b, rest)| {
                            inv.transform_point3(b.simulation_transform.w_axis.truncate())
                                .distance(*rest)
                        })
                        .collect();
                    let mean = displacement.iter().sum::<f32>() / displacement.len() as f32;
                    frames.push(json!({"time":(frame + 1) as f32 * dt,"pelvis_jump":jump,
                        "pelvis_angle_deg":angle,"skirt_mean_distance_from_idle":mean,
                        "skirt_max_distance_from_idle":displacement.into_iter().fold(0.0_f32,f32::max),
                        "pelvis_position": Vec3::from(current.w_axis.truncate()).to_array()}));
                }
                previous = current;
            }
            println!("fps={fps} phase={phase} pelvis_jump={max_jump:.5} angle_deg={max_angle:.5} invalid={invalid}");
            cases.push(
                json!({"fps":fps,"swim_phase_seconds":phase,"max_pelvis_jump":max_jump,
                "max_pelvis_angle_deg":max_angle,"invalid_skirt_samples":invalid,"frames":frames}),
            );
        }
    }
    fs::write(&args[3], serde_json::to_string_pretty(&json!({"model":args[1],
        "evidence":"offline Bullet probe; proxy movement is not visual mesh acceptance", "cases":cases})).unwrap()).unwrap();
}
