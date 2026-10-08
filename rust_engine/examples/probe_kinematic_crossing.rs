//! 对比整帧运动学目标跳跃与逐子步目标更新的离散接触。
use glam::{Mat4, Vec3};
use mmd_engine::physics::bullet_ffi::{BulletRigidBody, BulletShape, BulletWorld, RigidBodyInfo};

const SUBSTEPS: usize = 4;
const STEP: f32 = 1.0 / 60.0;

fn body_info(mass: f32, kinematic: bool, position: Vec3) -> RigidBodyInfo {
    RigidBodyInfo {
        mass,
        linear_damping: 0.0,
        angular_damping: 0.0,
        friction: 0.8,
        restitution: 0.0,
        additional_damping: false,
        is_kinematic: kinematic,
        disable_deactivation: true,
        no_contact_response: false,
        initial_transform: Mat4::from_translation(position),
    }
}

fn run_case(step_each_target: bool) {
    let world = BulletWorld::new(0.0, 0.0, 0.0).expect("create world");
    let sphere_shape = BulletShape::sphere(0.2).expect("create sphere");
    let box_shape = BulletShape::r#box(0.1, 0.35, 0.35).expect("create box");
    let mover = BulletRigidBody::new(
        &body_info(0.0, true, Vec3::new(-1.0, 0.0, 0.0)),
        &sphere_shape,
    )
    .expect("create kinematic body");
    let target = BulletRigidBody::new(&body_info(1.0, false, Vec3::ZERO), &box_shape)
        .expect("create dynamic body");
    world.add_rigid_body(&mover, 1, -1);
    world.add_rigid_body(&target, 2, -1);

    let mut contact_steps = 0;
    let mut peak_impulse = 0.0_f32;
    let mut peak_penetration = 0.0_f32;
    if step_each_target {
        for index in 1..=SUBSTEPS {
            let x = -1.0 + 2.0 * index as f32 / SUBSTEPS as f32;
            mover.set_kinematic_target(Mat4::from_translation(Vec3::new(x, 0.0, 0.0)));
            world.step(STEP, 1, STEP);
            for manifold in world.contact_manifolds() {
                contact_steps += 1;
                peak_impulse = peak_impulse.max(manifold.max_applied_impulse);
                peak_penetration = peak_penetration.max(manifold.max_penetration_depth);
            }
        }
    } else {
        mover.set_kinematic_target(Mat4::from_translation(Vec3::new(1.0, 0.0, 0.0)));
        world.step(STEP * SUBSTEPS as f32, SUBSTEPS as i32, STEP);
        for manifold in world.contact_manifolds() {
            contact_steps += 1;
            peak_impulse = peak_impulse.max(manifold.max_applied_impulse);
            peak_penetration = peak_penetration.max(manifold.max_penetration_depth);
        }
    }

    let simulation_x = target.get_simulation_transform().w_axis.x;
    let motion_state_x = target.get_transform().w_axis.x;
    println!(
        "case={} contact_samples={} peak_impulse={:.6} peak_penetration={:.6} dynamic_sim_x={:.6} motion_state_x={:.6}",
        if step_each_target { "per_substep" } else { "single_endpoint" },
        contact_steps,
        peak_impulse,
        peak_penetration,
        simulation_x,
        motion_state_x,
    );
}

fn main() {
    run_case(false);
    run_case(true);
}
