use super::*;

/// 只驱动动画骨骼：手臂后摆、保持、回到待机，然后观察发链恢复。
pub(super) fn run(
    model: &mut MmdModel,
    opts: &Options,
    fps: u32,
    reps: &[(usize, usize, f32)],
    output: &mut Vec<Value>,
) -> Result<(), String> {
    let arm_index = model
        .bone_manager
        .links()
        .position(|b| {
            matches!(
                b.name.as_str(),
                "左腕" | "左上腕" | "左手臂" | "LeftArm" | "left_arm"
            )
        })
        .ok_or("arm-contact-stop 未识别左上臂；需人工选择骨骼，不能猜测索引")?;
    let dt = 1.0 / fps as f32;
    let mut elapsed = 0.0;
    let mut last_sample = 0.0;
    for _ in 0..(opts.idle_seconds * fps as f32).ceil() as usize {
        push_tick(
            model,
            dt,
            0.0,
            0.0,
            &mut elapsed,
            &mut last_sample,
            opts.sample_interval,
            "initial-idle-warmup",
            reps,
            output,
        );
    }
    let bone = model.bone_manager.get_bone(arm_index).unwrap();
    let base = bone.animation_rotate;
    let translation = bone.animation_translate;
    output.push(
        json!({"kind":"event", "stage":"arm-input-start", "elapsed_seconds":elapsed,
        "bone_index":arm_index, "bone_name":bone.name,
        "base_local_rotation":base.to_array(), "translation":translation.to_array(),
        "local_x_degrees":opts.arm_degrees, "ramp_seconds":opts.move_seconds,
        "hold_seconds":opts.move_seconds,
        "input_source":"set_vpd_bone_override; runtime RHS; no rigid-body teleport"}),
    );
    let steps = (opts.move_seconds * fps as f32).ceil() as usize;
    // 用显式命令行角度扫描，不能把此运动当作游戏原动作。
    for (stage, reverse) in [
        ("arm-approach", false),
        ("arm-hold", false),
        ("arm-release", true),
    ] {
        for i in 0..steps {
            let t = ((i + 1) as f32 / steps as f32).min(1.0);
            let fraction = if stage == "arm-hold" {
                1.0
            } else if reverse {
                1.0 - t
            } else {
                t
            };
            let smooth = fraction * fraction * (3.0 - 2.0 * fraction);
            let rotation = Quat::from_rotation_x(opts.arm_degrees.to_radians() * smooth) * base;
            model.set_vpd_bone_override(arm_index, translation, rotation);
            push_tick(
                model,
                dt,
                0.0,
                0.0,
                &mut elapsed,
                &mut last_sample,
                opts.sample_interval,
                stage,
                reps,
                output,
            );
        }
        output.push(json!({"kind":"event", "stage":stage, "elapsed_seconds":elapsed}));
    }
    model.clear_vpd_bone_overrides();
    output.push(json!({"kind":"event", "stage":"arm-input-cleared", "elapsed_seconds":elapsed}));
    for _ in 0..(opts.settle_seconds * fps as f32).ceil() as usize {
        push_tick(
            model,
            dt,
            0.0,
            0.0,
            &mut elapsed,
            &mut last_sample,
            opts.sample_interval,
            "stop-settle",
            reps,
            output,
        );
    }
    output.push(sample(model, "case-final", elapsed, reps));
    Ok(())
}
