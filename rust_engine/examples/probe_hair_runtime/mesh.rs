//! 只读导出蒙皮网格，用于核对碰撞壳与可见表面的间隔。
use super::*;

pub(super) fn write(model: &MmdModel, dir: &Path, stage: &str, elapsed: f32) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let value = json!({
        "stage":stage,"elapsed_seconds":elapsed,"space":"runtime_right_model",
        "positions":model.update_positions.iter().map(|p|p.to_array()).collect::<Vec<_>>(),
        "indices":model.indices,
        "weights":model.weights.iter().map(weight_bones).collect::<Vec<_>>(),
        "bones":model.bone_manager.links().map(|b|json!({"name":b.name,"bind_position":b.initial_position.to_array()})).collect::<Vec<_>>(),
        "submeshes":model.submeshes.iter().map(|s|json!({"begin":s.begin_index,"count":s.index_count,
            "material":model.materials.get(s.material_id as usize).map(|m|m.name.as_str())})).collect::<Vec<_>>()
    });
    let bytes = serde_json::to_vec(&value).map_err(|e| e.to_string())?;
    fs::write(dir.join(format!("{stage}.json")), bytes).map_err(|e| e.to_string())
}
