//! 单因子诊断只修改探针内存副本，不能作为默认修复或写回资产。
use super::*;

pub(super) fn apply(model: &mut MmdModel, control: &str) -> Vec<Value> {
    let mut changes = Vec::new();
    if control == "shoulder-radius-70" {
        for (index, body) in model.rigid_bodies.iter_mut().enumerate() {
            let name = body.local_name.to_lowercase();
            if body.mode == RigidBodyMode::Static
                && (name.contains('肩') || name.contains("shoulder"))
            {
                let original = body.size;
                body.size[0] *= 0.7;
                changes.push(json!({"body":index,"name":body.local_name,
                    "original_size":original,"control_size":body.size}));
            }
        }
        return changes;
    }
    let axes: &[usize] = match control {
        "root-x-free" => &[0],
        "root-y-free" => &[1],
        "root-z-free" => &[2],
        "root-all-free" => &[0, 1, 2],
        _ => return changes,
    };
    let roots: BTreeSet<_> = model
        .joints
        .iter()
        .enumerate()
        .filter_map(|(index, j)| {
            let a = model
                .rigid_bodies
                .get(usize::try_from(j.rigid_body_a_index).ok()?)?;
            let b = model
                .rigid_bodies
                .get(usize::try_from(j.rigid_body_b_index).ok()?)?;
            let bone_name = model
                .bone_manager
                .get_bone(usize::try_from(b.bone_index).ok()?)?
                .name
                .as_str();
            (a.mode == RigidBodyMode::Static
                && b.mode != RigidBodyMode::Static
                && (is_hair_name(&b.local_name) || is_hair_name(bone_name)))
            .then_some(index)
        })
        .collect();
    for index in roots {
        let j = &mut model.joints[index];
        changes.push(json!({"joint":index,"name":j.local_name,"axes":axes,
            "original_lower":j.rotation_min,"original_upper":j.rotation_max,
            "control":"lower > upper; free selected axis only"}));
        for &axis in axes {
            j.rotation_min[axis] = 1.0;
            j.rotation_max[axis] = -1.0;
        }
    }
    changes
}
