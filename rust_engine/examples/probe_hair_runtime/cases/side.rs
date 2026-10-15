use super::*;

pub(super) fn run(
    model: &mut MmdModel,
    opts: &Options,
    fps: u32,
    reps: &[(usize, usize, f32)],
    output: &mut Vec<Value>,
) {
    let dt = 1.0 / fps as f32;
    let mut elapsed = 0.0;
    let mut last_sample = 0.0;
    // 保持侧转后再回正，分别检查持续支撑和离开接触后的恢复。
    for (stage, duration, from, to) in [
        ("initial-idle-warmup", opts.idle_seconds, 0.0, 0.0),
        (
            "head-side-approach",
            opts.move_seconds,
            0.0,
            opts.head_degrees,
        ),
        (
            "head-side-hold",
            opts.settle_seconds,
            opts.head_degrees,
            opts.head_degrees,
        ),
        (
            "head-side-release",
            opts.move_seconds,
            opts.head_degrees,
            0.0,
        ),
        ("stop-settle", opts.settle_seconds, 0.0, 0.0),
    ] {
        let steps = (duration * fps as f32).ceil() as usize;
        output.push(json!({"kind":"event","stage":stage,"elapsed_seconds":elapsed,
            "duration_seconds":duration,"head_y_degrees_from":from,"head_y_degrees_to":to,
            "input_source":"set_head_angle; same runtime head tracking path; no rigid-body teleport"}));
        for i in 0..steps {
            let t = (i + 1) as f32 / steps as f32;
            let smooth = t * t * (3.0 - 2.0 * t);
            let angle = (from + (to - from) * smooth).to_radians();
            push_tick(
                model,
                dt,
                0.0,
                angle,
                &mut elapsed,
                &mut last_sample,
                opts.sample_interval,
                stage,
                reps,
                output,
            );
        }
        output.push(sample(model, &format!("{stage}-end"), elapsed, reps));
        if let Some(dir) = opts.mesh_dir.as_deref() {
            if let Err(error) = mesh::write(model, dir, stage, elapsed) {
                output.push(json!({"kind":"error","stage":stage,"error":error}));
            }
        }
    }
    output.push(sample(model, "case-final", elapsed, reps));
}

pub(super) fn run_lateral(
    model: &mut MmdModel,
    opts: &Options,
    fps: u32,
    reps: &[(usize, usize, f32)],
    idle: &Path,
    walk: Option<&Path>,
    output: &mut Vec<Value>,
) -> Result<(), String> {
    let walk = walk.ok_or("lateral-stop 需要真实 walk.vmd 动画")?;
    let dt = 1.0 / fps as f32;
    let mut elapsed = 0.0;
    let mut last_sample = 0.0;
    let mut root_x = 0.0;
    for (stage, duration, direction) in [
        ("initial-idle-warmup", opts.idle_seconds, 0.0),
        ("lateral-right-walk", opts.move_seconds, 1.0),
        ("right-stop-settle", opts.settle_seconds, 0.0),
        ("lateral-left-walk", opts.move_seconds, -1.0),
        ("left-stop-settle", opts.settle_seconds, 0.0),
    ] {
        if direction != 0.0 {
            setup_animation(model, walk)?;
        } else if stage != "initial-idle-warmup" {
            let animation = VmdAnimation::load(idle).map_err(|e| e.to_string())?;
            model.transition_layer_to(0, Some(Arc::new(animation)), 0.25);
        }
        output.push(json!({"kind":"event","stage":stage,"elapsed_seconds":elapsed,
            "root_x_blocks":root_x,"direction":direction,"duration_seconds":duration,
            "speed_blocks_per_second":opts.move_speed,"head_y_degrees":0.0,
            "input_source":"actual walk/idle VMD and lateral model movement; no rigid-body teleport"}));
        for _ in 0..(duration * fps as f32).ceil() as usize {
            root_x += direction * opts.move_speed * dt;
            push_tick_at(
                model,
                dt,
                Vec3::new(root_x, 0.0, 0.0),
                0.0,
                &mut elapsed,
                &mut last_sample,
                opts.sample_interval,
                stage,
                reps,
                output,
            );
        }
        output.push(sample(model, &format!("{stage}-end"), elapsed, reps));
        if let Some(dir) = opts.mesh_dir.as_deref() {
            mesh::write(model, dir, stage, elapsed)?;
        }
    }
    output.push(sample(model, "case-final", elapsed, reps));
    Ok(())
}
