//! 头发刚体与绑定网格的只读几何审计。

use crate::model::{MmdModel, VertexWeight};
use glam::Vec3;
use mmd::pmx::rigid_body::{RigidBodyMode, RigidBodyShape};
use std::collections::{HashMap, HashSet, VecDeque};

#[derive(Clone, Debug, PartialEq)]
pub struct HairGeometryAudit {
    pub body_index: usize,
    pub bone_index: i32,
    pub bone_name: String,
    pub body_name: String,
    pub status: AuditStatus,
    pub skip_reason: Option<String>,
    pub weights: WeightSummary,
    pub components: Vec<ComponentSummary>,
    pub geometry: Option<GeometrySummary>,
    pub original: OriginalShapeSummary,
    pub bound_vertices: Vec<usize>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HairNameEvidence {
    pub candidate: bool,
    pub hair_terms: Vec<String>,
    pub excluded_terms: Vec<String>,
    pub soft_excluded_terms: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AuditStatus {
    Candidate,
    Skipped,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct WeightSummary {
    pub influenced_vertices: usize,
    pub positive_influences: usize,
    pub total_positive_weight: f64,
    pub mixed_boundary_vertices: usize,
}

#[derive(Default)]
struct InfluenceData {
    vertices: HashMap<usize, Vec<usize>>,
    summaries: HashMap<usize, WeightSummary>,
    invalid: bool,
}

struct MeshTopology {
    component_by_vertex: Vec<usize>,
    triangle_counts: HashMap<usize, usize>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ComponentSummary {
    pub vertex_count: usize,
    pub triangle_count: usize,
    pub bounds_min: Vec3,
    pub bounds_max: Vec3,
}

#[derive(Clone, Debug, PartialEq)]
pub struct GeometrySummary {
    pub bounds_min: Vec3,
    pub bounds_max: Vec3,
    pub extent: Vec3,
    pub sphere_center: Vec3,
    pub required_sphere_radius: f32,
    pub original_sphere_coverage_ratio: Option<f32>,
    pub capsule_axis: usize,
    pub fitted_capsule_center: Vec3,
    pub fitted_capsule_radius: f32,
    pub fitted_capsule_segment_length: f32,
    pub fitted_capsule_volume: f32,
    pub original_shape_volume: Option<f32>,
    pub fitted_to_original_volume_ratio: Option<f32>,
    pub original_shape_coverage_ratio: Option<f32>,
    pub body_local_points: Vec<Vec3>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct OriginalShapeSummary {
    pub shape: String,
    pub size: [f32; 3],
    pub radius_max: f32,
}

/// 对模型中可明确识别的动态头发刚体生成几何证据。
pub fn audit_hair_geometry(model: &MmdModel) -> Vec<HairGeometryAudit> {
    let influence_data = collect_influence_data(model);
    let invalid_weight_data = model.weights.len() != model.vertices.len() || influence_data.invalid;
    let mesh_topology = build_mesh_topology(model);
    let invalid_indices = model.indices.len() % 3 != 0
        || model
            .indices
            .iter()
            .any(|index| *index as usize >= model.vertices.len());
    let mut by_bone: HashMap<usize, Vec<usize>> = HashMap::new();
    for (i, body) in model.rigid_bodies.iter().enumerate() {
        if let Ok(bone) = usize::try_from(body.bone_index) {
            by_bone.entry(bone).or_default().push(i);
        }
    }

    let candidate_bodies: Vec<_> = model
        .rigid_bodies
        .iter()
        .enumerate()
        .filter_map(|(body_index, body)| {
            let bone_index = usize::try_from(body.bone_index)
                .ok()
                .filter(|index| model.bone_manager.get_bone(*index).is_some());
            let bone_name = bone_index
                .and_then(|index| model.bone_manager.get_bone(index))
                .map(|bone| bone.name.as_str())
                .unwrap_or("");
            is_hair_candidate(bone_name, &body.local_name, &body.universal_name)
                .then_some((body_index, bone_index))
        })
        .collect();

    let candidate_bones: HashSet<_> = candidate_bodies
        .iter()
        .filter_map(|(_, bone)| *bone)
        .collect();
    let topology_reasons = inspect_topology(model, &candidate_bones);
    let body_graph_reasons = inspect_body_graph(model, &candidate_bodies);
    candidate_bodies
        .into_iter()
        .map(|(body_index, bone_index)| {
            let body = &model.rigid_bodies[body_index];
            let bone = bone_index.and_then(|index| model.bone_manager.get_bone(index));
            let mut reason = bone
                .is_none()
                .then(|| "骨骼索引无效".to_string())
                .or_else(|| {
                    (body.mode == RigidBodyMode::Static)
                        .then(|| "静态刚体不作为动态头发几何候选".to_string())
                        .or_else(|| {
                            bone_index.and_then(|index| topology_reasons.get(&index).cloned())
                        })
                })
                .or_else(|| body_graph_reasons.get(&body_index).cloned());
            if bone_index
                .map(|index| by_bone.get(&index).map_or(0, Vec::len) != 1)
                .unwrap_or(false)
            {
                reason = Some("同一骨骼绑定了多个刚体".into());
            }
            if body
                .position
                .iter()
                .chain(body.rotation.iter())
                .chain(body.size.iter())
                .any(|value| !value.is_finite())
            {
                reason.get_or_insert_with(|| "刚体变换或尺寸含非有限数据".into());
            }
            let valid_size = match body.shape {
                RigidBodyShape::Sphere => body.size[0] > 0.0,
                RigidBodyShape::Capsule => body.size[0] > 0.0 && body.size[1] >= 0.0,
                RigidBodyShape::Box => body.size.iter().all(|value| *value > 0.0),
            };
            if !valid_size {
                reason.get_or_insert_with(|| "原始碰撞尺寸无效".into());
            }
            let vertices = bone_index
                .and_then(|index| influence_data.vertices.get(&index))
                .cloned()
                .unwrap_or_default();
            let weights = bone_index
                .and_then(|index| influence_data.summaries.get(&index))
                .cloned()
                .unwrap_or_default();
            let components = connected_components(model, &vertices, &mesh_topology, body);
            if invalid_weight_data {
                reason.get_or_insert_with(|| "权重数量不匹配或权重数据无效".into());
            }
            if invalid_indices {
                reason.get_or_insert_with(|| "面片索引越界".into());
            }
            if vertices.is_empty() {
                reason.get_or_insert_with(|| "没有有效的正权网格顶点".into());
            }
            if components.is_empty() && !vertices.is_empty() {
                reason.get_or_insert_with(|| "网格索引无效或无法建立面片连通分量".into());
            }
            if !components.is_empty() && components.iter().any(|c| c.triangle_count == 0) {
                reason
                    .get_or_insert_with(|| "存在没有有效面片连接的影响顶点，网格资格不明确".into());
            }
            let shape_points = vertices
                .iter()
                .filter_map(|&i| model.vertices.get(i).map(|v| v.position))
                .collect::<Vec<_>>();
            let body_local_points = transform_points_to_body_local(body, &shape_points);
            if body_local_points.is_empty() {
                reason.get_or_insert_with(|| "绑定变换或网格坐标无效".into());
            }
            if body_local_points.iter().any(|p| !p.is_finite()) {
                reason.get_or_insert_with(|| "存在非有限几何数据".into());
            }
            let geometry = if reason.is_none() {
                Some(summarize_geometry(
                    &body_local_points,
                    body.shape,
                    body.size,
                ))
            } else {
                None
            };
            HairGeometryAudit {
                body_index,
                bone_index: body.bone_index,
                bone_name: bone.map(|link| link.name.clone()).unwrap_or_default(),
                body_name: body.local_name.clone(),
                status: if reason.is_some() {
                    AuditStatus::Skipped
                } else {
                    AuditStatus::Candidate
                },
                skip_reason: reason,
                weights,
                components,
                geometry,
                original: OriginalShapeSummary {
                    shape: shape_name(body.shape).into(),
                    size: body.size,
                    radius_max: shape_radius_max(body.shape, body.size),
                },
                bound_vertices: vertices,
            }
        })
        .collect()
}

pub fn classify_hair_name(bone: &str, local: &str, universal: &str) -> HairNameEvidence {
    let joined = format!(
        "{} {} {}",
        bone.to_lowercase(),
        local.to_lowercase(),
        universal.to_lowercase()
    );
    // 显式尾巴、饰物和服装优先排除；马尾与双马尾属于头发候选。
    let excluded = [
        "尾巴",
        "尻尾",
        "しっぽ",
        "tailbone",
        "发饰",
        "髪飾",
        "头饰",
        "頭飾",
        "髮飾",
        "髪飾り",
        "簪",
        "かんざし",
        "发夹",
        "髮夾",
        "hairpin",
        "hairclip",
        "饰品",
        "装飾",
        "accessory",
        "ornament",
        "ribbon",
        "リボン",
        "蝴蝶結",
        "蝴蝶结",
    ];
    let excluded_terms = excluded
        .iter()
        .filter(|term| joined.contains(**term))
        .map(|term| (*term).to_string())
        .collect::<Vec<_>>();
    let clothing_terms = [
        "衣", "袖", "裙", "cloth", "sleeve", "skirt", "dress", "cape",
    ];
    let soft_excluded_terms = clothing_terms
        .iter()
        .filter(|term| joined.contains(**term))
        .map(|term| (*term).to_string())
        .collect::<Vec<_>>();
    let hair_cues = [
        "头发",
        "頭髪",
        "頭髮",
        "髮",
        "发",
        "髪",
        "hair",
        "ヘア",
        "ponytail",
        "twintail",
        "馬尾",
        "马尾",
        "ポニーテール",
        "ツインテール",
    ];
    let hair_terms = hair_cues
        .iter()
        .filter(|term| joined.contains(**term))
        .map(|term| (*term).to_string())
        .collect::<Vec<_>>();
    let explicit_bone_hair = hair_cues
        .iter()
        .any(|term| bone.to_lowercase().contains(*term));
    HairNameEvidence {
        candidate: excluded_terms.is_empty()
            && !hair_terms.is_empty()
            && (soft_excluded_terms.is_empty() || explicit_bone_hair),
        hair_terms,
        excluded_terms,
        soft_excluded_terms,
    }
}

fn is_hair_candidate(bone: &str, local: &str, universal: &str) -> bool {
    classify_hair_name(bone, local, universal).candidate
}

fn collect_influence_data(model: &MmdModel) -> InfluenceData {
    let mut output = InfluenceData::default();
    for (vertex_index, weight) in model.weights.iter().enumerate().take(model.vertices.len()) {
        let influences = weight_influences(weight);
        let sum: f64 = influences.iter().map(|(_, value)| *value as f64).sum();
        let roundoff = influences.len() as f64 * f32::EPSILON as f64;
        if influences
            .iter()
            .any(|(_, value)| !value.is_finite() || *value < 0.0)
            || sum <= 0.0
            || sum > 1.0 + roundoff
        {
            output.invalid = true;
        }
        let positive: Vec<_> = influences
            .iter()
            .filter(|(_, value)| *value > 0.0 && value.is_finite())
            .filter_map(|(bone, value)| {
                usize::try_from(*bone)
                    .ok()
                    .filter(|index| *index < model.bone_manager.bone_count())
                    .map(|index| (index, *value))
            })
            .collect();
        if positive.len() != influences.iter().filter(|(_, value)| *value > 0.0).count() {
            output.invalid = true;
        }
        let mut per_bone: HashMap<usize, (f64, usize)> = HashMap::new();
        for (bone, value) in &positive {
            let entry = per_bone.entry(*bone).or_default();
            entry.0 += *value as f64;
            entry.1 += 1;
        }
        for (bone, (sum, count)) in per_bone {
            output.vertices.entry(bone).or_default().push(vertex_index);
            let summary = output.summaries.entry(bone).or_default();
            summary.influenced_vertices += 1;
            summary.positive_influences += count;
            summary.total_positive_weight += sum;
            if positive.iter().any(|(other, _)| *other != bone) {
                summary.mixed_boundary_vertices += 1;
            }
        }
    }
    output
}

fn weight_influences(weight: &VertexWeight) -> Vec<(i32, f32)> {
    match weight {
        VertexWeight::Bdef1 { bone } => vec![(*bone, 1.0)],
        VertexWeight::Bdef2 { bones, weight } | VertexWeight::Sdef { bones, weight, .. } => {
            vec![(bones[0], *weight), (bones[1], 1.0 - *weight)]
        }
        VertexWeight::Bdef4 { bones, weights } | VertexWeight::Qdef { bones, weights } => {
            bones.iter().copied().zip(*weights).collect()
        }
    }
}

fn build_mesh_topology(model: &MmdModel) -> MeshTopology {
    let mut parent: Vec<usize> = (0..model.vertices.len()).collect();
    let mut rank = vec![0u8; parent.len()];
    let mut valid_triangles = Vec::new();
    for face in model.indices.chunks_exact(3) {
        let ids = [face[0] as usize, face[1] as usize, face[2] as usize];
        if ids.iter().any(|id| *id >= parent.len()) {
            continue;
        }
        union(&mut parent, &mut rank, ids[0], ids[1]);
        union(&mut parent, &mut rank, ids[1], ids[2]);
        valid_triangles.push(ids);
    }
    let component_by_vertex: Vec<_> = (0..parent.len()).map(|i| find(&mut parent, i)).collect();
    let mut triangle_counts = HashMap::new();
    for face in valid_triangles {
        *triangle_counts
            .entry(component_by_vertex[face[0]])
            .or_default() += 1;
    }
    MeshTopology {
        component_by_vertex,
        triangle_counts,
    }
}

fn find(parent: &mut [usize], index: usize) -> usize {
    if parent[index] != index {
        parent[index] = find(parent, parent[index]);
    }
    parent[index]
}

fn union(parent: &mut [usize], rank: &mut [u8], a: usize, b: usize) {
    let mut root_a = find(parent, a);
    let mut root_b = find(parent, b);
    if root_a == root_b {
        return;
    }
    if rank[root_a] < rank[root_b] {
        std::mem::swap(&mut root_a, &mut root_b);
    }
    parent[root_b] = root_a;
    if rank[root_a] == rank[root_b] {
        rank[root_a] += 1;
    }
}

fn connected_components(
    model: &MmdModel,
    vertices: &[usize],
    topology: &MeshTopology,
    body: &mmd::pmx::rigid_body::RigidBody,
) -> Vec<ComponentSummary> {
    let mut groups: HashMap<usize, Vec<usize>> = HashMap::new();
    for &vertex in vertices {
        if let Some(component) = topology.component_by_vertex.get(vertex) {
            groups.entry(*component).or_default().push(vertex);
        }
    }
    let mut output: Vec<_> = groups
        .into_iter()
        .filter_map(|(component, members)| {
            let points: Vec<_> = members
                .iter()
                .map(|index| model.vertices[*index].position)
                .collect();
            let points = transform_points_to_body_local(body, &points);
            bounds(&points).map(|(bounds_min, bounds_max)| ComponentSummary {
                vertex_count: members.len(),
                triangle_count: topology
                    .triangle_counts
                    .get(&component)
                    .copied()
                    .unwrap_or(0),
                bounds_min,
                bounds_max,
            })
        })
        .collect();
    output.sort_by(|a, b| {
        a.bounds_min
            .x
            .total_cmp(&b.bounds_min.x)
            .then(a.bounds_min.y.total_cmp(&b.bounds_min.y))
            .then(a.bounds_min.z.total_cmp(&b.bounds_min.z))
    });
    output
}

fn inspect_topology(model: &MmdModel, candidates: &HashSet<usize>) -> HashMap<usize, String> {
    let mut bad: HashMap<usize, &'static str> = HashMap::new();
    let mut candidate_children: HashMap<usize, usize> = HashMap::new();
    for child in candidates {
        if let Some(parent) = model
            .bone_manager
            .get_bone(*child)
            .and_then(|bone| usize::try_from(bone.parent_index).ok())
        {
            *candidate_children.entry(parent).or_default() += 1;
        }
    }
    for &idx in candidates {
        let mut seen = HashSet::new();
        let mut cursor = Some(idx);
        while let Some(i) = cursor {
            if !seen.insert(i) {
                bad.insert(idx, "骨骼父链存在环");
                break;
            }
            let Some(bone) = model.bone_manager.get_bone(i) else {
                bad.insert(idx, "骨骼索引无效");
                break;
            };
            if candidates.contains(&i) && candidate_children.get(&i).copied().unwrap_or(0) > 1 {
                bad.insert(idx, "头发候选骨骼存在分支");
            }
            cursor = usize::try_from(bone.parent_index).ok();
        }
    }
    // 一个分支或环会影响相邻整链，因此扩展到完整候选连通分量。
    let mut adjacency: HashMap<usize, Vec<usize>> = HashMap::new();
    for &idx in candidates {
        if let Some(parent) = model
            .bone_manager
            .get_bone(idx)
            .and_then(|bone| usize::try_from(bone.parent_index).ok())
            .filter(|parent| candidates.contains(parent))
        {
            adjacency.entry(idx).or_default().push(parent);
            adjacency.entry(parent).or_default().push(idx);
        }
    }
    let mut output: HashMap<usize, String> = HashMap::new();
    let mut visited = HashSet::new();
    for &start in candidates {
        if !visited.insert(start) {
            continue;
        }
        let mut queue = VecDeque::from([start]);
        let mut component = Vec::new();
        while let Some(node) = queue.pop_front() {
            component.push(node);
            for &next in adjacency.get(&node).into_iter().flatten() {
                if visited.insert(next) {
                    queue.push_back(next);
                }
            }
        }
        if let Some(reason) = component.iter().find_map(|node| bad.get(node)) {
            for node in component {
                output.insert(node, (*reason).to_string());
            }
        }
    }
    output
}

fn inspect_body_graph(
    model: &MmdModel,
    candidate_bodies: &[(usize, Option<usize>)],
) -> HashMap<usize, String> {
    let candidate_indices: HashSet<_> = candidate_bodies.iter().map(|(index, _)| *index).collect();
    let mut adjacency: HashMap<usize, Vec<usize>> = HashMap::new();
    let mut invalid = HashSet::new();
    for joint in &model.joints {
        let a = usize::try_from(joint.rigid_body_a_index).ok();
        let b = usize::try_from(joint.rigid_body_b_index).ok();
        if a.map_or(false, |i| candidate_indices.contains(&i))
            || b.map_or(false, |i| candidate_indices.contains(&i))
        {
            let (Some(a), Some(b)) = (a, b) else {
                if let Some(i) = a.filter(|i| candidate_indices.contains(i)) {
                    invalid.insert(i);
                }
                if let Some(i) = b.filter(|i| candidate_indices.contains(i)) {
                    invalid.insert(i);
                }
                continue;
            };
            if a >= model.rigid_bodies.len() || b >= model.rigid_bodies.len() {
                invalid.extend([a, b].into_iter().filter(|i| candidate_indices.contains(i)));
                continue;
            }
            if candidate_indices.contains(&a) && candidate_indices.contains(&b) {
                adjacency.entry(a).or_default().push(b);
                adjacency.entry(b).or_default().push(a);
            }
        }
    }
    let mut output: HashMap<usize, String> = HashMap::new();
    for &body in &candidate_indices {
        let edges = adjacency.get(&body).map_or(0, Vec::len);
        if invalid.contains(&body) {
            output.insert(body, "头发刚体关节引用无效".into());
        } else if edges > 2 {
            output.insert(body, "头发刚体关节图存在分支".into());
        }
    }
    let mut visited = HashSet::new();
    for &start in &candidate_indices {
        if !visited.insert(start) {
            continue;
        }
        let mut queue = VecDeque::from([start]);
        let mut component = Vec::new();
        let mut edge_twice = 0usize;
        while let Some(node) = queue.pop_front() {
            component.push(node);
            let neighbors = adjacency.get(&node).map(Vec::as_slice).unwrap_or(&[]);
            edge_twice += neighbors.len();
            for &next in neighbors {
                if visited.insert(next) {
                    queue.push_back(next);
                }
            }
        }
        if edge_twice / 2 >= component.len() && !component.is_empty() {
            for node in component {
                output.insert(node, "头发刚体关节图存在环".into());
            }
        } else if let Some(reason) = component.iter().find_map(|node| output.get(node).cloned()) {
            for node in component {
                output.insert(node, reason.clone());
            }
        }
    }
    output
}

fn transform_points_to_body_local(
    body: &mmd::pmx::rigid_body::RigidBody,
    points: &[Vec3],
) -> Vec<Vec3> {
    let body_world =
        crate::physics::MmdRigidBodyData::from_pmx(body, None, body.size).initial_transform;
    let body_inverse = body_world.inverse();
    points
        .iter()
        .map(|p| {
            let bullet_left = Vec3::new(p.x, p.y, -p.z);
            body_inverse.transform_point3(bullet_left)
        })
        .collect()
}

fn summarize_geometry(
    points: &[Vec3],
    original_shape: RigidBodyShape,
    size: [f32; 3],
) -> GeometrySummary {
    let (min, max) = bounds(points).unwrap();
    let extent = max - min;
    let center = (min + max) * 0.5;
    // 原始 PMX 球体以刚体局部原点为球心。
    let required_sphere_radius = points.iter().map(|p| p.length()).fold(0.0_f32, f32::max);
    let radius = size[0];
    let original_sphere_coverage_ratio = (radius > 0.0).then_some(required_sphere_radius / radius);
    let capsule_axis = (0..3)
        .max_by(|a, b| extent[*a].total_cmp(&extent[*b]))
        .unwrap_or(1);
    let (fitted_capsule_radius, fitted_capsule_segment_length, fitted_capsule_volume) =
        fit_capsule(points, center, capsule_axis, extent[capsule_axis]);
    let original_shape_volume = shape_volume(original_shape, size);
    let fitted_to_original_volume_ratio = original_shape_volume
        .filter(|volume| *volume > 0.0)
        .map(|volume| fitted_capsule_volume / volume);
    let original_shape_coverage_ratio = match original_shape {
        RigidBodyShape::Sphere => original_sphere_coverage_ratio,
        RigidBodyShape::Capsule if size[0] > 0.0 => {
            let half_segment = (size[1] * 0.5).max(0.0);
            let needed = points
                .iter()
                .map(|p| {
                    let axial = (p.y.abs() - half_segment).max(0.0);
                    Vec3::new(p.x, axial, p.z).length()
                })
                .fold(0.0_f32, f32::max);
            Some(needed / size[0])
        }
        RigidBodyShape::Box if size.iter().all(|v| *v > 0.0) => Some(
            (0..3)
                .map(|i| {
                    points
                        .iter()
                        .map(|p| p[i].abs() / size[i])
                        .fold(0.0_f32, f32::max)
                })
                .fold(0.0_f32, f32::max),
        ),
        _ => None,
    };
    GeometrySummary {
        bounds_min: min,
        bounds_max: max,
        extent,
        sphere_center: Vec3::ZERO,
        required_sphere_radius,
        original_sphere_coverage_ratio,
        capsule_axis,
        fitted_capsule_center: center,
        fitted_capsule_radius,
        fitted_capsule_segment_length,
        fitted_capsule_volume,
        original_shape_volume,
        fitted_to_original_volume_ratio,
        original_shape_coverage_ratio,
        body_local_points: points.to_vec(),
    }
}

fn fit_capsule(points: &[Vec3], center: Vec3, axis: usize, extent: f32) -> (f32, f32, f32) {
    const SEARCH_STEPS: usize = 64;
    let mut best = (0.0, 0.0, f32::INFINITY);
    for step in 0..=SEARCH_STEPS {
        let segment_length = extent * step as f32 / SEARCH_STEPS as f32;
        let half_segment = segment_length * 0.5;
        let radius = points
            .iter()
            .map(|point| {
                let delta = *point - center;
                let axial = (delta[axis].abs() - half_segment).max(0.0);
                let radial_squared = (0..3)
                    .filter(|index| *index != axis)
                    .map(|index| delta[index] * delta[index])
                    .sum::<f32>();
                (radial_squared + axial * axial).sqrt()
            })
            .fold(0.0_f32, f32::max);
        let volume = capsule_volume(radius, segment_length);
        if volume < best.2 {
            best = (radius, segment_length, volume);
        }
    }
    best
}

fn capsule_volume(radius: f32, cylinder_height: f32) -> f32 {
    let sphere = 4.0 / 3.0 * std::f32::consts::PI * radius.powi(3);
    sphere + std::f32::consts::PI * radius.powi(2) * cylinder_height
}

fn shape_volume(shape: RigidBodyShape, size: [f32; 3]) -> Option<f32> {
    let pi = std::f32::consts::PI;
    match shape {
        RigidBodyShape::Sphere if size[0] > 0.0 => Some(4.0 / 3.0 * pi * size[0].powi(3)),
        RigidBodyShape::Capsule if size[0] > 0.0 && size[1] >= 0.0 => {
            Some(capsule_volume(size[0], size[1]))
        }
        RigidBodyShape::Box if size.iter().all(|value| *value > 0.0) => {
            Some(8.0 * size[0] * size[1] * size[2])
        }
        _ => None,
    }
}

fn bounds(points: &[Vec3]) -> Option<(Vec3, Vec3)> {
    let first = *points.first()?;
    if !first.is_finite() {
        return None;
    }
    points
        .iter()
        .copied()
        .try_fold((first, first), |(min, max), p| {
            if !p.is_finite() {
                None
            } else {
                Some((min.min(p), max.max(p)))
            }
        })
}

fn shape_name(shape: RigidBodyShape) -> &'static str {
    match shape {
        RigidBodyShape::Sphere => "sphere",
        RigidBodyShape::Box => "box",
        RigidBodyShape::Capsule => "capsule",
    }
}

fn shape_radius_max(shape: RigidBodyShape, size: [f32; 3]) -> f32 {
    match shape {
        RigidBodyShape::Sphere => size[0],
        RigidBodyShape::Capsule => size[0] + size[1] * 0.5,
        RigidBodyShape::Box => size.iter().map(|v| v * v).sum::<f32>().sqrt(),
    }
}

#[cfg(test)]
#[path = "hair_geometry/tests.rs"]
mod tests;
