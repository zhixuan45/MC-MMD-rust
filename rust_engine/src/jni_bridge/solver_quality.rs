//! 全局物理求解精度 JNI 接口。

use jni::objects::JClass;
use jni::sys::{jboolean, jint};
use jni::JNIEnv;

use super::MODELS;

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_bridge_runtime_PhysicsSolverNative_SetIterations(
    _env: JNIEnv,
    _class: JClass,
    iterations: jint,
) -> jboolean {
    if !crate::physics::solver_quality::set_default_iterations(iterations) {
        return 0;
    }

    let models: Vec<_> = MODELS
        .read()
        .unwrap_or_else(|error| error.into_inner())
        .values()
        .cloned()
        .collect();
    let mut applied = 0usize;
    for model in models {
        let mut model = model.lock().unwrap_or_else(|error| error.into_inner());
        if model.configure_physics_solver_diagnostic(Some(iterations), None, None, None) {
            applied += 1;
        }
    }

    log::info!(
        "[物理诊断] Bullet solver iterations={}，热应用实例数={}",
        iterations,
        applied
    );
    1
}
