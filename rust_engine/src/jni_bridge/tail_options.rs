//! 单模型尾巴物理选项 JNI 接口。

use jni::objects::JObject;
use jni::sys::{jboolean, jfloat, jlong};
use jni::JNIEnv;

use super::MODELS;

/// 更新指定模型实例的尾巴受力选项。
#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_SetTailPhysicsOptions(
    _env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    idle_lift: jboolean,
    movement_boost: jboolean,
) {
    let models = MODELS.read().unwrap_or_else(|error| error.into_inner());
    if let Some(model) = models.get(&handle) {
        let mut model = model.lock().unwrap_or_else(|error| error.into_inner());
        model.set_tail_physics_options(idle_lift != 0, movement_boost != 0);
    }
}

/// 同步尾巴位移使用的实际渲染比例，单位：方块/模型单位。
#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_SetTailPhysicsScale(
    _env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    blocks_per_model_unit: jfloat,
) {
    let models = MODELS.read().unwrap_or_else(|error| error.into_inner());
    if let Some(model) = models.get(&handle) {
        let mut model = model.lock().unwrap_or_else(|error| error.into_inner());
        model.set_tail_physics_scale(blocks_per_model_unit);
    }
}
