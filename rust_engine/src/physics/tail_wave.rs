//! 尾巴链波浪延迟

use super::mmd_rigid_body::{MmdRigidBodyData, PhysicsMode};
use std::collections::HashMap;

/// 按尾巴骨骼链距离计算每段抬力延迟。
pub(super) fn tail_wave_delays(bodies: &[MmdRigidBodyData], bone_parents: &[i32]) -> Vec<f32> {
    let mut delays = vec![0.0; bodies.len()];
    let mut positions = HashMap::<usize, glam::Vec3>::new();
    for body in bodies
        .iter()
        .filter(|body| body.is_tail_dynamic && body.physics_mode != PhysicsMode::FollowBone)
    {
        if let Ok(bone) = usize::try_from(body.bone_index) {
            let position = body.initial_transform.w_axis.truncate();
            if bone < bone_parents.len() && position.is_finite() {
                positions.entry(bone).or_insert(position);
            }
        }
    }

    let mut parent_tail = HashMap::<usize, Option<usize>>::new();
    let mut invalid_bones = std::collections::HashSet::<usize>::new();
    for &bone in positions.keys() {
        let mut cursor = bone;
        let mut visited = vec![false; bone_parents.len()];
        loop {
            if cursor >= bone_parents.len() || visited[cursor] {
                invalid_bones.insert(bone);
                break;
            }
            visited[cursor] = true;
            let parent = bone_parents[cursor];
            if parent == -1 {
                break;
            }
            match usize::try_from(parent) {
                Ok(index) if index < bone_parents.len() => cursor = index,
                _ => {
                    invalid_bones.insert(bone);
                    break;
                }
            }
        }
    }
    for &bone in positions.keys() {
        if invalid_bones.contains(&bone) {
            parent_tail.insert(bone, None);
            continue;
        }
        let mut cursor = bone;
        let mut visited = vec![false; bone_parents.len()];
        let ancestor = loop {
            if cursor >= bone_parents.len() {
                invalid_bones.insert(bone);
                break None;
            }
            if visited[cursor] {
                invalid_bones.insert(bone);
                break None;
            }
            visited[cursor] = true;
            let parent_index = bone_parents[cursor];
            let parent = match usize::try_from(parent_index) {
                Ok(parent) if parent < bone_parents.len() => parent,
                _ if parent_index == -1 => break None,
                _ => {
                    invalid_bones.insert(bone);
                    break None;
                }
            };
            if positions.contains_key(&parent) {
                break Some(parent);
            }
            cursor = parent;
        };
        parent_tail.insert(bone, ancestor);
    }

    let mut distances = HashMap::<usize, f32>::new();
    fn distance_to_root(
        bone: usize,
        positions: &HashMap<usize, glam::Vec3>,
        parents: &HashMap<usize, Option<usize>>,
        distances: &mut HashMap<usize, f32>,
        active: &mut Vec<usize>,
        invalid_bones: &std::collections::HashSet<usize>,
    ) -> Option<f32> {
        if let Some(&distance) = distances.get(&bone) {
            return Some(distance);
        }
        if active.contains(&bone) {
            return None;
        }
        if invalid_bones.contains(&bone) {
            return None;
        }
        active.push(bone);
        let result = match parents.get(&bone).copied().flatten() {
            Some(parent) => {
                let prior =
                    distance_to_root(parent, positions, parents, distances, active, invalid_bones);
                match (prior, positions.get(&bone), positions.get(&parent)) {
                    (Some(prior), Some(position), Some(parent_position)) => {
                        Some(prior + position.distance(*parent_position))
                    }
                    _ => None,
                }
            }
            None => Some(0.0),
        };
        active.pop();
        if let Some(distance) = result {
            distances.insert(bone, distance);
        }
        result
    }
    for &bone in positions.keys() {
        let _ = distance_to_root(
            bone,
            &positions,
            &parent_tail,
            &mut distances,
            &mut Vec::new(),
            &invalid_bones,
        );
    }

    let mut root_max = HashMap::<usize, f32>::new();
    for (&bone, &distance) in &distances {
        let mut root = bone;
        let mut guard = 0;
        while let Some(Some(parent)) = parent_tail.get(&root) {
            root = *parent;
            guard += 1;
            if guard > positions.len() {
                break;
            }
        }
        root_max
            .entry(root)
            .and_modify(|max| *max = max.max(distance))
            .or_insert(distance);
    }
    for (index, body) in bodies.iter().enumerate() {
        let Ok(bone) = usize::try_from(body.bone_index) else {
            continue;
        };
        let Some(&distance) = distances.get(&bone) else {
            continue;
        };
        let mut root = bone;
        let mut guard = 0;
        while let Some(Some(parent)) = parent_tail.get(&root) {
            root = *parent;
            guard += 1;
            if guard > positions.len() {
                break;
            }
        }
        let max_distance = root_max.get(&root).copied().unwrap_or(0.0);
        if body.is_tail_dynamic && max_distance > f32::EPSILON {
            delays[index] = (distance / max_distance * 0.6).clamp(0.0, 0.6);
        }
    }
    delays
}

#[cfg(test)]
mod tests {
    use super::tail_wave_delays;
    use crate::physics::mmd_rigid_body::MmdRigidBodyData;
    use glam::Vec3;
    use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};

    fn body(name: &str, bone: i32, position: Vec3, tail: bool) -> MmdRigidBodyData {
        let pmx = RigidBody {
            local_name: name.into(),
            universal_name: String::new(),
            bone_index: bone,
            group: 0,
            un_collision_group_flag: 0,
            shape: RigidBodyShape::Sphere,
            size: [0.1, 0.0, 0.0],
            position: position.to_array(),
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode: RigidBodyMode::Dynamic,
        };
        let mut data = MmdRigidBodyData::from_pmx(&pmx, None, [0.1; 3]);
        data.is_tail_dynamic = tail;
        data
    }

    #[test]
    fn handles_unsorted_bodies_branches_and_non_tail_bones() {
        let bodies = [
            body("tip", 3, Vec3::Y * 3.0, true),
            body("other", 5, Vec3::X * 8.0, false),
            body("root", 0, Vec3::ZERO, true),
            body("branch", 4, Vec3::X * 2.0, true),
            body("middle", 2, Vec3::Y * 2.0, true),
            body("root duplicate", 0, Vec3::ZERO, true),
        ];
        let delays = tail_wave_delays(&bodies, &[-1, 0, 1, 2, 1, 4]);
        assert!((delays[0] - 0.6).abs() < 1e-5);
        assert_eq!(delays[1], 0.0);
        assert_eq!(delays[2], 0.0);
        assert!((delays[3] - 0.4).abs() < 1e-5);
        assert!((delays[4] - 0.4).abs() < 1e-5);
        assert_eq!(delays[5], delays[2]);
    }

    #[test]
    fn invalid_bones_and_parent_cycles_fall_back_to_zero() {
        let bodies = [
            body("a", 0, Vec3::ZERO, true),
            body("b", 1, Vec3::Y, true),
            body("invalid", 9, Vec3::Y * 2.0, true),
        ];
        assert_eq!(tail_wave_delays(&bodies, &[1, 0]), vec![0.0; 3]);
        let one = [body("a", 0, Vec3::ZERO, true)];
        assert_eq!(tail_wave_delays(&one, &[]), vec![0.0]);
        let mut non_finite = body("nan", 0, Vec3::splat(f32::NAN), true);
        non_finite.is_tail_dynamic = true;
        assert_eq!(tail_wave_delays(&[non_finite], &[-1]), vec![0.0]);
    }

    #[test]
    fn normalizes_each_root_and_handles_zero_length_duplicates() {
        let bodies = [
            body("short root", 0, Vec3::ZERO, true),
            body("short tip", 1, Vec3::Y, true),
            body("long root", 3, Vec3::X * 10.0, true),
            body("long middle", 4, Vec3::X * 12.0, true),
            body("long middle duplicate", 4, Vec3::X * 12.0, true),
            body("long tip", 5, Vec3::X * 14.0, true),
            body("zero root", 7, Vec3::ZERO, true),
            body("zero tip", 8, Vec3::ZERO, true),
        ];
        let delays = tail_wave_delays(&bodies, &[-1, 0, -1, -1, 3, 4, -1, 6, 7]);
        assert!((delays[1] - 0.6).abs() < 1e-5);
        assert!((delays[3] - 0.3).abs() < 1e-5);
        assert!((delays[4] - 0.3).abs() < 1e-5);
        assert_eq!(delays[3], delays[4]);
        assert!((delays[5] - 0.6).abs() < 1e-5);
        assert_eq!(delays[6], 0.0);
        assert_eq!(delays[7], 0.0);
    }
}
