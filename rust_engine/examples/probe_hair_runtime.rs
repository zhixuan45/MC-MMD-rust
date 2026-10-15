//! 连续驱动真实 MmdModel 动画与物理，记录头发刚体、骨骼和蒙皮证据。

use std::{
    collections::{BTreeSet, HashMap},
    env, fs,
    path::{Path, PathBuf},
    process::ExitCode,
    sync::Arc,
};

use glam::{Mat4, Quat, Vec3};

#[path = "probe_hair_runtime/cases.rs"]
mod cases;
#[path = "probe_hair_runtime/controls.rs"]
mod controls;
#[path = "probe_hair_runtime/mesh.rs"]
mod mesh;
#[path = "probe_hair_runtime/watch.rs"]
mod watch;
use cases::run_case;
use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{
    animation::VmdAnimation,
    model::hair_geometry::{audit_hair_geometry, AuditStatus},
    model::{load_pmx, MmdModel, VertexWeight},
    physics::{reset_config, set_config, CollisionStabilityMode, PhysicsConfig},
};
use serde_json::{json, Value};

#[derive(Debug)]
struct Options {
    models: Vec<PathBuf>,
    idle: Option<PathBuf>,
    walk: Option<PathBuf>,
    physics_config: Option<PathBuf>,
    output: PathBuf,
    fps: Vec<u32>,
    cases: BTreeSet<String>,
    idle_seconds: f32,
    move_seconds: f32,
    move_speed: f32,
    settle_seconds: f32,
    sample_interval: f32,
    head_degrees: f32,
    arm_degrees: f32,
    mesh_dir: Option<PathBuf>,
    diagnostic_control: String,
    watch_body: Option<String>,
}

impl Options {
    fn parse() -> Result<Self, String> {
        let mut args = env::args().skip(1);
        let mut value = Self {
            models: Vec::new(),
            idle: None,
            walk: None,
            physics_config: None,
            output: PathBuf::from("Logs/hair-shoulder-20261015/runtime-probe.jsonl"),
            fps: vec![60, 144],
            cases: [
                "audit",
                "idle",
                "head-stop",
                "walk-stop",
                "reset",
                "rebuild",
            ]
            .into_iter()
            .map(str::to_owned)
            .collect(),
            idle_seconds: 10.0,
            move_seconds: 3.0,
            move_speed: 4.3,
            settle_seconds: 20.0,
            sample_interval: 0.25,
            head_degrees: 18.0,
            arm_degrees: 60.0,
            mesh_dir: None,
            diagnostic_control: "authored".to_owned(),
            watch_body: None,
        };
        while let Some(arg) = args.next() {
            let next = |args: &mut std::iter::Skip<std::env::Args>, key: &str| {
                args.next().ok_or_else(|| format!("{key} 缺少值"))
            };
            match arg.as_str() {
                "--model" => value
                    .models
                    .push(PathBuf::from(next(&mut args, "--model")?)),
                "--idle-vmd" => value.idle = Some(PathBuf::from(next(&mut args, "--idle-vmd")?)),
                "--walk-vmd" => value.walk = Some(PathBuf::from(next(&mut args, "--walk-vmd")?)),
                "--physics-config" => {
                    value.physics_config = Some(PathBuf::from(next(&mut args, "--physics-config")?))
                }
                "--out" => value.output = PathBuf::from(next(&mut args, "--out")?),
                "--fps" => {
                    value.fps = next(&mut args, "--fps")?
                        .split(',')
                        .map(|v| v.parse().map_err(|_| format!("无效 FPS: {v}")))
                        .collect::<Result<_, _>>()?;
                }
                "--cases" => {
                    value.cases = next(&mut args, "--cases")?
                        .split(',')
                        .map(str::to_owned)
                        .collect();
                }
                "--idle-seconds" => value.idle_seconds = parse_f32(&mut args, "--idle-seconds")?,
                "--move-seconds" => value.move_seconds = parse_f32(&mut args, "--move-seconds")?,
                "--move-speed" => value.move_speed = parse_f32(&mut args, "--move-speed")?,
                "--settle-seconds" => {
                    value.settle_seconds = parse_f32(&mut args, "--settle-seconds")?
                }
                "--sample-interval" => {
                    value.sample_interval = parse_f32(&mut args, "--sample-interval")?
                }
                "--head-degrees" => value.head_degrees = parse_f32(&mut args, "--head-degrees")?,
                "--arm-degrees" => value.arm_degrees = parse_f32(&mut args, "--arm-degrees")?,
                "--mesh-dir" => {
                    value.mesh_dir = Some(PathBuf::from(next(&mut args, "--mesh-dir")?))
                }
                "--diagnostic-control" => {
                    value.diagnostic_control = next(&mut args, "--diagnostic-control")?
                }
                "--watch-body" => {
                    let needle = next(&mut args, "--watch-body")?;
                    if needle.trim().is_empty() {
                        return Err("--watch-body 不可为空".to_owned());
                    }
                    value.watch_body = Some(needle);
                }
                "-h" | "--help" => return Err(usage().to_owned()),
                path if !path.starts_with('-') => value.models.push(PathBuf::from(path)),
                _ => return Err(format!("未知参数: {arg}\n{}", usage())),
            }
        }
        if value.models.is_empty() {
            return Err(usage().to_owned());
        }
        if value.fps.is_empty() || value.fps.iter().any(|fps| *fps == 0) {
            return Err("FPS 必须是正整数".to_owned());
        }
        if ![
            "authored",
            "root-x-free",
            "root-y-free",
            "root-z-free",
            "root-all-free",
            "shoulder-radius-70",
        ]
        .contains(&value.diagnostic_control.as_str())
        {
            return Err("未知 diagnostic-control".to_owned());
        }
        let known_cases = [
            "audit",
            "idle",
            "head-stop",
            "head-side-hold",
            "lateral-stop",
            "arm-contact-stop",
            "walk-stop",
            "reset",
            "rebuild",
        ];
        if value.cases.is_empty()
            || value
                .cases
                .iter()
                .any(|case| !known_cases.contains(&case.as_str()))
        {
            return Err(format!("未知或空 case；可用值：{}", known_cases.join(", ")));
        }
        if !value.idle_seconds.is_finite()
            || !value.move_seconds.is_finite()
            || !value.settle_seconds.is_finite()
            || !value.sample_interval.is_finite()
            || !value.head_degrees.is_finite()
            || !value.arm_degrees.is_finite()
            || !value.move_speed.is_finite()
            || value.idle_seconds < 0.0
            || value.move_seconds < 0.0
            || value.settle_seconds < 0.0
            || value.sample_interval <= 0.0
            || value.move_speed < 0.0
        {
            return Err("时间和运动参数必须有限且非负，采样间隔必须为正".to_owned());
        }
        if (value.cases.contains("head-stop")
            || value.cases.contains("head-side-hold")
            || value.cases.contains("lateral-stop")
            || value.cases.contains("walk-stop")
            || value.cases.contains("arm-contact-stop"))
            && value.move_seconds <= 0.0
        {
            return Err("动作 case 的 --move-seconds 必须大于零".to_owned());
        }
        if (value.cases.contains("walk-stop") || value.cases.contains("lateral-stop"))
            && value.move_speed <= 0.0
        {
            return Err("移动 case 的 --move-speed 必须大于零".to_owned());
        }
        if value.mesh_dir.is_some() && (value.models.len() != 1 || value.fps.len() != 1) {
            return Err("网格导出需单模型、单 FPS，避免覆盖不同条件的证据".to_owned());
        }
        Ok(value)
    }
}

fn parse_f32(args: &mut std::iter::Skip<std::env::Args>, key: &str) -> Result<f32, String> {
    args.next()
        .ok_or_else(|| format!("{key} 缺少值"))?
        .parse()
        .map_err(|_| format!("{key} 需要数字"))
}

fn usage() -> &'static str {
    "用法: cargo run --manifest-path rust_engine/Cargo.toml --example probe_hair_runtime -- <模型.pmx> [--model <模型.pmx> ...] [--physics-config <config.json>] [--idle-vmd <idle.vmd>] [--walk-vmd <walk.vmd>] [--out <文件>] [--fps 60,144] [--cases audit,idle,head-stop,head-side-hold,lateral-stop,arm-contact-stop,walk-stop,reset,rebuild] [--idle-seconds 10] [--move-seconds 3] [--move-speed 4.3] [--settle-seconds 20] [--sample-interval 0.25] [--head-degrees 18] [--arm-degrees 60] [--mesh-dir <目录>] [--watch-body <刚体名称片段>] [--diagnostic-control authored|root-x-free|root-y-free|root-z-free|root-all-free|shoulder-radius-70]"
}

fn find_default_anim(model_path: &Path, filename: &str) -> Option<PathBuf> {
    model_path
        .ancestors()
        .take(7)
        .map(|ancestor| ancestor.join("DefaultAnim").join(filename))
        .find(|path| path.is_file())
}

fn find_physics_config(model_path: &Path) -> Option<PathBuf> {
    model_path.ancestors().take(7).find_map(|ancestor| {
        ["config/MmdSkin/config.json", "config/mmdskin.json"]
            .iter()
            .map(|relative| ancestor.join(relative))
            .find(|path| path.is_file())
    })
}

fn config_bool(json: &Value, key: &str, fallback: bool) -> bool {
    json.get(key).and_then(Value::as_bool).unwrap_or(fallback)
}

fn config_f32(json: &Value, key: &str, fallback: f32) -> f32 {
    json.get(key)
        .and_then(Value::as_f64)
        .map(|value| value as f32)
        .unwrap_or(fallback)
}

fn config_i32(json: &Value, key: &str, fallback: i32) -> i32 {
    json.get(key)
        .and_then(Value::as_i64)
        .and_then(|value| i32::try_from(value).ok())
        .unwrap_or(fallback)
}

fn load_physics_config(path: Option<&Path>) -> Result<PhysicsConfig, String> {
    let mut config = PhysicsConfig::default();
    if let Some(path) = path {
        let bytes =
            fs::read(path).map_err(|e| format!("读取物理配置 {} 失败: {e}", path.display()))?;
        let value: Value = serde_json::from_slice(&bytes)
            .map_err(|e| format!("解析物理配置 {} 失败: {e}", path.display()))?;
        config.enabled = config_bool(&value, "physicsEnabled", config.enabled);
        config.gravity_y = config_f32(&value, "physicsGravityY", config.gravity_y);
        config.physics_fps = config_f32(&value, "physicsFps", config.physics_fps);
        config.max_substep_count =
            config_i32(&value, "physicsMaxSubstepCount", config.max_substep_count);
        config.inertia_strength =
            config_f32(&value, "physicsInertiaStrength", config.inertia_strength);
        config.max_linear_velocity = config_f32(
            &value,
            "physicsMaxLinearVelocity",
            config.max_linear_velocity,
        );
        config.max_angular_velocity = config_f32(
            &value,
            "physicsMaxAngularVelocity",
            config.max_angular_velocity,
        );
        config.joints_enabled = config_bool(&value, "physicsJointsEnabled", config.joints_enabled);
        config.collision_enabled =
            config_bool(&value, "physicsCollisionEnabled", config.collision_enabled);
        config.auto_upper_body_colliders = config_bool(
            &value,
            "physicsAutoUpperBodyColliders",
            config.auto_upper_body_colliders,
        );
        config.auto_lower_body_colliders = config_bool(
            &value,
            "physicsAutoLowerBodyColliders",
            config.auto_lower_body_colliders,
        );
        config.kinematic_filter =
            config_bool(&value, "physicsKinematicFilter", config.kinematic_filter);
        config.static_collider_scale = config_f32(
            &value,
            "physicsStaticColliderScale",
            config.static_collider_scale,
        );
        if let Some(mode) = value
            .get("physicsCollisionStabilityMode")
            .and_then(Value::as_str)
        {
            config.collision_stability_mode = match mode.to_ascii_uppercase().as_str() {
                "STRICT" => CollisionStabilityMode::Strict,
                "RELAXED" => CollisionStabilityMode::Relaxed,
                _ => CollisionStabilityMode::Stable,
            };
        }
    }
    // 只启用聚合诊断，不改模拟参数。
    config.debug_log = true;
    Ok(config)
}

fn is_hair_name(name: &str) -> bool {
    let lower = name.to_lowercase();
    if [
        "尾巴",
        "尻尾",
        "しっぽ",
        "tailbone",
        "tail_hair",
        "tailhair",
        "发饰",
        "髪飾",
        "頭飾",
        "头饰",
        "accessory",
        "ornament",
        "ribbon",
        "リボン",
        "袖",
        "裙",
        "sleeve",
        "skirt",
        "cloth",
        "dress",
    ]
    .iter()
    .any(|part| lower.contains(part))
    {
        return false;
    }
    [
        "髪",
        "髮",
        "ツインテール",
        "ポニーテール",
        "おさげ",
        "アホ毛",
        "馬尾",
        "雙馬尾",
        "辮",
        "头发",
        "頭髮",
        "长发",
        "发",
        "長髪",
        "后长发",
        "後髪",
        "马尾",
        "双马尾",
        "辫",
        "呆毛",
        "hair",
        "ヘア",
        "twintail",
        "ponytail",
        "braid",
        "bangs",
        "ahoge",
        "머리",
    ]
    .iter()
    .any(|part| lower.contains(part))
}

fn rigid_mode_name(mode: RigidBodyMode) -> &'static str {
    match mode {
        RigidBodyMode::Static => "mode0-static-follow-bone",
        RigidBodyMode::Dynamic => "mode1-dynamic",
        RigidBodyMode::DynamicWithBonePosition => "mode2-dynamic-bone-position",
    }
}

fn weight_bones(weight: &VertexWeight) -> Vec<(i32, f32)> {
    match weight {
        VertexWeight::Bdef1 { bone } => vec![(*bone, 1.0)],
        VertexWeight::Bdef2 { bones, weight } | VertexWeight::Sdef { bones, weight, .. } => {
            vec![(bones[0], *weight), (bones[1], 1.0 - *weight)]
        }
        VertexWeight::Bdef4 { bones, weights } | VertexWeight::Qdef { bones, weights } => {
            bones.iter().copied().zip(weights.iter().copied()).collect()
        }
    }
}

fn audit_model(model: &MmdModel) -> Value {
    let links: Vec<_> = model.bone_manager.links().collect();
    let mut entries = Vec::new();
    for (index, body) in model.rigid_bodies.iter().enumerate() {
        let bone_name = usize::try_from(body.bone_index)
            .ok()
            .and_then(|i| links.get(i))
            .map(|bone| bone.name.clone());
        if !is_hair_name(&body.local_name)
            && !is_hair_name(&body.universal_name)
            && !bone_name.as_deref().is_some_and(is_hair_name)
        {
            continue;
        }
        let body_bone = body.bone_index;
        let weighted_vertices = model
            .weights
            .iter()
            .filter(|weight| {
                weight_bones(weight)
                    .iter()
                    .any(|(bone, w)| *bone == body_bone && *w > 0.0)
            })
            .count();
        entries.push(json!({
            "body_index": index,
            "local_name": body.local_name,
            "universal_name": body.universal_name,
            "mode": rigid_mode_name(body.mode),
            "bone_index": body_bone,
            "bone_name": bone_name,
            "weighted_vertices": weighted_vertices,
            "candidate_status": if weighted_vertices > 0 && body.mode != RigidBodyMode::Static { "dynamic-hair-mesh-candidate" } else { "name-only-or-static-candidate" }
        }));
    }
    json!({
        "model": model.name,
        "vertices": model.vertices.len(),
        "bones": links.len(),
        "rigid_bodies": model.rigid_bodies.len(),
        "joints": model.joints.len(),
        "hair_name_candidates": entries,
        "qualification_boundary": "名称、模式和权重命中仅作候选审计；完整几何/链拓扑资格需后续阶段确认"
    })
}

fn run_asset_audit(model_path: &Path) -> Result<Value, String> {
    let model = load_pmx(model_path).map_err(|e| format!("{}: {e}", model_path.display()))?;
    let mode_by_index: HashMap<_, _> = model
        .rigid_bodies
        .iter()
        .enumerate()
        .map(|(i, body)| (i, body.mode))
        .collect();
    let geometry: Vec<_> = audit_hair_geometry(&model)
        .iter()
        .map(|entry| {
            let mode = mode_by_index
                .get(&entry.body_index)
                .copied()
                .unwrap_or(RigidBodyMode::Static);
            json!({
                "body_index": entry.body_index,
                "body_name": entry.body_name,
                "bone_index": entry.bone_index,
                "bone_name": entry.bone_name,
                "mode": rigid_mode_name(mode),
                "mode2_dynamic_bone_position": mode == RigidBodyMode::DynamicWithBonePosition,
                "status": if entry.status == AuditStatus::Candidate { "candidate" } else { "skipped" },
                "skip_reason": entry.skip_reason,
                "influenced_vertices": entry.weights.influenced_vertices,
                "positive_influences": entry.weights.positive_influences,
                "weight_sum": entry.weights.total_positive_weight,
                "mixed_boundary_vertices": entry.weights.mixed_boundary_vertices,
                "connected_components": entry.components.iter().map(|c| json!({
                    "vertices": c.vertex_count, "triangles": c.triangle_count,
                    "bounds_min": c.bounds_min.to_array(), "bounds_max": c.bounds_max.to_array()
                })).collect::<Vec<_>>(),
                "original_shape": entry.original.shape,
                "original_size": entry.original.size,
                "geometry": entry.geometry.as_ref().map(|g| json!({
                    "extent": g.extent.to_array(),
                    "required_sphere_radius": g.required_sphere_radius,
                    "original_sphere_coverage_ratio": g.original_sphere_coverage_ratio,
                    "capsule_axis": g.capsule_axis,
                    "fitted_capsule_radius": g.fitted_capsule_radius,
                    "fitted_capsule_segment_length": g.fitted_capsule_segment_length,
                    "original_shape_coverage_ratio": g.original_shape_coverage_ratio
                }))
            })
        })
        .collect();
    Ok(json!({
        "kind": "asset-audit",
        "model_path": model_path,
        "model_name": model.name,
        "model_stats": {"vertices": model.vertices.len(), "bones": model.bone_manager.bone_count(), "rigid_bodies": model.rigid_bodies.len(), "joints": model.joints.len()},
        "name_mode_weight_audit": audit_model(&model),
        "geometry_eligibility_audit": geometry,
        "qualification_boundary": "mode2 仅描述 PMX 模式；候选资格来自绑定链、权重和连通网格审计，不代表已启用任何物理修正"
    }))
}

fn representative_vertices(
    model: &MmdModel,
    hair_bones: &BTreeSet<usize>,
) -> Vec<(usize, usize, f32)> {
    let mut best = HashMap::<usize, (usize, f32)>::new();
    for (vertex_index, weight) in model.weights.iter().enumerate() {
        for (bone, amount) in weight_bones(weight) {
            let Ok(bone) = usize::try_from(bone) else {
                continue;
            };
            if !hair_bones.contains(&bone) || !amount.is_finite() || amount <= 0.0 {
                continue;
            }
            let slot = best.entry(bone).or_insert((vertex_index, amount));
            if amount > slot.1 {
                *slot = (vertex_index, amount);
            }
        }
    }
    let mut out: Vec<_> = best
        .into_iter()
        .map(|(bone, (vertex, amount))| (bone, vertex, amount))
        .collect();
    out.sort_by_key(|entry| entry.0);
    out
}

fn hair_body_indices(model: &MmdModel) -> BTreeSet<usize> {
    model
        .rigid_bodies
        .iter()
        .enumerate()
        .filter(|(_, body)| {
            is_hair_name(&body.local_name)
                || is_hair_name(&body.universal_name)
                || usize::try_from(body.bone_index)
                    .ok()
                    .and_then(|i| model.bone_manager.get_bone(i))
                    .is_some_and(|bone| is_hair_name(&bone.name))
        })
        .map(|(index, _)| index)
        .collect()
}

fn hair_bone_indices(model: &MmdModel) -> BTreeSet<usize> {
    model
        .rigid_bodies
        .iter()
        .filter(|body| {
            is_hair_name(&body.local_name)
                || is_hair_name(&body.universal_name)
                || usize::try_from(body.bone_index)
                    .ok()
                    .and_then(|i| model.bone_manager.get_bone(i))
                    .is_some_and(|bone| is_hair_name(&bone.name))
        })
        .filter_map(|body| usize::try_from(body.bone_index).ok())
        .filter(|index| *index < model.bone_manager.bone_count())
        .collect()
}

fn setup_animation(model: &mut MmdModel, path: &Path) -> Result<(), String> {
    let animation = VmdAnimation::load(path).map_err(|e| format!("{}: {e}", path.display()))?;
    model.set_layer_animation(0, Some(Arc::new(animation)));
    model.set_layer_loop(0, true);
    model.play_layer(0);
    Ok(())
}

fn bone_sample(model: &MmdModel, samples: &[(usize, usize, f32)]) -> Vec<Value> {
    samples
        .iter()
        .map(|(bone, vertex, influence)| {
            let link = model.bone_manager.links().nth(*bone);
            let world = link.map_or(Mat4::IDENTITY, |bone| bone.global_transform());
            let skin = model
                .bone_manager
                .get_skinning_matrices()
                .get(*bone)
                .copied()
                .unwrap_or(Mat4::IDENTITY);
            let position = model
                .update_positions
                .get(*vertex)
                .copied()
                .unwrap_or(Vec3::ZERO);
            json!({
                "bone_index": bone,
                "bone_name": link.map(|bone| bone.name.as_str()),
                "representative_vertex": vertex,
                "bone_influence": influence,
                "final_bone_world_matrix": world.to_cols_array(),
                "skinning_matrix": skin.to_cols_array(),
                "vertex_original": model.vertices.get(*vertex).map(|v| v.position.to_array()),
                "vertex_skinned": position.to_array()
            })
        })
        .collect()
}

fn sample(
    model: &mut MmdModel,
    label: &str,
    elapsed: f32,
    bone_samples: &[(usize, usize, f32)],
) -> Value {
    if let Some(row) = watch::sample(model, label, elapsed, bone_samples) {
        return row;
    }
    let hair_bones = hair_bone_indices(model);
    let all_bodies = model.physics_body_snapshots_matching("");
    let bodies: Vec<_> = all_bodies
        .iter()
        .filter(|body| {
            is_hair_name(&body.name)
                || usize::try_from(body.bone_index)
                    .ok()
                    .is_some_and(|i| hair_bones.contains(&i))
        })
        .collect();
    let body_indices: BTreeSet<usize> = bodies.iter().map(|body| body.index).collect();
    let mut contacts = Vec::new();
    let mut contact_total = 0usize;
    let mut contact_truncated = false;
    let mut contact_targets_truncated = false;
    let mut contact_step = 0u64;
    let mut contact_substeps = 0usize;
    let mut contact_requested_substeps = 0usize;
    let mut contact_phase = "unavailable";
    for body in &bodies {
        let batch = model.physics_contact_points_matching(&body.name, 256);
        contact_total += batch.total_points;
        contact_truncated |= batch.truncated;
        contact_targets_truncated |= batch.targets_truncated;
        contact_step = batch.step_serial;
        contact_substeps = batch.substeps;
        contact_requested_substeps = batch.requested_substeps;
        contact_phase = match batch.phase {
            mmd_engine::physics::bullet_ffi::ContactSamplePhase::CollisionDetection => {
                "collision-detection"
            }
            mmd_engine::physics::bullet_ffi::ContactSamplePhase::PhysicsStep => "physics-step",
            mmd_engine::physics::bullet_ffi::ContactSamplePhase::Unavailable => "unavailable",
        };
        contacts.extend(batch.points);
    }
    contacts.sort_by(|a, b| {
        a.a_index
            .cmp(&b.a_index)
            .then(a.b_index.cmp(&b.b_index))
            .then(a.point_a.x.total_cmp(&b.point_a.x))
            .then(a.point_a.y.total_cmp(&b.point_a.y))
            .then(a.point_a.z.total_cmp(&b.point_a.z))
    });
    contacts.dedup_by(|a, b| {
        a.a_index == b.a_index
            && a.b_index == b.b_index
            && a.point_a.abs_diff_eq(b.point_a, 1e-5)
            && a.point_b.abs_diff_eq(b.point_b, 1e-5)
    });
    let hair_names: BTreeSet<_> = bodies.iter().map(|body| body.name.as_str()).collect();
    let joints: Vec<_> = model
        .physics_joint_snapshots_matching("")
        .into_iter()
        .filter(|joint| {
            hair_names
                .iter()
                .any(|name| joint.body_a_name.contains(name) || joint.body_b_name.contains(name))
                || is_hair_name(&joint.name)
        })
        .collect();
    let issue_snapshot = model.issue_debug_diagnostic(2 | 4);
    let debug_window = model.take_physics_debug_diagnostic().unwrap_or_default();
    let peer_indices: BTreeSet<_> = contacts
        .iter()
        .flat_map(|p| [p.a_index, p.b_index])
        .filter(|i| !body_indices.contains(i))
        .collect();
    json!({
        "kind": "sample",
        "stage": label,
        "elapsed_seconds": elapsed,
        "body_snapshots": bodies.iter().map(|body| json!({
            "index": body.index, "name": body.name, "bone_index": body.bone_index,
            "mode": format!("{:?}", body.mode), "mass": body.mass,
            "shape": body.shape_name, "shape_size": body.shape_size,
            "raw_shape": body.raw_shape_name, "raw_shape_size": body.raw_shape_size, "proxy_source": body.proxy_source,
            "initial_transform": body.initial_transform.to_cols_array(),
        "body_from_final_bone_transform": body_from_final_bone_transform(model, body.bone_index, body.initial_transform).map(|matrix| matrix.to_cols_array()),
            "simulation_transform": body.simulation_transform.to_cols_array(),
            "render_transform": body.render_transform.to_cols_array(),
            "linear_velocity": body.linear_velocity.to_array(),
            "angular_velocity": body.angular_velocity.to_array()
        })).collect::<Vec<_>>(),
        // 接触对方的真实形状和姿态，避免用 PMX 绑定姿态猜测当前肩部位置。
        "contact_body_snapshots": all_bodies.iter().filter(|b| peer_indices.contains(&b.index))
            .map(|b| json!({"index":b.index,"name":b.name,"bone_index":b.bone_index,
                "shape":b.shape_name,"shape_size":b.shape_size,"raw_shape_size":b.raw_shape_size,
                "initial_transform":b.initial_transform.to_cols_array(),
                "simulation_transform":b.simulation_transform.to_cols_array(),
                "body_from_final_bone_transform":body_from_final_bone_transform(model,b.bone_index,b.initial_transform).map(|m|m.to_cols_array())
            })).collect::<Vec<_>>(),
        "contacts": {
            "total_points_before_dedup": contact_total,
            "truncated": contact_truncated,
            "targets_truncated": contact_targets_truncated,
            "step_serial": contact_step,
            "substeps": contact_substeps,
            "requested_substeps": contact_requested_substeps,
            "phase": contact_phase,
            "points": contacts.iter().map(|point| json!({
                "a_index": point.a_index, "b_index": point.b_index,
                "a_name": point.a_name, "b_name": point.b_name,
                "point_a": point.point_a.to_array(), "point_b": point.point_b.to_array(),
                "normal_on_b": point.normal_on_b.to_array(),
                "normal_impulse": point.normal_impulse, "penetration": point.penetration,
                "a_is_hair": body_indices.contains(&point.a_index),
                "b_is_hair": body_indices.contains(&point.b_index),
                "normal_impulse_on_a": (point.normal_impulse * point.normal_on_b).to_array(),
                "normal_impulse_on_b": (-point.normal_impulse * point.normal_on_b).to_array(),
                "upward_impulse_on_hair_a": if body_indices.contains(&point.a_index) {
                    (point.normal_impulse * point.normal_on_b.y).max(0.0)
                } else { 0.0 },
                "upward_impulse_on_hair_b": if body_indices.contains(&point.b_index) {
                    (-point.normal_impulse * point.normal_on_b.y).max(0.0)
                } else { 0.0 }
            })).collect::<Vec<_>>()
        },
        "constraints": joints.iter().map(|joint| json!({
            "name": joint.name, "body_a": joint.body_a_name, "body_b": joint.body_b_name,
            "anchor_error": joint.anchor_error, "anchor_a": joint.anchor_a.to_array(),
            "anchor_b": joint.anchor_b.to_array(),
            "angular_position": joint.diagnostic.angular_position.to_array(),
            "angular_lower": joint.diagnostic.angular_lower.to_array(),
            "angular_upper": joint.diagnostic.angular_upper.to_array(),
            "angular_violation": joint.diagnostic.angular_violation.to_array(),
            "spring_enabled": joint.diagnostic.spring_enabled,
            "stiffness": joint.diagnostic.stiffness
        })).collect::<Vec<_>>(),
        "hair_bones_and_skin_points": bone_sample(model, bone_samples),
        "diagnostic_difference": {
            "issue_debug_diagnostic_flags_6": issue_snapshot,
            "take_physics_debug_diagnostic_window": debug_window,
            "meaning": "issue快照是当前读取时刻的侧部物理/排名接触；take快照是物理层聚合窗口，缺项不等于无接触"
        }
    })
}

fn push_tick(
    model: &mut MmdModel,
    dt: f32,
    root_z: f32,
    head_y: f32,
    elapsed: &mut f32,
    last_sample: &mut f32,
    interval: f32,
    stage: &str,
    bone_samples: &[(usize, usize, f32)],
    output: &mut Vec<Value>,
) {
    push_tick_at(
        model,
        dt,
        Vec3::new(0.0, 0.0, root_z),
        head_y,
        elapsed,
        last_sample,
        interval,
        stage,
        bone_samples,
        output,
    );
}

fn push_tick_at(
    model: &mut MmdModel,
    dt: f32,
    root: Vec3,
    head_y: f32,
    elapsed: &mut f32,
    last_sample: &mut f32,
    interval: f32,
    stage: &str,
    bone_samples: &[(usize, usize, f32)],
    output: &mut Vec<Value>,
) {
    model.set_model_position_and_yaw(root.x, root.y, root.z, 0.0);
    model.set_head_angle(0.0, head_y, 0.0);
    model.tick_animation(dt);
    *elapsed += dt;
    if *elapsed - *last_sample + 1e-6 >= interval {
        output.push(sample(model, stage, *elapsed, bone_samples));
        *last_sample = *elapsed;
    }
}

fn body_from_final_bone_transform(
    model: &MmdModel,
    bone_index: i32,
    body_initial: Mat4,
) -> Option<Mat4> {
    let index = usize::try_from(bone_index).ok()?;
    let bone = model.bone_manager.links().nth(index)?;
    let z_flip = Mat4::from_scale(Vec3::new(1.0, 1.0, -1.0));
    let bind_right = Mat4::from_translation(bone.initial_position);
    let bind_left = z_flip * bind_right * z_flip;
    let final_left = z_flip * bone.global_transform() * z_flip;
    let expected = final_left * bind_left.inverse() * body_initial;
    expected.is_finite().then_some(expected)
}

fn main() -> ExitCode {
    let opts = match Options::parse() {
        Ok(opts) => opts,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::FAILURE;
        }
    };
    watch::initialize(opts.watch_body.as_deref());
    let mut rows = Vec::new();
    for model in &opts.models {
        if opts.cases.contains("audit") {
            match run_asset_audit(model) {
                Ok(row) => rows.push(row),
                Err(error) => rows
                    .push(json!({"kind":"error","model_path":model,"case":"audit","error":error})),
            }
        }
        for fps in &opts.fps {
            for case in &opts.cases {
                if case == "audit" {
                    continue;
                }
                match run_case(&opts, model, *fps, case) {
                    Ok(mut result) => rows.append(&mut result),
                    Err(error) => rows.push(json!({"kind":"error","model_path":model,"render_fps":fps,"case":case,"error":error})),
                }
            }
        }
    }
    let output = opts.output;
    if let Some(parent) = output.parent() {
        if let Err(error) = fs::create_dir_all(parent) {
            eprintln!("创建输出目录失败: {error}");
            return ExitCode::FAILURE;
        }
    }
    let contents = rows
        .iter()
        .map(Value::to_string)
        .collect::<Vec<_>>()
        .join("\n")
        + "\n";
    match fs::write(&output, contents) {
        Ok(()) => {
            println!("已写入 {} 行取证记录：{}", rows.len(), output.display());
            if rows.iter().any(|row| row["kind"] == "error") {
                eprintln!("存在失败 case，请检查输出中的 error 记录");
                ExitCode::FAILURE
            } else {
                ExitCode::SUCCESS
            }
        }
        Err(error) => {
            eprintln!("写入失败 {}: {error}", output.display());
            ExitCode::FAILURE
        }
    }
}
