//! 检查跳帧恢复是否遗漏物理骨骼回写；不修改模型资产。
use glam::Mat4;
use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{model::load_pmx, physics::{set_config, PhysicsConfig}};
use std::{collections::BTreeSet, env};

fn distance(a: Mat4, b: Mat4) -> f32 {
    a.w_axis.truncate().distance(b.w_axis.truncate())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let path = args.next().ok_or("需要 PMX 路径 [delta_seconds] [physics_fps] [cpu|gpu]")?;
    let gap_delta: f32 = args.next().as_deref().unwrap_or("0.1").parse()?;
    let physics_fps: f32 = args.next().as_deref().unwrap_or("60").parse()?;
    let cpu = args.next().as_deref() == Some("cpu");
    let mut config = PhysicsConfig::default();
    config.static_collider_scale = 1.0;
    config.inertia_strength = 0.0;
    config.physics_fps = physics_fps;
    set_config(config);
    let mut model = load_pmx(&path)?;
    model.update_node_animation(false);
    let indices: Vec<_> = model.rigid_bodies.iter()
        .filter(|b| b.mode != RigidBodyMode::Static && b.bone_index >= 0)
        .map(|b| b.bone_index as usize).collect::<BTreeSet<_>>().into_iter().collect();
    let capture = |model: &mmd_engine::model::MmdModel| -> Vec<Mat4> {
        indices.iter().map(|&i| model.bone_manager.get_global_transform(i)).collect()
    };
    let bind = capture(&model);
    if !model.init_physics() { return Err("物理初始化失败".into()); }
    let tick = |model: &mut mmd_engine::model::MmdModel, dt| {
        if cpu { model.tick_animation(dt); } else { model.tick_animation_no_skinning(dt); }
    };
    for _ in 0..180 { tick(&mut model, 1.0 / 60.0); }
    let before = capture(&model);
    let solver_before = model.physics_joint_snapshots_matching("");
    // 恢复帧和零步长重复求值也应输出已有物理姿态。
    tick(&mut model, gap_delta);
    let gap = capture(&model);
    let solver_gap = model.physics_joint_snapshots_matching("");
    tick(&mut model, 1.0 / 60.0);
    let after = capture(&model);
    let mut animated_return = 0;
    let mut max_jump = 0.0_f32;
    for (j, &index) in indices.iter().enumerate() {
        let jump = distance(before[j], gap[j]);
        max_jump = max_jump.max(jump);
        if distance(before[j], bind[j]) > 0.01 && distance(gap[j], bind[j]) < 1e-4 {
            animated_return += 1;
        }
        if jump > 0.1 {
            println!("BONE index={index} name={:?} before_to_bind={:.6} gap_to_bind={:.6} after_to_bind={:.6} gap_jump={jump:.6}",
                model.bone_manager.get_bone(index).map(|b| b.name.as_str()).unwrap_or(""),
                distance(before[j], bind[j]), distance(gap[j], bind[j]), distance(after[j], bind[j]));
        }
    }
    // 求解器锚点与显示骨骼分别对照，避免把显示跳变误认为刚体重置。
    let solver_anchor_delta = solver_before.iter().zip(&solver_gap).map(|(a,b)| {
        a.anchor_a.distance(b.anchor_a).max(a.anchor_b.distance(b.anchor_b))
    }).fold(0.0_f32, f32::max);
    println!("GAP_RESULT delta={gap_delta} physics_fps={physics_fps} cpu={cpu} dynamic_bones={} animated_return={} max_visible_jump={max_jump:.6} solver_anchor_delta={solver_anchor_delta:.6} physics_enabled={}",
        indices.len(), animated_return, model.is_physics_enabled());
    if gap_delta.is_finite() && (gap_delta <= 0.0 || gap_delta > 5.0 / physics_fps) {
        assert_eq!(animated_return, 0, "跳步时显示骨骼不能退回动画姿态");
        assert!(max_jump < 1e-4, "跳步时必须保持物理显示姿态");
        assert!(solver_anchor_delta < 1e-4, "跳步不能重置求解器锚点");
    }
    Ok(())
}
