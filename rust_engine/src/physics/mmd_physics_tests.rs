use super::effective_collision_mask;
use crate::physics::config::PhysicsConfig;
use glam::{Mat4, Vec3};

/// 真实资产按需验证；单独串行运行，避免修改全局配置影响其它测试。
#[test]
#[ignore = "需要 MMD_COLLISION_PMX 指定只读样本"]
fn authored_selective_shells_real_pmx_contacts() {
    use super::{garment_contact_body_flags, CollisionStabilityMode, MMDPhysics};
    use crate::physics::config::{get_config, set_config};

    let path = std::env::var("MMD_COLLISION_PMX").expect("请指定真实 PMX 路径");
    let model = crate::model::load_pmx(&path).expect("读取 PMX");
    assert_eq!(model.rigid_bodies.len(), 348, "需要本次真珠样本");
    assert_eq!(model.rigid_bodies[272].local_name, "M-M-M-M-M-下半身");
    assert_eq!(model.rigid_bodies[272].un_collision_group_flag, 0x0200);
    let names: Vec<_> = model.bone_manager.links().map(|b| b.name.clone()).collect();
    let bind: Vec<_> = model
        .bone_manager
        .links()
        .map(|b| Mat4::from_translation(b.initial_position))
        .collect();
    let eligible = garment_contact_body_flags(&model.rigid_bodies, &model.joints, &names);
    let raw: Vec<_> = model
        .rigid_bodies
        .iter()
        .map(|b| b.un_collision_group_flag)
        .collect();
    let old_config = get_config();
    // 即使断言失败，也恢复调用前的配置。
    struct RestoreConfig(PhysicsConfig);
    impl Drop for RestoreConfig {
        fn drop(&mut self) {
            set_config(self.0.clone());
        }
    }
    let _restore = RestoreConfig(old_config.clone());
    for mode in [
        CollisionStabilityMode::Strict,
        CollisionStabilityMode::Stable,
        CollisionStabilityMode::Relaxed,
    ] {
        let plan = super::build_garment_contact_plan(&model.rigid_bodies, &eligible, true, mode);
        let mut opened = 0;
        for a in 0..raw.len() {
            for b in a + 1..raw.len() {
                let qualifies = |masks: &[u16]| {
                    masks[a] & (1 << model.rigid_bodies[b].group) != 0
                        && masks[b] & (1 << model.rigid_bodies[a].group) != 0
                };
                if !qualifies(&raw)
                    && qualifies(&plan.effective_masks)
                    && !plan.forced_ignore_pairs.contains(&(a, b))
                {
                    opened += 1;
                }
            }
        }
        println!("mode={mode:?} newly_opened_authored_denials={opened}");

        let mut config = old_config.clone();
        config.collision_stability_mode = mode;
        config.collision_enabled = true;
        config.joints_enabled = false; // 隔离掩码，不让关节禁碰遮住结果。
        config.kinematic_filter = false;
        set_config(config);
        let mut physics = MMDPhysics::new().expect("创建 Bullet 世界");
        physics.build_physics_with_bone_names(&model.rigid_bodies, &model.joints, &bind, &names);
        let last_skirt = model
            .rigid_bodies
            .iter()
            .position(|b| b.local_name == "裙_9_13")
            .expect("样本裙摆末段");
        let mut all_contacts_match = true;
        for (a, b, expected) in [
            (169, 28, true),
            (180, 28, false),
            (181, 28, false),
            (272, 28, false),
            (272, 198, true),
            (169, last_skirt, false),
        ] {
            for (i, rb) in physics.rigid_bodies.iter().enumerate() {
                let body = rb.bullet_body.as_ref().expect("创建样本刚体");
                let position = if i == a || i == b {
                    Vec3::ZERO
                } else {
                    Vec3::new(100.0 * (i + 1) as f32, 0.0, 0.0)
                };
                body.set_transform(Mat4::from_translation(position));
                physics.world.refresh_body_collision_filter(body);
            }
            physics.world.detect_collisions();
            let contacted = physics.world.contact_manifolds().iter().any(|c| {
                let i = physics.debug_body_pointer_indices[&c.body_a];
                let j = physics.debug_body_pointer_indices[&c.body_b];
                (i.min(j), i.max(j)) == (a.min(b), a.max(b)) && c.contact_count > 0
            });
            println!("mode={mode:?} pair=({a},{b}) contact={contacted} expected={expected}");
            all_contacts_match &= contacted == expected;
        }
        assert!(all_contacts_match, "真实 Bullet 通路不符 {mode:?}");
        assert_eq!(opened, 0, "不得开放作者拒绝的大小壳通路");
        assert_eq!(plan.effective_masks, raw);
    }
}

#[test]
fn collision_switch_disables_all_contact_pairs() {
    assert_eq!(effective_collision_mask(false, 0xFFFE), 0);
    assert_eq!(effective_collision_mask(true, 0xFFFE), 0xFFFE);
}






#[test]
fn skirt_compatibility_preserves_other_originally_blocked_pairs() {
    use super::MMDPhysics;
    use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

    let make_body = |name: &str, group: u8, raw_mask: u16, mode: RigidBodyMode| RigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: 0,
        group,
        un_collision_group_flag: raw_mask,
        shape: RigidBodyShape::Sphere,
        size: [1.0, 0.0, 0.0],
        position: [0.0; 3],
        rotation: [0.0; 3],
        mass: if mode == RigidBodyMode::Static {
            0.0
        } else {
            1.0
        },
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    };
    let bodies = [
        make_body("腰", 0, 0, RigidBodyMode::Static),
        make_body("スカート_11_7", 14, 0, RigidBodyMode::Dynamic),
        make_body("左脛", 0, 1 << 14, RigidBodyMode::Static),
        make_body("右袖", 14, 1, RigidBodyMode::Dynamic),
    ];
    let mut physics = MMDPhysics::new().expect("Bullet world should be available");
    physics.build_physics(&bodies, &[], &[Mat4::IDENTITY]);
    physics.world.detect_collisions();

    let pairs: Vec<_> = physics
        .world
        .contact_manifolds()
        .into_iter()
        .filter_map(|contact| {
            Some((
                *physics.debug_body_pointer_indices.get(&contact.body_a)?,
                *physics.debug_body_pointer_indices.get(&contact.body_b)?,
            ))
        })
        .map(|(a, b)| (a.min(b), a.max(b)))
        .collect();
    assert!(pairs.contains(&(0, 1)), "skirt should contact the pelvis");
    assert!(
        !pairs.contains(&(1, 2)),
        "the compatibility mask must not enable its shin pair"
    );
    assert!(
        !pairs.contains(&(0, 3)),
        "the compatibility mask must not enable a sleeve pair"
    );
}

#[test]
fn raw_pmx_masks_allow_cbe0_pelvis_with_bfff_skirt_but_block_group_14_self_collision() {
    use super::MMDPhysics;
    use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

    let make_body = |name: &str, group: u8, raw_mask: u16, mode: RigidBodyMode| RigidBody {
        local_name: name.into(),
        universal_name: String::new(),
        bone_index: 0,
        group,
        un_collision_group_flag: raw_mask,
        shape: RigidBodyShape::Sphere,
        size: [1.0, 0.0, 0.0],
        position: [0.0; 3],
        rotation: [0.0; 3],
        mass: if mode == RigidBodyMode::Static { 0.0 } else { 1.0 },
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.0,
        mode,
    };
    let bodies = [
        make_body("下半身", 0, 0xCBE0, RigidBodyMode::Static),
        make_body("スカート_11_7", 14, 0xBFFF, RigidBodyMode::Dynamic),
        make_body("スカート_11_8", 14, 0xBFFF, RigidBodyMode::Dynamic),
    ];
    let mut physics = MMDPhysics::new().expect("Bullet world should be available");
    physics.build_physics(&bodies, &[], &[Mat4::IDENTITY]);
    physics.world.detect_collisions();

    let pairs: Vec<_> = physics
        .world
        .contact_manifolds()
        .into_iter()
        .filter_map(|contact| {
            Some((
                *physics.debug_body_pointer_indices.get(&contact.body_a)?,
                *physics.debug_body_pointer_indices.get(&contact.body_b)?,
            ))
        })
        .map(|(a, b)| (a.min(b), a.max(b)))
        .collect();
    assert!(pairs.contains(&(0, 1)), "CBE0 and BFFF allow the bilateral pair");
    assert!(pairs.contains(&(0, 2)), "CBE0 and BFFF allow the second body pair");
    assert!(
        !pairs.contains(&(1, 2)),
        "BFFF excludes its own group-14 pair"
    );
}


#[test]
fn dynamic_writeback_uses_contact_solver_pose_in_both_modes() {
    use super::MMDPhysics;
    use glam::{Mat4, Quat};
    use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

    for mode in [
        RigidBodyMode::Dynamic,
        RigidBodyMode::DynamicWithBonePosition,
    ] {
        let body = RigidBody {
            local_name: "测试动态体".into(),
            universal_name: String::new(),
            bone_index: 0,
            group: 1,
            un_collision_group_flag: u16::MAX,
            shape: RigidBodyShape::Sphere,
            size: [0.1, 0.0, 0.0],
            position: [0.0; 3],
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode,
        };
        let mut physics = MMDPhysics::new().unwrap();
        physics.build_physics(&[body], &[], &[Mat4::IDENTITY]);
        physics.world.set_gravity(0.0, 0.0, 0.0);
        let rb = physics.rigid_bodies[0].bullet_body.as_ref().unwrap();
        rb.set_linear_velocity(3.0, 0.0, 0.0);
        rb.set_angular_velocity(0.0, 3.0, 0.0);
        physics.world.step(1.0 / 60.0, 1, 1.0 / 60.0);
        let solved = rb.get_simulation_transform();
        let delayed = rb.get_transform();
        assert!((solved.w_axis.x - delayed.w_axis.x).abs() > 0.04);
        let current = Mat4::from_translation(Vec3::new(7.0, 8.0, 9.0));
        let result = physics.get_dynamic_bone_transforms(&[current])[0].1;
        let solved_right = crate::physics::inv_z(solved);
        let actual_rotation = Quat::from_mat4(&result);
        let expected_rotation = Quat::from_mat4(&solved_right);
        assert!(actual_rotation.dot(expected_rotation).abs() > 0.99999);
        let expected_position = if mode == RigidBodyMode::Dynamic {
            solved_right.w_axis.truncate()
        } else {
            current.w_axis.truncate()
        };
        assert!(result.w_axis.truncate().distance(expected_position) < 1e-5);
    }
}
