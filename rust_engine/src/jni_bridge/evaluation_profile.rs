use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jfloat, jlong};
use jni::JNIEnv;
use super::MODELS;
use super::tacz_arm_target::{take_tacz_arm_targets, record_tacz_arm_apply_result};

/// One synchronous evaluation and one fixed-size result. Missing symbols in older DLLs
/// are capability-detected by Java before falling back to the existing evaluation API.
#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_UpdateModelProfiled(
    env: JNIEnv, _class: JClass, handle: jlong, delta: jfloat,
    cpu_skinning: jboolean, output: JByteBuffer,
) -> jboolean {
    let Ok(capacity) = env.get_direct_buffer_capacity(&output) else { return 0 };
    if capacity < 64 { return 0; }
    let Ok(address) = env.get_direct_buffer_address(&output) else { return 0 };
    // Drop the registry lock before evaluating; lifetime is pinned by the Arc.
    let instance = MODELS.read().unwrap_or_else(|e| e.into_inner()).get(&handle).cloned();
    let Some(instance) = instance else { return 0 };
    let targets = take_tacz_arm_targets(handle);
    let mask = targets.map_or(0, |v| v.valid_mask());
    let mut model = instance.lock().unwrap_or_else(|e| e.into_inner());
    let (outcome, sample) = crate::evaluation_profile::capture(||
        model.tick_animation_with_tacz_targets(delta, cpu_skinning != 0, targets));
    record_tacz_arm_apply_result(handle, mask, outcome);
    let values = [1, sample.nanos[0], sample.nanos[1], sample.nanos[2], sample.nanos[3],
        sample.steps, sample.substeps, sample.requested_substeps];
    for (i, value) in values.into_iter().enumerate() {
        // Direct buffers are not required to have 8-byte alignment.
        unsafe { std::ptr::write_unaligned(address.add(i * 8).cast::<u64>(), value) };
    }
    1
}
