//! 面向普通玩家的单模型问题定位快照。

use super::*;
use std::fmt::Write as _;

const MAX_ITEMS: usize = 24;
const MAX_WEIGHT_SCAN: usize = 100_000;

impl MmdModel {
    /// 按位生成只读诊断。1=变形，2=两侧物理，4=碰撞，0x100=静态清单。
    pub fn issue_debug_diagnostic(&self, flags: u32) -> String {
        let selected = flags & 7;
        if selected == 0 {
            return String::new();
        }
        let manifest = flags & 0x100 != 0;
        let mut out = String::new();
        if selected & 1 != 0 {
            self.write_deformation(&mut out, manifest);
        }
        if selected & 2 != 0 {
            self.write_side_physics(&mut out, manifest);
        }
        if selected & 4 != 0 {
            self.write_collision(&mut out);
        }
        out
    }

    fn write_deformation(&self, out: &mut String, manifest: bool) {
        let (bdef1, bdef2, bdef4, sdef, qdef, scanned) =
            weight_counts(&self.weights, MAX_WEIGHT_SCAN);
        let format = if self.is_vrm { "VRM" } else { "PMX" };
        let global_physics_enabled = crate::physics::config::get_config().enabled;
        let _ = writeln!(out,
            "[MMD诊断][DEFORMATION] model={} format={} model_physics_enabled={} global_physics_enabled={} vertices={} weights(BDEF1={},BDEF2={},BDEF4={},SDEF={},QDEF={}) weights_scanned={} weights_truncated={} skinning=SDEF->BDEF2,QDEF->BDEF4 original_vertex_space=right_model_local current_bone_space=right_model_local vertex_sample=original_mesh_position current_cpu_gpu_deformation_not_reconstructed=true",
            escape(&self.name), escape(format), self.physics_enabled, global_physics_enabled, self.vertices.len(), bdef1, bdef2, bdef4, sdef, qdef, scanned, self.weights.len().saturating_sub(scanned));
        if manifest {
            let _ = writeln!(out,
                "[MMD诊断][DEFORMATION] manifest bones={} raw_rigid_bodies={} raw_joints={} model_physics_enabled={} physics_built={} rebuild_pending={}",
                self.bone_manager.bone_count(), self.rigid_bodies.len(), self.joints.len(),
                self.physics_enabled, self.physics.is_some(), self.physics_rebuild_pending);
        }
        let facts = collect_weight_facts(&self.weights, MAX_WEIGHT_SCAN);
        let dynamic_bones = self.physics.as_ref().map(|p| p.get_dynamic_bone_indices());
        let mut candidates = Vec::new();
        let mut bone_scanned = 0usize;
        for (index, bone) in self.bone_manager.links().take(8192).enumerate() {
            bone_scanned += 1;
            let weight = facts.get(&index).copied().unwrap_or_default();
            let semantic = bone_candidate_score(&bone.name);
            let dynamic = dynamic_bones.is_some_and(|bones| bones.contains(&index));
            if semantic == 0 && !dynamic && weight.sdef_qdef == 0 {
                continue;
            }
            candidates.push((
                semantic + if dynamic { 12 } else { 0 } + if weight.sdef_qdef > 0 { 5 } else { 0 },
                index,
            ));
        }
        candidates.sort_by_key(|(score, index)| (std::cmp::Reverse(*score), *index));
        let candidate_total = candidates.len();
        for (_, index) in candidates.into_iter().take(MAX_ITEMS) {
            let Some(bone) = self.bone_manager.get_bone(index) else {
                continue;
            };
            let append = bone.append_config.as_ref().map_or_else(
                || "none".to_owned(),
                |a| format!("parent:{} rate:{:.4}", a.parent, a.rate),
            );
            let transform = bone.global_transform();
            let rotation = glam::Quat::from_mat4(&transform);
            let representative =
                representative_vertex(&self.weights, &self.vertices, index, MAX_WEIGHT_SCAN)
                    .map_or_else(
                        || "none".to_owned(),
                        |(vi, kind, raw, pos)| {
                            format!(
                                "index={vi} type={kind} original={} weights={raw}",
                                vec3(pos)
                            )
                        },
                    );
            let _ = writeln!(out,
                "[MMD诊断][DEFORMATION] candidate_bone index={} name={} parent={} flags=0x{:X} append={} current_position={} current_rotation_quat={:.4},{:.4},{:.4},{:.4} weighted_vertices={} sdef_qdef_vertices={} representative={}",
                index, escape(&bone.name), bone.parent_index, bone.flags.bits(), escape(&append), vec3(transform.w_axis.truncate()),
                rotation.x, rotation.y, rotation.z, rotation.w, facts.get(&index).map_or(0, |f| f.vertices),
                facts.get(&index).map_or(0, |f| f.sdef_qdef), escape(&representative));
        }
        if candidate_total > MAX_ITEMS || bone_scanned < self.bone_manager.bone_count() {
            let _ = writeln!(out, "[MMD诊断][DEFORMATION] candidate_bones_truncated={} candidate_total={} scanned_bones={} total_bones={}",
                candidate_total.saturating_sub(MAX_ITEMS), candidate_total, bone_scanned, self.bone_manager.bone_count());
        }
        if manifest {
            let mut raw_bodies: Vec<_> = self
                .rigid_bodies
                .iter()
                .enumerate()
                .take(8192)
                .map(|(index, body)| {
                    (
                        body_candidate_score(&body.local_name)
                            + body_candidate_score(&body.universal_name),
                        index,
                        body,
                    )
                })
                .collect();
            raw_bodies.sort_by_key(|(score, index, _)| (std::cmp::Reverse(*score), *index));
            let scanned_bodies = raw_bodies.len();
            for (_, index, body) in raw_bodies.into_iter().take(MAX_ITEMS) {
                let _ = writeln!(
                    out,
                    "[MMD诊断][DEFORMATION] candidate_raw_body index={} name={} english={} bone={}",
                    index,
                    escape(&body.local_name),
                    escape(&body.universal_name),
                    body.bone_index
                );
            }
            if scanned_bodies < self.rigid_bodies.len() || scanned_bodies > MAX_ITEMS {
                let _ = writeln!(out, "[MMD诊断][DEFORMATION] candidate_raw_bodies_truncated={} scanned_bodies={} total_bodies={}",
                    scanned_bodies.saturating_sub(MAX_ITEMS), scanned_bodies, self.rigid_bodies.len());
            }
        }
    }

    fn write_side_physics(&self, out: &mut String, include_manifest: bool) {
        let global_physics_enabled = crate::physics::config::get_config().enabled;
        let Some(physics) = self.physics.as_ref() else {
            let _ = writeln!(out, "[MMD诊断][SIDE_PHYSICS] model={} physics=unavailable model_physics_enabled={} global_physics_enabled={} raw_bodies={} raw_joints={}", escape(&self.name), self.physics_enabled, global_physics_enabled, self.rigid_bodies.len(), self.joints.len());
            return;
        };
        physics.write_hair_issue_diagnostic(out);
        let mut entries: Vec<_> = physics
            .rigid_bodies
            .iter()
            .take(8192)
            .enumerate()
            .filter(|(_, b)| b.physics_mode != crate::physics::PhysicsMode::FollowBone)
            .collect();
        let classified = entries
            .iter()
            .filter(|(_, b)| is_side_accessory(&b.name, &b.universal_name))
            .count();
        entries.sort_by_key(|(_, b)| (!is_side_accessory(&b.name, &b.universal_name),));
        let total = entries.len();
        let shown = total.min(MAX_ITEMS);
        // 摘要扫描所有已纳入的动态体，避免最热刚体落在明细截断之外。
        let mut peak = (0.0_f32, None);
        let mut non_finite = 0;
        for &(index, body) in &entries {
            if let Some(bullet) = body.bullet_body.as_ref() {
                let velocity = bullet.get_linear_velocity();
                let speed = velocity.length();
                if !speed.is_finite() {
                    non_finite += 1;
                } else if peak.1.is_none() || speed > peak.0 {
                    peak = (speed, Some(index));
                }
            }
        }
        let _ = writeln!(out, "[MMD诊断][SIDE_PHYSICS] peak_velocity body={} speed={:.5} non_finite={} scanned={} truncated={} sample=current_read_not_window_peak",
            peak.1.map_or_else(|| "unavailable".to_owned(), |index| index.to_string()), peak.0, non_finite, total, physics.rigid_bodies.len() > 8192);
        let _ = writeln!(out,
            "[MMD诊断][SIDE_PHYSICS] model={} model_physics_enabled={} global_physics_enabled={} rebuild_pending={} simulation_space=bullet_left_model render_space=bullet_left_model final_bone_space=right_model_local units=mmd_local raw_bodies={} runtime_bodies={} synthesized_bodies={} raw_joints={} runtime_joints={} synthesized_joints={} dynamic_scanned={} named_accessories={} unknown_dynamic={} sample={} truncated={} scan_truncated={}",
            escape(&self.name), self.physics_enabled, global_physics_enabled, self.physics_rebuild_pending,
            self.rigid_bodies.len(), physics.rigid_bodies.len(), physics.rigid_bodies.len().saturating_sub(self.rigid_bodies.len()),
            self.joints.len(), physics.joint_count(), physics.joint_count().saturating_sub(self.joints.len()), total, classified, total.saturating_sub(classified), shown, total.saturating_sub(shown), physics.rigid_bodies.len() > 8192);
        for (index, body) in entries.into_iter().take(MAX_ITEMS) {
            let bone_index = usize::try_from(body.bone_index).ok();
            let bone_name = bone_index
                .and_then(|i| self.bone_manager.get_bone(i))
                .map_or("<invalid>".to_owned(), |b| b.name.clone());
            let final_pose = bone_index
                .and_then(|i| self.bone_manager.get_bone(i))
                .map(|b| {
                    let matrix = b.global_transform();
                    (matrix.w_axis.truncate(), glam::Quat::from_mat4(&matrix))
                });
            let Some(bullet) = body.bullet_body.as_ref() else {
                continue;
            };
            let simulation = bullet.get_simulation_transform().w_axis.truncate();
            let simulation_matrix = bullet.get_simulation_transform();
            let render_matrix = bullet.get_transform();
            let render = render_matrix.w_axis.truncate();
            let simulation_rotation = glam::Quat::from_mat4(&simulation_matrix);
            let render_rotation = glam::Quat::from_mat4(&render_matrix);
            let velocity = bullet.get_linear_velocity();
            let angular_velocity = bullet.get_angular_velocity();
            let _ = writeln!(out,
                "[MMD诊断][SIDE_PHYSICS] body={} name={} mode={:?} mass={:.4} bone={} bone_name={} simulation_position={} simulation_rotation={} render_position={} render_rotation={} linear_velocity={} angular_velocity={} final_bone_position={} final_bone_rotation={} candidate_meshes={}",
                index, escape(&body.name), body.physics_mode, body.mass, body.bone_index, escape(&bone_name),
                vec3(simulation), quat(simulation_rotation), vec3(render), quat(render_rotation), vec3(velocity), quat_vec(angular_velocity),
                final_pose.map_or("unavailable".to_owned(), |pose| vec3(pose.0)),
                final_pose.map_or("unavailable".to_owned(), |pose| quat(pose.1)),
                candidate_submeshes(&self.weights, &self.indices, &self.submeshes, bone_index, MAX_WEIGHT_SCAN));
        }
        if include_manifest {
            let _ = writeln!(
                out,
                "[MMD诊断][SIDE_PHYSICS] dynamic_bones={} note=manifest_requested",
                physics.get_dynamic_bone_indices().len()
            );
        }
    }

    fn write_collision(&self, out: &mut String) {
        if let Some(physics) = self.physics.as_ref() {
            physics.write_issue_diagnostic(
                out,
                &self.name,
                self.physics_enabled,
                self.physics_rebuild_pending,
            );
        } else {
            let config = crate::physics::config::get_config();
            let _ = writeln!(out, "[MMD诊断][COLLISION] model={} physics=unavailable model_physics_enabled={} global_physics_enabled={} requested_stability={} requested_collision={} requested_joints={} requested_gravity_y={:.4} requested_static_collider_scale={:.4}",
                escape(&self.name), self.physics_enabled, config.enabled, config.collision_stability_mode.as_str(), config.collision_enabled, config.joints_enabled, config.gravity_y, config.static_collider_scale);
        }
    }
}

fn weight_counts(
    weights: &[VertexWeight],
    limit: usize,
) -> (usize, usize, usize, usize, usize, usize) {
    let mut counts = (0, 0, 0, 0, 0);
    let scanned = weights.len().min(limit);
    for weight in weights.iter().take(scanned) {
        match weight {
            VertexWeight::Bdef1 { .. } => counts.0 += 1,
            VertexWeight::Bdef2 { .. } => counts.1 += 1,
            VertexWeight::Bdef4 { .. } => counts.2 += 1,
            VertexWeight::Sdef { .. } => counts.3 += 1,
            VertexWeight::Qdef { .. } => counts.4 += 1,
        }
    }
    (counts.0, counts.1, counts.2, counts.3, counts.4, scanned)
}

fn candidate_submeshes(
    weights: &[VertexWeight],
    indices: &[u32],
    submeshes: &[SubMesh],
    bone: Option<usize>,
    limit: usize,
) -> String {
    let Some(bone) = bone else {
        return "none".to_owned();
    };
    let mut candidates = Vec::new();
    let mut scanned = 0usize;
    for mesh in submeshes {
        let start = mesh.begin_index as usize;
        let end = start
            .saturating_add(mesh.index_count as usize)
            .min(indices.len());
        let mut found = false;
        for &vertex in indices.get(start..end).unwrap_or_default() {
            if scanned >= limit {
                break;
            }
            scanned += 1;
            if weights
                .get(vertex as usize)
                .is_some_and(|w| weight_uses_bone(w, bone))
            {
                found = true;
                break;
            }
        }
        if found {
            candidates.push(mesh.material_id);
        }
        if scanned >= limit || candidates.len() >= 8 {
            break;
        }
    }
    let suffix = if scanned >= limit { "+" } else { "" };
    if candidates.is_empty() {
        format!("none{suffix}")
    } else {
        format!("materials={:?}{suffix}", candidates)
    }
}

fn weight_uses_bone(weight: &VertexWeight, bone: usize) -> bool {
    match weight {
        VertexWeight::Bdef1 { bone: b } => usize::try_from(*b).ok() == Some(bone),
        VertexWeight::Bdef2 { bones, .. } | VertexWeight::Sdef { bones, .. } => {
            bones.iter().any(|b| usize::try_from(*b).ok() == Some(bone))
        }
        VertexWeight::Bdef4 { bones, .. } | VertexWeight::Qdef { bones, .. } => {
            bones.iter().any(|b| usize::try_from(*b).ok() == Some(bone))
        }
    }
}

#[derive(Clone, Copy, Default)]
struct BoneWeightFacts {
    vertices: usize,
    sdef_qdef: usize,
}

fn collect_weight_facts(weights: &[VertexWeight], limit: usize) -> HashMap<usize, BoneWeightFacts> {
    let mut facts = HashMap::new();
    for weight in weights.iter().take(limit) {
        let (bones, special) = match weight {
            VertexWeight::Bdef1 { bone } => ([Some(*bone), None, None, None], false),
            VertexWeight::Bdef2 { bones, .. } => {
                ([Some(bones[0]), Some(bones[1]), None, None], false)
            }
            VertexWeight::Sdef { bones, .. } => {
                ([Some(bones[0]), Some(bones[1]), None, None], true)
            }
            VertexWeight::Bdef4 { bones, .. } => (
                [
                    Some(bones[0]),
                    Some(bones[1]),
                    Some(bones[2]),
                    Some(bones[3]),
                ],
                false,
            ),
            VertexWeight::Qdef { bones, .. } => (
                [
                    Some(bones[0]),
                    Some(bones[1]),
                    Some(bones[2]),
                    Some(bones[3]),
                ],
                true,
            ),
        };
        let mut seen = [usize::MAX; 4];
        let mut seen_count = 0;
        for raw in bones.into_iter().flatten() {
            let Ok(bone) = usize::try_from(raw) else {
                continue;
            };
            if seen[..seen_count].contains(&bone) {
                continue;
            }
            seen[seen_count] = bone;
            seen_count += 1;
            let entry = facts.entry(bone).or_insert_with(BoneWeightFacts::default);
            entry.vertices += 1;
            if special {
                entry.sdef_qdef += 1;
            }
        }
    }
    facts
}

fn representative_vertex<'a>(
    weights: &'a [VertexWeight],
    vertices: &[RuntimeVertex],
    bone: usize,
    limit: usize,
) -> Option<(usize, &'static str, String, glam::Vec3)> {
    let mut any = None;
    for (index, weight) in weights.iter().take(limit).enumerate() {
        if !weight_uses_bone(weight, bone) {
            continue;
        }
        let value = (
            index,
            weight_kind(weight),
            weight_summary(weight),
            vertices.get(index).map_or(glam::Vec3::ZERO, |v| v.position),
        );
        if matches!(
            weight,
            VertexWeight::Sdef { .. } | VertexWeight::Qdef { .. }
        ) {
            return Some(value);
        }
        if any.is_none() {
            any = Some(value);
        }
    }
    any
}

fn weight_kind(weight: &VertexWeight) -> &'static str {
    match weight {
        VertexWeight::Bdef1 { .. } => "BDEF1",
        VertexWeight::Bdef2 { .. } => "BDEF2",
        VertexWeight::Bdef4 { .. } => "BDEF4",
        VertexWeight::Sdef { .. } => "SDEF",
        VertexWeight::Qdef { .. } => "QDEF",
    }
}

fn weight_summary(weight: &VertexWeight) -> String {
    match weight {
        VertexWeight::Bdef1 { bone } => format!("bones=[{bone}] weights=[1]"),
        VertexWeight::Bdef2 { bones, weight } => {
            format!("bones={bones:?} weights=[{weight:.4},{:.4}]", 1.0 - *weight)
        }
        VertexWeight::Sdef {
            bones,
            weight,
            c,
            r0,
            r1,
        } => format!(
            "bones={bones:?} weights=[{weight:.4},{:.4}] C={} R0={} R1={}",
            1.0 - *weight,
            vec3(*c),
            vec3(*r0),
            vec3(*r1)
        ),
        VertexWeight::Bdef4 { bones, weights } | VertexWeight::Qdef { bones, weights } => {
            format!("bones={bones:?} weights={weights:?}")
        }
    }
}

fn bone_candidate_score(name: &str) -> usize {
    let text = name.to_lowercase();
    [
        "腰", "背", "裙", "胸", "hip", "waist", "back", "skirt", "chest", "sleeve", "袖", "髪",
        "发",
    ]
    .iter()
    .map(|part| usize::from(text.contains(part)) * 10)
    .sum()
}

fn body_candidate_score(name: &str) -> usize {
    let text = name.to_lowercase();
    [
        "裙", "裾", "腰", "腿", "袖", "髪", "发", "背", "胸", "skirt", "dress", "hem", "hip",
        "thigh", "sleeve", "hair",
    ]
    .iter()
    .map(|part| usize::from(text.contains(part)) * 10)
    .sum()
}

fn is_side_accessory(local: &str, universal: &str) -> bool {
    let text = format!("{} {}", local.to_lowercase(), universal.to_lowercase());
    [
        "袖",
        "髪",
        "发",
        "飾",
        "饰",
        "accessory",
        "hair",
        "sleeve",
        "side",
        "装飾",
        "リボン",
    ]
    .iter()
    .any(|part| text.contains(part))
}

fn vec3(value: glam::Vec3) -> String {
    format!("{:.4},{:.4},{:.4}", value.x, value.y, value.z)
}

fn quat(value: glam::Quat) -> String {
    format!(
        "{:.4},{:.4},{:.4},{:.4}",
        value.x, value.y, value.z, value.w
    )
}
fn quat_vec(value: glam::Vec3) -> String {
    vec3(value)
}

fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_and_unknown_only_flags_do_not_emit_data() {
        let model = MmdModel::new();
        assert!(model.issue_debug_diagnostic(0).is_empty());
        assert!(model.issue_debug_diagnostic(0x100).is_empty());
        assert!(model.issue_debug_diagnostic(8).is_empty());
    }

    #[test]
    fn flags_select_only_requested_categories() {
        let model = MmdModel::new();
        let output = model.issue_debug_diagnostic(2);
        assert!(output.contains("[MMD诊断][SIDE_PHYSICS]"));
        assert!(output.contains("model_physics_enabled="));
        assert!(output.contains("global_physics_enabled="));
        assert!(!output.contains("[MMD诊断][DEFORMATION]"));
        assert!(!output.contains("[MMD诊断][COLLISION]"));
    }

    #[test]
    fn manifest_is_explicit_and_no_physics_is_reported_safely() {
        let model = MmdModel::new();
        let normal = model.issue_debug_diagnostic(1);
        let manifest = model.issue_debug_diagnostic(1 | 0x100);
        assert!(!normal.contains("manifest bones="));
        assert!(normal.contains("model_physics_enabled="));
        assert!(normal.contains("global_physics_enabled="));
        assert!(normal.contains("current_cpu_gpu_deformation_not_reconstructed=true"));
        assert!(normal.contains("original_vertex_space=right_model_local"));
        assert!(manifest.contains("manifest bones="));
        assert!(model
            .issue_debug_diagnostic(2)
            .contains("physics=unavailable"));
        assert!(model
            .issue_debug_diagnostic(4)
            .contains("physics=unavailable"));
        let collision = model.issue_debug_diagnostic(4);
        assert!(collision.contains("model_physics_enabled="));
        assert!(collision.contains("global_physics_enabled="));
    }

    #[test]
    fn side_snapshot_distinguishes_dynamic_and_bone_position_modes() {
        use crate::skeleton::BoneLink;
        use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

        let mut model = MmdModel::new();
        for name in ["left sleeve", "right sleeve"] {
            model.bone_manager.add_bone(BoneLink::new(name.to_owned()));
        }
        model.bone_manager.build_hierarchy();
        model.rigid_bodies = [
            ("left sleeve", RigidBodyMode::Dynamic, 0),
            ("right sleeve", RigidBodyMode::DynamicWithBonePosition, 1),
        ]
        .into_iter()
        .map(|(name, mode, bone_index)| RigidBody {
            local_name: name.to_owned(),
            universal_name: String::new(),
            bone_index,
            group: 0,
            un_collision_group_flag: u16::MAX,
            shape: RigidBodyShape::Sphere,
            size: [0.1, 0.0, 0.0],
            position: [0.0; 3],
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode,
        })
        .collect();
        assert!(model.init_physics());
        let output = model.issue_debug_diagnostic(2);
        assert!(output.contains("mode=Physics"));
        assert!(output.contains("mode=PhysicsWithBone"));
        assert!(output.contains("simulation_rotation="));
        assert!(output.contains("angular_velocity="));
        assert!(output.contains("final_bone_rotation="));
        assert!(output.contains("render_space=bullet_left_model"));
    }
}
