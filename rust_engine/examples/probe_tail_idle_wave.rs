//! 导出真实模型尾巴闲置受力与骨骼位移，按同帧关闭效果的模型作对照。
use std::{
    env,
    error::Error,
    fs::File,
    io::{BufWriter, Write},
    path::Path,
};

use glam::Vec3;
use mmd_engine::{
    model::load_pmx,
    physics::{get_config, set_config, PhysicsConfig},
};

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = env::args().collect();
    let model_path = args.get(1).ok_or(
        "usage: probe_tail_idle_wave <model.pmx> <output.csv> [fps] [seconds] [idle,moving,reset] [blocks_per_model_unit]",
    )?;
    let out_path = args.get(2).ok_or("missing output.csv")?;
    let fps: usize = args.get(3).map(|v| v.parse()).transpose()?.unwrap_or(60);
    let seconds: usize = args.get(4).map(|v| v.parse()).transpose()?.unwrap_or(80);
    let scale: f32 = args.get(6).map(|v| v.parse()).transpose()?.unwrap_or(0.09);
    assert!(fps > 0 && seconds > 0 && scale.is_finite() && scale > 0.0);
    if let Some(parent) = Path::new(out_path).parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut out = BufWriter::new(File::create(out_path)?);
    writeln!(
        out,
        "case,time_s,bone,delay_s,lift_accel,y,rear_z,delta_y,delta_rear_z,wave_height"
    )?;
    let original = get_config();
    set_config(PhysicsConfig {
        inertia_strength: 0.3,
        ..PhysicsConfig::default()
    });
    let cases = args
        .get(5)
        .map(String::as_str)
        .unwrap_or("idle,moving,reset");
    std::fs::write(
        Path::new(out_path).with_extension("metadata.json"),
        serde_json::to_string_pretty(&serde_json::json!({
            "units": "blocks", "blocks_per_model_unit": scale, "fps": fps,
            "seconds_per_case": seconds, "cases": cases,
            "target_up_blocks": 1.0, "target_rear_blocks": 0.5,
            "random_interval_seconds": [10, 20], "propagation_seconds": 0.6
        }))?,
    )?;
    for case in cases.split(',') {
        assert!(["idle", "moving", "reset"].contains(&case));
        let mut active = load_pmx(model_path)?;
        let mut control = load_pmx(model_path)?;
        active.set_tail_physics_options(true, false);
        control.set_tail_physics_options(false, false);
        for model in [&mut active, &mut control] {
            model.set_tail_physics_scale(scale);
            model.update_node_animation(false);
            model.set_model_position_and_yaw(0.0, 0.0, 0.0, 0.0);
            assert!(model.init_physics());
        }
        let selected: Vec<_> = active
            .rigid_bodies
            .iter()
            .filter(|b| b.local_name.contains("Sp_Hi_Tail0_B") && b.mass > 0.0)
            .map(|b| b.bone_index as usize)
            .collect();
        assert!(!selected.is_empty(), "teio tail not found");
        let dt = 1.0 / fps as f32;
        let mut position = 0.0;
        for frame in 0..seconds * fps {
            let time = (frame + 1) as f32 * dt;
            let speed = if case == "moving" && (20.0..50.0).contains(&time) {
                4.3
            } else {
                0.0
            };
            position += speed * dt;
            if case == "reset" && frame == 30 * fps {
                active.reset_physics();
                control.reset_physics();
            }
            for model in [&mut active, &mut control] {
                model.set_model_position_and_yaw(0.0, 0.0, position, 0.0);
                model.tick_animation_no_skinning(dt);
            }
            let forces = active.physics_tail_idle_samples();
            assert_eq!(forces.len(), selected.len());
            for &bone in &selected {
                let current = active
                    .bone_manager
                    .get_global_transform(bone)
                    .w_axis
                    .truncate();
                let baseline = control
                    .bone_manager
                    .get_global_transform(bone)
                    .w_axis
                    .truncate();
                assert!(current.is_finite() && baseline.is_finite());
                let (_, delay, accel, wave_height) = forces
                    .iter()
                    .find(|(i, _, _, _)| *i == bone as i32)
                    .unwrap();
                let current = current * scale;
                let baseline = baseline * scale;
                let delta: Vec3 = current - baseline;
                writeln!(
                    out,
                    "{case},{time:.6},{bone},{delay:.6},{accel:.6},{:.6},{:.6},{:.6},{:.6},{:.6}",
                    current.y, -current.z, delta.y, -delta.z, wave_height
                )?;
            }
        }
        println!(
            "case={case} fps={fps} seconds={seconds} tail_bones={} finite=true units=blocks blocks_per_model_unit={scale}",
            selected.len()
        );
    }
    set_config(original);
    out.flush()?;
    Ok(())
}
