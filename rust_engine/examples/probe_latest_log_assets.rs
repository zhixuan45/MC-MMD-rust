//! 对实际 PMX 和可选 VMD 运行裙环默认/off A/B 探针。

use std::{
    collections::BTreeSet,
    env,
    ffi::OsString,
    fs,
    path::{Path, PathBuf},
    process::ExitCode,
    sync::Arc,
};

use mmd_engine::{
    animation::{VmdAnimation, VmdFile},
    model::{load_pmx, MmdModel, VertexWeight},
    physics::{
        get_alloc_stats, reset_config, set_config, BulletAllocStats, CollisionStabilityMode,
        PhysicsConfig, PhysicsJointSnapshot,
    },
};
use serde_json::{json, Value};

const DT: f32 = 1.0 / 60.0;
const SAMPLE_STEPS: [usize; 3] = [120, 600, 1800];
const SYNTH_NAME: &str = "Synthesized_Skirt_Cross";

struct EnvGuard(Option<OsString>);

impl EnvGuard {
    fn set(value: Option<&str>) -> Self {
        let previous = env::var_os("MMD_SKIRT_CROSS_SYNTHESIS");
        if let Some(value) = value {
            env::set_var("MMD_SKIRT_CROSS_SYNTHESIS", value);
        } else {
            env::remove_var("MMD_SKIRT_CROSS_SYNTHESIS");
        }
        Self(previous)
    }
}

impl Drop for EnvGuard {
    fn drop(&mut self) {
        if let Some(value) = self.0.take() {
            env::set_var("MMD_SKIRT_CROSS_SYNTHESIS", value);
        } else {
            env::remove_var("MMD_SKIRT_CROSS_SYNTHESIS");
        }
    }
}

struct ConfigGuard;

impl Drop for ConfigGuard {
    fn drop(&mut self) {
        reset_config();
    }
}

#[derive(Default)]
struct JointStats {
    samples: usize,
    invalid: usize,
    anchor_sum: f64,
    anchor_max: f32,
    anchor_peak: Option<(String, String, String)>,
    linear_violation_sum: f64,
    linear_violation_max: f32,
    linear_peak: Option<(String, String, String)>,
}

impl JointStats {
    fn observe(&mut self, joint: &PhysicsJointSnapshot) {
        let linear = joint.diagnostic.linear_violation.length();
        if !joint.anchor_error.is_finite() || !linear.is_finite() {
            self.invalid += 1;
            return;
        }
        self.samples += 1;
        self.anchor_sum += joint.anchor_error as f64;
        self.linear_violation_sum += linear as f64;
        if joint.anchor_error > self.anchor_max {
            self.anchor_max = joint.anchor_error;
            self.anchor_peak = Some((
                joint.name.clone(),
                joint.body_a_name.clone(),
                joint.body_b_name.clone(),
            ));
        }
        if linear > self.linear_violation_max {
            self.linear_violation_max = linear;
            self.linear_peak = Some((
                joint.name.clone(),
                joint.body_a_name.clone(),
                joint.body_b_name.clone(),
            ));
        }
    }

    fn value(&self) -> Value {
        json!({
            "joint_samples": self.samples,
            "invalid_samples": self.invalid,
            "anchor_mean": if self.samples == 0 { 0.0 } else { self.anchor_sum / self.samples as f64 },
            "anchor_max": self.anchor_max,
            "anchor_peak": self.anchor_peak,
            "linear_violation_mean": if self.samples == 0 { 0.0 } else { self.linear_violation_sum / self.samples as f64 },
            "linear_violation_max": self.linear_violation_max,
            "linear_peak": self.linear_peak,
        })
    }
}

#[derive(Default)]
struct StepStats {
    frames: usize,
    all: JointStats,
    synthesized: JointStats,
    garment_contact_frames: usize,
    garment_contact_samples: usize,
    garment_penetration_max: f32,
    motion_state_delta_max: f32,
}

impl StepStats {
    fn observe(&mut self, model: &MmdModel) {
        self.frames += 1;
        for joint in model.physics_joint_snapshots_matching("") {
            self.all.observe(&joint);
            if joint.name.contains(SYNTH_NAME) {
                self.synthesized.observe(&joint);
            }
        }
        // 此接口只统计腿部与裙摆命名分类之间的真实接触。
        let contacts = model.garment_physics_snapshot();
        if !contacts.contacts.is_empty() {
            self.garment_contact_frames += 1;
        }
        self.garment_contact_samples += contacts.contacts.len();
        self.garment_penetration_max = self.garment_penetration_max.max(
            contacts
                .contacts
                .iter()
                .map(|contact| contact.max_penetration)
                .filter(|value| value.is_finite())
                .fold(0.0, f32::max),
        );
        self.motion_state_delta_max = self
            .motion_state_delta_max
            .max(contacts.max_simulation_motion_state_delta);
    }

    fn value(&self) -> Value {
        json!({
            "frames": self.frames,
            "all_joints": self.all.value(),
            "synthesized_skirt_cross": self.synthesized.value(),
            "garment_contact_classifier": {
                "frames_with_contact": self.garment_contact_frames,
                "contact_samples": self.garment_contact_samples,
                "penetration_max": self.garment_penetration_max,
                "motion_state_delta_max": self.motion_state_delta_max,
            },
        })
    }
}

fn event(output: &mut String, value: Value) {
    output.push_str(&serde_json::to_string(&value).expect("JSONL event must serialize"));
    output.push('\n');
}

fn configure_probe() -> ConfigGuard {
    let mut config = PhysicsConfig::default();
    // 只覆盖日志可确认的三个值，其它保持 crate 默认值。
    config.collision_stability_mode = CollisionStabilityMode::Strict;
    config.gravity_y = -98.0;
    config.static_collider_scale = 0.8;
    set_config(config);
    ConfigGuard
}

fn config_event(case: &str, mode: &str) -> Value {
    let config = mmd_engine::physics::get_config();
    json!({
        "event": "config",
        "case": case,
        "synthesis": mode,
        "profile_scope": "log-confirmed Strict/gravity/static_scale only; remaining values are PhysicsConfig::default",
            "velocity_sampling_note": "per-step dynamic velocity accessor is not public; production text diagnostic is captured at 2/10/30s and is not a continuous peak",
        "enabled": config.enabled,
        "gravity_y": config.gravity_y,
        "physics_fps": config.physics_fps,
        "max_substep_count": config.max_substep_count,
        "inertia_strength": config.inertia_strength,
        "max_linear_velocity": config.max_linear_velocity,
        "max_angular_velocity": config.max_angular_velocity,
        "joints_enabled": config.joints_enabled,
        "collision_enabled": config.collision_enabled,
        "collision_stability_mode": config.collision_stability_mode.as_str(),
        "kinematic_filter": config.kinematic_filter,
        "static_collider_scale": config.static_collider_scale,
        "debug_log": config.debug_log,
        "velocity_cap_note": "default cap retained; logged speed is an observation, not evidence for changing cap",
    })
}

fn weight_for_bone(weight: &VertexWeight, bone: usize) -> f32 {
    let matches = |index: i32| usize::try_from(index).ok() == Some(bone);
    match weight {
        VertexWeight::Bdef1 { bone: b } => {
            if matches(*b) {
                1.0
            } else {
                0.0
            }
        }
        VertexWeight::Bdef2 { bones, weight } | VertexWeight::Sdef { bones, weight, .. } => {
            let mut total = 0.0;
            if matches(bones[0]) {
                total += *weight;
            }
            if matches(bones[1]) {
                total += 1.0 - *weight;
            }
            total
        }
        VertexWeight::Bdef4 { bones, weights } | VertexWeight::Qdef { bones, weights } => bones
            .iter()
            .zip(weights)
            .filter(|(index, _)| matches(**index))
            .map(|(_, value)| *value)
            .sum(),
    }
}

fn material_weights(model: &MmdModel, bone: usize) -> Vec<Value> {
    let mut result = Vec::new();
    for mesh in &model.submeshes {
        let start = mesh.begin_index as usize;
        let end = start
            .saturating_add(mesh.index_count as usize)
            .min(model.indices.len());
        let mut vertices = BTreeSet::new();
        for &index in model.indices.get(start..end).unwrap_or_default() {
            let vertex = index as usize;
            if vertex < model.weights.len() {
                vertices.insert(vertex);
            }
        }
        let mut touched = 0usize;
        let mut total_weight = 0.0_f64;
        let mut non_finite = 0usize;
        for vertex in vertices {
            let value = weight_for_bone(&model.weights[vertex], bone);
            if !value.is_finite() {
                non_finite += 1;
            } else if value > 0.0 {
                touched += 1;
                total_weight += value as f64;
            }
        }
        if touched > 0 || non_finite > 0 {
            let material = usize::try_from(mesh.material_id)
                .ok()
                .and_then(|index| model.materials.get(index));
            result.push(json!({
                "material_id": mesh.material_id,
                "material_name": material.map(|value| value.name.as_str()),
                "weighted_unique_vertices": touched,
                "bone_weight_sum": total_weight,
                "non_finite_weights": non_finite,
            }));
        }
    }
    result
}

fn endpoint_candidates(model: &MmdModel, name: &str) -> Value {
    let candidates: Vec<_> = model
        .rigid_bodies
        .iter()
        .enumerate()
        .filter(|(_, body)| body.local_name == name)
        .map(|(index, body)| {
            let bone = usize::try_from(body.bone_index).ok();
            let bone_name = bone
                .and_then(|index| model.bone_manager.get_bone(index))
                .map(|bone| bone.name.as_str());
            json!({
                "source_pmx_body_index": index,
                "local_name": body.local_name,
                "universal_name": body.universal_name,
                "bone_index": body.bone_index,
                "bone_name": bone_name,
                "material_weight_candidates": bone.map_or_else(Vec::new, |index| material_weights(model, index)),
            })
        })
        .collect();
    let status = match candidates.len() {
        0 => "no_unique_source_body_match",
        1 => "unique_name_match",
        _ => "ambiguous_duplicate_body_name",
    };
    json!({ "runtime_body_name": name, "mapping_status": status, "source_candidates": candidates })
}

fn emit_joint_details(output: &mut String, model: &MmdModel, case: &str, mode: &str) {
    for joint in model.physics_joint_snapshots_matching(SYNTH_NAME) {
        event(
            output,
            json!({
                "event": "synthesized_joint_endpoint_detail",
                "case": case,
                "synthesis": mode,
                "joint": joint.name,
                "body_a": endpoint_candidates(model, &joint.body_a_name),
                "body_b": endpoint_candidates(model, &joint.body_b_name),
                "anchor_error": joint.anchor_error,
                "anchor_a": joint.anchor_a.to_array(),
                "anchor_b": joint.anchor_b.to_array(),
                "linear_violation": joint.diagnostic.linear_violation.to_array(),
            }),
        );
    }
}

fn emit_instant_snapshot(
    output: &mut String,
    model: &MmdModel,
    case: &str,
    mode: &str,
    seconds: usize,
    segment: &StepStats,
) {
    let all = model.physics_joint_snapshots_matching("");
    let synthesized: Vec<_> = all
        .iter()
        .filter(|joint| joint.name.contains(SYNTH_NAME))
        .collect();
    let mut all_now = JointStats::default();
    let mut synthesized_now = JointStats::default();
    for joint in &all {
        all_now.observe(joint);
    }
    for joint in synthesized {
        synthesized_now.observe(joint);
    }
    event(
        output,
        json!({
            "event": "instant_snapshot",
            "case": case,
            "synthesis": mode,
            "seconds": seconds,
            "all_joint_count": all.len(),
            "synthesized_joint_count": model.physics_joint_snapshots_matching(SYNTH_NAME).len(),
            "all_joints": all_now.value(),
            "synthesized_skirt_cross": synthesized_now.value(),
            "fixed_step_interval_stats": segment.value(),
            "production_diagnostic_flags_0x106": model.issue_debug_diagnostic(0x106),
        }),
    );
}

fn run_case(
    output: &mut String,
    pmx_path: &Path,
    vmd_path: Option<&Path>,
    mode: &str,
    off: bool,
) -> Result<(), String> {
    let _env = EnvGuard::set(if off { Some("off") } else { None });
    let _config = configure_probe();
    let case = if vmd_path.is_some() {
        "supplied-motion"
    } else {
        "static"
    };
    event(output, config_event(case, mode));

    let mut model = load_pmx(pmx_path).map_err(|error| error.to_string())?;
    if let Some(path) = vmd_path {
        let vmd = VmdFile::load(path).map_err(|error| error.to_string())?;
        let vmd_info = json!({
            "event": "vmd_identity",
            "case": case,
            "motion_label": "supplied-motion",
            "path": path.to_string_lossy(),
            "model_name": vmd.model_name.as_str(),
            "max_frame": vmd.motion.duration(),
            "duration_seconds_at_30fps": vmd.motion.duration() as f64 / 30.0,
            "bone_track_count": vmd.motion.bone_tracks.len(),
            "morph_track_count": vmd.motion.morph_tracks.len(),
            "ik_track_count": vmd.motion.ik_tracks.len(),
            "camera_keyframe_count": vmd.motion.camera_keyframe_count(),
        });
        event(output, vmd_info);
        let animation = VmdAnimation::from_vmd_file(vmd);
        model.set_layer_animation(0, Some(Arc::new(animation)));
        model.set_layer_loop(0, true);
        model.play_layer(0);
    }
    model.update_node_animation(false);
    if !model.init_physics() {
        return Err(format!("PMX 物理初始化失败: {}", pmx_path.display()));
    }

    let initial_joints = model.physics_joint_snapshots_matching("");
    event(
        output,
        json!({
            "event": "initialized",
            "case": case,
            "synthesis": mode,
            "model_name": model.name,
            "pmx_vertices": model.vertices.len(),
            "bones": model.bone_manager.bone_count(),
            "pmx_rigid_bodies": model.rigid_bodies.len(),
            "pmx_joints": model.joints.len(),
            "runtime_joints": initial_joints.len(),
            "synthesized_skirt_cross": initial_joints.iter().filter(|joint| joint.name.contains(SYNTH_NAME)).count(),
        }),
    );
    emit_joint_details(output, &model, case, mode);

    let mut interval = StepStats::default();
    let mut next_sample = 0usize;
    for step in 1..=SAMPLE_STEPS[2] {
        model.tick_animation_no_skinning(DT);
        interval.observe(&model);
        if step == SAMPLE_STEPS[next_sample] {
            let seconds = (step as f32 * DT).round() as usize;
            emit_instant_snapshot(output, &model, case, mode, seconds, &interval);
            interval = StepStats::default();
            next_sample += 1;
            if next_sample == SAMPLE_STEPS.len() {
                break;
            }
        }
    }
    Ok(())
}

fn stats_value(stats: BulletAllocStats) -> Value {
    json!({
        "worlds": stats.worlds,
        "shapes": stats.shapes,
        "rigid_bodies": stats.rigid_bodies,
        "constraints": stats.constraints,
        "motion_states": stats.motion_states,
    })
}

fn stats_equal(a: BulletAllocStats, b: BulletAllocStats) -> bool {
    a.worlds == b.worlds
        && a.shapes == b.shapes
        && a.rigid_bodies == b.rigid_bodies
        && a.constraints == b.constraints
        && a.motion_states == b.motion_states
}

fn run_lifecycle(output: &mut String, pmx_path: &Path, cycles: usize) -> Result<(), String> {
    let _env = EnvGuard::set(None);
    let _config = configure_probe();
    let baseline = get_alloc_stats();
    event(
        output,
        json!({
            "event": "lifecycle_begin",
            "cycles": cycles,
            "synthesis": "default",
            "process_global_baseline": stats_value(baseline),
            "note": "Bullet counters are process-global; run standalone without other live Bullet worlds",
        }),
    );
    for cycle in 0..cycles {
        let before = get_alloc_stats();
        let mut model = load_pmx(pmx_path).map_err(|error| error.to_string())?;
        model.update_node_animation(false);
        if !model.init_physics() {
            return Err(format!("生命周期第 {cycle} 轮物理初始化失败"));
        }
        let live = get_alloc_stats();
        let runtime_joints = model.physics_joint_snapshots_matching("").len();
        drop(model);
        let after_drop = get_alloc_stats();
        event(
            output,
            json!({
                "event": "lifecycle_cycle",
                "cycle": cycle + 1,
                "before": stats_value(before),
                "live": stats_value(live),
                "runtime_joints": runtime_joints,
                "after_drop": stats_value(after_drop),
                "baseline_restored": stats_equal(after_drop, baseline),
            }),
        );
        if !stats_equal(after_drop, baseline) {
            return Err(format!(
                "生命周期第 {} 轮 drop 后 Bullet 计数未回到基线",
                cycle + 1
            ));
        }
    }
    Ok(())
}

fn usage() -> &'static str {
    "用法: probe_latest_log_assets <model.pmx> <output.jsonl> [motion.vmd] [--cycles=N] [--default-only]"
}

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let Some(pmx_path) = args.next().map(PathBuf::from) else {
        eprintln!("{}", usage());
        return ExitCode::FAILURE;
    };
    let Some(output_path) = args.next().map(PathBuf::from) else {
        eprintln!("{}", usage());
        return ExitCode::FAILURE;
    };
    let mut vmd_path = None;
    let mut cycles = 0usize;
    let mut default_only = false;
    for arg in args {
        if let Some(value) = arg.strip_prefix("--cycles=") {
            match value.parse::<usize>() {
                Ok(value) => cycles = value,
                Err(error) => {
                    eprintln!("无效 --cycles 参数: {error}");
                    return ExitCode::FAILURE;
                }
            }
        } else if arg == "--default-only" {
            default_only = true;
        } else if vmd_path.is_none() {
            vmd_path = Some(PathBuf::from(arg));
        } else {
            eprintln!("多余参数: {arg}\n{}", usage());
            return ExitCode::FAILURE;
        }
    }

    let mut output = String::new();
    event(
        &mut output,
        json!({
            "event": "probe_manifest",
            "pmx": pmx_path.to_string_lossy(),
            "vmd": vmd_path.as_ref().map(|path| path.to_string_lossy().to_string()),
            "motion_label": "supplied-motion",
            "cases": ["static", "supplied-motion (only if VMD supplied)"],
            "default_only": default_only,
            "synthesis_modes": if default_only { vec!["default"] } else { vec!["default", "off"] },
            "off_case_note": if default_only { "skipped by --default-only" } else { "included" },
            "samples_seconds": [2, 10, 30],
            "step_rate_hz": 60,
            "lifecycle_cycles": cycles,
            "runtime_profile_note": "only log-confirmed Strict/gravity/static_scale are overridden; this is not the complete original runtime configuration",
            "display_scale_note": "model display/root scale is not fed into Bullet construction",
        }),
    );

    let result = (|| -> Result<(), String> {
        let modes: &[(&str, bool)] = if default_only {
            &[("default", false)]
        } else {
            &[("default", false), ("off", true)]
        };
        for &(mode, off) in modes {
            run_case(&mut output, &pmx_path, None, mode, off)?;
            if let Some(path) = vmd_path.as_deref() {
                run_case(&mut output, &pmx_path, Some(path), mode, off)?;
            }
        }
        if cycles > 0 {
            run_lifecycle(&mut output, &pmx_path, cycles)?;
        }
        Ok(())
    })();

    let succeeded = result.is_ok();
    if let Err(error) = result {
        event(&mut output, json!({ "event": "error", "message": error }));
    }
    if let Some(parent) = output_path.parent() {
        if !parent.as_os_str().is_empty() {
            if let Err(error) = fs::create_dir_all(parent) {
                eprintln!("创建输出目录失败: {error}");
                return ExitCode::FAILURE;
            }
        }
    }
    if let Err(error) = fs::write(&output_path, &output) {
        eprintln!("写探针日志失败: {error}");
        return ExitCode::FAILURE;
    }
    print!("{}", output);
    if succeeded {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}
