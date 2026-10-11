//! 使用生产 PMX 与纹理加载器检查单个日志相关模型。

use std::collections::HashSet;
use std::env;
use std::fs::File;
use std::io::BufReader;
use std::path::Path;
use std::process::ExitCode;

use mmd::HeaderReader;
use mmd_engine::{model::load_pmx, texture::load_texture};
use serde_json::{json, Value};

fn run(path: &Path) -> Result<Value, String> {
    let header = HeaderReader::new(BufReader::new(
        File::open(path).map_err(|error| format!("open PMX header: {error}"))?,
    ))
    .map_err(|error| format!("parse PMX header: {error}"))?;
    let model = load_pmx(path).map_err(|error| format!("production load_pmx: {error}"))?;

    let mut weight_counts = [0usize; 5];
    for weight in &model.weights {
        let index = match weight {
            mmd_engine::model::VertexWeight::Bdef1 { .. } => 0,
            mmd_engine::model::VertexWeight::Bdef2 { .. } => 1,
            mmd_engine::model::VertexWeight::Bdef4 { .. } => 2,
            mmd_engine::model::VertexWeight::Sdef { .. } => 3,
            mmd_engine::model::VertexWeight::Qdef { .. } => 4,
        };
        weight_counts[index] += 1;
    }

    let material_textures: Vec<Option<&str>> = model
        .materials
        .iter()
        .map(|material| {
            usize::try_from(material.texture_index)
                .ok()
                .and_then(|index| model.texture_paths.get(index))
                .map(String::as_str)
        })
        .collect();

    let mut seen = HashSet::new();
    let mut diffuse_textures = Vec::new();
    for path in material_textures.iter().flatten().copied() {
        if !seen.insert(path) {
            continue;
        }
        let exists = Path::new(path).is_file();
        let decode = match load_texture(path) {
            Ok(texture) => json!({
                "ok": true,
                "width": texture.width,
                "height": texture.height,
                "has_alpha": texture.has_alpha,
                "decoded_bytes": texture.byte_count()
            }),
            Err(error) => json!({ "ok": false, "error": error.to_string() }),
        };
        diffuse_textures.push(json!({ "path": path, "exists": exists, "decode": decode }));
    }

    let materials: Vec<Value> = model
        .materials
        .iter()
        .zip(material_textures)
        .enumerate()
        .map(|(index, (material, diffuse_path))| {
            json!({
                "index": index,
                "name": material.name,
                "diffuse_texture_index": material.texture_index,
                "diffuse_path": diffuse_path,
                "diffuse_exists": diffuse_path.is_some_and(|value| Path::new(value).is_file()),
                "environment_texture_index": material.environment_index,
                "toon_texture_index": material.toon_index
            })
        })
        .collect();

    let bones: Vec<Value> = (0..model.bone_manager.bone_count())
        .filter_map(|index| {
            model.bone_manager.get_bone(index).map(|bone| {
                json!({
                    "index": index,
                    "name": bone.name,
                    "parent_index": bone.parent_index,
                    "initial_position": bone.initial_position.to_array()
                })
            })
        })
        .collect();
    let bodies: Vec<Value> = model
        .rigid_bodies
        .iter()
        .enumerate()
        .map(|(index, body)| {
            json!({
                "index": index,
                "local_name": body.local_name,
                "universal_name": body.universal_name,
                "bone_index": body.bone_index,
                "mode": format!("{:?}", body.mode),
                "group": body.group,
                "collision_mask": body.un_collision_group_flag,
                "position": body.position
            })
        })
        .collect();
    let joint_endpoints: Vec<Value> = model
        .joints
        .iter()
        .enumerate()
        .map(|(index, joint)| {
            json!({
                "index": index,
                "body_a_index": joint.rigid_body_a_index,
                "body_b_index": joint.rigid_body_b_index
            })
        })
        .collect();

    let light_map_path = path
        .parent()
        .unwrap_or_else(|| Path::new(""))
        .join("lightMap.png");
    // 仅审计时解码一次；不代表运行时额外重试。
    let light_map_decode = match load_texture(&light_map_path) {
        Ok(texture) => json!({
            "ok": true,
            "width": texture.width,
            "height": texture.height,
            "has_alpha": texture.has_alpha,
            "decoded_bytes": texture.byte_count()
        }),
        Err(error) => json!({ "ok": false, "error": error.to_string() }),
    };
    Ok(json!({
        "source_path": path.to_string_lossy(),
        "pmx_metadata": {
            "version": header.version,
            "local_name": header.model_local_name,
            "universal_name": header.model_universal_name,
            "local_comment_bytes": header.local_comments.len(),
            "universal_comment_bytes": header.universal_comments.len()
        },
        "runtime_model_name": model.name,
        "counts": {
            "vertices": model.vertices.len(),
            "indices": model.indices.len(),
            "bones": model.bone_manager.bone_count(),
            "bodies": model.rigid_bodies.len(),
            "joints": model.joints.len(),
            "materials": model.materials.len(),
            "weights": {
                "BDEF1": weight_counts[0],
                "BDEF2": weight_counts[1],
                "BDEF4": weight_counts[2],
                "SDEF": weight_counts[3],
                "QDEF": weight_counts[4]
            }
        },
        "materials": materials,
        "bone_position_space": "right_model_local",
        "bones": bones,
        "raw_body_position_space": "pmx_left_model",
        "raw_bodies": bodies,
        "joint_endpoint_index_space": "raw_rigid_body_indices",
        "raw_joint_endpoints": joint_endpoints,
        "distinct_runtime_diffuse_textures": diffuse_textures,
        "optional_light_map": {
            "path": light_map_path.to_string_lossy(),
            "exists": light_map_path.is_file(),
            "decode": light_map_decode
        }
    }))
}

fn main() -> ExitCode {
    let Some(path) = env::args_os().nth(1) else {
        eprintln!("用法: cargo run --manifest-path rust_engine/Cargo.toml --example audit_latest_log_assets -- <模型.pmx>");
        return ExitCode::FAILURE;
    };

    match run(Path::new(&path)) {
        Ok(report) => match serde_json::to_string_pretty(&report) {
            Ok(output) => {
                println!("{output}");
                ExitCode::SUCCESS
            }
            Err(error) => {
                eprintln!("JSON 序列化失败: {error}");
                ExitCode::FAILURE
            }
        },
        Err(error) => {
            eprintln!("模型审计失败: {error}");
            ExitCode::FAILURE
        }
    }
}
