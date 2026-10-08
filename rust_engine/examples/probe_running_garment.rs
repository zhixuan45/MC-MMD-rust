//! 用完整模型运行资产跑步与裙摆刚体接触离线探针。

use std::{path::PathBuf, process::ExitCode, sync::Arc, time::Instant};

use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{
    animation::VmdAnimation,
    model::{load_pmx, MmdModel},
    physics::{reset_config, set_config, CollisionStabilityMode, PhysicsConfig},
};

const RENDER_FPS: [usize; 3] = [30, 60, 144];
const WARM_SECONDS: usize = 2;
const SAMPLE_SECONDS: usize = 3;
const ROTATION_EPSILON: f32 = 1e-6;
const WALK_SPEED: f32 = 4.3;
const SPRINT_SPEED: f32 = 5.6;

struct Asset {
    name: &'static str,
    model: PathBuf,
    animations: PathBuf,
    static_scale: f32,
}

fn lower(name: &str) -> String {
    name.to_lowercase()
}
fn is_leg(name: &str) -> bool {
    let name = lower(name);
    [
        "leg", "thigh", "shin", "calf", "足", "脚", "腿", "膝", "下半身",
        "腰", "hip", "pelvis", "waist",
    ]
        .iter()
        .any(|part| name.contains(part))
}
fn has_non_garment_semantics(name: &str) -> bool {
    let name = lower(name);
    [
        "胸", "乳", "バスト", "おっぱい", "breast", "bust", "chest", "shoe",
        "shoes", "heel", "靴", "くつ", "鞋", "accessory", "accessories",
        "アクセサリー", "装飾", "配件", "附件", "飾", "饰", "tassel", "穗",
        "穂", "房穗", "リボン", "ribbon", "bow", "蝴蝶结", "蝴蝶結",
        "袖", "sleeve", "手臂", "上臂", "前臂", "腕", "upperarm",
        "forearm", "髪", "髮", "头发", "頭髪", "hair",
    ]
    .iter()
    .any(|part| name.contains(part))
}
fn is_garment(local_name: &str, universal_name: &str) -> bool {
    if has_non_garment_semantics(local_name) || has_non_garment_semantics(universal_name) {
        return false;
    }
    let local = lower(local_name);
    let universal = lower(universal_name);
    [
        "skirt", "dress", "petticoat", "hem", "布", "裙", "衣摆",
        "スカート", "下摆", "裾", "下装", "下衣", "摆", "后摆",
        "风衣", "外套", "コート", "coat", "cloak", "cape", "燕尾", "flap",
    ]
    .iter()
    .any(|part| local.contains(part) || universal.contains(part))
}

fn print_mask_audit(model: &MmdModel) {
    let mut candidates = 0;
    let mut allowed = 0;
    for leg in model.rigid_bodies.iter().filter(|b| {
        b.mode == RigidBodyMode::Static && (is_leg(&b.local_name) || is_leg(&b.universal_name))
    }) {
        for skirt in model.rigid_bodies.iter().filter(|b| {
            b.mode != RigidBodyMode::Static
                && is_garment(&b.local_name, &b.universal_name)
        }) {
            candidates += 1;
            let mask_leg = leg.un_collision_group_flag;
            let mask_skirt = skirt.un_collision_group_flag;
            let can_collide =
                mask_leg & (1u16 << skirt.group) != 0 && mask_skirt & (1u16 << leg.group) != 0;
            allowed += usize::from(can_collide);
            println!("MASK_PAIR leg={:?}(group={},allowed_mask={:#06x}) skirt={:?}(group={},allowed_mask={:#06x}) pmx_allowed={}",
                leg.local_name, leg.group, leg.un_collision_group_flag,
                skirt.local_name, skirt.group, skirt.un_collision_group_flag, can_collide);
        }
    }
    println!(
        "MASK_SUMMARY leg_skirt_pairs={} pmx_allowed_pairs={} rule=mutual_group_bit_allowed",
        candidates, allowed
    );
}

fn run(asset: &Asset, animation_name: &str, render_fps: usize) -> Result<(), String> {
    PREVIOUS.with(|p| *p.borrow_mut() = None);
    let mut config = PhysicsConfig::default();
    config.physics_fps = 60.0;
    config.max_substep_count = 5;
    config.inertia_strength = 1.0;
    config.static_collider_scale = asset.static_scale;
    config.collision_stability_mode = CollisionStabilityMode::Stable;
    set_config(config);

    let mut model = load_pmx(&asset.model).map_err(|e| e.to_string())?;
    if animation_name != "bind" {
        let vmd_path = asset.animations.join(format!("{animation_name}.vmd"));
        let animation = VmdAnimation::load(vmd_path).map_err(|e| e.to_string())?;
        model.set_layer_animation(0, Some(Arc::new(animation)));
        model.set_layer_loop(0, true);
        model.play_layer(0);
    }
    model.update_node_animation(false);
    model.set_model_position_and_yaw(0.0, 0.0, 0.0, 0.0);
    if animation_name == "bind" && render_fps == 30 {
        print_mask_audit(&model);
    }
    if !model.init_physics() {
        return Err(format!("{} 物理初始化失败", asset.name));
    }

    let dynamic_skirt_bones: Vec<usize> = model
        .rigid_bodies
        .iter()
        .filter(|body| {
            body.mode == RigidBodyMode::Dynamic
                && is_garment(&body.local_name, &body.universal_name)
        })
        .filter_map(|body| usize::try_from(body.bone_index).ok())
        .collect();
    let dt = 1.0 / render_fps as f32;
    let total = (WARM_SECONDS + SAMPLE_SECONDS) * render_fps;
    let mut contact_frames = 0usize;
    let mut manifold_sum = 0usize;
    let mut manifold_max = 0usize;
    let mut depth_sum = 0.0f64;
    let mut depth_count = 0usize;
    let mut depth_max = 0.0f32;
    let mut max_step = 0.0f32;
    let mut step_delta_max = 0.0f32;
    let mut max_state_delta = 0.0f32;
    let mut invalid = 0usize;
    let mut frame_times_ms = Vec::with_capacity(SAMPLE_SECONDS * render_fps);
    let mut repeated_bone_frames = 0usize;
    let mut bone_frame_count = 0usize;
    let mut max_rotation_delta = 0.0f32;
    for frame in 0..total {
        let speed = match animation_name {
            "walk" => WALK_SPEED,
            "sprint" => SPRINT_SPEED,
            _ => 0.0,
        };
        model.set_model_position_and_yaw(
            0.0,
            0.0,
            speed * (frame + 1) as f32 / render_fps as f32,
            0.0,
        );
        let tick_started = Instant::now();
        model.tick_animation_no_skinning(dt);
        let tick_elapsed = tick_started.elapsed().as_secs_f64() * 1000.0;
        let warm_frames = WARM_SECONDS * render_fps;
        if frame + 1 == warm_frames {
            println!("CASE asset={} motion={} root_speed={} render_fps={} physics_fps=60 scale={} inertia=1.0 stable=true warm_s={} sample_s={} skirt_dynamic_bones={}",
                asset.name, animation_name, speed, render_fps, asset.static_scale, WARM_SECONDS, SAMPLE_SECONDS, dynamic_skirt_bones.len());
        }
        // 暖机帧不计入样本；边界帧留在暖机区，避免 contact_frames 比 sample_frames 多一帧。
        if frame + 1 <= warm_frames {
            continue;
        }
        frame_times_ms.push(tick_elapsed);
        // 相邻渲染帧比较动态裙摆骨骼，排除模型根位移。
        let mut current = Vec::with_capacity(dynamic_skirt_bones.len());
        for &bone in &dynamic_skirt_bones {
            let transform = model.bone_manager.get_global_transform(bone);
            if transform.is_finite() {
                current.push(transform);
            } else {
                invalid += 1;
                current.push(glam::Mat4::from_cols_array(&[f32::NAN; 16]));
            }
        }
        if let Some(previous) = PREVIOUS.with(|p| p.borrow().clone()) {
            for (before, now) in previous.iter().zip(&current) {
                let delta = before.w_axis.truncate().distance(now.w_axis.truncate());
                if delta.is_finite() {
                    step_delta_max = step_delta_max.max(delta);
                } else {
                    invalid += 1;
                }
                let (_, old_rotation, _) = before.to_scale_rotation_translation();
                let (_, new_rotation, _) = now.to_scale_rotation_translation();
                let rotation_delta =
                    2.0 * old_rotation.dot(new_rotation).abs().clamp(0.0, 1.0).acos();
                if rotation_delta.is_finite() {
                    max_rotation_delta = max_rotation_delta.max(rotation_delta);
                } else {
                    invalid += 1;
                }
                if delta <= ROTATION_EPSILON && rotation_delta <= ROTATION_EPSILON {
                    repeated_bone_frames += 1;
                }
                bone_frame_count += 1;
            }
        }
        PREVIOUS.with(|p| *p.borrow_mut() = Some(current));
        let snapshot = model.garment_physics_snapshot();
        manifold_sum += snapshot.contacts.len();
        manifold_max = manifold_max.max(snapshot.contacts.len());
        if !snapshot.contacts.is_empty() {
            contact_frames += 1;
        }
        max_step = max_step.max(step_delta_max);
        max_state_delta = max_state_delta.max(snapshot.max_simulation_motion_state_delta);
        for contact in &snapshot.contacts {
            if contact.max_penetration.is_finite() {
                depth_sum += contact.max_penetration as f64;
                depth_count += 1;
                depth_max = depth_max.max(contact.max_penetration);
            } else {
                invalid += 1;
            }
        }
        // 累积只读采样量，结尾输出该场景汇总。
    }
    frame_times_ms.sort_by(f64::total_cmp);
    let time_mean = frame_times_ms.iter().sum::<f64>() / frame_times_ms.len().max(1) as f64;
    let p95 = frame_times_ms
        .get((frame_times_ms.len().saturating_sub(1) * 95) / 100)
        .copied()
        .unwrap_or(0.0);
    let time_max = frame_times_ms.last().copied().unwrap_or(0.0);
    let speed = match animation_name {
        "walk" => WALK_SPEED,
        "sprint" => SPRINT_SPEED,
        _ => 0.0,
    };
    println!("RESULT asset={} motion={} root_speed={} render_fps={} physics_fps=60 scale={} inertia=1.0 stable=true sample_frames={} contact_frames={} contact_manifolds_mean_per_frame={:.4} contact_manifolds_max_per_frame={} penetration_mean_per_manifold={} penetration_max={} dynamic_skirt_bone_step_max={} dynamic_skirt_bone_rotation_step_max_deg={} repeated_bone_transform_ratio={} simulation_to_motion_state_delta_max={} tick_ms_mean={} tick_ms_p95={} tick_ms_max={} invalid={}",
        asset.name, animation_name, speed, render_fps, asset.static_scale, SAMPLE_SECONDS * render_fps,
        contact_frames, manifold_sum as f64 / (SAMPLE_SECONDS * render_fps) as f64,
        manifold_max, if depth_count == 0 { 0.0 } else { depth_sum / depth_count as f64 },
        depth_max, max_step, max_rotation_delta.to_degrees(),
        repeated_bone_frames as f64 / bone_frame_count.max(1) as f64,
        max_state_delta, time_mean, p95, time_max, invalid);
    Ok(())
}

thread_local! { static PREVIOUS: std::cell::RefCell<Option<Vec<glam::Mat4>>> = const { std::cell::RefCell::new(None) }; }

fn main() -> ExitCode {
    println!("METRICS garment contacts are Bullet leg-followbone/dynamic-garment manifolds with contact_count>0; penetration is Bullet proxy-body depth, not mesh intersection; bone repeat epsilon={ROTATION_EPSILON}; tick cost excludes GPU/rendering.");
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .find(|path| {
            path.join("neoforge/run/client/3d-skin/DefaultAnim")
                .is_dir()
        })
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")));
    let assets = [
        Asset { name: "蝶律", model: root.join("neoforge/run/client/3d-skin/EntityPlayer/TDA 方舟指令[乐正绫][蝶律] Ver 1.00/TDA 方舟指令[乐正绫][蝶律] Ver 1.00.pmx"), animations: root.join("neoforge/run/client/3d-skin/DefaultAnim"), static_scale: 1.0 },
        Asset { name: "Oguri-D", model: PathBuf::from(r"D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\3d-skin\EntityPlayer\oguri\1006_Oguri Cap.pmx"), animations: PathBuf::from(r"D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\3d-skin\DefaultAnim"), static_scale: 0.8 },
    ];
    let result = (|| -> Result<(), String> {
        for asset in &assets {
            for animation in ["bind", "idle", "walk", "sprint"] {
                for fps in RENDER_FPS {
                    run(asset, animation, fps)?;
                }
            }
        }
        Ok(())
    })();
    reset_config();
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("跑步裙摆探针失败: {error}");
            ExitCode::FAILURE
        }
    }
}
