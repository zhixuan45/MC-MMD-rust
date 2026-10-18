use super::{
    assert_vec3_close, body, kinematic_body, BulletRigidBody, BulletShape, BulletWorld,
    RigidBodyInfo,
};
use glam::{Mat4, Quat, Vec3};

fn static_body(shape: &BulletShape, transform: Mat4) -> BulletRigidBody {
    BulletRigidBody::new(
        &RigidBodyInfo {
            mass: 0.0,
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
    .expect("应能创建 Bullet 静态测试刚体")
}

#[test]
fn co_moving_dynamic_contact_preserves_shared_render_prediction() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let shape = BulletShape::sphere(0.5).unwrap();
    let a = body(&shape, Mat4::IDENTITY);
    let b = body(&shape, Mat4::from_translation(Vec3::new(0.99, 0.0, 0.0)));
    world.add_rigid_body(&a, 1, -1);
    world.add_rigid_body(&b, 1, -1);
    world.detect_collisions();
    assert!(!world.contact_manifolds().is_empty(), "应建立真实接触流形");

    let step = 1.0 / 60.0;
    a.set_linear_velocity(3.0, 0.0, 0.0);
    b.set_linear_velocity(3.0, 0.0, 0.0);
    world.step(step * 0.5, 1, step);
    let solved_a = a.get_simulation_transform();
    let solved_b = b.get_simulation_transform();
    let solved_gap = solved_b.w_axis.x - solved_a.w_axis.x;
    let solved_center = (solved_a.w_axis.x + solved_b.w_axis.x) * 0.5;
    let velocity_a = a.get_linear_velocity();
    let velocity_b = b.get_linear_velocity();
    world.sync_render_states();

    let rendered_a = a.get_transform();
    let rendered_b = b.get_transform();
    let delta_a = rendered_a.w_axis.x - solved_a.w_axis.x;
    let delta_b = rendered_b.w_axis.x - solved_b.w_axis.x;
    assert!((delta_a - 0.025).abs() < 1e-5, "A 侧预测被裁: {delta_a}");
    assert!((delta_b - 0.025).abs() < 1e-5, "B 侧预测被裁: {delta_b}");
    assert!((delta_a - delta_b).abs() < 1e-5, "共速接触被拆开: a={delta_a}, b={delta_b}");
    assert!(((rendered_b.w_axis.x - rendered_a.w_axis.x) - solved_gap).abs() < 1e-5);
    assert!(((rendered_a.w_axis.x + rendered_b.w_axis.x) * 0.5 - solved_center - 0.025).abs() < 1e-5);
    assert!(a.get_simulation_transform().abs_diff_eq(solved_a, 0.0));
    assert!(b.get_simulation_transform().abs_diff_eq(solved_b, 0.0));
    assert_vec3_close(a.get_linear_velocity(), velocity_a);
    assert_vec3_close(b.get_linear_velocity(), velocity_b);

    world.remove_rigid_body(&b);
    world.remove_rigid_body(&a);
}

#[test]
fn kinematic_target_motion_is_included_in_zero_substep_contact_prediction() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let shape = BulletShape::sphere(0.5).unwrap();
    let kinematic = kinematic_body(&shape, Mat4::IDENTITY);
    let dynamic = body(&shape, Mat4::from_translation(Vec3::new(0.99, 0.0, 0.0)));
    world.add_rigid_body(&kinematic, 1, -1);
    world.add_rigid_body(&dynamic, 1, -1);
    world.detect_collisions();
    assert!(!world.contact_manifolds().is_empty(), "应建立真实接触流形");

    let step = 1.0 / 60.0;
    let shared_delta = 0.01;
    kinematic.set_kinematic_target(Mat4::from_translation(Vec3::new(-shared_delta, 0.0, 0.0)));
    dynamic.set_linear_velocity(-shared_delta / (step * 0.5), 0.0, 0.0);
    let solved_kinematic = kinematic.get_simulation_transform();
    let solved_dynamic = dynamic.get_simulation_transform();
    let solved_gap = solved_dynamic.w_axis.x - solved_kinematic.w_axis.x;
    let dynamic_velocity = dynamic.get_linear_velocity();
    world.step(step * 0.5, 1, step);
    world.sync_render_states();

    let rendered_kinematic = kinematic.get_transform();
    let rendered_dynamic = dynamic.get_transform();
    assert!((rendered_kinematic.w_axis.x + shared_delta).abs() < 1e-5);
    assert!((rendered_dynamic.w_axis.x - (solved_dynamic.w_axis.x - shared_delta)).abs() < 1e-5);
    assert!(((rendered_dynamic.w_axis.x - rendered_kinematic.w_axis.x) - solved_gap).abs() < 1e-5);
    assert!(dynamic.get_simulation_transform().abs_diff_eq(solved_dynamic, 0.0));
    assert_vec3_close(dynamic.get_linear_velocity(), dynamic_velocity);

    world.remove_rigid_body(&dynamic);
    world.remove_rigid_body(&kinematic);
}

#[test]
fn approaching_dynamic_contact_keeps_center_motion_and_relative_spacing() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let shape = BulletShape::sphere(0.5).unwrap();
    let a = body(&shape, Mat4::IDENTITY);
    let b = body(&shape, Mat4::from_translation(Vec3::new(0.99, 0.0, 0.0)));
    world.add_rigid_body(&a, 1, -1);
    world.add_rigid_body(&b, 1, -1);
    world.detect_collisions();
    assert!(!world.contact_manifolds().is_empty(), "应建立真实接触流形");

    let step = 1.0 / 60.0;
    a.set_linear_velocity(3.0, 0.0, 0.0);
    b.set_linear_velocity(1.0, 0.0, 0.0);
    world.step(step * 0.5, 1, step);
    let solved_a = a.get_simulation_transform();
    let solved_b = b.get_simulation_transform();
    let solved_gap = solved_b.w_axis.x - solved_a.w_axis.x;
    let solved_center = (solved_a.w_axis.x + solved_b.w_axis.x) * 0.5;
    world.sync_render_states();

    let rendered_a = a.get_transform();
    let rendered_b = b.get_transform();
    assert!(((rendered_b.w_axis.x - rendered_a.w_axis.x) - solved_gap).abs() < 1e-5);
    assert!(((rendered_a.w_axis.x + rendered_b.w_axis.x) * 0.5 - solved_center - 0.016_666_7).abs() < 1e-5);
    assert!(a.get_simulation_transform().abs_diff_eq(solved_a, 0.0));
    assert!(b.get_simulation_transform().abs_diff_eq(solved_b, 0.0));
    assert_vec3_close(a.get_linear_velocity(), Vec3::new(3.0, 0.0, 0.0));
    assert_vec3_close(b.get_linear_velocity(), Vec3::new(1.0, 0.0, 0.0));

    world.remove_rigid_body(&b);
    world.remove_rigid_body(&a);
}

#[test]
fn contact_rotation_is_preserved_while_inward_point_motion_is_corrected() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let wall_shape = BulletShape::r#box(0.1, 0.5, 0.5).unwrap();
    let moving_shape = BulletShape::r#box(0.3, 0.1, 0.1).unwrap();
    let wall = static_body(&wall_shape, Mat4::IDENTITY);
    let moving = body(
        &moving_shape,
        Mat4::from_translation(Vec3::new(0.4, 0.0, 0.0)),
    );
    world.add_rigid_body(&wall, 1, -1);
    world.add_rigid_body(&moving, 2, -1);
    world.detect_collisions();
    let contacts = world.contact_points_for(&[&moving], 32).unwrap();
    assert!(contacts.total_hits > 0, "应建立真实接触点");

    let step = 1.0 / 60.0;
    moving.set_angular_velocity(0.0, 0.0, 2.0);
    world.step(step * 0.5, 1, step);
    let solved = moving.get_simulation_transform();
    let linear_velocity = moving.get_linear_velocity();
    let angular_velocity = moving.get_angular_velocity();
    let raw_rotation = Quat::from_rotation_z(angular_velocity.z * step * 0.5);
    let raw_prediction = Mat4::from_rotation_translation(raw_rotation, solved.w_axis.truncate());
    let raw_inward = contacts
        .points
        .iter()
        .map(|contact| {
            let delta_a = if contact.body_a == moving.as_ptr() as usize {
                let local = solved.inverse().transform_point3(contact.point_a);
                raw_prediction.transform_point3(local) - contact.point_a
            } else {
                Vec3::ZERO
            };
            let delta_b = if contact.body_b == moving.as_ptr() as usize {
                let local = solved.inverse().transform_point3(contact.point_b);
                raw_prediction.transform_point3(local) - contact.point_b
            } else {
                Vec3::ZERO
            };
            (delta_a - delta_b).dot(contact.normal_on_b)
        })
        .fold(f32::INFINITY, f32::min);
    assert!(raw_inward < -1e-5, "测试旋转未造成接触点内侵: {raw_inward}");
    world.sync_render_states();

    let rendered = moving.get_transform();
    let solved_rotation = Quat::from_mat4(&solved);
    let rendered_rotation = Quat::from_mat4(&rendered);
    assert!(solved_rotation.angle_between(rendered_rotation) > 0.01);
    assert!(moving.get_simulation_transform().abs_diff_eq(solved, 0.0));
    assert_vec3_close(moving.get_linear_velocity(), linear_velocity);
    assert_vec3_close(moving.get_angular_velocity(), angular_velocity);

    let wall_transform = wall.get_transform();
    for contact in contacts.points {
        let delta_a = if contact.body_a == moving.as_ptr() as usize {
            let local = solved.inverse().transform_point3(contact.point_a);
            rendered.transform_point3(local) - contact.point_a
        } else {
            Vec3::ZERO
        };
        let delta_b = if contact.body_b == moving.as_ptr() as usize {
            let local = solved.inverse().transform_point3(contact.point_b);
            rendered.transform_point3(local) - contact.point_b
        } else {
            Vec3::ZERO
        };
        assert!(
            (delta_a - delta_b).dot(contact.normal_on_b) >= -1e-4,
            "旋转后接触点进一步内侵: {contact:?}"
        );
    }
    assert!(wall.get_transform().abs_diff_eq(wall_transform, 0.0));

    world.remove_rigid_body(&moving);
    world.remove_rigid_body(&wall);
}

#[test]
fn relative_inward_prediction_stays_outside_static_contact() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let wall_shape = BulletShape::r#box(0.1, 1.0, 1.0).unwrap();
    let sphere_shape = BulletShape::sphere(0.2).unwrap();
    let wall = static_body(&wall_shape, Mat4::IDENTITY);
    let moving = body(
        &sphere_shape,
        Mat4::from_translation(Vec3::new(0.29, 0.0, 0.0)),
    );
    world.add_rigid_body(&wall, 1, -1);
    world.add_rigid_body(&moving, 2, -1);
    world.detect_collisions();
    assert!(!world.contact_manifolds().is_empty(), "应建立真实接触流形");

    let step = 1.0 / 60.0;
    moving.set_linear_velocity(-3.0, 0.0, 0.0);
    let wall_transform = wall.get_transform();
    let velocity = moving.get_linear_velocity();
    world.step(step * 0.5, 1, step);
    let solved = moving.get_simulation_transform();
    world.sync_render_states();

    assert!(moving.get_simulation_transform().abs_diff_eq(solved, 0.0));
    assert!(moving.get_transform().w_axis.x >= solved.w_axis.x - 1e-5);
    assert_vec3_close(moving.get_linear_velocity(), velocity);
    assert!(wall.get_transform().abs_diff_eq(wall_transform, 0.0));

    world.remove_rigid_body(&moving);
    world.remove_rigid_body(&wall);
}

#[test]
fn corner_contacts_do_not_reintroduce_inward_prediction() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let x_wall_shape = BulletShape::r#box(0.1, 2.0, 1.0).unwrap();
    let y_wall_shape = BulletShape::r#box(2.0, 0.1, 1.0).unwrap();
    let sphere_shape = BulletShape::sphere(0.2).unwrap();
    let x_wall = static_body(&x_wall_shape, Mat4::IDENTITY);
    let y_wall = static_body(&y_wall_shape, Mat4::IDENTITY);
    let moving = body(
        &sphere_shape,
        Mat4::from_translation(Vec3::new(0.29, 0.29, 0.0)),
    );
    world.add_rigid_body(&x_wall, 1, -1);
    world.add_rigid_body(&y_wall, 1, -1);
    world.add_rigid_body(&moving, 2, -1);
    world.detect_collisions();
    assert!(world.contact_manifolds().len() >= 2, "应建立墙角两侧接触");

    let step = 1.0 / 60.0;
    moving.set_linear_velocity(-3.0, -3.0, 0.0);
    let velocity = moving.get_linear_velocity();
    let solved = moving.get_simulation_transform();
    world.step(step * 0.5, 1, step);
    world.sync_render_states();

    let rendered = moving.get_transform();
    assert!(rendered.w_axis.x >= solved.w_axis.x - 1e-5);
    assert!(rendered.w_axis.y >= solved.w_axis.y - 1e-5);
    assert!(moving.get_simulation_transform().abs_diff_eq(solved, 0.0));
    assert_vec3_close(moving.get_linear_velocity(), velocity);

    world.remove_rigid_body(&moving);
    world.remove_rigid_body(&y_wall);
    world.remove_rigid_body(&x_wall);
}

#[test]
fn squeezed_rotating_body_falls_back_to_safe_solved_pose() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let wall_shape = BulletShape::r#box(0.1, 0.8, 0.5).unwrap();
    let moving_shape = BulletShape::r#box(0.3, 0.2, 0.1).unwrap();
    let left_wall = static_body(
        &wall_shape,
        Mat4::from_translation(Vec3::new(-0.39, 0.0, 0.0)),
    );
    let right_wall = static_body(
        &wall_shape,
        Mat4::from_translation(Vec3::new(0.39, 0.0, 0.0)),
    );
    let moving = body(&moving_shape, Mat4::IDENTITY);
    world.add_rigid_body(&left_wall, 1, -1);
    world.add_rigid_body(&right_wall, 1, -1);
    world.add_rigid_body(&moving, 2, -1);
    world.detect_collisions();
    assert!(world.contact_manifolds().len() >= 2, "应建立两侧夹持接触");
    let contacts = world.contact_points_for(&[&moving], 64).unwrap();
    assert!(contacts.total_hits >= 2, "应读取到两侧的接触点");

    let step = 1.0 / 60.0;
    moving.set_angular_velocity(0.0, 0.0, 2.0);
    let solved = moving.get_simulation_transform();
    let angular_velocity = moving.get_angular_velocity();
    world.step(step * 0.5, 1, step);
    world.sync_render_states();

    let rendered = moving.get_transform();
    assert!(Quat::from_mat4(&solved).angle_between(Quat::from_mat4(&rendered)) < 1e-5);
    assert!(moving.get_simulation_transform().abs_diff_eq(solved, 0.0));
    assert_vec3_close(moving.get_angular_velocity(), angular_velocity);
    for contact in contacts.points {
        let local_a = solved.inverse().transform_point3(contact.point_a);
        let local_b = solved.inverse().transform_point3(contact.point_b);
        let delta_a = if contact.body_a == moving.as_ptr() as usize {
            rendered.transform_point3(local_a) - contact.point_a
        } else {
            Vec3::ZERO
        };
        let delta_b = if contact.body_b == moving.as_ptr() as usize {
            rendered.transform_point3(local_b) - contact.point_b
        } else {
            Vec3::ZERO
        };
        assert!(
            (delta_a - delta_b).dot(contact.normal_on_b) >= -1e-4,
            "夹墙回退后仍加深接触: {contact:?}"
        );
    }

    world.remove_rigid_body(&moving);
    world.remove_rigid_body(&right_wall);
    world.remove_rigid_body(&left_wall);
}
