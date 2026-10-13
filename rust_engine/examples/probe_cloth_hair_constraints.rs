//! Synthetic diagnosis only: no PMX assets or production parameters are modified.
use glam::{Mat4, Quat, Vec3};
use mmd::pmx::{
    joint::{Joint, JointType},
    rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape},
};
use mmd_engine::physics::{set_config, CollisionStabilityMode, MMDPhysics, PhysicsConfig};
use serde_json::json;

const DT: f32 = 1.0 / 60.0;

fn body(name: &str, index: i32, pos: Vec3, mode: RigidBodyMode) -> RigidBody {
    RigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: index,
        group: 3,
        un_collision_group_flag: u16::MAX,
        shape: RigidBodyShape::Box,
        size: [0.3, 1.0, 0.025],
        position: pos.to_array(),
        rotation: [0.0; 3],
        mass: 0.2,
        move_attenuation: 0.5,
        rotation_attenuation: 0.5,
        repulsion: 0.0,
        friction: 0.5,
        mode,
    }
}

fn joint(a: i32, b: i32, pos: Vec3, spring: f32) -> Joint {
    Joint {
        local_name: "probe_joint".into(),
        universal_name: String::new(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: a,
        rigid_body_b_index: b,
        position: pos.to_array(),
        rotation: [0.0; 3],
        position_min: [0.0; 3],
        position_max: [0.0; 3],
        rotation_min: [-0.6; 3],
        rotation_max: [0.6; 3],
        position_spring: [0.0; 3],
        rotation_spring: [spring; 3],
    }
}

fn build(
    bodies: &[RigidBody],
    joints: &[Joint],
    collision: bool,
    mode: CollisionStabilityMode,
    gravity: f32,
) -> (MMDPhysics, Vec<Mat4>) {
    set_config(PhysicsConfig {
        collision_enabled: collision,
        collision_stability_mode: mode,
        gravity_y: gravity,
        inertia_strength: 0.0,
        ..PhysicsConfig::default()
    });
    let bind: Vec<_> = bodies
        .iter()
        .map(|b| Mat4::from_translation(Vec3::new(b.position[0], b.position[1], -b.position[2])))
        .collect();
    let mut physics = MMDPhysics::new().unwrap();
    physics.build_physics(bodies, joints, &bind);
    physics.initialize(&bind);
    (physics, bind)
}

fn hair_fixture(name: &str, reversed: bool, spring: f32) -> (MMDPhysics, Vec<Mat4>) {
    let head = body("頭", 0, Vec3::new(0.0, 10.0, 0.0), RigidBodyMode::Static);
    let hair = body(name, 1, Vec3::new(0.0, 8.0, 1.0), RigidBodyMode::Dynamic);
    let j = joint(
        if reversed { 1 } else { 0 },
        if reversed { 0 } else { 1 },
        Vec3::new(0.0, 10.0, 1.0),
        spring,
    );
    build(
        &[head, hair],
        &[j],
        false,
        CollisionStabilityMode::Stable,
        -98.0,
    )
}

fn inspect_hair_sign(reversed: bool, axis: Vec3, angle: f32) {
    let (physics, _) = hair_fixture("後髪_01", reversed, 16.0);
    let pivot = Vec3::new(0.0, 10.0, 1.0);
    let rotation = Quat::from_axis_angle(axis, angle);
    let hair_body = physics.rigid_bodies[1].bullet_body.as_ref().unwrap();
    let position = pivot + rotation * (Vec3::new(0.0, 8.0, 1.0) - pivot);
    hair_body.set_transform(Mat4::from_rotation_translation(rotation, position));
    let s = physics.joint_snapshots_matching("").remove(0);
    println!(
        "{}",
        json!({
            "case":"hair_limit_sign", "reversed":reversed, "axis":axis.to_array(),
            "physical_rotation_deg":angle.to_degrees(), "center":position.to_array(),
            "outward_delta_z":position.z - 1.0,
            "bullet_angle_deg":s.diagnostic.angular_position.to_array().map(f32::to_degrees),
            "limit_lower_deg":s.diagnostic.angular_lower.to_array().map(f32::to_degrees),
            "limit_upper_deg":s.diagnostic.angular_upper.to_array().map(f32::to_degrees),
            "violation_deg":s.diagnostic.angular_violation.to_array().map(f32::to_degrees)
        })
    );
}

fn hair_head_tilt(name: &str, reversed: bool, spring: f32, pitch: f32) {
    let (mut physics, mut pose) = hair_fixture(name, reversed, spring);
    let initial = physics.joint_snapshots_matching("").remove(0).diagnostic;
    for frame in 0..900 {
        // Left-handed +pitch corresponds to Minecraft look-up (right-handed -pitch).
        let t = ((frame as f32 - 300.0) / 120.0).clamp(0.0, 1.0);
        let q = Quat::from_rotation_x(-pitch * t);
        pose[0] = Mat4::from_rotation_translation(q, Vec3::new(0.0, 10.0, 0.0));
        physics.sync_bodies(&pose);
        physics.step_simulation(DT);
    }
    let transform = physics.rigid_bodies[1]
        .bullet_body
        .as_ref()
        .unwrap()
        .get_simulation_transform();
    let (_, q, p) = transform.to_scale_rotation_translation();
    let s = physics.joint_snapshots_matching("").remove(0);
    println!(
        "{}",
        json!({
            "case":"head_tilt", "name":name, "reversed":reversed, "spring":spring,
            "head_pitch_left_deg":pitch.to_degrees(), "hair_center_left":p.to_array(),
            "hair_down_axis_left":(q * -Vec3::Y).to_array(),
            "hair_world_pitch_deg":q.to_euler(glam::EulerRot::XYZ).0.to_degrees(),
            "effective_lower_deg":initial.angular_lower.to_array().map(f32::to_degrees),
            "effective_upper_deg":initial.angular_upper.to_array().map(f32::to_degrees),
            "anchor_error":s.anchor_error,
            "angular_violation_deg":s.diagnostic.angular_violation.to_array().map(f32::to_degrees)
        })
    );
}

fn static_plates(shared_root: bool, collision: bool, mode: CollisionStabilityMode) {
    let mut a = body(
        "リボン_L",
        2,
        Vec3::new(0.0, 8.0, 0.0),
        RigidBodyMode::Dynamic,
    );
    let mut b = body(
        "リボン_R",
        3,
        Vec3::new(0.0, 8.0, 0.02),
        RigidBodyMode::Dynamic,
    );
    a.size = [0.5, 1.0, 0.025];
    b.size = a.size;
    b.mass = 0.1;
    b.rotation = [0.01, 0.02, 0.03];
    let mut root_a = body(
        "anchor_L",
        0,
        Vec3::new(0.0, 10.0, 0.0),
        RigidBodyMode::Static,
    );
    let mut root_b = body(
        "anchor_R",
        1,
        Vec3::new(0.0, 10.0, 0.02),
        RigidBodyMode::Static,
    );
    // Isolate dynamic-dynamic contact; anchor geometry must not inject energy.
    root_a.un_collision_group_flag = 0;
    root_b.un_collision_group_flag = 0;
    let ja = joint(0, 2, Vec3::new(0.0, 9.0, 0.0), 16.0);
    let jb = joint(
        if shared_root { 0 } else { 1 },
        3,
        Vec3::new(0.0, 9.0, 0.02),
        16.0,
    );
    let (mut physics, pose) = build(&[root_a, root_b, a, b], &[ja, jb], collision, mode, -98.0);
    let mut previous = [Mat4::IDENTITY; 2];
    let mut max_delta = 0.0_f32;
    let mut mean_delta = 0.0_f64;
    let mut max_angle = 0.0_f32;
    let mut max_speed = 0.0_f32;
    let mut max_anchor_error = 0.0_f32;
    let mut count = 0;
    for frame in 0..600 {
        physics.sync_bodies(&pose);
        physics.step_simulation(DT);
        for (slot, index) in [2, 3].into_iter().enumerate() {
            let body = physics.rigid_bodies[index].bullet_body.as_ref().unwrap();
            let current = body.get_simulation_transform();
            if frame >= 300 {
                let (_, q0, p0) = previous[slot].to_scale_rotation_translation();
                let (_, q1, p1) = current.to_scale_rotation_translation();
                let delta = p0.distance(p1);
                max_delta = max_delta.max(delta);
                mean_delta += delta as f64;
                max_angle = max_angle.max(2.0 * q0.dot(q1).abs().min(1.0).acos());
                max_speed = max_speed.max(body.get_linear_velocity().length());
                count += 1;
            }
            previous[slot] = current;
        }
        if frame >= 300 {
            for j in physics.joint_snapshots_matching("") {
                max_anchor_error = max_anchor_error.max(j.anchor_error);
            }
        }
    }
    let a = physics.rigid_bodies[2].bullet_body.as_ref().unwrap();
    let b = physics.rigid_bodies[3].bullet_body.as_ref().unwrap();
    println!(
        "{}",
        json!({
            "case":"static_overlapping_plates", "shared_root":shared_root,
            "collision":collision, "mode":mode.as_str(),
            "pair_ignore_filter_allows":a.check_collide_with(b) && b.check_collide_with(a),
            "sample_count":count, "max_delta":max_delta, "mean_delta":mean_delta / count as f64,
            "max_angle_deg":max_angle.to_degrees(), "max_speed_after_cap":max_speed,
            "max_anchor_error":max_anchor_error
        })
    );
}

fn locked_translation(gravity: f32, hz: f32, linear_spring: f32, damping: f32) {
    let root = body(
        "anchor",
        0,
        Vec3::new(0.0, 10.0, 0.0),
        RigidBodyMode::Static,
    );
    let mut plate = body("袖_1", 1, Vec3::new(0.0, 8.0, 0.0), RigidBodyMode::Dynamic);
    plate.move_attenuation = damping;
    plate.rotation_attenuation = damping;
    let mut j = joint(0, 1, Vec3::new(0.0, 9.0, 0.0), 16.0);
    j.position_spring = [linear_spring; 3];
    set_config(PhysicsConfig {
        collision_enabled: false,
        inertia_strength: 0.0,
        physics_fps: hz,
        gravity_y: gravity,
        ..PhysicsConfig::default()
    });
    let pose = vec![
        Mat4::from_translation(Vec3::new(0.0, 10.0, 0.0)),
        Mat4::from_translation(Vec3::new(0.0, 8.0, 0.0)),
    ];
    let mut physics = MMDPhysics::new().unwrap();
    physics.build_physics(&[root, plate], &[j], &pose);
    physics.initialize(&pose);
    let mut previous = Vec3::new(0.0, 8.0, 0.0);
    let mut max_delta = 0.0_f32;
    let mut total_delta = 0.0_f64;
    let mut sampled = 0;
    let mut release_count = 0;
    let mut trace = None;
    for frame in 0..(10.0 * hz) as usize {
        let pre = physics.joint_snapshots_matching("").remove(0).diagnostic;
        physics.sync_bodies(&pose);
        physics.step_simulation(1.0 / hz);
        let b = physics.rigid_bodies[1].bullet_body.as_ref().unwrap();
        let current = b.get_simulation_transform().w_axis.truncate();
        let delta = current.distance(previous);
        if frame >= (5.0 * hz) as usize {
            assert!(current.is_finite());
            max_delta = max_delta.max(delta);
            total_delta += delta as f64;
            sampled += 1;
            if pre.linear_position.y == 0.0 && delta > 1e-5 {
                release_count += 1;
                trace.get_or_insert(json!({"frame":frame,
                    "pre_linear_position":pre.linear_position.to_array(),
                    "post_center":current.to_array(),
                    "post_velocity":b.get_linear_velocity().to_array(), "delta":delta}));
            }
        }
        previous = current;
    }
    println!(
        "{}",
        json!({"case":"locked_translation", "gravity":gravity,
        "physics_hz":hz, "linear_spring":linear_spring, "body_damping":damping,
        "collision":false, "max_delta":max_delta,
        "mean_delta":total_delta / sampled as f64, "sample_count":sampled,
        "release_at_exact_zero_count":release_count, "first_release":trace})
    );
}

fn main() {
    for reversed in [false, true] {
        for angle in [-10.0_f32, 10.0] {
            inspect_hair_sign(reversed, Vec3::X, angle.to_radians());
        }
        for name in ["後髪_01", "neutral_part"] {
            for pitch in [0.0_f32, 50.0, -50.0] {
                hair_head_tilt(name, reversed, 16.0, pitch.to_radians());
            }
        }
    }
    for name in ["後髪_01", "neutral_part"] {
        hair_head_tilt(name, false, 0.0, 50.0_f32.to_radians());
    }
    for mode in [
        CollisionStabilityMode::Strict,
        CollisionStabilityMode::Stable,
        CollisionStabilityMode::Relaxed,
    ] {
        for shared in [false, true] {
            for collision in [false, true] {
                static_plates(shared, collision, mode);
            }
        }
    }
    for hz in [60.0, 120.0] {
        for gravity in [0.0, -98.0] {
            for spring in [0.0, 0.01, 16.0] {
                for damping in [0.0, 0.5] {
                    locked_translation(gravity, hz, spring, damping);
                }
            }
        }
    }
    mmd_engine::physics::reset_config();
}
