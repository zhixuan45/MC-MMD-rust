use super::{body, BulletShape, BulletWorld, Mat4, Vec3};
use crate::evaluation_profile::capture;

#[test]
fn evaluation_profile_counts_actual_zero_clamped_and_variable_steps() {
    let world = BulletWorld::new(0.0, -9.8, 0.0).unwrap();
    let dt = 1.0 / 64.0;
    let (_, zero) = capture(|| world.step(0.0, 3, dt));
    assert_eq!((zero.steps, zero.substeps, zero.requested_substeps), (1, 0, 0));
    let (_, clamped) = capture(|| world.step(dt * 8.0, 3, dt));
    assert_eq!((clamped.steps, clamped.substeps, clamped.requested_substeps), (1, 3, 8));
    let (_, variable) = capture(|| world.step(dt, 0, dt));
    assert_eq!((variable.steps, variable.substeps, variable.requested_substeps), (1, 1, 1));
}

fn simulate(profile: bool, barrier: Option<&std::sync::Barrier>) -> (Mat4, Vec3, u64) {
    let world = BulletWorld::new(0.0, -9.8, 0.0).unwrap();
    let shape = BulletShape::sphere(0.1).unwrap();
    let rigid = body(&shape, Mat4::from_translation(Vec3::Y));
    world.add_rigid_body(&rigid, 1, 0);
    rigid.set_linear_velocity(1.0, 2.0, 3.0);
    if let Some(barrier) = barrier { barrier.wait(); }
    let run = || {
        for _ in 0..96 { world.step(1.0 / 64.0, 4, 1.0 / 64.0); }
    };
    let steps = if profile { capture(run).1.substeps } else { run(); 0 };
    let result = (rigid.get_simulation_transform(), rigid.get_linear_velocity(), steps);
    world.remove_rigid_body(&rigid);
    result
}

#[test]
fn evaluation_profile_preserves_physics_and_parallel_world_results() {
    let baseline = simulate(false, None);
    let sampled = simulate(true, None);
    assert_eq!(baseline.0, sampled.0);
    assert_eq!(baseline.1, sampled.1);
    assert_eq!(sampled.2, 96);
    let barrier = std::sync::Arc::new(std::sync::Barrier::new(4));
    let workers: Vec<_> = (0..4).map(|_| {
        let barrier = barrier.clone();
        std::thread::spawn(move || simulate(true, Some(&barrier)))
    }).collect();
    for worker in workers {
        let parallel = worker.join().unwrap();
        assert_eq!(sampled, parallel);
    }
}
