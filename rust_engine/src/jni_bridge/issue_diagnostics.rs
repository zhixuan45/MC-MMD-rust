//! 问题诊断快照 JNI。每次只读，不触发模拟或重建。

use jni::objects::JObject;
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use super::MODELS;

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_GetIssueDebugDiagnostic(
    env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    flags: jint,
) -> jstring {
    jni_guard!(env, std::ptr::null_mut(), {
        let diagnostic = if handle <= 0 {
            "[MMD诊断] invalid model handle".to_owned()
        } else {
            let model = MODELS
                .read()
                .unwrap_or_else(super::boundary::reject_poison)
                .get(&handle)
                .cloned();
            match model {
                Some(model) => model
                    .lock()
                    .unwrap_or_else(super::boundary::reject_poison)
                    .issue_debug_diagnostic(flags.max(0) as u32),
                None => "[MMD诊断] invalid handle or model registry unavailable".to_owned(),
            }
        };
        env.new_string(diagnostic)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut())
    })
}
