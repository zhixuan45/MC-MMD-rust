use super::*;

#[path = "cases/arm.rs"]
mod arm;
#[path = "cases/side.rs"]
mod side;

pub(super) fn run_case(
    opts: &Options,
    model_path: &Path,
    fps: u32,
    case: &str,
) -> Result<Vec<Value>, String> {
    reset_config();
    let physics_config_path = opts
        .physics_config
        .clone()
        .or_else(|| find_physics_config(model_path));
    let config = load_physics_config(physics_config_path.as_deref())?;
    let physics_fps = config.physics_fps;
    let config_report = json!({
        "enabled": config.enabled, "gravity_y": config.gravity_y,
        "physics_fps": config.physics_fps, "max_substep_count": config.max_substep_count,
        "inertia_strength": config.inertia_strength, "max_linear_velocity": config.max_linear_velocity,
        "max_angular_velocity": config.max_angular_velocity, "joints_enabled": config.joints_enabled,
        "collision_enabled": config.collision_enabled,
        "auto_upper_body_colliders": config.auto_upper_body_colliders,
        "auto_lower_body_colliders": config.auto_lower_body_colliders,
        "collision_stability_mode": config.collision_stability_mode.as_str(),
        "kinematic_filter": config.kinematic_filter,
        "static_collider_scale": config.static_collider_scale,
    });
    set_config(config);
    let idle_path = opts
        .idle
        .clone()
        .or_else(|| find_default_anim(model_path, "idle.vmd"))
        .ok_or_else(|| "未找到 DefaultAnim/idle.vmd，请传 --idle-vmd".to_owned())?;
    let walk_path = opts
        .walk
        .clone()
        .or_else(|| find_default_anim(model_path, "walk.vmd"));
    if case == "walk-stop" && walk_path.is_none() {
        return Err("walk-stop 需要 --walk-vmd 或可发现的 DefaultAnim/walk.vmd".to_owned());
    }
    let mut model = load_pmx(model_path).map_err(|e| format!("{}: {e}", model_path.display()))?;
    if let Some(needle) = opts.watch_body.as_deref() {
        // 拼错观察名称时明确失败，避免把空轨迹当作平稳。
        if !model.rigid_bodies.iter().any(|b| b.local_name.contains(needle)) {
            return Err(format!("--watch-body 没有匹配刚体：{needle}"));
        }
    }
    let audit = audit_model(&model);
    let control_changes = controls::apply(&mut model, &opts.diagnostic_control);
    let body_indices = hair_body_indices(&model);
    let bone_indices = hair_bone_indices(&model);
    if !model.init_physics() {
        return Err("完整 MmdModel 物理初始化失败".to_owned());
    }
    setup_animation(&mut model, &idle_path)?;
    let reps = representative_vertices(&model, &bone_indices);
    if reps.is_empty() {
        eprintln!("警告：头发名称候选没有找到关联骨骼权重代表点");
    }
    let dt = 1.0 / fps as f32;
    let head_amp = opts.head_degrees.to_radians();
    let mut elapsed = 0.0;
    let mut last_sample = 0.0;
    let mut output = vec![json!({
        "kind": "case_start", "model_path": model_path, "model_name": model.name,
        "case": case, "render_fps": fps, "physics_fps": physics_fps,
        "idle_vmd": idle_path, "walk_vmd": walk_path,
        "input_physics_parameters_changed": !control_changes.is_empty(),
        "diagnostic_control":opts.diagnostic_control,"diagnostic_control_changes":control_changes,
        "watch_body":opts.watch_body,
        "physics_config_path": physics_config_path,
        "physics_config_values": config_report,
        "diagnostic_only_override": "debug_log=true",
        "proxy_strategy_environment": env::var("MMD_HAIR_COLLIDER_COMPAT").ok(),
        "joint_strategy_environment": env::var("MMD_HAIR_JOINT_COMPAT").ok(),
        "root_motion": if case == "walk-stop" { json!({"speed_blocks_per_second": opts.move_speed, "source": "set_model_position_and_yaw probe input"}) } else { Value::Null },
        "hair_body_indices": body_indices,
        "asset_audit": audit
    })];
    let total_idle = opts.idle_seconds;
    let total_settle = opts.settle_seconds;

    if case == "head-side-hold" {
        side::run(&mut model, opts, fps, &reps, &mut output);
        return Ok(output);
    }
    if case == "lateral-stop" {
        side::run_lateral(
            &mut model,
            opts,
            fps,
            &reps,
            &idle_path,
            walk_path.as_deref(),
            &mut output,
        )?;
        return Ok(output);
    }

    if case == "arm-contact-stop" {
        arm::run(&mut model, opts, fps, &reps, &mut output)?;
        return Ok(output);
    }

    if case == "idle"
        || case == "head-stop"
        || case == "walk-stop"
        || case == "reset"
        || case == "rebuild"
    {
        let needs_warmup = matches!(case, "head-stop" | "walk-stop" | "reset" | "rebuild");
        if needs_warmup {
            output.push(json!({
                "kind": "event", "stage": "initial-idle-warmup-start",
                "elapsed_seconds": elapsed, "duration_seconds": total_idle,
                "head_angle_radians": 0.0, "root_position_blocks": [0.0, 0.0, 0.0]
            }));
            let warmup_steps = (total_idle * fps as f32).ceil() as usize;
            for _ in 0..warmup_steps {
                push_tick(
                    &mut model,
                    dt,
                    0.0,
                    0.0,
                    &mut elapsed,
                    &mut last_sample,
                    opts.sample_interval,
                    "initial-idle-warmup",
                    &reps,
                    &mut output,
                );
            }
            output.push(json!({
                "kind": "event", "stage": "initial-idle-warmup-end",
                "elapsed_seconds": elapsed, "duration_seconds": total_idle,
                "head_angle_radians": 0.0, "root_position_blocks": [0.0, 0.0, 0.0]
            }));
        }
        let walk_seconds = if case == "walk-stop" {
            opts.move_seconds
        } else {
            0.0
        };
        if walk_seconds > 0.0 {
            setup_animation(&mut model, walk_path.as_ref().unwrap())?;
        }
        let prelude = if walk_seconds > 0.0 {
            walk_seconds
        } else if case == "head-stop" {
            opts.move_seconds
        } else {
            total_idle
        };
        let steps = (prelude * fps as f32).ceil() as usize;
        for i in 0..steps {
            let t = i as f32 * dt;
            let head = if case == "head-stop" && t < opts.move_seconds {
                head_amp * (std::f32::consts::TAU * t / opts.move_seconds.max(dt)).sin()
            } else {
                0.0
            };
            push_tick(
                &mut model,
                dt,
                if walk_seconds > 0.0 {
                    opts.move_speed * ((i + 1) as f32 * dt).min(walk_seconds)
                } else {
                    0.0
                },
                head,
                &mut elapsed,
                &mut last_sample,
                opts.sample_interval,
                if walk_seconds > 0.0 {
                    "walk"
                } else if case == "head-stop" {
                    "head-swing"
                } else {
                    "idle"
                },
                &reps,
                &mut output,
            );
        }
        if walk_seconds > 0.0 {
            let idle_animation = VmdAnimation::load(&idle_path)
                .map_err(|e| format!("{}: {e}", idle_path.display()))?;
            model.transition_layer_to(0, Some(Arc::new(idle_animation)), 0.25);
            output.push(json!({"kind":"event","stage":"walk-to-idle","elapsed_seconds":elapsed,"transition":"layer0 replaced with actual idle VMD"}));
        } else if case == "head-stop" {
            output.push(json!({"kind":"event","stage":"head-stop","elapsed_seconds":elapsed,"head_angle_radians":0.0}));
        }
        if case == "reset" {
            model.reset_physics();
            output
                .push(json!({"kind":"event","stage":"reset-requested","elapsed_seconds":elapsed}));
        }
        if case == "rebuild" {
            model.request_physics_rebuild();
            output.push(
                json!({"kind":"event","stage":"rebuild-requested","elapsed_seconds":elapsed}),
            );
        }
        let settle_steps = (total_settle * fps as f32).ceil() as usize;
        for _ in 0..settle_steps {
            push_tick(
                &mut model,
                dt,
                if walk_seconds > 0.0 {
                    opts.move_speed * walk_seconds
                } else {
                    0.0
                },
                0.0,
                &mut elapsed,
                &mut last_sample,
                opts.sample_interval,
                "stop-settle",
                &reps,
                &mut output,
            );
        }
        output.push(sample(&mut model, "case-final", elapsed, &reps));
    }
    Ok(output)
}
