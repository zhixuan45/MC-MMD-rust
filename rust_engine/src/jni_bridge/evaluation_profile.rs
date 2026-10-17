use super::tacz_arm_target::{record_tacz_arm_apply_result, take_tacz_arm_targets};
use super::MODELS;
use jni::objects::{JByteBuffer, JClass, JObject};
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;

const PROFILE_V1_BYTES: usize = 288;
const CAPABILITIES_V1: jlong = 0x7;

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_GetEvaluationCapabilities(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    CAPABILITIES_V1
        | if crate::physics::bullet_ffi::BulletWorld::parallel_available() {
            0x8
        } else {
            0
        }
}

/// `false` 只在进入模型更新前返回，避免 Java 对已推进的物理重复求值。
#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_UpdateModelParallel(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    delta: jfloat,
    cpu_skinning: jboolean,
    thread_budget: jint,
    physics_parallel: jboolean,
    output: JObject,
) -> jboolean {
    if thread_budget < 0 || !delta.is_finite() {
        return 0;
    }
    let profile_requested = !output.is_null();
    let output = JByteBuffer::from(output);
    let output_address = if profile_requested {
        let Ok(capacity) = env.get_direct_buffer_capacity(&output) else {
            return 0;
        };
        if capacity < 64 {
            return 0;
        }
        let Ok(address) = env.get_direct_buffer_address(&output) else {
            return 0;
        };
        Some((address, capacity as usize))
    } else {
        None
    };
    // 先固定实例 Arc，再执行更新；false 的所有返回点都位于动画与物理之前。
    let instance = MODELS
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .get(&handle)
        .cloned();
    let Some(instance) = instance else { return 0 };
    let targets = take_tacz_arm_targets(handle);
    let mask = targets.map_or(0, |v| v.valid_mask());
    let mut model = instance.lock().unwrap_or_else(|e| e.into_inner());
    let policy = crate::evaluation_parallelism::EvaluationPolicy {
        thread_budget: crate::evaluation_parallelism::bounded_thread_budget(thread_budget as usize),
        physics_parallel: physics_parallel != 0,
    };
    let ((outcome, sample), failed) = crate::evaluation_profile::failure_scope(|| {
        if profile_requested {
            crate::evaluation_profile::capture(|| {
                crate::evaluation_profile::set_policy(
                    policy.thread_budget,
                    policy.physics_parallel,
                );
                model.tick_animation_with_policy(delta, cpu_skinning != 0, targets, policy)
            })
        } else {
            (
                model.tick_animation_with_policy(delta, cpu_skinning != 0, targets, policy),
                crate::evaluation_profile::Sample::default(),
            )
        }
    });
    if failed {
        let _ = env.throw_new(
            "java/lang/RuntimeException",
            "Bullet MT evaluation failed after entering the physics step; model state may be partial",
        );
        return 1;
    }
    record_tacz_arm_apply_result(handle, mask, outcome);

    if let Some((address, capacity)) = output_address {
        let legacy = [
            1,
            sample.nanos[0],
            sample.nanos[1],
            sample.nanos[2],
            sample.nanos[3],
            sample.steps,
            sample.substeps,
            sample.requested_substeps,
        ];
        for (index, value) in legacy.into_iter().enumerate() {
            unsafe { std::ptr::write_unaligned(address.add(index * 8).cast::<u64>(), value) };
        }
        if capacity >= PROFILE_V1_BYTES {
            let flags = u64::from(sample.matrix_parallel_batches > 0)
                | (u64::from(sample.bullet_parallel_width > 1) << 1)
                | (sample.physics_parallel_requested << 2);
            let detail = [
                1,
                PROFILE_V1_BYTES as u64,
                sample.thread_budget,
                flags,
                sample
                    .matrix_parallel_width
                    .max(sample.bullet_parallel_width),
                sample.nanos[0],
                sample.nanos[1],
                sample.detail_nanos[0],
                sample.detail_nanos[1],
                sample.detail_nanos[2],
                sample.detail_nanos[3],
                sample.detail_nanos[4],
                sample.detail_nanos[5],
                sample.nanos[3],
                sample.rigid_bodies,
                sample.constraints,
                sample.manifolds,
                sample.contacts,
                sample.steps,
                sample.substeps,
                sample.requested_substeps,
                sample.physics_islands,
                sample.max_island_bodies,
                sample.max_island_constraints,
                sample.physics_peak,
                sample.physics_tasks,
                sample.matrix_tasks,
                sample.matrix_peak,
            ];
            for (index, value) in detail.into_iter().enumerate() {
                unsafe {
                    std::ptr::write_unaligned(address.add(64 + index * 8).cast::<u64>(), value)
                };
            }
        }
    }
    1
}

/// One synchronous evaluation and one fixed-size result. Missing symbols in older DLLs
/// are capability-detected by Java before falling back to the existing evaluation API.
#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_UpdateModelProfiled(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    delta: jfloat,
    cpu_skinning: jboolean,
    output: JByteBuffer,
) -> jboolean {
    let Ok(capacity) = env.get_direct_buffer_capacity(&output) else {
        return 0;
    };
    if capacity < 64 {
        return 0;
    }
    let Ok(address) = env.get_direct_buffer_address(&output) else {
        return 0;
    };
    // Drop the registry lock before evaluating; lifetime is pinned by the Arc.
    let instance = MODELS
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .get(&handle)
        .cloned();
    let Some(instance) = instance else { return 0 };
    let targets = take_tacz_arm_targets(handle);
    let mask = targets.map_or(0, |v| v.valid_mask());
    let mut model = instance.lock().unwrap_or_else(|e| e.into_inner());
    let (outcome, sample) = crate::evaluation_profile::capture(|| {
        model.tick_animation_with_tacz_targets(delta, cpu_skinning != 0, targets)
    });
    record_tacz_arm_apply_result(handle, mask, outcome);
    let values = [
        1,
        sample.nanos[0],
        sample.nanos[1],
        sample.nanos[2],
        sample.nanos[3],
        sample.steps,
        sample.substeps,
        sample.requested_substeps,
    ];
    for (i, value) in values.into_iter().enumerate() {
        // Direct buffers are not required to have 8-byte alignment.
        unsafe { std::ptr::write_unaligned(address.add(i * 8).cast::<u64>(), value) };
    }
    1
}
