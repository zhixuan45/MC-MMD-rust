use super::{BulletConstraint, BulletRigidBody, BulletShape, BulletWorld, RigidBodyInfo};
use glam::{Mat4, Quat, Vec3};

fn body(shape: &BulletShape, transform: Mat4) -> BulletRigidBody {
    BulletRigidBody::new(
        &RigidBodyInfo {
            mass: 1.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            friction: 0.5,
            restitution: 0.0,
            additional_damping: false,
            is_kinematic: false,
            disable_deactivation: true,
            no_contact_response: false,
            initial_transform: transform,
        },
        shape,
    )
    .expect("应能创建 Bullet 测试刚体")
}

fn kinematic_body(shape: &BulletShape, transform: Mat4) -> BulletRigidBody {
    BulletRigidBody::new(
        &RigidBodyInfo {
            mass: 0.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            friction: 0.5,
            restitution: 0.0,
            additional_damping: false,
            is_kinematic: true,
            disable_deactivation: true,
            no_contact_response: false,
            initial_transform: transform,
        },
        shape,
    )
    .expect("应能创建 Bullet 运动学测试刚体")
}

extern "C" {
    fn bw_test_only_clear_motion_state(body: *mut std::ffi::c_void);
}

fn assert_vec3_close(actual: Vec3, expected: Vec3) {
    assert!(
        actual.abs_diff_eq(expected, 1e-4),
        "actual={actual:?}, expected={expected:?}"
    );
}


#[test]
fn refreshing_one_filter_preserves_other_contacts_and_can_restore_the_pair() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let shape = BulletShape::sphere(1.0).unwrap();
    let shell = kinematic_body(&shape, Mat4::IDENTITY);
    let other = kinematic_body(&shape, Mat4::from_translation(Vec3::new(2.5, 0.0, 0.0)));
    let dynamic = body(&shape, Mat4::from_translation(Vec3::X));
    world.add_rigid_body(&shell, 1, 2);
    world.add_rigid_body(&other, 1, 2);
    world.add_rigid_body(&dynamic, 2, 1);
    world.detect_collisions();
    assert_eq!(world.contact_manifolds().len(), 2);
    dynamic.set_ignore_collision_check(&shell, true);
    world.refresh_body_collision_filter(&dynamic);
    world.detect_collisions();
    let contacts = world.contact_manifolds();
    assert_eq!(contacts.len(), 1);
    assert!(contacts
        .iter()
        .all(|c| c.body_a != shell.as_ptr() as usize && c.body_b != shell.as_ptr() as usize));
    dynamic.set_ignore_collision_check(&shell, false);
    world.refresh_body_collision_filter(&dynamic);
    world.detect_collisions();
    assert_eq!(world.contact_manifolds().len(), 2);
    world.remove_rigid_body(&dynamic);
    world.remove_rigid_body(&other);
    world.remove_rigid_body(&shell);
}

#[test]
fn simulation_pose_is_distinct_from_interpolated_motion_state() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let shape = BulletShape::sphere(0.1).unwrap();
    let body = body(&shape, Mat4::IDENTITY);
    world.add_rigid_body(&body, 1, 0);
    body.set_linear_velocity(3.0, 0.0, 0.0);
    world.step(1.0 / 60.0, 5, 1.0 / 60.0);
    assert_vec3_close(
        body.get_simulation_transform().w_axis.truncate(),
        Vec3::new(0.05, 0.0, 0.0),
    );
    // 固定步的 MotionState 有一帧插值延迟，不能用于求解锚点诊断。
    assert!(body.get_transform().w_axis.x < body.get_simulation_transform().w_axis.x);
    world.remove_rigid_body(&body);
}

#[test]
fn six_dof_round_trip_preserves_frames_limits_and_defaults() {
    let shape = BulletShape::sphere(0.25).expect("应能创建 Bullet 测试形状");
    let transform_a = Mat4::from_rotation_translation(
        Quat::from_euler(glam::EulerRot::XYZ, 0.21, -0.37, 0.44),
        Vec3::new(1.2, -0.8, 2.4),
    );
    let transform_b = Mat4::from_rotation_translation(
        Quat::from_euler(glam::EulerRot::XYZ, -0.18, 0.29, -0.51),
        Vec3::new(-0.7, 1.6, 0.3),
    );
    let joint_transform = Mat4::from_rotation_translation(
        Quat::from_rotation_z(0.63) * Quat::from_rotation_y(-0.42) * Quat::from_rotation_x(0.17),
        Vec3::new(0.4, 0.9, -1.1),
    );
    let frame_a = transform_a.inverse() * joint_transform;
    let frame_b = transform_b.inverse() * joint_transform;
    let body_a = body(&shape, transform_a);
    let body_b = body(&shape, transform_b);
    let constraint = BulletConstraint::new_6dof_spring(&body_a, &body_b, frame_a, frame_b, true)
        .expect("应能创建 Bullet 测试约束");

    let linear_lower = Vec3::new(-0.3, -0.2, -0.1);
    let linear_upper = Vec3::new(0.4, 0.5, 0.6);
    let angular_lower = Vec3::new(-0.7, -0.5, -0.3);
    let angular_upper = Vec3::new(0.2, 0.4, 0.8);
    constraint.set_linear_lower_limit(linear_lower.x, linear_lower.y, linear_lower.z);
    constraint.set_linear_upper_limit(linear_upper.x, linear_upper.y, linear_upper.z);
    constraint.set_angular_lower_limit(angular_lower.x, angular_lower.y, angular_lower.z);
    constraint.set_angular_upper_limit(angular_upper.x, angular_upper.y, angular_upper.z);

    let diagnostic = constraint.diagnostic().expect("应能回读约束状态");
    assert!(diagnostic.frame_a.abs_diff_eq(frame_a, 1e-5));
    assert!(diagnostic.frame_b.abs_diff_eq(frame_b, 1e-5));
    assert_vec3_close(diagnostic.linear_position, Vec3::ZERO);
    assert_vec3_close(diagnostic.angular_position, Vec3::ZERO);
    assert_vec3_close(diagnostic.linear_lower, linear_lower);
    assert_vec3_close(diagnostic.linear_upper, linear_upper);
    assert_vec3_close(diagnostic.angular_lower, angular_lower);
    assert_vec3_close(diagnostic.angular_upper, angular_upper);
    assert_eq!(diagnostic.equilibrium, [0.0; 6]);
    assert_eq!(diagnostic.damping, [1.0; 6]);
    assert_eq!(diagnostic.spring_enabled, [false; 6]);
    assert!(diagnostic.use_frame_offset);
}


#[test]
fn kinematic_target_is_interpolated_across_batched_substeps() {
    let shape = BulletShape::sphere(0.25).expect("应能创建 Bullet 测试形状");
    let body = kinematic_body(&shape, Mat4::IDENTITY);
    let world = BulletWorld::new(0.0, 0.0, 0.0).expect("应能创建 Bullet 测试世界");
    world.add_rigid_body(&body, 1, -1);

    let dt = 1.0 / 60.0;
    let target = Mat4::from_rotation_translation(
        Quat::from_rotation_z(std::f32::consts::FRAC_PI_2),
        Vec3::X,
    );
    body.set_kinematic_target(target);
    world.step(dt * 4.0, 4, dt);

    assert!(body.get_simulation_transform().abs_diff_eq(target, 1e-5));
    // 四个子步把 1 米位移摊进 4/60 秒，每个子步速度为 15。
    assert_vec3_close(body.get_linear_velocity(), Vec3::new(15.0, 0.0, 0.0));
    assert_vec3_close(
        body.get_angular_velocity(),
        Vec3::new(0.0, 0.0, std::f32::consts::FRAC_PI_2 / (dt * 4.0)),
    );
    world.remove_rigid_body(&body);
}



#[test]
fn render_sync_predicts_free_motion_by_fixed_step_remainder() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).expect("应能创建 Bullet 测试世界");
    let shape = BulletShape::sphere(0.1).expect("应能创建 Bullet 测试形状");
    let body = body(&shape, Mat4::IDENTITY);
    world.add_rigid_body(&body, 1, 0);
    body.set_linear_velocity(3.0, 0.0, 0.0);
    let step = 1.0 / 60.0;
    world.step(step, 1, step);
    world.step(step * 0.5, 1, step);

    let solved = body.get_simulation_transform();
    assert!((world.render_time_offset() - step * 0.5).abs() < 1e-7);
    world.sync_render_states();
    assert!(body.get_simulation_transform().abs_diff_eq(solved, 0.0));
    assert!((body.get_transform().w_axis.x - (solved.w_axis.x + 3.0 * step * 0.5)).abs() < 1e-5);
    world.remove_rigid_body(&body);
}

#[test]
fn render_sync_does_not_advance_a_body_into_its_contact_plane() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).expect("应能创建 Bullet 测试世界");
    let wall_shape = BulletShape::r#box(0.1, 1.0, 1.0).expect("应能创建墙体");
    let sphere_shape = BulletShape::sphere(0.2).expect("应能创建球体");
    let wall = kinematic_body(&wall_shape, Mat4::IDENTITY);
    let moving = body(
        &sphere_shape,
        Mat4::from_translation(Vec3::new(0.29, 0.0, 0.0)),
    );
    world.add_rigid_body(&wall, 1, -1);
    world.add_rigid_body(&moving, 2, -1);
    world.detect_collisions();
    assert!(!world.contact_manifolds().is_empty());

    let step = 1.0 / 60.0;
    moving.set_linear_velocity(-3.0, 0.0, 0.0);
    world.step(step * 0.5, 5, step);
    let solved = moving.get_simulation_transform();
    world.sync_render_states();

    assert!(moving.get_simulation_transform().abs_diff_eq(solved, 0.0));
    assert!(
        moving.get_transform().w_axis.x >= solved.w_axis.x - 1e-5,
        "渲染预测进入接触平面: solved={:?}, render={:?}",
        solved.w_axis.truncate(),
        moving.get_transform().w_axis.truncate()
    );
    world.remove_rigid_body(&moving);
    world.remove_rigid_body(&wall);
}


fn run_kinematic_crossing(manual_substeps: bool) -> (f32, usize, f32) {
    let world = BulletWorld::new(0.0, 0.0, 0.0).expect("应能创建 Bullet 测试世界");
    let sphere = BulletShape::sphere(0.2).expect("应能创建运动学球体");
    let obstacle = BulletShape::r#box(0.1, 0.35, 0.35).expect("应能创建动态盒体");
    let mover = kinematic_body(&sphere, Mat4::from_translation(Vec3::new(-1.0, 0.0, 0.0)));
    let target = body(&obstacle, Mat4::IDENTITY);
    world.add_rigid_body(&mover, 1, -1);
    world.add_rigid_body(&target, 2, -1);

    let step = 1.0 / 60.0;
    let mut contact_samples = 0;
    let mut total_impulse = 0.0;
    if manual_substeps {
        for index in 1..=4 {
            let x = -1.0 + index as f32 * 0.5;
            mover.set_kinematic_target(Mat4::from_translation(Vec3::new(x, 0.0, 0.0)));
            world.step(step, 1, step);
            for contact in world.contact_manifolds() {
                contact_samples += 1;
                total_impulse += contact.total_applied_impulse;
            }
        }
    } else {
        mover.set_kinematic_target(Mat4::from_translation(Vec3::X));
        world.step(step * 4.0, 4, step);
        for contact in world.contact_manifolds() {
            contact_samples += 1;
            total_impulse += contact.total_applied_impulse;
        }
    }

    let final_x = target.get_simulation_transform().w_axis.x;
    world.remove_rigid_body(&target);
    world.remove_rigid_body(&mover);
    (final_x, contact_samples, total_impulse)
}

#[test]
fn batched_kinematic_crossing_matches_manual_substep_targets() {
    let batched = run_kinematic_crossing(false);
    let manual = run_kinematic_crossing(true);
    assert!(batched.1 > 0, "批量子步仍漏掉运动学体沿途接触");
    assert!(manual.1 > 0, "逐子步基线未建立接触");
    assert!(
        (batched.0 - manual.0).abs() < 1e-4,
        "批量={batched:?}, 手动={manual:?}"
    );
    assert!(batched.2 > 0.0, "批量子步未产生有效冲量: {batched:?}");
}



#[test]
fn kinematic_target_is_applied_before_constraint_solving() {
    let shape = BulletShape::sphere(0.25).expect("应能创建 Bullet 测试形状");
    let parent = kinematic_body(&shape, Mat4::IDENTITY);
    let child = body(&shape, Mat4::IDENTITY);
    let world = BulletWorld::new(0.0, 0.0, 0.0).expect("应能创建 Bullet 测试世界");
    world.add_rigid_body(&parent, 1, -1);
    world.add_rigid_body(&child, 1, -1);
    let constraint =
        BulletConstraint::new_6dof_spring(&parent, &child, Mat4::IDENTITY, Mat4::IDENTITY, true)
            .expect("应能创建运动学父体约束");
    constraint.set_linear_lower_limit(0.0, 0.0, 0.0);
    constraint.set_linear_upper_limit(0.0, 0.0, 0.0);
    world.add_constraint(&constraint, true);

    parent.set_kinematic_target(Mat4::from_translation(Vec3::X));
    let mut previous_violation = 1.0;
    for _ in 0..6 {
        world.step(1.0 / 60.0, 1, 1.0 / 60.0);

        // Bullet 按 ERP 分步纠偏，锚点误差应持续收敛而不是首步归零。
        let diagnostic = constraint.diagnostic().expect("应能回读约束状态");
        let violation = diagnostic.linear_violation.max_element();
        assert!(
            violation < previous_violation,
            "运动学父体的锚点误差未收敛: previous={previous_violation}, current={violation}"
        );
        previous_violation = violation;
    }
    assert!(
        previous_violation < 0.1,
        "6 个求解步后的锚点残差为 {previous_violation}"
    );
    assert_vec3_close(parent.get_transform().w_axis.truncate(), Vec3::X);
    world.remove_constraint(&constraint);
    world.remove_rigid_body(&child);
    world.remove_rigid_body(&parent);
}
