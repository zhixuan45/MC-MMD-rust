use super::{body, kinematic_body};
use crate::physics::bullet_ffi::{BulletShape, BulletWorld, ContactSamplePhase};
use glam::{Mat4, Vec3};

#[test]
fn solved_contact_keeps_points_normal_and_impulse_from_one_bullet_point() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let wall_shape = BulletShape::r#box(0.5, 0.5, 0.5).unwrap();
    let sphere_shape = BulletShape::sphere(0.25).unwrap();
    let wall = kinematic_body(&wall_shape, Mat4::IDENTITY);
    let sphere = body(
        &sphere_shape,
        Mat4::from_translation(Vec3::new(0.69, 0.0, 0.0)),
    );
    world.add_rigid_body(&wall, 1, 2);
    world.add_rigid_body(&sphere, 2, 1);
    sphere.set_linear_velocity(-1.0, 0.0, 0.0);
    world.step(1.0 / 60.0, 1, 1.0 / 60.0);

    let query = world.contact_points_for(&[&sphere], 32).unwrap();
    assert_eq!(query.phase, ContactSamplePhase::PhysicsStep);
    assert_eq!(query.step_serial, 1);
    assert_eq!(query.substeps, 1);
    assert_eq!(query.requested_substeps, 1);
    assert!(query.total_hits > 0, "未建立目标刚体的真实接触");
    let point = &query.points[0];
    assert!(point.distance < 0.0);
    assert!(point.applied_normal_impulse > 0.0);
    assert!(point.normal_on_b.is_normalized());
    let signed_distance = (point.point_a - point.point_b).dot(point.normal_on_b);
    assert!((signed_distance - point.distance).abs() < 1e-3);

    let direction_a = point
        .impulse_direction_for_body(point.body_a)
        .expect("A 侧应有冲量方向");
    let direction_b = point
        .impulse_direction_for_body(point.body_b)
        .expect("B 侧应有冲量方向");
    assert!(direction_a.abs_diff_eq(-direction_b, 1e-6));
    assert!(point.impulse_direction_for_body(usize::MAX).is_none());

    world.step(2.0 / 60.0, 1, 1.0 / 60.0);
    let clamped = world.contact_points_for(&[&sphere], 32).unwrap();
    assert_eq!(clamped.step_serial, 2);
    assert_eq!(clamped.substeps, 1);
    assert_eq!(clamped.requested_substeps, 2);
    world.remove_rigid_body(&sphere);
    world.remove_rigid_body(&wall);
}

#[test]
fn target_filter_counts_only_target_points_and_reports_capacity_truncation() {
    let world = BulletWorld::new(0.0, 0.0, 0.0).unwrap();
    let box_shape = BulletShape::r#box(0.5, 0.5, 0.5).unwrap();
    let moving = body(&box_shape, Mat4::IDENTITY);
    let other = kinematic_body(
        &box_shape,
        Mat4::from_translation(Vec3::new(-0.8, 0.0, 0.0)),
    );
    let target = kinematic_body(&box_shape, Mat4::from_translation(Vec3::new(0.8, 0.0, 0.0)));
    world.add_rigid_body(&moving, 2, 1);
    world.add_rigid_body(&other, 1, 2);
    world.add_rigid_body(&target, 1, 2);
    world.detect_collisions();

    let all = world.contact_points_for(&[], 128).unwrap();
    let targeted = world.contact_points_for(&[&target], 1).unwrap();
    assert!(all.total_hits > targeted.total_hits);
    assert!(targeted.total_hits > 1, "盒体接触应提供多个真实接触点");
    assert_eq!(targeted.points.len(), 1);
    assert!(targeted.truncated);
    assert!(targeted.points.iter().all(|point| {
        point.body_a == target.as_ptr() as usize || point.body_b == target.as_ptr() as usize
    }));

    let count_only = world.contact_points_for(&[&target], 0).unwrap();
    assert!(count_only.points.is_empty());
    assert_eq!(count_only.total_hits, targeted.total_hits);
    assert!(count_only.truncated);

    moving.set_ignore_collision_check(&target, true);
    world.refresh_body_collision_filter(&moving);
    world.detect_collisions();
    assert_eq!(
        world.contact_points_for(&[&target], 8).unwrap().total_hits,
        0
    );
    assert!(!world.contact_manifolds().is_empty(), "非目标接触应保留");
    world.remove_rigid_body(&target);
    world.remove_rigid_body(&other);
    world.remove_rigid_body(&moving);
}
