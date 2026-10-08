//! 用完整模型路径检查静止、转头和物理重置。

use std::{
    collections::{BTreeMap, HashSet},
    env, fs,
    path::PathBuf,
    process::ExitCode,
};

use glam::Mat4;
use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{
    model::{load_pmx, MmdModel},
    physics::config::{reset_config, set_config, PhysicsConfig},
};

const WARM_SECONDS: usize = 5;
const SAMPLE_SECONDS: usize = 5;

#[derive(Clone, Copy, Default)]
struct TransformStats {
    samples: usize,
    invalid: usize,
    distance_sum: f64,
    distance_max: f32,
    angle_max: f32,
}

impl TransformStats {
    fn observe(&mut self, previous: Mat4, current: Mat4) {
        if !previous.is_finite() || !current.is_finite() {
            self.invalid += 1;
            return;
        }
        let (_, q0, p0) = previous.to_scale_rotation_translation();
        let (_, q1, p1) = current.to_scale_rotation_translation();
        let distance = p0.distance(p1);
        let angle = 2.0 * q0.dot(q1).abs().clamp(0.0, 1.0).acos();
        if !distance.is_finite() || !angle.is_finite() {
            self.invalid += 1;
            return;
        }
        self.samples += 1;
        self.distance_sum += distance as f64;
        self.distance_max = self.distance_max.max(distance);
        self.angle_max = self.angle_max.max(angle);
    }

    fn mean(self) -> f64 {
        if self.samples == 0 {
            0.0
        } else {
            self.distance_sum / self.samples as f64
        }
    }
}

#[derive(Default)]
struct JointStats {
    samples: usize,
    invalid: usize,
    anchor_sum: f64,
    anchor_max: f32,
    anchor_peak: Option<AnchorPeak>,
    linear_violation_max: f32,
    linear_peak: Option<LinearPeak>,
    angular_violation_max: f32,
}

struct AnchorPeak {
    joint: String,
    body_a: String,
    body_b: String,
    error: f32,
    anchor_a: glam::Vec3,
    anchor_b: glam::Vec3,
}

struct LinearPeak {
    joint: String,
    body_a: String,
    body_b: String,
    magnitude: f32,
    position: glam::Vec3,
    lower: glam::Vec3,
    upper: glam::Vec3,
    violation: glam::Vec3,
}

impl JointStats {
    fn from_model(model: &MmdModel) -> Self {
        let mut stats = Self::default();
        stats.observe_model(model);
        stats
    }

    fn observe_model(&mut self, model: &MmdModel) {
        for joint in model.physics_joint_snapshots_matching("") {
            let linear = joint.diagnostic.linear_violation.length();
            let angular = joint.diagnostic.angular_violation.length();
            if !joint.anchor_error.is_finite() || !linear.is_finite() || !angular.is_finite() {
                self.invalid += 1;
                continue;
            }
            self.samples += 1;
            self.anchor_sum += joint.anchor_error as f64;
            if joint.anchor_error > self.anchor_max {
                self.anchor_max = joint.anchor_error;
                self.anchor_peak = Some(AnchorPeak {
                    joint: joint.name.clone(),
                    body_a: joint.body_a_name.clone(),
                    body_b: joint.body_b_name.clone(),
                    error: joint.anchor_error,
                    anchor_a: joint.anchor_a,
                    anchor_b: joint.anchor_b,
                });
            }
            if linear > self.linear_violation_max {
                self.linear_violation_max = linear;
                self.linear_peak = Some(LinearPeak {
                    joint: joint.name.clone(),
                    body_a: joint.body_a_name.clone(),
                    body_b: joint.body_b_name.clone(),
                    magnitude: linear,
                    position: joint.diagnostic.linear_position,
                    lower: joint.diagnostic.linear_lower,
                    upper: joint.diagnostic.linear_upper,
                    violation: joint.diagnostic.linear_violation,
                });
            }
            self.angular_violation_max = self.angular_violation_max.max(angular);
        }
    }
}

fn append_joint_stats(output: &mut String, label: &str, stats: &JointStats) {
    let anchor_mean = if stats.samples == 0 {
        0.0
    } else {
        stats.anchor_sum / stats.samples as f64
    };
    output.push_str(&format!(
        "{label} samples={} invalid={} anchor_max={:.6} anchor_mean={:.6} linear_violation_max={:.6} angular_violation_max={:.6}\n",
        stats.samples, stats.invalid, stats.anchor_max, anchor_mean,
        stats.linear_violation_max, stats.angular_violation_max
    ));
    if let Some(peak) = &stats.anchor_peak {
        output.push_str(&format!(
            "{label}_ANCHOR_PEAK joint={:?} bodies={:?}/{:?} error={:.6} point_a={:?} point_b={:?}\n",
            peak.joint, peak.body_a, peak.body_b, peak.error, peak.anchor_a, peak.anchor_b
        ));
    }
    if let Some(peak) = &stats.linear_peak {
        output.push_str(&format!(
            "{label}_LINEAR_PEAK joint={:?} bodies={:?}/{:?} magnitude={:.6} position={:?} lower={:?} upper={:?} violation={:?}\n",
            peak.joint, peak.body_a, peak.body_b, peak.magnitude,
            peak.position, peak.lower, peak.upper, peak.violation
        ));
    }
}

#[derive(Clone)]
struct WatchedBone {
    name: String,
    index: usize,
    categories: Vec<&'static str>,
}

#[derive(Clone)]
struct Mode2Relation {
    name: String,
    index: usize,
    parent: usize,
    rest_offset: Mat4,
    rest_length: f32,
}

#[derive(Clone, Default)]
struct Mode2Stats {
    samples: usize,
    invalid: usize,
    offset_error_sum: f64,
    offset_error_max: f32,
    parent_follow_error_sum: f64,
    parent_follow_error_max: f32,
    rotation_follow_error_sum: f64,
    rotation_follow_error_max: f32,
    parent_rotation_sum: f64,
    parent_rotation_max: f32,
}

impl Mode2Stats {
    fn observe(
        &mut self,
        relation: &Mode2Relation,
        previous_parent: Mat4,
        parent: Mat4,
        child: Mat4,
    ) {
        if !previous_parent.is_finite() || !parent.is_finite() || !child.is_finite() {
            self.invalid += 1;
            return;
        }
        let parent_rotation_delta = rotation_delta(previous_parent, parent);
        let expected_child = parent * relation.rest_offset;
        let expected_child_position = expected_child.w_axis.truncate();
        let actual_offset = child.w_axis.truncate() - parent.w_axis.truncate();
        let offset_error = (actual_offset.length() - relation.rest_length).abs();
        let parent_follow_error = child.w_axis.truncate().distance(expected_child_position);
        let rotation_follow_error = rotation_delta(expected_child, child);
        if !parent_rotation_delta.is_finite()
            || !offset_error.is_finite()
            || !parent_follow_error.is_finite()
            || !rotation_follow_error.is_finite()
        {
            self.invalid += 1;
            return;
        }
        self.samples += 1;
        self.offset_error_sum += offset_error as f64;
        self.offset_error_max = self.offset_error_max.max(offset_error);
        self.parent_follow_error_sum += parent_follow_error as f64;
        self.parent_follow_error_max = self.parent_follow_error_max.max(parent_follow_error);
        self.rotation_follow_error_sum += rotation_follow_error as f64;
        self.rotation_follow_error_max = self.rotation_follow_error_max.max(rotation_follow_error);
        self.parent_rotation_sum += parent_rotation_delta as f64;
        self.parent_rotation_max = self.parent_rotation_max.max(parent_rotation_delta);
    }
}

fn rotation_delta(previous: Mat4, current: Mat4) -> f32 {
    let (_, q0, _) = previous.to_scale_rotation_translation();
    let (_, q1, _) = current.to_scale_rotation_translation();
    2.0 * q0.dot(q1).abs().clamp(0.0, 1.0).acos()
}

fn is_hair_name(name: &str) -> bool {
    let lower = name.to_lowercase();
    ["髪", "髮", "前髪", "アホ毛", "hair", "머리"]
        .iter()
        .any(|part| lower.contains(part))
}

fn collect_watched_bones(model: &MmdModel) -> Vec<WatchedBone> {
    let mut mode2_hair = HashSet::new();
    for body in &model.rigid_bodies {
        if body.mode == RigidBodyMode::DynamicWithBonePosition
            && (is_hair_name(&body.local_name) || is_hair_name(&body.universal_name))
            && body.bone_index >= 0
        {
            mode2_hair.insert(body.bone_index as usize);
        }
    }

    model
        .bone_manager
        .links()
        .enumerate()
        .filter_map(|(index, bone)| {
            let lower = bone.name.to_lowercase();
            let mut categories = Vec::new();
            if ["胸", "乳", "chest", "bust"]
                .iter()
                .any(|part| lower.contains(part))
            {
                categories.push("chest");
            }
            if ["穗", "穂", "穗", "jewel", "ornament"]
                .iter()
                .any(|part| lower.contains(part))
            {
                categories.push("ornament");
            }
            if ["袖", "sleeve"].iter().any(|part| lower.contains(part)) {
                categories.push("sleeve");
            }
            if mode2_hair.contains(&index) {
                categories.push("mode2_hair");
            }
            (!categories.is_empty()).then(|| WatchedBone {
                name: bone.name.clone(),
                index,
                categories,
            })
        })
        .collect()
}

fn collect_mode2_relations(model: &MmdModel) -> Vec<Mode2Relation> {
    let mode2_bones: HashSet<_> = model
        .rigid_bodies
        .iter()
        .filter(|body| {
            body.mode == RigidBodyMode::DynamicWithBonePosition
                && (is_hair_name(&body.local_name) || is_hair_name(&body.universal_name))
                && body.bone_index >= 0
        })
        .map(|body| body.bone_index as usize)
        .collect();
    let links: Vec<_> = model.bone_manager.links().collect();
    mode2_bones
        .into_iter()
        .filter_map(|index| {
            let child = links.get(index)?;
            let parent = usize::try_from(child.parent_index).ok()?;
            let parent_bind = model.bone_manager.get_global_transform(parent);
            let child_bind = model.bone_manager.get_global_transform(index);
            let rest_offset = parent_bind.inverse() * child_bind;
            Some(Mode2Relation {
                name: child.name.clone(),
                index,
                parent,
                rest_length: rest_offset.w_axis.truncate().length(),
                rest_offset,
            })
        })
        .collect()
}

fn capture_bones(model: &MmdModel, watched: &[WatchedBone]) -> Vec<Mat4> {
    watched
        .iter()
        .map(|bone| model.bone_manager.get_global_transform(bone.index))
        .collect()
}

fn run_case(path: &str, fps: usize, slow_head_turn: bool) -> Result<String, String> {
    let mut config = PhysicsConfig::default();
    config.inertia_strength = 0.0;
    config.physics_fps = 60.0;
    config.debug_log = false;
    set_config(config);

    let mut model = load_pmx(path).map_err(|error| error.to_string())?;
    model.update_node_animation(false);
    model.set_tail_physics_options(true, true);
    if !model.init_physics() {
        return Err("完整 MmdModel 物理初始化失败".to_owned());
    }
    let joints_after_first_init = JointStats::from_model(&model);

    // 覆盖 reset/reinit 路径；选项效果在此模型上无法直接观测。
    model.reset_physics();
    model.tick_animation_no_skinning(1.0 / fps as f32);
    if !model.init_physics() || !model.has_physics() {
        return Err("物理 reset/reinit 后未能重新初始化".to_owned());
    }
    let joints_after_reinit = JointStats::from_model(&model);

    let watched = collect_watched_bones(&model);
    let mode2 = collect_mode2_relations(&model);
    let dt = 1.0 / fps as f32;
    let mut elapsed = 0.0;
    for _ in 0..(WARM_SECONDS * fps) {
        let angle = if slow_head_turn {
            0.2 * (std::f32::consts::TAU * elapsed / 10.0).sin()
        } else {
            0.0
        };
        model.set_head_angle(0.0, angle, 0.0);
        model.tick_animation_no_skinning(dt);
        elapsed += dt;
    }
    let mut previous = capture_bones(&model, &watched);
    let mut previous_mode2: Vec<_> = mode2
        .iter()
        .map(|relation| {
            (
                model.bone_manager.get_global_transform(relation.parent),
                model.bone_manager.get_global_transform(relation.index),
            )
        })
        .collect();
    let mut mode2_stats = vec![Mode2Stats::default(); mode2.len()];
    let mut per_bone = vec![TransformStats::default(); watched.len()];
    let mut grouped = BTreeMap::<&'static str, TransformStats>::new();
    let mut joints = JointStats::default();

    for _ in 0..(SAMPLE_SECONDS * fps) {
        let angle = if slow_head_turn {
            0.2 * (std::f32::consts::TAU * elapsed / 10.0).sin()
        } else {
            0.0
        };
        model.set_head_angle(0.0, angle, 0.0);
        model.tick_animation_no_skinning(dt);
        elapsed += dt;
        let current = capture_bones(&model, &watched);
        for (index, bone) in watched.iter().enumerate() {
            per_bone[index].observe(previous[index], current[index]);
            for category in &bone.categories {
                grouped
                    .entry(category)
                    .or_default()
                    .observe(previous[index], current[index]);
            }
        }
        for (index, relation) in mode2.iter().enumerate() {
            let parent = model.bone_manager.get_global_transform(relation.parent);
            let child = model.bone_manager.get_global_transform(relation.index);
            mode2_stats[index].observe(relation, previous_mode2[index].0, parent, child);
            previous_mode2[index] = (parent, child);
        }
        joints.observe_model(&model);
        previous = current;
    }

    let mut output = format!(
        "CASE render_fps={fps} physics_fps=60 motion={} warmup_s={WARM_SECONDS} sample_s={SAMPLE_SECONDS} watched_bones={} mode2_hair_bones={} pmx_bodies={} pmx_joints={} physics_joints={} tail_options_set=true inertia_strength=0\n",
        if slow_head_turn { "slow_head_turn" } else { "fixed_pose" },
        watched.len(), mode2.len(), model.rigid_bodies.len(), model.joints.len(),
        model.physics_joint_snapshots_matching("").len()
    );
    output.push_str(&format!(
        "RESET reset_physics=true resync_tick=true init_physics_again=true has_physics={} option_values_set_before_rebuild=true persistence_effect=unverified\n",
        model.has_physics()
    ));
    append_joint_stats(
        &mut output,
        "JOINT_AFTER_FIRST_INIT",
        &joints_after_first_init,
    );
    append_joint_stats(&mut output, "JOINT_AFTER_REINIT", &joints_after_reinit);
    for (name, stats) in &grouped {
        output.push_str(&format!(
            "GROUP {name} bones={} samples={} invalid={} delta_max={:.6} delta_mean={:.6} angle_max={:.6}\n",
            watched.iter().filter(|bone| bone.categories.contains(name)).count(),
            stats.samples, stats.invalid, stats.distance_max, stats.mean(), stats.angle_max
        ));
    }
    for (relation, stats) in mode2.iter().zip(&mode2_stats) {
        let mean_offset = if stats.samples == 0 {
            0.0
        } else {
            stats.offset_error_sum / stats.samples as f64
        };
        let mean_follow = if stats.samples == 0 {
            0.0
        } else {
            stats.parent_follow_error_sum / stats.samples as f64
        };
        let mean_rotation_follow = if stats.samples == 0 {
            0.0
        } else {
            stats.rotation_follow_error_sum / stats.samples as f64
        };
        let mean_parent_rotation = if stats.samples == 0 {
            0.0
        } else {
            stats.parent_rotation_sum / stats.samples as f64
        };
        output.push_str(&format!(
            "MODE2 name={:?} bone={} parent={} samples={} invalid={} offset_len_error_max={:.6} offset_len_error_mean={:.6} parent_follow_error_max={:.6} parent_follow_error_mean={:.6} rotation_follow_error_max={:.6} rotation_follow_error_mean={:.6} parent_rotation_max={:.6} parent_rotation_mean={:.6}\n",
            relation.name, relation.index, relation.parent, stats.samples, stats.invalid,
            stats.offset_error_max, mean_offset, stats.parent_follow_error_max, mean_follow,
            stats.rotation_follow_error_max, mean_rotation_follow, stats.parent_rotation_max,
            mean_parent_rotation
        ));
    }
    for category in ["chest", "ornament", "sleeve", "mode2_hair"] {
        let mut selected: Vec<_> = watched
            .iter()
            .enumerate()
            .filter(|(_, bone)| bone.categories.contains(&category))
            .collect();
        selected.sort_by(|a, b| {
            per_bone[b.0]
                .distance_max
                .total_cmp(&per_bone[a.0].distance_max)
        });
        for (index, bone) in selected {
            let stats = per_bone[index];
            output.push_str(&format!(
                "BONE {category} index={} name={:?} samples={} invalid={} delta_max={:.6} delta_mean={:.6} angle_max={:.6}\n",
                bone.index, bone.name, stats.samples, stats.invalid, stats.distance_max,
                stats.mean(), stats.angle_max
            ));
        }
    }
    append_joint_stats(&mut output, "JOINT", &joints);
    Ok(output)
}

fn main() -> ExitCode {
    let args: Vec<_> = env::args().collect();
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..");
    let Some(model_argument) = args.get(1) else {
        eprintln!("用法: probe_model_physics <模型.pmx> [日志路径]");
        return ExitCode::FAILURE;
    };
    let model_path = PathBuf::from(model_argument);
    let log_path = args.get(2).map_or_else(
        || root.join(".codex-tmp/probe-model-physics.txt"),
        PathBuf::from,
    );
    let mut log = format!("MODEL {:?}\n", model_path);
    let result = (|| -> Result<(), String> {
        for fps in [30, 60, 144] {
            log.push_str(&run_case(&model_path.to_string_lossy(), fps, false)?);
            log.push_str(&run_case(&model_path.to_string_lossy(), fps, true)?);
        }
        Ok(())
    })();
    reset_config();
    match result {
        Ok(()) => {
            if let Some(parent) = log_path.parent() {
                if !parent.as_os_str().is_empty() {
                    if let Err(error) = fs::create_dir_all(parent) {
                        eprintln!("创建日志目录失败: {error}");
                        return ExitCode::FAILURE;
                    }
                }
            }
            if let Err(error) = fs::write(&log_path, &log) {
                eprintln!("写探针日志失败: {error}");
                return ExitCode::FAILURE;
            }
            print!("{log}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            let _ = fs::write(&log_path, format!("{log}\nERROR {error}\n"));
            eprintln!("探针失败: {error}");
            ExitCode::FAILURE
        }
    }
}
