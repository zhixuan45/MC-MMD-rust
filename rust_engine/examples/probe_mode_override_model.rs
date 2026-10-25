use std::{env, error::Error, fs, path::PathBuf, sync::Arc};

use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{model::load_pmx, VmdAnimation};
use serde_json::{json, Value};

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = env::args_os().skip(1).collect();
    if args.len() < 2 {
        return Err("usage: probe_mode_override_model <model.pmx> <motion-directory> [steps]".into());
    }
    let steps: usize = args.get(2).map_or(Ok(600), |v| v.to_string_lossy().parse())?;
    let mut motions: Vec<PathBuf> = fs::read_dir(&args[1])?
        .map(|entry| entry.map(|entry| entry.path()))
        .collect::<Result<_, _>>()?;
    motions.retain(|p| p.extension().is_some_and(|e| e.eq_ignore_ascii_case("vmd")));
    motions.sort();
    let mut reports = Vec::new();
    for path in motions {
        let animation = VmdAnimation::load(&path)?;
        let tracks = animation.bone_track_names().len();
        if tracks == 0 {
            eprintln!("Skipping non-bone VMD: {}", path.display());
            continue;
        }
        let max_frame = animation.max_frame();
        let mut model = load_pmx(&args[0])?;
        let mut authored = [0usize; 3];
        for body in &model.rigid_bodies {
            authored[body.mode as usize] += 1;
        }
        let affected: Vec<_> = model.rigid_bodies.iter()
            .filter(|rb| rb.mode == RigidBodyMode::DynamicWithBonePosition && rb.bone_index >= 0)
            .map(|rb| rb.bone_index as usize)
            .filter(|&bone| bone < model.bone_manager.bone_count())
            .collect();
        model.set_layer_animation(0, Some(Arc::new(animation)));
        model.play_layer(0);
        assert!(model.init_physics(), "physics initialization failed");
        let debug: Value = serde_json::from_str(&model.get_physics_debug_info())?;
        assert_eq!(debug["stats"]["dynamic_bone"].as_u64(), Some(0));
        assert_eq!(debug["stats"]["dynamic"].as_u64(), Some((authored[1] + authored[2]) as u64));
        let mut previous: Vec<_> = affected.iter()
            .map(|&i| model.bone_manager.get_global_transform(i).w_axis.truncate()).collect();
        let mut max_step = 0.0f32;
        for step in 0..steps {
            model.tick_animation_no_skinning(1.0 / 60.0);
            for i in 0..model.bone_manager.bone_count() {
                assert!(model.bone_manager.get_global_transform(i).is_finite(),
                    "non-finite bone {i} at step {step}");
            }
            for (index, &bone) in affected.iter().enumerate() {
                let position = model.bone_manager.get_global_transform(bone).w_axis.truncate();
                max_step = max_step.max(position.distance(previous[index]));
                previous[index] = position;
            }
        }
        let remaining_type_two = model.rigid_bodies.iter()
            .filter(|rb| rb.mode == RigidBodyMode::DynamicWithBonePosition).count();
        assert_eq!(remaining_type_two, authored[2], "authored modes were modified");
        reports.push(json!({
            "model": PathBuf::from(&args[0]), "motion": path,
            "bone_tracks": tracks, "motion_max_frame": max_frame,
            "authored_mode_counts_0_1_2": authored,
            "runtime": debug["stats"], "steps": steps, "dt": 1.0 / 60.0,
            "all_bone_transforms_finite": true,
            "max_type2_bone_translation_step_model_units": max_step,
            "visual_acceptance": "not_performed"
        }));
    }
    if reports.is_empty() {
        return Err("no VMD with bone tracks found".into());
    }
    println!("{}", serde_json::to_string_pretty(&reports)?);
    Ok(())
}
