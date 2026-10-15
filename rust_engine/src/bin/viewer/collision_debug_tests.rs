use super::{append_runtime_body, mirror_z, CollisionLine};
use glam::{Mat4, Quat, Vec3};
use mmd_engine::physics::{PhysicsBodySnapshot, PhysicsMode};

fn snapshot(shape_name: &'static str, shape_size: [f32; 3]) -> PhysicsBodySnapshot {
    PhysicsBodySnapshot {
        index: 0,
        name: "viewer fixture".to_owned(),
        bone_index: -1,
        mode: PhysicsMode::Physics,
        mass: 1.0,
        shape_name,
        shape_size,
        raw_shape_size: shape_size,
        raw_shape_name: shape_name,
        proxy_source: "pmx",
        initial_transform: Mat4::IDENTITY,
        simulation_transform: Mat4::IDENTITY,
        render_transform: Mat4::IDENTITY,
        linear_velocity: Vec3::ZERO,
        angular_velocity: Vec3::ZERO,
    }
}

fn endpoints(lines: &[CollisionLine]) -> impl Iterator<Item = Vec3> + '_ {
    lines.iter().flat_map(|line| [line.start, line.end])
}

#[test]
fn runtime_shape_uses_motion_state_pose_and_bullet_to_model_handedness() {
    let mut body = snapshot("sphere", [0.25, 0.0, 0.0]);
    body.simulation_transform = Mat4::from_translation(Vec3::new(20.0, 0.0, 0.0));
    body.render_transform = Mat4::from_translation(Vec3::new(1.0, 2.0, -3.0));
    let mut lines = Vec::new();

    append_runtime_body(&mut lines, &body);

    assert_eq!(lines.len(), 96);
    assert!(lines[0].start.abs_diff_eq(Vec3::new(1.25, 2.0, 3.0), 1e-6));
    assert_eq!(lines[0].color, [1.0, 0.15, 0.1, 0.85]);
}

#[test]
fn runtime_box_and_capsule_draw_the_snapshotted_effective_dimensions() {
    let mut box_lines = Vec::new();
    append_runtime_body(&mut box_lines, &snapshot("box", [0.3, 0.6, 0.9]));
    assert_eq!(box_lines.len(), 12);
    let box_max = endpoints(&box_lines).fold(Vec3::ZERO, |max, p| max.max(p.abs()));
    assert!(box_max.abs_diff_eq(Vec3::new(0.3, 0.6, 0.9), 1e-6));

    let mut capsule_lines = Vec::new();
    append_runtime_body(&mut capsule_lines, &snapshot("capsule_y", [0.2, 0.8, 0.0]));
    assert_eq!(capsule_lines.len(), 132);
    let capsule_max = endpoints(&capsule_lines).fold(Vec3::ZERO, |max, p| max.max(p.abs()));
    assert!(capsule_max.abs_diff_eq(Vec3::new(0.2, 0.6, 0.2), 1e-5));
}

#[test]
fn handedness_conversion_round_trips_rotated_runtime_pose() {
    let pose = Mat4::from_rotation_translation(
        Quat::from_euler(glam::EulerRot::ZYX, 0.3, -0.5, 0.7),
        Vec3::new(1.0, 2.0, 3.0),
    );
    assert!(mirror_z(mirror_z(pose)).abs_diff_eq(pose, 1e-6));
}

#[test]
fn runtime_snapshot_with_nonfinite_pose_or_size_draws_nothing() {
    let mut body = snapshot("sphere", [0.25, 0.0, 0.0]);
    body.render_transform.w_axis.x = f32::NAN;
    let mut lines = Vec::new();
    append_runtime_body(&mut lines, &body);
    assert!(lines.is_empty());

    body.render_transform = Mat4::IDENTITY;
    body.shape_size[0] = f32::INFINITY;
    append_runtime_body(&mut lines, &body);
    assert!(lines.is_empty());
}
