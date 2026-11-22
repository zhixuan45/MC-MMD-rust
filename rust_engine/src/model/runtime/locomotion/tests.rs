use super::*;

fn input() -> LocomotionInput {
    LocomotionInput {
        mode: 2,
        grounded: true,
        terrain: true,
        origin: Vec3::new(0.25, 0.0, 0.25),
        entity: Vec3::new(0.25, 0.0, 0.25),
        movement: Vec3::new(0.20, 0.0, 0.0),
        scale: 0.09,
        surfaces: vec![
            [-1.0, -0.50, 0.0, 0.50, 0.00, 1.0],
            [0.50, -0.50, 0.0, 1.00, 0.50, 1.0],
            [1.00, 0.00, 0.0, 1.50, 1.00, 1.0],
        ],
        ..Default::default()
    }
}

fn move_to(input: &mut LocomotionInput, x: f32, y: f32, dy: f32) {
    input.tick += 1;
    input.frame += 1;
    input.entity = Vec3::new(x, y, 0.25);
    input.origin = input.entity;
    input.movement.y = dy;
}

#[test]
fn support_uses_the_actual_foot_point() {
    let input = input();
    assert_eq!(
        input.support(DVec3::new(0.49, 0.0, 0.25), 0.6, 0.6),
        Some(0.0)
    );
    assert_eq!(
        input.support(DVec3::new(0.50, 0.0, 0.25), 0.6, 0.6),
        Some(0.5)
    );
    assert_eq!(input.support(DVec3::new(0.50, 0.0, 1.01), 0.6, 0.6), None);
}

#[test]
fn half_step_reduces_peak_at_60_fps_in_both_directions() {
    for rising in [true, false] {
        let mut state = LocomotionState::default();
        let mut input = input();
        let (from, to, x) = if rising {
            (0.0, 0.5, 0.60)
        } else {
            (0.5, 0.0, 0.32)
        };
        if !rising {
            input.entity.x = 0.65;
            input.entity.y = from;
            input.origin = input.entity;
            input.movement.x = -input.movement.x;
        }
        assert_eq!(state.body_offset(&input, 0.0), Some(0.0));
        move_to(&mut input, x, to, to - from);
        input.origin.y = from;
        assert_eq!(state.body_offset(&input, 0.0), Some(0.0));
        let mut previous = from;
        let mut peak: f32 = 0.0;
        for frame in 1..=15 {
            let vanilla = from + (to - from) * (frame as f32 / 3.0).min(1.0);
            input.origin.y = vanilla;
            if frame > 3 {
                input.movement = Vec3::ZERO;
            }
            let offset = state.body_offset(&input, 1.0 / 60.0).unwrap();
            assert!(offset.abs() <= MAX_BODY_OFFSET);
            assert_eq!(state.body_offset(&input, 0.0), Some(offset));
            let visual = vanilla + offset;
            peak = peak.max((visual - previous).abs());
            previous = visual;
        }
        assert!(peak < 0.14, "maximum frame delta was {peak}");
        assert_eq!(previous, to);
    }
}

#[test]
fn consecutive_and_reversed_steps_retarget_from_current_height() {
    let mut state = LocomotionState::default();
    let mut input = input();
    state.body_offset(&input, 0.0);
    move_to(&mut input, 0.75, 0.5, 0.5);
    state.body_offset(&input, 0.02);
    state.body_offset(&input, 0.07);
    let (height, velocity) = state.step.as_ref().unwrap().sample();

    move_to(&mut input, 1.04, 1.0, 0.5);
    state.body_offset(&input, 0.0);
    let continued = state.step.as_ref().unwrap().sample();
    assert_eq!(continued, (height, velocity));
    state.body_offset(&input, 0.03);
    let before_turn = state.step.as_ref().unwrap().sample().0;

    move_to(&mut input, 0.82, 0.5, -0.5);
    state.body_offset(&input, 0.0);
    assert_eq!(state.step.as_ref().unwrap().sample().0, before_turn);
    for _ in 0..10 {
        state.body_offset(&input, 0.017);
    }
    assert!(state.step.is_none());
    assert_eq!(state.body_offset(&input, 0.017), Some(0.0));
}

#[test]
fn descent_can_begin_before_landing_but_jump_and_warp_clear_contact() {
    let mut state = LocomotionState::default();
    let mut input = input();
    input.entity.x = 0.75;
    input.entity.y = 0.5;
    input.origin = input.entity;
    state.begin_frame(&input, 0.016);
    state.body_offset(&input, 0.016);

    move_to(&mut input, 0.43, 0.45, -0.05);
    input.grounded = false;
    assert!(state.body_offset(&input, 0.016).is_some());
    assert_eq!(state.step.as_ref().unwrap().to, 0.0);
    input.tick += 1;
    input.movement.y = 0.42;
    assert!(state.body_offset(&input, 0.016).is_none());
    assert!(state.tread.is_none());

    state.feet[0].anchor = Some((DVec3::ZERO, 0.0));
    input.world = 3;
    input.frame += 1;
    state.begin_frame(&input, 0.016);
    assert!(state.feet[0].anchor.is_none());
    state.feet[0].anchor = Some((DVec3::ZERO, 0.0));
    input.movement.x = 5.0;
    input.frame += 1;
    state.begin_frame(&input, 0.016);
    assert!(state.feet[0].anchor.is_none());
}

#[test]
fn descent_keeps_intermediate_tread_when_the_collision_root_skips_it() {
    let mut state = LocomotionState::default();
    let mut input = input();
    move_to(&mut input, 1.08, 1.0, 0.0);
    input.movement.x = -0.25;
    state.body_offset(&input, 0.016);

    move_to(&mut input, 0.86, 0.92, -0.08);
    input.grounded = false;
    assert!(state.body_offset(&input, 0.016).is_some());
    assert_eq!(state.step.as_ref().unwrap().to, 0.5);

    move_to(&mut input, 0.44, 0.77, -0.15);
    input.movement.x = -0.42;
    state.body_offset(&input, 0.016);
    assert_eq!(state.step.as_ref().unwrap().to, 0.5);

    move_to(&mut input, 0.32, 0.54, -0.23);
    state.body_offset(&input, 0.016);
    assert_eq!(state.step.as_ref().unwrap().to, 0.0);
    assert_eq!(state.tread.unwrap().1, 0.0);

    move_to(&mut input, 0.26, 0.0, -0.25);
    input.grounded = true;
    state.body_offset(&input, 0.016);
    assert_eq!(state.step.as_ref().unwrap().to, 0.0);
}

#[test]
fn planted_ankle_remains_at_world_anchor_across_block_origins() {
    let mut input = input();
    input.block.x = 27_000_000;
    let mut contact = FootContact::default();
    let first = input.to_world(Vec3::new(0.0, 1.0, 0.0));
    let sample = |foot| ContactSample {
        foot,
        hip: foot + DVec3::Y * 0.4,
        reach: 0.78,
        surface: Some(0.0),
        terrain_y: 0.0,
        movable_stance: true,
        authored_stance: true,
        body: 0.0,
        dt: 0.06,
    };
    assert_eq!(contact.correction(&input, sample(first)), Vec3::ZERO);
    assert_eq!(contact.weight, 1.0);
    input.block.x += 1;
    input.origin.x -= 0.88;
    for bounds in &mut input.surfaces {
        bounds[0] -= 1.0;
        bounds[3] -= 1.0;
    }
    let moved = input.to_world(Vec3::new(0.0, 1.0, 0.0));
    let correction = contact.correction(&input, sample(moved));
    assert!((moved + correction.as_dvec3()).distance(first) < 0.00001);

    let swinging = ContactSample {
        movable_stance: false,
        authored_stance: false,
        dt: 0.02,
        ..sample(moved)
    };
    assert!(contact.correction(&input, swinging).length() < correction.length());
    assert!(contact.anchor.is_none());
    assert!(contact.weight > 0.0);
}

#[test]
fn walk_adapts_within_natural_band_and_sprint_adjusts_only_the_base_layer() {
    let walk = Arc::new(
        VmdAnimation::load_from_bytes(include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../common/src/main/resources/assets/mmdskin/default_anim/walk.vmd"
        )))
        .unwrap(),
    );
    let sprint = Arc::new(
        VmdAnimation::load_from_bytes(include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../common/src/main/resources/assets/mmdskin/default_anim/sprint.vmd"
        )))
        .unwrap(),
    );
    let sprint_stop = VmdAnimation::load_from_bytes(include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../common/src/main/resources/assets/mmdskin/default_anim/sprintStop.vmd"
    )))
    .unwrap();
    assert_eq!(sprint_stop.max_frame(), 22);
    for name in ["センター", "左足ＩＫ", "右足ＩＫ"] {
        let end = sprint_stop.get_bone_transform(name, sprint_stop.max_frame(), 0.0);
        assert!(
            end.translation.length() < 1e-4,
            "sprintStop should settle to neutral translation on {name}"
        );
    }
    let walk_speed = Gait::analyze(&walk).speed.unwrap();
    let sprint_speed = Gait::analyze(&sprint).speed.unwrap();
    assert!(
        (walk_speed * 0.09 - 4.32).abs() < 0.15,
        "unexpected authored walk speed: {}",
        walk_speed * 0.09
    );
    assert_eq!(walk.max_frame(), 20);
    assert!(
        (sprint_speed * 0.09 - 4.48).abs() < 0.25,
        "unexpected authored sprint speed: {}",
        sprint_speed * 0.09
    );
    assert_eq!(sprint.max_frame(), 16);
    for (clip_name, clip) in [("walk", &walk), ("sprint", &sprint)] {
        for name in [
            "左足ＩＫ",
            "右足ＩＫ",
            "センター",
            "下半身",
            "上半身",
            "上半身2",
            "首",
            "左腕",
            "右腕",
            "左ひじ",
            "右ひじ",
        ] {
            let start = clip.get_bone_transform(name, 0, 0.0);
            let end = clip.get_bone_transform(name, clip.max_frame(), 0.0);
            assert!(
                (start.translation - end.translation).length() < 1e-4,
                "{clip_name} loop translation mismatch on {name}"
            );
            assert!(
                start.orientation.dot(end.orientation).abs() > 0.9999,
                "{clip_name} loop rotation mismatch on {name}"
            );
        }
    }
    let mut layers = AnimationLayerManager::new(2);
    layers.set_layer_animation(0, Some(walk));
    layers.set_layer_animation(1, Some(Arc::clone(&sprint)));
    layers.get_layer_mut(0).unwrap().play();
    layers.get_layer_mut(1).unwrap().play();
    let mut state = LocomotionState::default();
    let mut input = input();
    // Even at 2x speed, walk caps upper cadence to 1.12x so it never turns frantic.
    input.movement.x = walk_speed * input.scale * 2.0 / 20.0;
    let dt = state.begin_frame(&input, 0.02);
    let cadence = state.cadence(&input, &layers, dt);
    assert!((cadence.unwrap().1 - 1.12).abs() < 1e-4);
    layers.update_with_locomotion(dt, cadence);
    let base_frame = layers.get_layer(0).unwrap().current_frame();
    assert!((base_frame - 0.02 * 30.0 * 1.12).abs() < 0.002);
    assert!((layers.get_layer(1).unwrap().current_frame() - 0.6).abs() < 0.001);

    let repeat = state.begin_frame(&input, 0.02);
    let cadence = state.cadence(&input, &layers, repeat);
    layers.update_with_locomotion(0.02, cadence);
    assert_eq!(layers.get_layer(0).unwrap().current_frame(), base_frame);

    // At sneaking speed (~1.3 m/s), walk slows down smoothly toward 0.35x.
    input.frame += 1;
    input.movement.x = 1.30 / 20.0;
    let dt = state.begin_frame(&input, 0.05);
    let slowed_walk = state.cadence(&input, &layers, dt).unwrap().1;
    assert!(slowed_walk < 1.0, "expected slowed walk rate, got {slowed_walk}");

    // At normal MC walk speed (~4.317 m/s), rate sits right around 1.0x.
    state.rate = None;
    input.frame += 1;
    input.movement.x = 4.317 / 20.0;
    let dt = state.begin_frame(&input, 0.02);
    let normal_rate = state.cadence(&input, &layers, dt).unwrap().1;
    assert!(
        (normal_rate - 1.0).abs() < 0.05,
        "expected ~1.0 walk rate at 4.317 m/s, got {normal_rate}"
    );
    state.set_sample_frame(&layers);
    assert!(state.gait.stance(0).is_some());

    input.mode = 3;
    layers.set_layer_animation(0, Some(sprint));
    layers.get_layer_mut(0).unwrap().play();
    input.frame += 1;
    input.movement.x = sprint_speed * input.scale * 2.0 / 20.0;
    let dt = state.begin_frame(&input, 0.02);
    let cadence = state.cadence(&input, &layers, dt);
    assert!((cadence.unwrap().1 - 1.60).abs() < 1e-4);
    layers.update_with_locomotion(dt, cadence);
    assert!((layers.get_layer(0).unwrap().current_frame() - 0.02 * 30.0 * 1.60).abs() < 0.002);
    input.frame += 1;
    input.movement.x *= 0.5;
    let dt = state.begin_frame(&input, 0.02);
    let slowed = state.cadence(&input, &layers, dt).unwrap().1;
    assert!(slowed > 1.0 && slowed < 1.60);
}

#[test]
fn stride_scale_adapts_to_leg_reach_and_caps_world_extent_to_one_block_per_foot() {
    // Reference model (Grass Wonder, reach = 8.92) keeps 1.0x stride.
    assert!((stride_scale_for(8.92, 0.09, 7.30, 3) - 1.0).abs() < 1e-3);
    // Tall HSR model (Zhenzhu, reach = 10.61) scales stride proportionally (~1.19x).
    let zhenzhu = stride_scale_for(10.61, 0.09, 7.30, 3);
    assert!((zhenzhu - 10.61 / 8.92).abs() < 1e-3);
    assert!(7.30 * zhenzhu * 0.09 <= MAX_FOOT_EXTENT_BLOCKS + 1e-4);
    // Large model scale still caps each foot to <= 1.0 block from origin (<= 2.0 blocks total).
    let huge_scale = stride_scale_for(12.5, 0.16, 7.30, 3);
    assert!(7.30 * huge_scale * 0.16 <= MAX_FOOT_EXTENT_BLOCKS + 1e-4);
    // Short / chibi model scales stride down proportionally.
    let chibi = stride_scale_for(6.0, 0.09, 7.30, 3);
    assert!((chibi - 6.0 / 8.92).abs() < 1e-3);
}

#[test]
fn flat_ground_walk_and_sprint_track_ground_without_early_release_or_crouch() {
    let fixtures = [
        "../run/3d-skin/EntityPlayer/grasswonder/1011_Grass Wonder.pmx",
        "../run/3d-skin/EntityPlayer/teio/1003_Tokai Teio.pmx",
        "../neoforge/run/client/3d-skin/EntityPlayer/TDA 方舟指令[乐正绫][蝶律] Ver 1.00/TDA 方舟指令[乐正绫][蝶律] Ver 1.00.pmx",
        "D:/minecraft/.minecraft/versions/1.21.1-NeoForge_21.1.252/3d-skin/EntityPlayer/真珠_眼部修复/星穹铁道—真珠2.pmx",
    ];
    let walk = Arc::new(
        VmdAnimation::load_from_bytes(include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../common/src/main/resources/assets/mmdskin/default_anim/walk.vmd"
        )))
        .unwrap(),
    );
    let sprint = Arc::new(
        VmdAnimation::load_from_bytes(include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../common/src/main/resources/assets/mmdskin/default_anim/sprint.vmd"
        )))
        .unwrap(),
    );
    let mut cfg = crate::physics::get_config();
    cfg.foot_ik_enabled = true;
    crate::physics::set_config(cfg);

    let mut failures = Vec::new();
    for (label, mode, speed_mps, clip) in [
        ("walk", 2, 4.317_f32, &walk),
        ("sprint", 3, 5.612_f32, &sprint),
    ] {
        for rel_path in fixtures {
            let pmx_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(rel_path);
            if !pmx_path.exists() {
                continue;
            }
            let mut model = crate::model::load_pmx(&pmx_path).expect("load fixture pmx");
            model
                .animation_layer_manager
                .set_layer_animation(0, Some(Arc::clone(clip)));
            model.animation_layer_manager.set_layer_loop(0, true);
            model.animation_layer_manager.get_layer_mut(0).unwrap().play();

            let dt = 1.0_f32 / 60.0;
            let mut world_z = 0.0_f64;
            let mut early_releases = 0usize;
            let mut max_pelvis_dip = 0.0_f32;
            let mut max_planted_slip = 0.0_f64;
            let mut prev_planted = [None::<DVec3>; 2];
            let mut sprint_lean_sum = 0.0_f32;
            let mut sprint_lean_count = 0usize;
            let mut min_foot_z_blocks = f32::INFINITY;
            let mut max_foot_z_blocks = f32::NEG_INFINITY;

            for step in 0..120 {
                world_z += f64::from(speed_mps * dt);
                let block_z = world_z.floor() as i32;
                let rel_z = (world_z - f64::from(block_z)) as f32;
                let loc = model.locomotion_input_mut();
                loc.mode = mode;
                loc.grounded = true;
                loc.terrain = true;
                loc.tick = (step / 3) as i64;
                loc.frame = step as i64 + 1;
                loc.world = 1;
                loc.block = IVec3::new(0, 0, block_z);
                loc.origin = Vec3::new(0.0, 0.0, rel_z);
                loc.entity = loc.origin;
                loc.movement = Vec3::new(0.0, 0.0, speed_mps / 20.0);
                loc.yaw = 0.0;
                loc.scale = 0.09;
                loc.surfaces = vec![[-3.0, -1.0, -3.0, 3.0, 0.0, 3.0]];

                model.tick_animation_no_skinning(dt);

                if step >= 10 {
                    for side in 0..2 {
                        if model.locomotion.feet[side].releasing {
                            early_releases += 1;
                        }
                        let ankle_name = if side == 0 { "左足首" } else { "右足首" };
                        let ankle_idx = model.bone_manager.find_bone_by_name(ankle_name).unwrap();
                        let ankle_pos = model
                            .bone_manager
                            .get_global_transform(ankle_idx)
                            .w_axis
                            .truncate();
                        let foot_z_blocks = ankle_pos.z * model.locomotion_input.scale;
                        min_foot_z_blocks = min_foot_z_blocks.min(foot_z_blocks);
                        max_foot_z_blocks = max_foot_z_blocks.max(foot_z_blocks);
                        let world_ankle = model.locomotion_input.to_world(ankle_pos);
                        if model.locomotion.feet[side].anchor.is_some()
                            && model.locomotion.feet[side].weight >= 0.99
                        {
                            if let Some(prev) = prev_planted[side] {
                                let slip = (world_ankle - prev).xz().length();
                                max_planted_slip = max_planted_slip.max(slip);
                            }
                            prev_planted[side] = Some(world_ankle);
                        } else {
                            prev_planted[side] = None;
                        }
                    }
                    max_pelvis_dip = max_pelvis_dip.max(model.locomotion.pelvis.abs());
                    if label == "sprint" {
                        let l_hip = model.bone_manager.find_bone_by_name("左足").unwrap();
                        let r_hip = model.bone_manager.find_bone_by_name("右足").unwrap();
                        let neck_idx = model.bone_manager.find_bone_by_name("首").unwrap();
                        let hip_mid = 0.5
                            * (model.bone_manager.get_global_transform(l_hip).w_axis.truncate()
                                + model.bone_manager.get_global_transform(r_hip).w_axis.truncate());
                        let neck = model.bone_manager.get_global_transform(neck_idx).w_axis.truncate();
                        let lean_deg = (neck.z - hip_mid.z).atan2(neck.y - hip_mid.y).to_degrees();
                        sprint_lean_sum += lean_deg;
                        sprint_lean_count += 1;
                    }
                }
            }
            let avg_lean_opt =
                (label == "sprint").then(|| sprint_lean_sum / sprint_lean_count as f32);
            failures.push((
                label,
                rel_path,
                avg_lean_opt,
                early_releases,
                max_pelvis_dip,
                max_planted_slip,
                min_foot_z_blocks,
                max_foot_z_blocks,
            ));
        }
    }
    for (
        label,
        rel_path,
        avg_lean_opt,
        early_releases,
        max_pelvis_dip,
        max_planted_slip,
        min_foot_z_blocks,
        max_foot_z_blocks,
    ) in failures
    {
        if let Some(avg_lean) = avg_lean_opt {
            assert!(
                (41.0..=49.0).contains(&avg_lean),
                "{label} on {rel_path}: expected 41..49 deg whole-body hip->neck lean, got {avg_lean:.1} deg"
            );
        }
        assert!(
            min_foot_z_blocks >= -MAX_FOOT_EXTENT_BLOCKS - 0.02
                && max_foot_z_blocks <= MAX_FOOT_EXTENT_BLOCKS + 0.02,
            "{label} on {rel_path}: foot Z extent [{min_foot_z_blocks:.2}, {max_foot_z_blocks:.2}] exceeded ±{MAX_FOOT_EXTENT_BLOCKS} block cap"
        );
        assert_eq!(
            early_releases, 0,
            "{label} on {rel_path}: feet released anchor prematurely on flat ground"
        );
        assert!(
            max_pelvis_dip < 0.025,
            "{label} on {rel_path}: unexpected flat-ground pelvis crouch: {max_pelvis_dip:.4}m"
        );
        assert!(
            max_planted_slip < 0.012,
            "{label} on {rel_path}: planted foot slipped {max_planted_slip:.4}m per frame"
        );
    }
}
