use serde_json::{json, Value};

use crate::{MmdError, Result};

use super::{BoneSelection, SmoothingGroup, SmoothingOptions};

/// 读取分组 JSON，并验证字段类型与参数范围。
pub fn parse_group_config(source: &str) -> Result<Vec<SmoothingGroup>> {
    let value: Value =
        serde_json::from_str(source).map_err(|_| config_error("组配置不是有效 JSON"))?;
    let root = value
        .as_object()
        .ok_or_else(|| config_error("组配置根节点必须是对象"))?;
    reject_unknown_keys(root, &["groups"], "组配置")?;
    let groups = root
        .get("groups")
        .ok_or_else(|| config_error("组配置缺少必填字段 groups"))?;
    let groups = groups
        .as_array()
        .ok_or_else(|| config_error("groups 必须是数组"))?;
    if groups.len() > 64 {
        return Err(config_error("平滑组最多允许 64 组"));
    }

    groups
        .iter()
        .enumerate()
        .map(|(index, value)| parse_group(value, index))
        .collect()
}

/// 序列化为稳定的平铺分组配置。
pub fn serialize_group_config(groups: &[SmoothingGroup]) -> Result<String> {
    if groups.len() > 64 {
        return Err(config_error("平滑组最多允许 64 组"));
    }
    let values = groups
        .iter()
        .map(|group| {
            if group.name.trim().is_empty() {
                return Err(config_error("平滑组名称不能为空"));
            }
            group.options.validate()?;
            let (selection, names) = match &group.options.selection {
                BoneSelection::UpperBody => ("upper_body", Vec::<String>::new()),
                BoneSelection::AllExceptIk => ("all_except_ik", Vec::new()),
                BoneSelection::All => ("all", Vec::new()),
                BoneSelection::Named(names) => ("named", names.clone()),
            };
            Ok(json!({
                "name": group.name,
                "enabled": group.enabled,
                "strength": group.options.strength,
                "radius": group.options.radius,
                "looped": group.options.looped,
                "selection": selection,
                "names": names,
            }))
        })
        .collect::<Result<Vec<_>>>()?;
    serde_json::to_string_pretty(&json!({"groups": values}))
        .map_err(|_| config_error("组配置无法序列化"))
}

fn parse_group(value: &Value, index: usize) -> Result<SmoothingGroup> {
    let object = value
        .as_object()
        .ok_or_else(|| config_error("每组配置必须是对象"))?;
    reject_unknown_keys(
        object,
        &[
            "name",
            "enabled",
            "strength",
            "radius",
            "looped",
            "selection",
            "names",
        ],
        "平滑组",
    )?;
    let name = match object.get("name") {
        Some(value) => value
            .as_str()
            .ok_or_else(|| config_error("组 name 必须是字符串"))?
            .to_owned(),
        None => format!("组 {}", index + 1),
    };
    if name.trim().is_empty() {
        return Err(config_error("平滑组名称不能为空"));
    }
    let enabled = optional_bool(object.get("enabled"), true, "enabled")?;
    let strength = match object.get("strength") {
        Some(value) => value
            .as_f64()
            .ok_or_else(|| config_error("strength 必须是数值"))? as f32,
        None => SmoothingOptions::default().strength,
    };
    let radius = match object.get("radius") {
        Some(value) => u32::try_from(
            value
                .as_u64()
                .ok_or_else(|| config_error("radius 必须是非负整数"))?,
        )
        .map_err(|_| config_error("radius 超出整数范围"))?,
        None => SmoothingOptions::default().radius,
    };
    let looped = optional_bool(object.get("looped"), false, "looped")?;
    let selection_name = match object.get("selection") {
        Some(value) => value
            .as_str()
            .ok_or_else(|| config_error("selection 必须是字符串"))?,
        None => "upper_body",
    };
    let names = match object.get("names") {
        Some(value) => value
            .as_array()
            .ok_or_else(|| config_error("names 必须是字符串数组"))?
            .iter()
            .map(|name| {
                name.as_str()
                    .map(str::to_owned)
                    .ok_or_else(|| config_error("names 必须是字符串数组"))
            })
            .collect::<Result<Vec<_>>>()?,
        None => Vec::new(),
    };
    let selection = match selection_name {
        "upper_body" => BoneSelection::UpperBody,
        "all_except_ik" => BoneSelection::AllExceptIk,
        "all" => BoneSelection::All,
        "named" => BoneSelection::Named(names),
        _ => {
            return Err(config_error(
                "selection 仅支持 upper_body、all_except_ik、all、named",
            ))
        }
    };
    let options = SmoothingOptions {
        strength,
        radius,
        looped,
        selection,
    };
    options.validate()?;
    Ok(SmoothingGroup {
        name,
        enabled,
        options,
    })
}

fn optional_bool(value: Option<&Value>, default: bool, field: &str) -> Result<bool> {
    match value {
        Some(value) => value
            .as_bool()
            .ok_or_else(|| config_error(&format!("{} 必须是布尔值", field))),
        None => Ok(default),
    }
}

fn reject_unknown_keys(
    object: &serde_json::Map<String, Value>,
    allowed: &[&str],
    section: &str,
) -> Result<()> {
    if let Some(key) = object.keys().find(|key| !allowed.contains(&key.as_str())) {
        return Err(config_error(&format!("{}包含未知字段：{}", section, key)));
    }
    Ok(())
}

fn config_error(message: &str) -> MmdError {
    MmdError::VmdParse(message.to_string())
}
