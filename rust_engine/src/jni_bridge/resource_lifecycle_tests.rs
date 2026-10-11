use super::*;
use crate::model::MmdModel;
use crate::physics::MMDPhysics;
use crate::skeleton::BoneLink;
use jni::objects::JClass;
use jni::JNIEnv;
use mmd::pmx::joint::{Joint, JointType};
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

fn body(name: &str, position: [f32; 3], mode: RigidBodyMode) -> RigidBody {
    RigidBody {
        local_name: name.to_owned(),
        universal_name: String::new(),
        bone_index: -1,
        group: 0,
        un_collision_group_flag: u16::MAX,
        shape: RigidBodyShape::Sphere,
        size: [0.25, 0.0, 0.0],
        position,
        rotation: [0.0; 3],
        mass: if mode == RigidBodyMode::Static {
            0.0
        } else {
            1.0
        },
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.5,
        mode,
    }
}

fn joint() -> Joint {
    Joint {
        local_name: "lifecycle-joint".to_owned(),
        universal_name: String::new(),
        type_: JointType::Spring6DOF,
        rigid_body_a_index: 0,
        rigid_body_b_index: 1,
        position: [0.5, 0.0, 0.0],
        rotation: [0.0; 3],
        position_min: [-0.1; 3],
        position_max: [0.1; 3],
        rotation_min: [-0.1; 3],
        rotation_max: [0.1; 3],
        position_spring: [1.0; 3],
        rotation_spring: [1.0; 3],
    }
}

fn model_with_physics() -> MmdModel {
    let mut model = MmdModel::new();
    for name in ["lifecycle-a", "lifecycle-b"] {
        model.bone_manager.add_bone(BoneLink::new(name.to_owned()));
    }
    model.bone_manager.build_hierarchy();
    model.rigid_bodies = vec![
        body("lifecycle-a", [0.0, 0.0, 0.0], RigidBodyMode::Dynamic),
        body("lifecycle-b", [0.5, 0.0, 0.0], RigidBodyMode::Dynamic),
    ];
    model.joints.push(joint());
    assert!(
        model.init_physics(),
        "synthetic model must initialize Bullet"
    );
    model
}

fn assert_stats_equal(
    actual: crate::physics::BulletAllocStats,
    expected: crate::physics::BulletAllocStats,
    cycle: usize,
) {
    assert_eq!(
        actual.worlds, expected.worlds,
        "cycle {cycle} world baseline"
    );
    assert_eq!(
        actual.shapes, expected.shapes,
        "cycle {cycle} shape baseline"
    );
    assert_eq!(
        actual.rigid_bodies, expected.rigid_bodies,
        "cycle {cycle} body baseline"
    );
    assert_eq!(
        actual.constraints, expected.constraints,
        "cycle {cycle} constraint baseline"
    );
    assert_eq!(
        actual.motion_states, expected.motion_states,
        "cycle {cycle} motion-state baseline"
    );
}

fn print_stats(
    phase: &str,
    cycle: usize,
    handle: i64,
    count: usize,
    stats: crate::physics::BulletAllocStats,
) {
    println!(
        "[lifecycle-test] cycle={} phase={} handle={} model_handles={} global_worlds={} global_shapes={} global_bodies={} global_constraints={} global_motion_states={}",
        cycle, phase, handle, count, stats.worlds, stats.shapes, stats.rigid_bodies,
        stats.constraints, stats.motion_states
    );
}

#[test]
#[ignore = "进程级 Bullet 计数需单独串行运行，避免和其他物理测试竞争"]
fn twenty_native_model_handle_cycles_return_bullet_objects_to_baseline() {
    let baseline = MMDPhysics::alloc_stats();
    let handle_count = MODELS.read().unwrap().len();
    let mut peak = crate::physics::BulletAllocStats::default();

    for cycle in 0..20 {
        assert_stats_equal(MMDPhysics::alloc_stats(), baseline, cycle);
        let handle = register_model(model_with_physics());
        assert!(MODELS.read().unwrap().contains_key(&handle));
        let registered_count = MODELS.read().unwrap().len();
        assert_eq!(
            registered_count,
            handle_count + 1,
            "cycle {cycle} registers one model handle"
        );

        let live = MMDPhysics::alloc_stats();
        assert_eq!(
            live.worlds - baseline.worlds,
            1,
            "cycle {cycle} world delta"
        );
        assert_eq!(
            live.rigid_bodies - baseline.rigid_bodies,
            2,
            "cycle {cycle} body delta"
        );
        assert_eq!(
            live.constraints - baseline.constraints,
            1,
            "cycle {cycle} constraint delta"
        );
        peak.worlds = peak.worlds.max(live.worlds - baseline.worlds);
        peak.shapes = peak.shapes.max(live.shapes - baseline.shapes);
        peak.rigid_bodies = peak
            .rigid_bodies
            .max(live.rigid_bodies - baseline.rigid_bodies);
        peak.constraints = peak
            .constraints
            .max(live.constraints - baseline.constraints);
        peak.motion_states = peak
            .motion_states
            .max(live.motion_states - baseline.motion_states);
        print_stats("before_delete", cycle, handle, registered_count, live);

        // 模拟正在执行的 native 调用持有 Arc，删除句柄不能提前释放世界。
        let in_flight_model = MODELS.read().unwrap().get(&handle).unwrap().clone();

        // DeleteModel 不使用 JNIEnv/JClass；零值只用于直接驱动现有 JNI 导出函数。
        unsafe {
            Java_com_shiroha_mmdskin_NativeFunc_DeleteModel(
                std::mem::zeroed::<JNIEnv>(),
                JClass::from_raw(std::ptr::null_mut()),
                handle,
            );
        }

        assert!(!MODELS.read().unwrap().contains_key(&handle));
        let remaining_count = MODELS.read().unwrap().len();
        assert_eq!(
            remaining_count, handle_count,
            "cycle {cycle} restores handle count"
        );
        assert_stats_equal(MMDPhysics::alloc_stats(), live, cycle);
        unsafe {
            // 重复删除失效句柄必须幂等，不能触碰仍在使用的 native 对象。
            Java_com_shiroha_mmdskin_NativeFunc_DeleteModel(
                std::mem::zeroed::<JNIEnv>(),
                JClass::from_raw(std::ptr::null_mut()),
                handle,
            );
        }
        assert_stats_equal(MMDPhysics::alloc_stats(), live, cycle);
        drop(in_flight_model);
        let after = MMDPhysics::alloc_stats();
        print_stats("after_delete", cycle, handle, remaining_count, after);
        assert_stats_equal(after, baseline, cycle);
    }

    assert_eq!(MODELS.read().unwrap().len(), handle_count);
    println!(
        "[lifecycle-test] peak_net world={} shapes={} bodies={} constraints={} motion_states={}",
        peak.worlds, peak.shapes, peak.rigid_bodies, peak.constraints, peak.motion_states
    );
}
