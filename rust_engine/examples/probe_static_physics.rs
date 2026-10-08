//! 固定姿态探针：对比碰撞、帧率与关节，统计稳定后的逐帧变化。
use std::{env, process::ExitCode};

use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{
    model::load_pmx,
    physics::{config::set_config, CollisionStabilityMode, MMDPhysics, PhysicsConfig, PhysicsMode},
};

struct ProbeLogger;
impl log::Log for ProbeLogger {
    fn enabled(&self, _: &log::Metadata<'_>) -> bool {
        true
    }
    fn log(&self, record: &log::Record<'_>) {
        if record.args().to_string().contains("身体初始嵌入过滤") {
            println!("{}", record.args());
        }
    }
    fn flush(&self) {}
}
static LOGGER: ProbeLogger = ProbeLogger;

#[derive(Default, Clone)]
struct MotionPeak {
    distance: f32,
    angle: f32,
    speed: f32,
    angular_speed: f32,
    samples: usize,
    distance_sum: f32,
}

fn pose(body: &mmd_engine::physics::bullet_ffi::BulletRigidBody) -> Mat4 {
    if env::args().any(|arg| arg == "--simulation-pose") {
        body.get_simulation_transform()
    } else {
        body.get_transform()
    }
}

fn run(path: &str, label: &str, collision: bool, fps: f32, mode: CollisionStabilityMode) {
    let mut config = PhysicsConfig::default();
    config.collision_enabled = collision;
    config.collision_stability_mode = mode;
    config.inertia_strength = 0.0;
    config.debug_log = env::args().any(|arg| arg == "--contacts");
    if let Some(hz) = env::args().find_map(|arg| {
        arg.strip_prefix("--physics-fps=")
            .and_then(|v| v.parse::<f32>().ok())
    }) {
        config.physics_fps = hz;
    }
    set_config(config);
    let mut model = load_pmx(path).expect("读取模型失败");
    if let Some(part) = env::args().find_map(|arg| arg.strip_prefix("--only=").map(str::to_owned)) {
        // 保留全部跟骨碰撞壳，重映射缩减后的关节索引。
        let mut remap = vec![None; model.rigid_bodies.len()];
        let mut next = 0;
        let mut old = 0;
        model.rigid_bodies.retain(|body| {
            let keep = body.mode == RigidBodyMode::Static || body.local_name.contains(&part);
            if keep {
                remap[old] = Some(next);
                next += 1;
            }
            old += 1;
            keep
        });
        model.joints.retain_mut(|joint| {
            let a = usize::try_from(joint.rigid_body_a_index)
                .ok()
                .and_then(|i| remap.get(i))
                .copied()
                .flatten();
            let b = usize::try_from(joint.rigid_body_b_index)
                .ok()
                .and_then(|i| remap.get(i))
                .copied()
                .flatten();
            if let (Some(a), Some(b)) = (a, b) {
                joint.rigid_body_a_index = a;
                joint.rigid_body_b_index = b;
                true
            } else {
                false
            }
        });
    }
    if env::args().any(|arg| arg == "--no-springs") {
        for joint in &mut model.joints {
            joint.position_spring = [0.0; 3];
            joint.rotation_spring = [0.0; 3];
        }
    }
    let bind: Vec<_> = model
        .bone_manager
        .links()
        .map(|b| Mat4::from_translation(b.initial_position))
        .collect();
    let mut physics = MMDPhysics::new().expect("物理世界创建失败");
    physics.build_physics(&model.rigid_bodies, &model.joints, &bind);
    if !env::args().any(|arg| arg == "--raw-body-contacts") {
        let names: Vec<_> = model.bone_manager.links().map(|b| b.name.clone()).collect();
        let parents: Vec<_> = model.bone_manager.links().map(|b| b.parent_index).collect();
        let filtered = physics.configure_embedded_body_contacts(&names, &parents);
        println!("BODY_CONTACT_FILTER pairs={filtered}");
    }
    physics.initialize(&bind);
    let mut previous: Vec<_> = physics
        .rigid_bodies
        .iter()
        .map(|r| r.bullet_body.as_ref().map(pose))
        .collect();
    let mut peaks = vec![MotionPeak::default(); previous.len()];
    // 前五秒用于垂落，随后五秒用于量化持续抖动。
    for frame in 0..(fps * 10.0) as usize {
        physics.sync_bodies(&bind);
        physics.step_simulation(1.0 / fps);
        if let Some(message) = physics.take_debug_diagnostic() {
            if frame >= (fps * 9.0) as usize {
                println!("{message}");
            }
        }
        for (i, r) in physics.rigid_bodies.iter().enumerate() {
            let Some(body) = &r.bullet_body else { continue };
            let current = pose(body);
            if frame >= (fps * 5.0) as usize && r.physics_mode != PhysicsMode::FollowBone {
                let old = previous[i].unwrap();
                let (_, q0, p0) = old.to_scale_rotation_translation();
                let (_, q1, p1) = current.to_scale_rotation_translation();
                let distance = p0.distance(p1);
                let angle = 2.0 * q0.dot(q1).abs().clamp(0.0, 1.0).acos();
                let p = &mut peaks[i];
                p.distance = p.distance.max(distance);
                p.angle = p.angle.max(angle);
                p.speed = p.speed.max(body.get_linear_velocity().length());
                p.angular_speed = p.angular_speed.max(body.get_angular_velocity().length());
                p.samples += 1;
                p.distance_sum += distance;
            }
            previous[i] = Some(current);
        }
    }
    println!(
        "\nCASE {label} render_fps={fps} bodies={} joints={}",
        model.rigid_bodies.len(),
        model.joints.len()
    );
    let mut order: Vec<_> = peaks
        .iter()
        .enumerate()
        .filter(|(_, p)| p.samples > 0)
        .collect();
    order.sort_by(|a, b| b.1.distance.total_cmp(&a.1.distance));
    for (i, p) in order.into_iter().take(18) {
        println!("BODY #{i} '{}' delta_max={:.6} delta_mean={:.6} angle_max={:.5} speed={:.4} angular_speed={:.4}",
            physics.rigid_bodies[i].name, p.distance, p.distance_sum / p.samples as f32,
            p.angle, p.speed, p.angular_speed);
    }
    // 胸部、飾品另行输出，避免被长链排名掩盖。
    for (i, r) in physics
        .rigid_bodies
        .iter()
        .enumerate()
        .filter(|(_, r)| r.name.contains('胸') || r.name.contains('乳'))
    {
        let p = &peaks[i];
        if p.samples > 0 {
            println!(
                "CHEST #{i} '{}' delta_max={:.6} angle_max={:.5} speed={:.4}",
                r.name, p.distance, p.angle, p.speed
            );
        }
    }
    let mut snapshots = physics.joint_snapshots_matching("");
    snapshots.sort_by(|a, b| b.anchor_error.total_cmp(&a.anchor_error));
    for s in snapshots.iter().take(10) {
        println!(
            "JOINT '{}' anchor_error={:.6} linear_violation={:.5} angular_violation={:.5}",
            s.name,
            s.anchor_error,
            s.diagnostic.linear_violation.length(),
            s.diagnostic.angular_violation.length()
        );
    }
    // 模式 2 保留骨骼平移，故此差值仅供观察，不作为兼容性失败判据。
    let mirror = Mat4::from_scale(Vec3::new(1.0, 1.0, -1.0));
    for r in physics
        .rigid_bodies
        .iter()
        .filter(|r| r.physics_mode == PhysicsMode::PhysicsWithBone)
    {
        let Some(body) = &r.bullet_body else { continue };
        let Some(bone) = bind.get(r.bone_index as usize) else {
            continue;
        };
        let simulated = body.get_transform();
        let bone_left = mirror * *bone * mirror;
        let rendered = r.compute_bone_matrix_rotation_only(simulated, bone_left.w_axis.truncate());
        let visual_body = r.compute_body_matrix(rendered);
        println!(
            "MODE2 '{}' visual_body_error={:.6}",
            r.name,
            visual_body
                .w_axis
                .truncate()
                .distance(simulated.w_axis.truncate())
        );
    }
}

fn main() -> ExitCode {
    let _ = log::set_logger(&LOGGER);
    log::set_max_level(log::LevelFilter::Info);
    let Some(path) = env::args().nth(1) else {
        eprintln!("用法: cargo run --example probe_static_physics -- <模型.pmx>");
        return ExitCode::FAILURE;
    };
    for (label, collision, fps, mode) in [
        (
            "stable-contact-60",
            true,
            60.0,
            CollisionStabilityMode::Stable,
        ),
        ("no-contact-60", false, 60.0, CollisionStabilityMode::Stable),
        (
            "stable-contact-144",
            true,
            144.0,
            CollisionStabilityMode::Stable,
        ),
    ] {
        run(&path, label, collision, fps, mode);
        if env::args().any(|arg| arg == "--single") {
            break;
        }
    }
    mmd_engine::physics::config::reset_config();
    ExitCode::SUCCESS
}
