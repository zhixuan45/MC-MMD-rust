//! PMX 球面贴图 JNI 查询。

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use super::MODELS;
use crate::model::MmdMaterial;

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_bridge_runtime_SphereMaterialNative_getMode(
    _env: JNIEnv,
    _class: JClass,
    model_handle: jlong,
    material_index: jint,
) -> jint {
    sphere_texture_mode(model_handle, material_index)
}

fn sphere_texture_mode(model_handle: jlong, material_index: jint) -> jint {
    let models = MODELS.read().unwrap_or_else(|error| error.into_inner());
    let Some(model) = models.get(&model_handle) else {
        return 0;
    };
    let model = model.lock().unwrap_or_else(|error| error.into_inner());
    let Some(material) = model.materials.get(material_index as usize) else {
        return 0;
    };
    material_sphere_mode(material, model.texture_paths.len())
}

fn material_sphere_mode(material: &MmdMaterial, texture_count: usize) -> jint {
    let texture_index = material.environment_index;
    if texture_index < 0 || texture_index as usize >= texture_count {
        return 0;
    }
    material.environment_blend_mode as jint
}

#[cfg(test)]
mod tests {
    use super::{material_sphere_mode, sphere_texture_mode};
    use crate::model::MmdMaterial;

    #[test]
    fn sphere_mode_is_returned_only_for_a_valid_environment_texture() {
        let mut material = MmdMaterial::default();
        material.environment_index = 0;
        material.environment_blend_mode = 2;

        assert_eq!(material_sphere_mode(&material, 1), 2);
        assert_eq!(material_sphere_mode(&material, 0), 0);
    }

    #[test]
    fn invalid_model_or_material_returns_disabled_mode() {
        assert_eq!(sphere_texture_mode(0, 0), 0);
        assert_eq!(sphere_texture_mode(0, -1), 0);
    }
}
