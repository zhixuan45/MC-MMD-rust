use super::super::MMDPhysics;
use crate::physics::config::get_config;
use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyMode, RigidBodyShape};

const DT: f32 = 1.0 / 60.0;

fn dynamic_body(name: &str, position: [f32; 3]) -> PmxRigidBody {
    PmxRigidBody {
        local_name: name.to_owned(),
        universal_name: String::new(),
        bone_index: -1,
        group: 0,
        un_collision_group_flag: 0,
        shape: RigidBodyShape::Sphere,
        size: [0.08, 0.0, 0.0],
        position,
        rotation: [0.0; 3],
        mass: 1.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode: RigidBodyMode::Dynamic,
    }
}

fn run_motion(velocity: Vec3, idle_lift: bool, movement_boost: bool) -> (Vec3, Vec3, Vec3) {
    let config = get_config();
    assert!(config.inertia_strength > 0.0, "该回归需使用默认非零惯性");

    let mut physics = MMDPhysics::new().expect("Bullet 世界应可创建");
    physics.world.set_gravity(0.0, 0.0, 0.0);
    let bodies = [
        dynamic_body("Tail_01", [0.0, 0.0, 0.0]),
        dynamic_body("Accessory_01", [10.0, 0.0, 0.0]),
    ];
    physics.build_physics(&bodies, &[], &[]);
    physics.set_tail_physics_options(idle_lift, movement_boost);

    // 模拟已收敛的速度，只施力一步，避免未步进时积累多帧力。
    physics.prev_model_position = Some(Vec3::ZERO);
    physics.smoothed_model_velocity = velocity;
    physics.sync_bodies_with_model_velocity(&[], DT, Mat4::from_translation(velocity * DT));
    physics.step_simulation(DT);

    let tail_velocity = physics.rigid_bodies[0]
        .bullet_body
        .as_ref()
        .expect("尾巴 Bullet 刚体已创建")
        .get_linear_velocity();
    let other_velocity = physics.rigid_bodies[1]
        .bullet_body
        .as_ref()
        .expect("普通 Bullet 刚体已创建")
        .get_linear_velocity();
    let tail_angular = physics.rigid_bodies[0]
        .bullet_body
        .as_ref()
        .unwrap()
        .get_angular_velocity();
    (tail_velocity, other_velocity, tail_angular)
}

fn assert_vec3_close(actual: Vec3, expected: Vec3, tolerance: f32) {
    assert!(
        actual.abs_diff_eq(expected, tolerance),
        "实际速度 {actual:?}，期望 {expected:?}"
    );
}

#[test]
fn disabled_movement_uses_ordinary_inertia_without_extra_lift_or_torque() {
    let directions = [
        Vec3::X,
        -Vec3::X,
        Vec3::Y,
        -Vec3::Y,
        Vec3::Z,
        -Vec3::Z,
        Vec3::new(1.0, -2.0, 3.0).normalize(),
    ];
    for idle in [false, true] {
        for direction in directions {
            let (tail, other, angular) = run_motion(direction * 43.0, idle, false);
            // 第一帧没有闲置脉冲；尾巴应与同质量普通刚体一致。
            assert!(other.length() > 1e-4);
            assert_vec3_close(tail, other, 1e-5);
            assert_vec3_close(angular, Vec3::ZERO, 1e-6);
        }
    }
}

#[test]
fn enabled_movement_preserves_the_existing_wind_response() {
    let config = get_config();
    for velocity in [
        Vec3::X * 43.0,
        Vec3::Y * 43.0,
        Vec3::Z * 43.0,
        Vec3::Z * 56.0,
    ] {
        let (tail, other, _) = run_motion(velocity, false, true);
        let forward = velocity.z.max(0.0);
        let strength = config.inertia_strength;
        let original_wind = Vec3::new(
            -velocity.x * 2.8 * strength,
            -velocity.y * 1.8 * strength + 0.012 * forward * forward * strength,
            (velocity.z * 2.8 + 0.025 * forward * forward * strength) * strength,
        );
        assert_vec3_close(tail, original_wind * 1.5 * DT, 1e-5);
        let (_, baseline_other, _) = run_motion(velocity, false, false);
        assert_vec3_close(other, baseline_other, 1e-5);
    }
}

#[test]
fn disabling_movement_on_a_running_instance_stops_new_wind_and_torque() {
    let mut physics = MMDPhysics::new().expect("Bullet 世界");
    physics.world.set_gravity(0.0, 0.0, 0.0);
    physics.build_physics(
        &[
            dynamic_body("Tail_01", [0.0; 3]),
            dynamic_body("Accessory_01", [10.0, 0.0, 0.0]),
        ],
        &[],
        &[],
    );
    let velocity = Vec3::Z * 43.0;
    physics.set_tail_physics_options(false, true);
    physics.prev_model_position = Some(Vec3::ZERO);
    physics.smoothed_model_velocity = velocity;
    physics.sync_bodies_with_model_velocity(&[], DT, Mat4::from_translation(velocity * DT));
    physics.step_simulation(DT);
    let before = physics.rigid_bodies[0]
        .bullet_body
        .as_ref()
        .unwrap()
        .get_linear_velocity();
    let angular_before = physics.rigid_bodies[0]
        .bullet_body
        .as_ref()
        .unwrap()
        .get_angular_velocity();
    assert!(angular_before.length() > 1e-4);
    physics.set_tail_physics_options(false, false);
    physics.sync_bodies_with_model_velocity(&[], DT, Mat4::from_translation(velocity * DT * 2.0));
    physics.step_simulation(DT);
    let body = physics.rigid_bodies[0].bullet_body.as_ref().unwrap();
    let ordinary = Vec3::Z * velocity.z * get_config().inertia_strength * DT;
    // 关闭不抹掉已有动量，但下一步不再增加额外受力。
    assert_vec3_close(body.get_linear_velocity() - before, ordinary, 1e-5);
    assert_vec3_close(body.get_angular_velocity(), angular_before, 1e-5);
}

#[test]
fn idle_toggle_controls_the_entire_lift_force_independently_of_movement_boost() {
    for boost in [false, true] {
        for enabled in [false, true] {
            let mut physics = MMDPhysics::new().expect("Bullet 世界");
            physics.world.set_gravity(0.0, 0.0, 0.0);
            physics.build_physics(
                &[
                    dynamic_body("Tail_01", [0.0, 0.0, 0.0]),
                    dynamic_body("Accessory_01", [10.0, 0.0, 0.0]),
                ],
                &[],
                &[],
            );
            physics.set_tail_physics_options(enabled, boost);
            // 固定模型、不播放动作；排除重力与运动输入，只观察闲置力。
            for _ in 0..120 {
                physics.sync_bodies_with_model_velocity(&[], DT, Mat4::IDENTITY);
                physics.step_simulation(DT);
            }
            let tail = physics.rigid_bodies[0]
                .bullet_body
                .as_ref()
                .unwrap()
                .get_linear_velocity();
            let other = physics.rigid_bodies[1]
                .bullet_body
                .as_ref()
                .unwrap()
                .get_linear_velocity();
            let angular = physics.rigid_bodies[0]
                .bullet_body
                .as_ref()
                .unwrap()
                .get_angular_velocity();
            if enabled {
                assert!(
                    tail.y > 0.0 && tail.z > 0.0,
                    "闲置抬起应施加向上/后向力: {tail:?}"
                );
                assert!(angular.length() > 1e-4, "闲置抬起应独立产生力矩");
            } else {
                assert_vec3_close(tail, Vec3::ZERO, 1e-6);
                assert_vec3_close(angular, Vec3::ZERO, 1e-6);
            }
            assert_vec3_close(other, Vec3::ZERO, 1e-6);
        }
    }
}

#[test]
fn tail_force_at_local_tip_generates_angular_response_without_changing_linear_force() {
    let mut physics = MMDPhysics::new().expect("Bullet world");
    physics.world.set_gravity(0.0, 0.0, 0.0);
    physics.build_physics(&[dynamic_body("Tail_01", [0.0, 0.0, 0.0])], &[], &[]);
    let body = physics.rigid_bodies[0].bullet_body.as_ref().unwrap();
    body.apply_force_at_local_offset(Vec3::new(0.0, 0.0, 10.0), Vec3::new(0.0, 0.5, 0.0));
    physics.step_simulation(DT);
    let body = physics.rigid_bodies[0].bullet_body.as_ref().unwrap();
    let linear = body.get_linear_velocity();
    let angular = body.get_angular_velocity();
    assert!(linear.z > 0.0, "偏心施力仍应保留线性响应: {linear:?}");
    assert!(
        angular.x.abs() > 1e-4,
        "偏心施力应产生绕 X 轴力矩: {angular:?}"
    );
}

#[test]
fn idle_wave_drives_bullet_segments_in_bone_order_and_stops_on_disable() {
    let mut physics = MMDPhysics::new().expect("Bullet 世界");
    physics.world.set_gravity(0.0, 0.0, 0.0);
    // 刚体数组故意乱序，不能据此决定传播顺序。
    let bodies: Vec<_> = [2, 0, 1]
        .into_iter()
        .map(|bone_index| {
            let mut body = dynamic_body("Tail", [0.0, -(bone_index as f32), 0.0]);
            body.bone_index = bone_index;
            body
        })
        .collect();
    let transforms: Vec<_> = (0..3)
        .map(|index| Mat4::from_translation(Vec3::new(0.0, -(index as f32), 0.0)))
        .collect();
    physics.build_physics(&bodies, &[], &transforms);
    physics.set_tail_bone_names(&vec!["Tail".into(); 3], &[-1, 0, 1]);
    physics.set_tail_physics_options(true, false);
    let mut onset: [Option<usize>; 3] = [None; 3];
    for frame in 0..140 {
        physics.sync_bodies_with_model_velocity(&[], DT, Mat4::IDENTITY);
        physics.step_simulation(DT);
        for (index, body) in physics.rigid_bodies.iter().enumerate() {
            let velocity = body.bullet_body.as_ref().unwrap().get_linear_velocity();
            if velocity.y > 1e-4 && onset[index].is_none() {
                assert!(velocity.z > 0.0);
                onset[index] = Some(frame);
            }
        }
    }
    let [tip, root, mid] = onset.map(|frame| frame.expect("每段均需收到脉冲"));
    assert!(root < mid && mid < tip, "脉冲必须从尾根传向尾尖");
    assert!((tip - root).abs_diff(36) <= 1);
    physics.set_tail_physics_options(false, false);
    // 只清测试刚体的已有动量，以检测关闭后的新增受力。
    for body in &physics.rigid_bodies {
        let bullet = body.bullet_body.as_ref().unwrap();
        bullet.set_linear_velocity(0.0, 0.0, 0.0);
        bullet.set_angular_velocity(0.0, 0.0, 0.0);
    }
    for _ in 0..120 {
        physics.sync_bodies_with_model_velocity(&[], DT, Mat4::IDENTITY);
        physics.step_simulation(DT);
        for body in &physics.rigid_bodies {
            let bullet = body.bullet_body.as_ref().unwrap();
            assert_vec3_close(bullet.get_linear_velocity(), Vec3::ZERO, 1e-6);
            assert_vec3_close(bullet.get_angular_velocity(), Vec3::ZERO, 1e-6);
        }
    }
}
