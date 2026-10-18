use super::{body, kinematic_body, BulletShape, Mat4, Vec3};
use super::super::{BulletSolverSettings, BulletWorld};

fn world() -> BulletWorld {
    BulletWorld::new(0.0, 0.0, 0.0).expect("应能创建 Bullet 世界")
}

#[test]
fn solver_settings_read_bullet_defaults_and_round_trip() {
    let world = world();
    let defaults = world.solver_settings().expect("应能读取求解器参数");
    assert_eq!(defaults.iterations, 10);
    assert!((defaults.contact_erp - 0.2).abs() < 1.0e-6);
    assert!(defaults.split_impulse);
    assert!((defaults.split_impulse_penetration_threshold + 0.04).abs() < 1.0e-6);

    let updated = BulletSolverSettings {
        iterations: 32,
        contact_erp: 0.35,
        split_impulse: false,
        split_impulse_penetration_threshold: -0.12,
    };
    assert!(world.set_solver_settings(updated));
    assert_eq!(world.solver_settings(), Some(updated));
}

#[test]
fn invalid_solver_settings_leave_world_unchanged() {
    let world = world();
    let baseline = world.solver_settings().expect("应能读取求解器参数");
    let invalid = [
        BulletSolverSettings { iterations: 0, ..baseline },
        BulletSolverSettings { iterations: 257, ..baseline },
        BulletSolverSettings { contact_erp: f32::NAN, ..baseline },
        BulletSolverSettings { contact_erp: -0.01, ..baseline },
        BulletSolverSettings { contact_erp: 1.01, ..baseline },
        BulletSolverSettings {
            split_impulse_penetration_threshold: f32::NAN,
            ..baseline
        },
        BulletSolverSettings {
            split_impulse_penetration_threshold: 0.01,
            ..baseline
        },
    ];

    for settings in invalid {
        assert!(!world.set_solver_settings(settings));
        assert_eq!(world.solver_settings(), Some(baseline));
    }
}

#[test]
fn changing_one_solver_setting_preserves_the_other_defaults() {
    let world = world();
    let defaults = world.solver_settings().expect("应能读取求解器参数");
    let changed = BulletSolverSettings {
        contact_erp: 0.4,
        ..defaults
    };
    assert!(world.set_solver_settings(changed));

    let actual = world.solver_settings().expect("应能回读求解器参数");
    assert!((actual.contact_erp - 0.4).abs() < 1.0e-6);
    assert_eq!(actual.iterations, defaults.iterations);
    assert_eq!(actual.split_impulse, defaults.split_impulse);
    assert_eq!(
        actual.split_impulse_penetration_threshold,
        defaults.split_impulse_penetration_threshold
    );
}

#[test]
fn contact_erp_changes_real_contact_correction() {
    fn correction_velocity(erp: f32) -> f32 {
        let world = world();
        let mut settings = world.solver_settings().unwrap();
        settings.contact_erp = erp;
        // 关闭分离冲量，让接触纠偏直接体现在读取的速度中。
        settings.split_impulse = false;
        assert!(world.set_solver_settings(settings));
        let wall_shape = BulletShape::r#box(0.1, 1.0, 1.0).unwrap();
        let moving_shape = BulletShape::sphere(0.2).unwrap();
        let wall = kinematic_body(&wall_shape, Mat4::IDENTITY);
        let moving = body(&moving_shape, Mat4::from_translation(Vec3::new(0.29, 0.0, 0.0)));
        world.add_rigid_body(&wall, 1, -1);
        world.add_rigid_body(&moving, 2, -1);
        world.step(1.0 / 60.0, 1, 1.0 / 60.0);
        let velocity = moving.get_linear_velocity().x;
        world.remove_rigid_body(&moving);
        world.remove_rigid_body(&wall);
        velocity
    }

    let soft = correction_velocity(0.05);
    let hard = correction_velocity(0.8);
    assert!(soft > 0.0, "夹具必须建立实际接触纠偏");
    assert!(hard > soft * 2.0, "接触 ERP 未作用于实际纠偏：soft={soft}, hard={hard}");
}
