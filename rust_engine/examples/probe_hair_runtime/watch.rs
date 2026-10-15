//! 只读定点采样，便于逐渲染帧区分关节运动、插值与骨骼回写。
use super::*;
use std::sync::OnceLock;

static WATCH_BODY: OnceLock<String> = OnceLock::new();

pub(super) fn initialize(needle: Option<&str>) {
    if let Some(needle) = needle {
        let _ = WATCH_BODY.set(needle.to_owned());
    }
}

pub(super) fn sample(
    model: &MmdModel,
    stage: &str,
    elapsed: f32,
    reps: &[(usize, usize, f32)],
) -> Option<Value> {
    let needle = WATCH_BODY.get()?;
    let all = model.physics_body_snapshots_matching("");
    let watched: BTreeSet<_> = all
        .iter()
        .filter(|b| b.name.contains(needle))
        .map(|b| b.index)
        .collect();
    // 同时观察关节另一端，剔除整束头发运动对饰品抖动判断的干扰。
    let joints: Vec<_> = model
        .physics_joint_snapshots_matching("")
        .into_iter()
        .filter(|j| {
            all.iter().any(|b| {
                watched.contains(&b.index) && (j.body_a_name == b.name || j.body_b_name == b.name)
            })
        })
        .collect();
    let bodies: Vec<_> = all
        .iter()
        .filter(|b| {
            watched.contains(&b.index)
                || joints
                    .iter()
                    .any(|j| j.body_a_name == b.name || j.body_b_name == b.name)
        })
        .collect();
    let selected_bones: BTreeSet<_> = bodies
        .iter()
        .filter_map(|b| usize::try_from(b.bone_index).ok())
        .collect();
    let selected_reps: Vec<_> = reps
        .iter()
        .copied()
        .filter(|(bone, _, _)| selected_bones.contains(bone))
        .collect();
    let contacts: Vec<_> = bodies
        .iter()
        .map(|b| {
            let batch = model.physics_contact_points_matching(&b.name, 128);
            json!({"body_index":b.index,"step_serial":batch.step_serial,
                "substeps":batch.substeps,"requested_substeps":batch.requested_substeps,
                "phase":format!("{:?}",batch.phase),"truncated":batch.truncated,
                "targets_truncated":batch.targets_truncated,"total_points":batch.total_points,
                "points":batch.points.iter().map(|p| json!({
                    "a_index":p.a_index,"b_index":p.b_index,
                    "normal_on_b":p.normal_on_b.to_array(),"normal_impulse":p.normal_impulse,
                    "penetration":p.penetration
                })).collect::<Vec<_>>()})
        })
        .collect();
    Some(json!({
        "kind":"sample","trace":"watch-body","watch_body":needle,
        "stage":stage,"elapsed_seconds":elapsed,
        "matched_bodies":watched.len(),
        "body_snapshots":bodies.iter().map(|b| json!({
            "index":b.index,"name":b.name,"bone_index":b.bone_index,
            "watched":watched.contains(&b.index),"mode":format!("{:?}",b.mode),
            "simulation_transform":b.simulation_transform.to_cols_array(),
            "render_transform":b.render_transform.to_cols_array(),
            "body_from_final_bone_transform":body_from_final_bone_transform(model,b.bone_index,b.initial_transform)
                .map(|m|m.to_cols_array()),
            "linear_velocity":b.linear_velocity.to_array(),
            "angular_velocity":b.angular_velocity.to_array()
        })).collect::<Vec<_>>(),
        "constraints":joints.iter().map(|j| json!({
            "name":j.name,"body_a":j.body_a_name,"body_b":j.body_b_name,
            "anchor_error":j.anchor_error,"angular_position":j.diagnostic.angular_position.to_array(),
            "angular_violation":j.diagnostic.angular_violation.to_array(),
            "angular_lower":j.diagnostic.angular_lower.to_array(),
            "angular_upper":j.diagnostic.angular_upper.to_array(),
            "body_b_relative_to_a":all.iter().find(|b| b.name == j.body_a_name)
                .zip(all.iter().find(|b| b.name == j.body_b_name))
                .map(|(a,b)|json!({
                    "simulation":(a.simulation_transform.inverse()*b.simulation_transform).to_cols_array(),
                    "render":(a.render_transform.inverse()*b.render_transform).to_cols_array()
                }))
        })).collect::<Vec<_>>(),
        "contacts":contacts,
        "hair_bones_and_skin_points":bone_sample(model,&selected_reps),
        "sampling_boundary":"逐渲染帧读取最后物理子步；零步帧复用模拟状态，未观测中间子步；CPU蒙皮不能替代游戏GPU画面"
    }))
}
