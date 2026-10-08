//! 用真实蒙皮网格检查腿面与裙面交叉，可导出最严重帧供离线查看。
use glam::Vec3;
use mmd_engine::{
    animation::VmdAnimation,
    model::{load_pmx, MmdModel, VertexWeight},
    physics::{set_config, PhysicsConfig},
};
use std::{
    collections::{HashMap, HashSet},
    env, fs,
    sync::Arc,
};

fn garment(name: &str) -> bool {
    let n = name.to_lowercase();
    !["穗", "飾", "饰", "鞋", "胸", "袖", "hair", "tail"]
        .iter()
        .any(|s| n.contains(s))
        && ["skirt", "スカート", "裙", "裾", "衣摆"]
            .iter()
            .any(|s| n.contains(s))
}

fn weight_pairs(weight: &VertexWeight) -> Vec<(i32, f32)> {
    match weight {
        VertexWeight::Bdef1 { bone } => vec![(*bone, 1.0)],
        VertexWeight::Bdef2 { bones, weight } | VertexWeight::Sdef { bones, weight, .. } => {
            vec![(bones[0], *weight), (bones[1], 1.0 - weight)]
        }
        VertexWeight::Bdef4 { bones, weights } | VertexWeight::Qdef { bones, weights } => {
            bones.iter().copied().zip(weights.iter().copied()).collect()
        }
    }
}

fn categories(model: &MmdModel) -> Vec<u8> {
    let skirt: HashSet<i32> = model
        .rigid_bodies
        .iter()
        .filter(|b| garment(&b.local_name) || garment(&b.universal_name))
        .map(|b| b.bone_index)
        .collect();
    let leg: HashSet<i32> = model
        .bone_manager
        .links()
        .enumerate()
        .filter_map(|(i, b)| {
            let n = b.name.to_lowercase();
            (!["ik", "ＩＫ", "d", "裙", "スカート"]
                .iter()
                .any(|s| n.contains(s))
                && ["足", "ひざ", "膝", "腿", "thigh", "shin"]
                    .iter()
                    .any(|s| n.contains(s)))
            .then_some(i as i32)
        })
        .collect();
    model
        .weights
        .iter()
        .map(|w| {
            let pairs = weight_pairs(w);
            let sw: f32 = pairs
                .iter()
                .filter(|(i, _)| skirt.contains(i))
                .map(|(_, w)| w)
                .sum();
            let lw: f32 = pairs
                .iter()
                .filter(|(i, _)| leg.contains(i))
                .map(|(_, w)| w)
                .sum();
            if sw > 0.5 {
                1
            } else if lw > 0.5 {
                2
            } else {
                0
            }
        })
        .collect()
}

#[derive(Clone)]
struct Triangle {
    indices: [usize; 3],
    points: [Vec3; 3],
    min: Vec3,
    max: Vec3,
}

fn triangles(model: &MmdModel, categories: &[u8], category: u8) -> Vec<Triangle> {
    // 三个顶点都需由该部位主导，避免腰部交界与混合权重误计。
    let visible = visible_indices(model);
    visible
        .chunks_exact(3)
        .filter_map(|ids| {
            let indices = [ids[0] as usize, ids[1] as usize, ids[2] as usize];
            if indices.iter().any(|&i| categories[i] != category) {
                return None;
            }
            let points = indices.map(|i| model.update_positions[i]);
            Some(Triangle {
                indices,
                points,
                min: points[0].min(points[1]).min(points[2]),
                max: points[0].max(points[1]).max(points[2]),
            })
        })
        .collect()
}

fn visible_indices(model: &MmdModel) -> Vec<u32> {
    model
        .submeshes
        .iter()
        .filter(|mesh| {
            let index = mesh.material_id as usize;
            model.is_material_visible(index) && model.materials[index].diffuse.w > 0.001
        })
        .flat_map(|mesh| {
            model.indices[mesh.begin_index as usize..(mesh.begin_index + mesh.index_count) as usize]
                .iter()
                .copied()
        })
        .collect()
}

fn edge_hits_triangle(a: Vec3, b: Vec3, triangle: &[Vec3; 3]) -> bool {
    let direction = b - a;
    let e1 = triangle[1] - triangle[0];
    let e2 = triangle[2] - triangle[0];
    let cross = direction.cross(e2);
    let det = e1.dot(cross);
    if det.abs() < 1e-7 {
        return false;
    }
    let inverse = det.recip();
    let from = a - triangle[0];
    let u = from.dot(cross) * inverse;
    let q = from.cross(e1);
    let v = direction.dot(q) * inverse;
    let t = e2.dot(q) * inverse;
    // 排除仅相切或共面的情况，只统计确实越过表面的交叉。
    u >= 0.0 && v >= 0.0 && u + v <= 1.0 && t > 1e-5 && t < 1.0 - 1e-5
}

fn intersects(a: &Triangle, b: &Triangle) -> bool {
    if a.min.cmpgt(b.max).any() || b.min.cmpgt(a.max).any() {
        return false;
    }
    (0..3).any(|i| {
        edge_hits_triangle(a.points[i], a.points[(i + 1) % 3], &b.points)
            || edge_hits_triangle(b.points[i], b.points[(i + 1) % 3], &a.points)
    })
}

fn cells(triangle: &Triangle) -> Vec<(i32, i32, i32)> {
    let lo = triangle.min.floor().as_ivec3();
    let hi = triangle.max.floor().as_ivec3();
    let mut cells = Vec::new();
    for x in lo.x..=hi.x {
        for y in lo.y..=hi.y {
            for z in lo.z..=hi.z {
                cells.push((x, y, z));
            }
        }
    }
    cells
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 4 {
        return Err("用法: probe_garment_mesh <PMX> <VMD> <输出JSON> [身体倍率] [渲染FPS]".into());
    }
    let scale = args.get(4).map_or(Ok(1.0), |s| s.parse::<f32>())?;
    let fps = args.get(5).map_or(Ok(60), |s| s.parse::<usize>())?;
    if fps == 0 {
        return Err("渲染FPS必须大于零".into());
    }
    let mut config = PhysicsConfig::default();
    config.static_collider_scale = scale;
    config.inertia_strength = 1.0;
    set_config(config);
    let mut model = load_pmx(&args[1])?;
    model.set_layer_animation(0, Some(Arc::new(VmdAnimation::load(&args[2])?)));
    model.set_layer_loop(0, true);
    model.play_layer(0);
    model.update_node_animation(false);
    if !model.init_physics() {
        return Err("物理初始化失败".into());
    }
    let categories = categories(&model);
    let mut worst_count = 0;
    let mut worst_frame = 0;
    let mut samples = 0;
    let mut crossing_samples = 0;
    let mut sum = 0;
    let mut worst = serde_json::Value::Null;
    for frame in 0..5 * fps {
        model.tick_animation(1.0 / fps as f32);
        if frame < 2 * fps || frame % (fps / 15).max(1) != 0 {
            continue;
        }
        let skirts = triangles(&model, &categories, 1);
        let legs = triangles(&model, &categories, 2);
        let mut count = 0;
        let mut crossed = HashSet::new();
        // 网格空间分桶，避免裙面与腿面全量两两扫描。
        let mut buckets: HashMap<_, Vec<usize>> = HashMap::new();
        for (index, leg) in legs.iter().enumerate() {
            for cell in cells(leg) {
                buckets.entry(cell).or_default().push(index);
            }
        }
        for skirt in &skirts {
            let candidates: HashSet<_> = cells(skirt)
                .iter()
                .filter_map(|cell| buckets.get(cell))
                .flatten()
                .copied()
                .collect();
            for index in candidates {
                let leg = &legs[index];
                if intersects(skirt, leg) {
                    count += 1;
                    crossed.extend(skirt.indices);
                    crossed.extend(leg.indices);
                }
            }
        }
        samples += 1;
        sum += count;
        crossing_samples += usize::from(count > 0);
        if worst.is_null() || count > worst_count {
            worst_count = count;
            worst_frame = frame;
            worst = serde_json::json!({ "model": args[1], "motion": args[2],
                "frame": frame, "fps": fps, "scale": scale, "intersection_pairs": count,
                "skirt_triangles": skirts.len(), "leg_triangles": legs.len(),
                "positions": model.update_positions.iter().map(|p| p.to_array()).collect::<Vec<_>>(),
                "indices": visible_indices(&model), "categories": categories,
                "crossed_vertices": crossed.into_iter().collect::<Vec<_>>() });
        }
    }
    fs::write(&args[3], serde_json::to_vec(&worst)?)?;
    println!("MESH samples={samples} crossing_samples={crossing_samples} intersection_pair_mean={:.3} intersection_pair_max={worst_count} worst_time={:.3}s json={}",
        sum as f64 / samples.max(1) as f64, worst_frame as f32 / fps as f32, args[3]);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
}
