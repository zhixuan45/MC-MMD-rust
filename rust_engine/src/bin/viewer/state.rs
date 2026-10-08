use std::{
    fs,
    path::{Path, PathBuf},
};

use mmd_engine::vmd_smoothing::{
    parse_group_config, serialize_group_config, BoneSelection, SmoothingGroup, SmoothingOptions,
};
use serde_json::{json, Value};

pub struct ViewerPersistedState {
    pub model_path: String,
    pub animation_path: String,
    pub selected_fbx_stack: Option<String>,
    pub static_collider_scale: f32,
    pub smoothing_options: SmoothingOptions,
    pub smoothing_groups: Vec<SmoothingGroup>,
    pub active_group_index: usize,
}

impl ViewerPersistedState {
    pub fn load() -> Option<Self> {
        let content = fs::read_to_string(state_file_path()).ok()?;
        Self::parse(&content)
    }

    fn parse(content: &str) -> Option<Self> {
        let value: Value = serde_json::from_str(content).ok()?;
        let smoothing_options = parse_legacy_smoothing(value.get("smoothing"));
        let smoothing_groups = value
            .get("smoothing_groups")
            .and_then(Value::as_array)
            .and_then(|groups| {
                let config = serde_json::to_string(&json!({ "groups": groups })).ok()?;
                parse_group_config(&config).ok()
            })
            .filter(|groups| !groups.is_empty())
            .unwrap_or_else(|| {
                vec![SmoothingGroup {
                    name: "默认平滑组".into(),
                    enabled: true,
                    options: smoothing_options.clone(),
                }]
            })
            .into_iter()
            .take(64)
            .collect::<Vec<_>>();
        let active_group_index = value
            .get("active_group_index")
            .and_then(Value::as_u64)
            .map(|index| index as usize)
            .unwrap_or(0)
            .min(smoothing_groups.len().saturating_sub(1));
        Some(Self {
            model_path: value
                .get("model_path")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
            animation_path: value
                .get("animation_path")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
            selected_fbx_stack: value
                .get("selected_fbx_stack")
                .and_then(Value::as_str)
                .map(ToString::to_string),
            static_collider_scale: value
                .get("static_collider_scale")
                .and_then(Value::as_f64)
                .map(|value| value as f32)
                .unwrap_or(crate::physics::STATIC_COLLISION_SHAPE_SCALE)
                .clamp(0.1, 1.5),
            smoothing_options,
            smoothing_groups,
            active_group_index,
        })
    }

    pub fn save(&self) -> Result<(), String> {
        let path = state_file_path();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| format!("创建配置目录失败: {}", error))?;
        }
        let group_config = serialize_group_config(&self.smoothing_groups)
            .map_err(|error| format!("序列化平滑组失败: {error}"))?;
        let group_value: Value = serde_json::from_str(&group_config)
            .map_err(|error| format!("解析平滑组配置失败: {error}"))?;
        let groups = group_value
            .get("groups")
            .cloned()
            .unwrap_or_else(|| json!([]));
        let content = serde_json::to_string_pretty(&json!({
            "model_path": self.model_path,
            "animation_path": self.animation_path,
            "selected_fbx_stack": self.selected_fbx_stack,
            "static_collider_scale": self.static_collider_scale,
            "smoothing_groups": groups,
            "active_group_index": self.active_group_index,
            "smoothing": legacy_smoothing_json(&self.smoothing_options),
        }))
        .map_err(|error| format!("序列化 viewer 配置失败: {}", error))?;
        fs::write(path, content).map_err(|error| format!("写入 viewer 配置失败: {}", error))
    }
}

fn parse_legacy_smoothing(value: Option<&Value>) -> SmoothingOptions {
    let mut options = SmoothingOptions::default();
    let Some(value) = value else {
        return options;
    };
    options.strength = value
        .get("strength")
        .and_then(Value::as_f64)
        .map(|value| value as f32)
        .unwrap_or(options.strength)
        .clamp(0.0, 1.0);
    options.radius = value
        .get("radius")
        .and_then(Value::as_u64)
        .map(|value| value.min(120) as u32)
        .unwrap_or(options.radius)
        .clamp(1, 120);
    options.looped = value
        .get("looped")
        .and_then(Value::as_bool)
        .unwrap_or(options.looped);
    options.selection = match value.get("selection").and_then(Value::as_str) {
        Some("all_except_ik") => BoneSelection::AllExceptIk,
        Some("all") => BoneSelection::All,
        Some("named") => BoneSelection::Named(read_names(value)),
        _ => BoneSelection::UpperBody,
    };
    options
}

fn read_names(value: &Value) -> Vec<String> {
    value
        .get("names")
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(Value::as_str)
                .map(ToString::to_string)
                .collect()
        })
        .unwrap_or_default()
}

fn legacy_smoothing_json(options: &SmoothingOptions) -> Value {
    json!({
        "strength": options.strength,
        "radius": options.radius,
        "looped": options.looped,
        "selection": match &options.selection {
            BoneSelection::UpperBody => "upper_body",
            BoneSelection::AllExceptIk => "all_except_ik",
            BoneSelection::All => "all",
            BoneSelection::Named(_) => "named",
        },
        "names": match &options.selection {
            BoneSelection::Named(names) => names.clone(),
            _ => Vec::new(),
        },
    })
}

fn state_file_path() -> PathBuf {
    app_data_dir()
        .unwrap_or_else(|| {
            std::env::current_dir()
                .unwrap_or_else(|_| PathBuf::from("."))
                .join(".viewer")
        })
        .join("viewer_state.json")
}

fn app_data_dir() -> Option<PathBuf> {
    std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .map(|path| path.join(Path::new("mmdskin").join("viewer")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_smoothing_migrates_to_single_group_in_memory() {
        let state = ViewerPersistedState::parse(
            r#"{"model_path":"D:/Dream Journey.pmx","animation_path":"walk.vmd","smoothing":{"strength":0.7,"radius":5,"looped":true,"selection":"named","names":["左手"]}}"#,
        )
        .unwrap();
        assert_eq!(state.model_path, "D:/Dream Journey.pmx");
        assert_eq!(state.smoothing_groups.len(), 1);
        assert_eq!(state.smoothing_groups[0].options.strength, 0.7);
        assert!(state.smoothing_groups[0].options.looped);
        assert!(matches!(
            &state.smoothing_groups[0].options.selection,
            BoneSelection::Named(names) if names.len() == 1 && names[0] == "左手"
        ));
    }

}
