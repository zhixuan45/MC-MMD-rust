//! 用法：cargo run --manifest-path rust_engine/Cargo.toml --example audit_hair_geometry -- <model.pmx> [--output <report.json>]

use mmd_engine::model::hair_geometry::{
    audit_hair_geometry, classify_hair_name, HairGeometryAudit,
};
use mmd_engine::model::load_pmx;
use std::env;
use std::fs;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let mut model_path = None;
    let mut output_path = None;
    while let Some(arg) = args.next() {
        if arg == "--output" {
            output_path = Some(args.next().ok_or("--output 后缺少路径")?);
        } else if model_path.is_none() {
            model_path = Some(arg);
        } else {
            return Err(format!("未知参数：{arg}").into());
        }
    }
    let path = model_path.ok_or("请传入 PMX 文件路径")?;
    let model = load_pmx(&path)?;
    let plan = mmd_engine::physics::hair_collision_proxies::build_hair_collision_proxies(&model);
    let report = serde_json::json!({
        "model": model.name,
        "source": path,
        "experimental_proxy_plan": {
            "enabled_by_default": false,
            "proxies": plan.proxies.iter().map(|p| serde_json::json!({"body_index": p.body_index, "shape": format!("{:?}",p.effective_shape), "size":p.effective_size,"bind_transform":p.bind_transform.to_cols_array(),"raw_bind":p.raw_bind.to_cols_array(),"covered_points":p.covered_point_count,"volume_ratio":p.volume_ratio})).collect::<Vec<_>>(),
            "skipped": plan.skipped.iter().map(|p| serde_json::json!({"body_index":p.body_index,"reason":p.reason})).collect::<Vec<_>>()
        },
        "counts": {
            "vertices": model.vertices.len(),
            "triangles": model.indices.len() / 3,
            "rigid_bodies": model.rigid_bodies.len(),
        },
        "selection": {
            "basis": "explicit hair terms plus bound bone and rigid body",
            "excluded_terms": ["explicit tail", "ornament", "accessory", "clothing"],
            "coordinates": "runtime vertex RHS -> Bullet LHS by Z reflection -> inverse rigid-body bind transform",
            "fit_thresholds": "none; ratios report measured fit, not an acceptance threshold",
        },
        "audits": audit_hair_geometry(&model).iter().map(audit_json).collect::<Vec<_>>(),
        "name_inventory": model.rigid_bodies.iter().enumerate().map(|(body_index, body)| {
            let bone_name = usize::try_from(body.bone_index)
                .ok()
                .and_then(|index| model.bone_manager.get_bone(index))
                .map(|bone| bone.name.as_str())
                .unwrap_or("");
            let evidence = classify_hair_name(bone_name, &body.local_name, &body.universal_name);
            serde_json::json!({
                "body_index": body_index,
                "bone_index": body.bone_index,
                "bone_name": bone_name,
                "local_name": body.local_name,
                "universal_name": body.universal_name,
                "name_candidate": evidence.candidate,
                "hair_terms": evidence.hair_terms,
                "excluded_terms": evidence.excluded_terms,
                "soft_excluded_terms": evidence.soft_excluded_terms,
            })
        }).collect::<Vec<_>>(),
    });
    let encoded = serde_json::to_string_pretty(&report)?;
    if let Some(output) = output_path {
        fs::write(&output, encoded)?;
        let audits = report["audits"].as_array().map_or(0, Vec::len);
        println!(
            "{}",
            serde_json::json!({"status":"written", "path":output, "audits":audits})
        );
    } else {
        println!("{encoded}");
    }
    Ok(())
}

fn audit_json(item: &HairGeometryAudit) -> serde_json::Value {
    let geometry = item.geometry.as_ref().map(|g| {
        serde_json::json!({
            "bounds_min": vec3(g.bounds_min),
            "bounds_max": vec3(g.bounds_max),
            "extent": vec3(g.extent),
            "sphere_center": vec3(g.sphere_center),
            "required_sphere_radius": g.required_sphere_radius,
            "original_sphere_coverage_ratio": g.original_sphere_coverage_ratio,
            "capsule_axis": g.capsule_axis,
        "fitted_capsule_center": vec3(g.fitted_capsule_center),
            "fitted_capsule_radius": g.fitted_capsule_radius,
            "fitted_capsule_segment_length": g.fitted_capsule_segment_length,
            "fitted_capsule_volume": g.fitted_capsule_volume,
            "original_shape_volume": g.original_shape_volume,
            "fitted_to_original_volume_ratio": g.fitted_to_original_volume_ratio,
            "original_shape_coverage_ratio": g.original_shape_coverage_ratio,
        })
    });
    serde_json::json!({
        "body_index": item.body_index,
        "bone_index": item.bone_index,
        "bone_name": item.bone_name,
        "body_name": item.body_name,
        "status": format!("{:?}", item.status).to_lowercase(),
        "skip_reason": item.skip_reason,
        "weights": {
            "influenced_vertices": item.weights.influenced_vertices,
            "positive_influences": item.weights.positive_influences,
            "target_weight_sum": item.weights.total_positive_weight,
            "mixed_boundary_vertices": item.weights.mixed_boundary_vertices,
        },
        "components": item.components.iter().map(|c| serde_json::json!({
            "vertex_count": c.vertex_count,
            "triangle_count": c.triangle_count,
            "bounds_min": vec3(c.bounds_min),
            "bounds_max": vec3(c.bounds_max),
        })).collect::<Vec<_>>(),
        "geometry": geometry,
        "original": {
            "shape": item.original.shape,
            "size": item.original.size,
            "radius_max": item.original.radius_max,
        },
        "bound_vertices": item.bound_vertices,
    })
}

fn vec3(value: glam::Vec3) -> [f32; 3] {
    value.to_array()
}
