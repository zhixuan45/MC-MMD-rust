//! 裙环候选链与所属组件；PMX 关节 A/B 不代表父子顺序。

use std::collections::{BTreeMap, BTreeSet};

use glam::Vec3;
use mmd::pmx::{
    joint::Joint,
    rigid_body::{RigidBody, RigidBodyMode},
};

use super::body_part::{classify, BodyPart};

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord)]
enum Owner {
    Bone(i32),
    Anchors(Vec<usize>),
    Unbound,
}

/// 按现有骨骼、锚点和名称证据分组；无绑定组件归属仍是启发式判断。
pub(super) fn grouped_chains(
    bodies: &[RigidBody],
    joints: &[Joint],
    names: &[String],
    parents: &[i32],
) -> Vec<Vec<Vec<usize>>> {
    let candidates: Vec<_> = bodies
        .iter()
        .map(|body| {
            body.mode != RigidBodyMode::Static
                && Vec3::from_array(body.position).is_finite()
                && Vec3::from_array(body.position).length_squared().is_finite()
                && Vec3::from_array(body.rotation).is_finite()
                && classify(
                    body,
                    usize::try_from(body.bone_index)
                        .ok()
                        .and_then(|i| names.get(i))
                        .map(String::as_str),
                ) == BodyPart::Garment
                && skirt_family(body).is_some()
                && valid_attachment(body.bone_index, names, parents)
        })
        .collect();
    let mut children = vec![BTreeSet::new(); bodies.len()];
    let mut incoming = vec![BTreeSet::new(); bodies.len()];
    let mut anchors = vec![BTreeSet::new(); bodies.len()];
    for joint in joints {
        let (Ok(a), Ok(b)) = (
            usize::try_from(joint.rigid_body_a_index),
            usize::try_from(joint.rigid_body_b_index),
        ) else {
            continue;
        };
        if a >= bodies.len() || b >= bodies.len() || a == b {
            continue;
        }
        if candidates[a] && bodies[b].mode == RigidBodyMode::Static {
            anchors[a].insert(b);
        }
        if candidates[b] && bodies[a].mode == RigidBodyMode::Static {
            anchors[b].insert(a);
        }
        if !candidates[a] || !candidates[b] {
            continue;
        }
        let direction = vertical_direction(a, b, bodies, parents);
        if let Some((parent, child)) = direction {
            children[parent].insert(child);
            incoming[child].insert(parent);
        }
    }
    let mut groups = BTreeMap::<(Owner, String), Vec<Vec<usize>>>::new();
    let mut rejected = 0;
    for root in 0..bodies.len() {
        if !candidates[root] || !incoming[root].is_empty() {
            continue;
        }
        let mut chain = Vec::new();
        let mut visited = BTreeSet::new();
        let mut current = root;
        let mut ambiguous = false;
        loop {
            // 分支、多父级或环都不能任取第一个子节点。
            if !visited.insert(current)
                || incoming[current].len() > 1
                || children[current].len() > 1
            {
                ambiguous = true;
                break;
            }
            chain.push(current);
            let Some(&next) = children[current].iter().next() else {
                break;
            };
            current = next;
        }
        if ambiguous {
            rejected += 1;
            continue;
        }
        let Some(owner) = chain_owner(root, bodies, parents, names, &anchors[root]) else {
            rejected += 1;
            continue;
        };
        let family = skirt_family(&bodies[root]).expect("候选已验证");
        groups.entry((owner, family)).or_default().push(chain);
    }
    if super::config::get_config().debug_log {
        log::info!("[Bullet3][SKIRT_CROSS_TOPOLOGY] candidates={} groups={} rejected_ambiguous={} source=authored_joints_and_bone_hierarchy",
            candidates.iter().filter(|&&value| value).count(), groups.len(), rejected);
    }
    if super::config::get_config().debug_log {
        for ((owner, family), chains) in &groups {
            log::info!(
                "[Bullet3][SKIRT_CROSS_GROUP] owner={owner:?} family={family:?} roots={:?}",
                chains.iter().map(|chain| chain[0]).collect::<Vec<_>>()
            );
        }
    }
    groups.into_values().collect()
}

fn valid_bone(index: i32, parents: &[i32]) -> Option<usize> {
    usize::try_from(index)
        .ok()
        .filter(|&index| index < parents.len())
}

fn is_ancestor(ancestor: i32, bone: i32, parents: &[i32]) -> bool {
    let mut current = bone;
    for _ in 0..parents.len() {
        let Some(index) = valid_bone(current, parents) else {
            return false;
        };
        current = parents[index];
        if current == ancestor {
            return true;
        }
    }
    false
}

fn vertical_direction(
    a: usize,
    b: usize,
    bodies: &[RigidBody],
    parents: &[i32],
) -> Option<(usize, usize)> {
    let ba = bodies[a].bone_index;
    let bb = bodies[b].bone_index;
    if valid_bone(ba, parents).is_some() && valid_bone(bb, parents).is_some() {
        // 同骨骼、兄弟骨骼之间的关节是横向/附加约束。
        if ba == bb {
            return None;
        }
        let ab = is_ancestor(ba, bb, parents);
        let ba = is_ancestor(bb, ba, parents);
        return match (ab, ba) {
            (true, false) => Some((a, b)),
            (false, true) => Some((b, a)),
            _ => None,
        };
    }
    // 无骨骼的兼容路径只接受明显沿 Y 的纵向边。
    let delta = Vec3::from_array(bodies[b].position) - Vec3::from_array(bodies[a].position);
    if delta.y.abs() <= 1e-4 || delta.y.abs() <= delta.x.abs().max(delta.z.abs()) {
        return None;
    }
    Some(if delta.y < 0.0 { (a, b) } else { (b, a) })
}

fn chain_owner(
    root: usize,
    bodies: &[RigidBody],
    parents: &[i32],
    names: &[String],
    anchors: &BTreeSet<usize>,
) -> Option<Owner> {
    if let Some(index) = valid_bone(bodies[root].bone_index, parents) {
        let mut current = parents[index];
        for _ in 0..parents.len() {
            let Some(index) = valid_bone(current, parents) else {
                return Some(Owner::Bone(current));
            };
            // 裙片中间骨、裙根控制骨归属到第一个非裙骨骼。
            if !names.get(index).is_some_and(|name| has_skirt_name(name)) {
                return Some(Owner::Bone(current));
            }
            current = parents[index];
        }
        return None;
    }
    if anchors.is_empty() {
        Some(Owner::Unbound)
    } else {
        Some(Owner::Anchors(anchors.iter().copied().collect()))
    }
}

fn valid_attachment(bone: i32, names: &[String], parents: &[i32]) -> bool {
    let mut current = bone;
    for _ in 0..parents.len() {
        let Some(index) = valid_bone(current, parents) else {
            return current == -1 || bone < 0;
        };
        if names.get(index).is_some_and(|name| {
            let name = name.to_lowercase();
            [
                "頭", "头", "頭髪", "髪", "髮", "发", "辫", "head", "hair", "braid", "腕", "手臂",
                "upperarm", "forearm", "首", "neck",
            ]
            .iter()
            .any(|part| name.contains(part))
                || name
                    .split(|ch: char| !ch.is_ascii_alphanumeric())
                    .any(|token| token == "arm")
        }) {
            return false;
        }
        current = parents[index];
    }
    // 非空骨骼表中的循环或坏父级不参与合成。
    current == -1 || parents.is_empty()
}

fn has_skirt_name(name: &str) -> bool {
    let name = name.to_lowercase();
    [
        "裙",
        "スカート",
        "skirt",
        "petticoat",
        "裾",
        "摆",
        "下摆",
        "衣摆",
        "后摆",
        "下装",
        "下衣",
        "风衣",
        "外套",
        "コート",
        "coat",
        "cloak",
        "cape",
        "燕尾",
        "flap",
    ]
    .iter()
    .any(|part| name.contains(part))
}

fn skirt_family(body: &RigidBody) -> Option<String> {
    [&body.local_name, &body.universal_name]
        .into_iter()
        .find(|name| has_skirt_name(name))
        .map(|name| {
            let lower = name.to_lowercase();
            let normalized = lower.trim_start_matches("m-");
            // 清掉编号段，保留 outer/inner 等语义段。
            normalized
                .split(|ch: char| ch == '_' || ch.is_ascii_whitespace())
                .filter(|part| !part.is_empty() && !part.chars().all(|ch| ch.is_ascii_digit()))
                .collect::<Vec<_>>()
                .join("_")
        })
}
