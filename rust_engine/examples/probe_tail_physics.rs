//! 用完整 MmdModel 比较尾链闲置抬升与恒速移动响应。

use std::{collections::BTreeMap, env, path::PathBuf, process::ExitCode, sync::Arc};

use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{
    animation::VmdAnimation,
    model::{load_pmx, MmdModel},
    physics::config::{reset_config, set_config, PhysicsConfig},
};

const WARM_SECONDS: usize = 5;
const SAMPLE_SECONDS: usize = 5;
const FPS: usize = 60;
const TAIL_NEEDLE: &str = "Sp_Hi_Tail0_B";

#[derive(Clone)]
struct TailBone {
    body_name: String,
    bone_name: String,
    bone_index: usize,
    mass: f32,
}

#[derive(Clone, Default)]
struct BoneMotion {
    samples: usize,
    invalid: usize,
    position_sum: Vec3,
    max_delta: f32,
    max_up: f32,
    min_up: f32,
    max_angle: f32,
    bind_angle_max: f32,
}

#[derive(Default)]
struct JointPeak {
    linear: f32,
    linear_name: String,
    linear_bodies: String,
    linear_vector: Vec3,
    angular: f32,
    angular_name: String,
    angular_bodies: String,
    angular_vector: Vec3,
    anchor: f32,
    anchor_name: String,
    anchor_bodies: String,
}

struct Scenario {
    label: &'static str,
    speed_blocks_per_second: f32,
    idle_lift: bool,
    movement_boost: bool,
}

fn is_tail_name(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.contains("tail")
        || name.contains("尻尾")
        || name.contains("しっぽ")
        || name.contains("尾巴")
}

fn collect_tail_bones(model: &MmdModel) -> Vec<TailBone> {
    let links: Vec<_> = model.bone_manager.links().collect();
    let mut selected = BTreeMap::new();
    for body in &model.rigid_bodies {
        if body.mode != RigidBodyMode::Dynamic || body.bone_index < 0 {
            continue;
        }
        if !is_tail_name(&body.local_name) && !is_tail_name(&body.universal_name) {
            continue;
        }
        let bone_index = body.bone_index as usize;
        let Some(bone) = links.get(bone_index) else {
            continue;
        };
        selected.entry(bone_index).or_insert_with(|| TailBone {
            body_name: body.local_name.clone(),
            bone_name: bone.name.clone(),
            bone_index,
            mass: body.mass,
        });
    }
    selected.into_values().collect()
}

fn capture_tail(model: &MmdModel, bones: &[TailBone]) -> Vec<Mat4> {
    bones
        .iter()
        .map(|bone| model.bone_manager.get_global_transform(bone.bone_index))
        .collect()
}

fn rotation_delta(a: Mat4, b: Mat4) -> f32 {
    let (_, qa, _) = a.to_scale_rotation_translation();
    let (_, qb, _) = b.to_scale_rotation_translation();
    2.0 * qa.dot(qb).abs().clamp(0.0, 1.0).acos()
}

fn observe_joints(model: &MmdModel, peak: &mut JointPeak) {
    for joint in model.physics_joint_snapshots_matching(TAIL_NEEDLE) {
        let linear = joint.diagnostic.linear_violation;
        let angular = joint.diagnostic.angular_violation;
        let linear_len = linear.length();
        let angular_len = angular.length();
        if linear_len > peak.linear {
            peak.linear = linear_len;
            peak.linear_name = joint.name.clone();
            peak.linear_bodies = format!("{}/{}", joint.body_a_name, joint.body_b_name);
            peak.linear_vector = linear;
        }
        if angular_len > peak.angular {
            peak.angular = angular_len;
            peak.angular_name = joint.name.clone();
            peak.angular_bodies = format!("{}/{}", joint.body_a_name, joint.body_b_name);
            peak.angular_vector = angular;
        }
        if joint.anchor_error > peak.anchor {
            peak.anchor = joint.anchor_error;
            peak.anchor_name = joint.name;
            peak.anchor_bodies = format!("{}/{}", joint.body_a_name, joint.body_b_name);
        }
    }
}

fn run_case(path: &str, scenario: &Scenario, animate: bool) -> Result<String, String> {
    let mut config = PhysicsConfig::default();
    config.physics_fps = 60.0;
    config.inertia_strength = 0.5;
    config.static_collider_scale = 0.1;
    set_config(config);

    let mut model = load_pmx(path).map_err(|e| e.to_string())?;
    model.set_tail_physics_options(scenario.idle_lift, scenario.movement_boost);
    let animation_name = if !animate {
        "none"
    } else if scenario.label.starts_with("walk") {
        "walk"
    } else if scenario.label.starts_with("run") {
        "sprint"
    } else {
        "idle"
    };
    if animate {
        let animation_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../neoforge/run/client/3d-skin/DefaultAnim")
            .join(format!("{animation_name}.vmd"));
        let animation = VmdAnimation::load(animation_path).map_err(|error| error.to_string())?;
        model.set_layer_animation(0, Some(Arc::new(animation)));
        model.set_layer_loop(0, true);
        model.play_layer(0);
    }
    model.update_node_animation(false);
    model.set_model_position_and_yaw(0.0, 0.0, 0.0, 0.0);
    let tail_bones = collect_tail_bones(&model);
    if tail_bones.is_empty() {
        return Err(format!("没有识别到动态尾骨: {path}"));
    }
    let bind = capture_tail(&model, &tail_bones);
    if !model.init_physics() {
        return Err(format!("物理初始化失败: {path}"));
    }

    let dt = 1.0 / FPS as f32;
    let mut elapsed = 0.0_f32;
    for _ in 0..(WARM_SECONDS * FPS) {
        let z = scenario.speed_blocks_per_second * elapsed;
        model.set_model_position_and_yaw(0.0, 0.0, z, 0.0);
        model.tick_animation_no_skinning(dt);
        elapsed += dt;
    }
    let baseline = capture_tail(&model, &tail_bones);
    let baseline_span = baseline
        .last()
        .zip(baseline.first())
        .map(|(tip, root)| tip.w_axis.truncate() - root.w_axis.truncate())
        .unwrap_or(Vec3::ZERO);
    let baseline_tip_y = baseline.last().map_or(0.0, |tip| tip.w_axis.truncate().y);
    let mut motions = vec![BoneMotion::default(); tail_bones.len()];
    let mut span_drift_angle_max = 0.0_f32;
    let mut inclination_max = 0.0_f32;
    let mut span_angle_min = f32::INFINITY;
    let mut span_angle_sum = 0.0_f64;
    let mut span_samples = 0_usize;
    let mut root_yz_sum = Vec3::ZERO;
    let mut tip_yz_sum = Vec3::ZERO;
    let mut span_yz_sum = Vec3::ZERO;
    let mut tip_up_max = 0.0_f32;
    let mut tip_up_min = 0.0_f32;
    let mut joints = JointPeak::default();

    for _ in 0..(SAMPLE_SECONDS * FPS) {
        let z = scenario.speed_blocks_per_second * elapsed;
        model.set_model_position_and_yaw(0.0, 0.0, z, 0.0);
        model.tick_animation_no_skinning(dt);
        elapsed += dt;
        let current = capture_tail(&model, &tail_bones);
        for (index, ((before, now), bind_transform)) in
            baseline.iter().zip(&current).zip(&bind).enumerate()
        {
            if !before.is_finite() || !now.is_finite() || !bind_transform.is_finite() {
                motions[index].invalid += 1;
                continue;
            }
            let start = before.w_axis.truncate();
            let position = now.w_axis.truncate();
            let delta = position.distance(start);
            let up = position.y - start.y;
            let angle = rotation_delta(*before, *now);
            let bind_angle = rotation_delta(*bind_transform, *now);
            if !delta.is_finite()
                || !up.is_finite()
                || !angle.is_finite()
                || !bind_angle.is_finite()
            {
                motions[index].invalid += 1;
                continue;
            }
            let motion = &mut motions[index];
            motion.samples += 1;
            motion.position_sum += position;
            motion.max_delta = motion.max_delta.max(delta);
            motion.max_up = motion.max_up.max(up);
            motion.min_up = motion.min_up.min(up);
            motion.max_angle = motion.max_angle.max(angle);
            motion.bind_angle_max = motion.bind_angle_max.max(bind_angle);
        }
        if let (Some(root), Some(tip)) = (current.first(), current.last()) {
            let root_position = root.w_axis.truncate();
            let tip_position = tip.w_axis.truncate();
            let span = tip_position - root_position;
            let denom = (baseline_span.length() * span.length()).max(1e-6);
            let angle = (baseline_span.dot(span) / denom).clamp(-1.0, 1.0).acos();
            span_drift_angle_max = span_drift_angle_max.max(angle);
            let downward_angle = (span.dot(-Vec3::Y) / span.length().max(1e-6))
                .clamp(-1.0, 1.0)
                .acos();
            span_angle_min = span_angle_min.min(downward_angle);
            inclination_max = inclination_max.max(downward_angle);
            span_angle_sum += downward_angle as f64;
            span_samples += 1;
            root_yz_sum += root_position;
            tip_yz_sum += tip_position;
            span_yz_sum += span;
            let tip_up = tip_position.y - baseline_tip_y;
            tip_up_max = tip_up_max.max(tip_up);
            tip_up_min = tip_up_min.min(tip_up);
        }
        observe_joints(&model, &mut joints);
    }

    let mut out = format!(
        "CASE model={:?} scenario={} probe_input_speed_blocks_s={:.2} expected_model_units_s={:.2} idle_lift={} movement_boost={} physics_fps=60 inertia_strength=0.5 static_collider_scale=0.1 warmup_s={} sample_s={} tail_dynamic_bones={} tail_joint_count={} input_source=constant_probe_transform\n",
        path,
        scenario.label,
        scenario.speed_blocks_per_second,
        scenario.speed_blocks_per_second * 10.0,
        scenario.idle_lift,
        scenario.movement_boost,
        WARM_SECONDS,
        SAMPLE_SECONDS,
        tail_bones.len(),
        model.physics_joint_snapshots_matching(TAIL_NEEDLE).len(),
    );
    out.push_str(&format!("ANIMATION default_vmd={animation_name}\n"));
    out.push_str(&format!(
        "TAIL_SPAN post_warmup_drift_angle_max={:.6} inclination_down_deg_mean={:.3} inclination_down_deg_min={:.3} inclination_down_deg_max={:.3} root_yz_mean=({:.5},{:.5}) tip_yz_mean=({:.5},{:.5}) span_yz_mean=({:.5},{:.5}) tip_up_max={:.6} tip_up_min={:.6}\n",
        span_drift_angle_max,
        (span_angle_sum / span_samples.max(1) as f64).to_degrees(),
        span_angle_min.to_degrees(),
        inclination_max.to_degrees(),
        (root_yz_sum.y / span_samples.max(1) as f32),
        (root_yz_sum.z / span_samples.max(1) as f32),
        (tip_yz_sum.y / span_samples.max(1) as f32),
        (tip_yz_sum.z / span_samples.max(1) as f32),
        (span_yz_sum.y / span_samples.max(1) as f32),
        (span_yz_sum.z / span_samples.max(1) as f32),
        tip_up_max, tip_up_min
    ));
    for (bone, motion) in tail_bones.iter().zip(motions) {
        let position_mean = motion.position_sum / motion.samples.max(1) as f32;
        out.push_str(&format!(
            "TAIL_BONE index={} name={:?} body={:?} mass={:.3} samples={} invalid={} mean_yz=({:.5},{:.5}) delta_max={:.6} up_max={:.6} up_min={:.6} angle_from_warmup_max={:.6} angle_from_bind_max={:.6}\n",
            bone.bone_index, bone.bone_name, bone.body_name, bone.mass,
            motion.samples, motion.invalid, position_mean.y, position_mean.z,
            motion.max_delta, motion.max_up, motion.min_up, motion.max_angle,
            motion.bind_angle_max
        ));
    }
    out.push_str(&format!(
        "TAIL_JOINT_PEAK linear={:.6} joint={:?} bodies={:?} violation={:?} angular={:.6} joint={:?} bodies={:?} violation={:?} anchor={:.6} joint={:?} bodies={:?}\n",
        joints.linear, joints.linear_name, joints.linear_bodies, joints.linear_vector,
        joints.angular, joints.angular_name, joints.angular_bodies, joints.angular_vector,
        joints.anchor, joints.anchor_name, joints.anchor_bodies
    ));
    Ok(out)
}

fn main() -> ExitCode {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..");
    let defaults = [
        root.join("neoforge/run/client/3d-skin/EntityPlayer/oguri/1006_Oguri Cap.pmx"),
        root.join("neoforge/run/client/3d-skin/EntityPlayer/oguri/1006_小栗帽.pmx"),
        root.join("neoforge/run/client/3d-skin/EntityPlayer/teio/1003_Tokai Teio.pmx"),
    ];
    let animate = env::args().any(|arg| arg == "--with-animations");
    let args: Vec<_> = env::args()
        .skip(1)
        .filter(|arg| arg != "--with-animations")
        .collect();
    let paths: Vec<_> = if args.is_empty() {
        defaults.to_vec()
    } else {
        args.iter().map(PathBuf::from).collect()
    };
    let scenarios = [
        Scenario {
            label: "idle_off",
            speed_blocks_per_second: 0.0,
            idle_lift: false,
            movement_boost: false,
        },
        Scenario {
            label: "idle_on",
            speed_blocks_per_second: 0.0,
            idle_lift: true,
            movement_boost: false,
        },
        Scenario {
            label: "walk_base",
            speed_blocks_per_second: 4.3,
            idle_lift: false,
            movement_boost: false,
        },
        Scenario {
            label: "walk_boost",
            speed_blocks_per_second: 4.3,
            idle_lift: false,
            movement_boost: true,
        },
        Scenario {
            label: "walk_boost_idle",
            speed_blocks_per_second: 4.3,
            idle_lift: true,
            movement_boost: true,
        },
        Scenario {
            label: "run_base",
            speed_blocks_per_second: 5.6,
            idle_lift: false,
            movement_boost: false,
        },
        Scenario {
            label: "run_boost",
            speed_blocks_per_second: 5.6,
            idle_lift: false,
            movement_boost: true,
        },
        Scenario {
            label: "run_boost_idle",
            speed_blocks_per_second: 5.6,
            idle_lift: true,
            movement_boost: true,
        },
    ];

    let result = (|| -> Result<String, String> {
        let mut out = String::new();
        for path in paths {
            for scenario in &scenarios {
                out.push_str(&run_case(&path.to_string_lossy(), scenario, animate)?);
            }
        }
        Ok(out)
    })();
    reset_config();
    match result {
        Ok(output) => {
            print!("{output}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("尾巴物理探针失败: {error}");
            ExitCode::FAILURE
        }
    }
}
