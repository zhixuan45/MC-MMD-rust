//! 检查跳帧恢复是否遗漏物理骨骼回写；不修改模型资产。
use glam::Mat4;
use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{model::load_pmx, physics::{set_config, PhysicsConfig}};
use std::{collections::BTreeSet, env};

fn distance(a: Mat4, b: Mat4) -> f32 {
    a.w_axis.truncate().distance(b.w_axis.truncate())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = env::args().nth(1).ok_or("需要 PMX 路径")?;
    let mut config = PhysicsConfig::default();
    config.static_collider_scale = 1.0;
    config.inertia_strength = 0.0;
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
    for _ in 0..180 { model.tick_animation_no_skinning(1.0 / 60.0); }
    let before = capture(&model);
    let solver_before = model.physics_joint_snapshots_matching("");
    // 超过默认 5/60 秒门槛，恢复帧也应输出已有物理姿态。
    model.tick_animation_no_skinning(0.1);
    let gap = capture(&model);
    let solver_gap = model.physics_joint_snapshots_matching("");
    model.tick_animation_no_skinning(1.0 / 60.0);
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
    println!("GAP_RESULT dynamic_bones={} animated_return={} max_visible_jump={max_jump:.6} solver_anchor_delta={solver_anchor_delta:.6} physics_enabled={}",
        indices.len(), animated_return, model.is_physics_enabled());
    Ok(())
}
