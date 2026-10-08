//! 过滤模式 1 身体刚体与祖先躯干壳的初始深穿透。

use std::collections::BTreeSet;

use glam::{Mat4, Vec3};

use super::bullet_ffi::ContactManifold;
use super::mmd_rigid_body::{MmdRigidBodyData, PhysicsMode};

const MIN_EMBEDDED_PENETRATION: f32 = 0.05;

/// 深嵌入的胸部动态体触发后，隔离它与全部祖先躯干壳。
pub(super) fn embedded_body_pairs(
    bodies: &[MmdRigidBodyData],
    bone_names: &[String],
    bone_parents: &[i32],
    contacts: &[(usize, usize, ContactManifold)],
) -> Vec<(usize, usize)> {
    let facts: Vec<_> = bodies
        .iter()
        .map(|body| BodyFacts {
            name: &body.name,
            universal_name: &body.universal_name,
            bone_index: body.bone_index,
            mode: body.physics_mode,
            transform: body.initial_transform,
            shape_name: body.shape_name,
            shape_size: body.shape_size,
        })
        .collect();

    select_embedded_body_pairs(&facts, bone_names, bone_parents, contacts)
}

#[derive(Clone, Copy)]
struct BodyFacts<'a> {
    name: &'a str,
    universal_name: &'a str,
    bone_index: i32,
    mode: PhysicsMode,
    transform: Mat4,
    shape_name: &'a str,
    shape_size: [f32; 3],
}

fn select_embedded_body_pairs(
    bodies: &[BodyFacts<'_>],
    bone_names: &[String],
    bone_parents: &[i32],
    contacts: &[(usize, usize, ContactManifold)],
) -> Vec<(usize, usize)> {
    let mut embedded_bodies = BTreeSet::new();
    for (a, b, contact) in contacts {
        if contact.contact_count <= 0
            || !contact.max_penetration_depth.is_finite()
            || contact.max_penetration_depth < MIN_EMBEDDED_PENETRATION
        {
            continue;
        }
        let (Some(body_a), Some(body_b)) = (bodies.get(*a), bodies.get(*b)) else {
            continue;
        };
        if is_embedded_breast_in_shell(*body_a, *body_b, bone_names, bone_parents) {
            embedded_bodies.insert(*a);
        } else if is_embedded_breast_in_shell(*body_b, *body_a, bone_names, bone_parents) {
            embedded_bodies.insert(*b);
        }
    }

    // 一次深嵌触发后，隔离该身体与全部祖先躯干壳的接触。
    let mut pairs = BTreeSet::new();
    for dynamic_index in embedded_bodies {
        let Some(dynamic) = bodies.get(dynamic_index).copied() else {
            continue;
        };
        for (shell_index, shell) in bodies.iter().copied().enumerate() {
            if is_breast_body_for_bone(dynamic, bone_names)
                && is_ancestor_torso_shell(dynamic, shell, bone_names, bone_parents)
            {
                pairs.insert((dynamic_index, shell_index));
            }
        }
    }
    pairs.into_iter().collect()
}

fn is_embedded_breast_in_shell(
    dynamic: BodyFacts<'_>,
    shell: BodyFacts<'_>,
    bone_names: &[String],
    bone_parents: &[i32],
) -> bool {
    if !is_breast_body_for_bone(dynamic, bone_names)
        || !is_ancestor_torso_shell(dynamic, shell, bone_names, bone_parents)
    {
        return false;
    }
    center_is_inside(dynamic.transform.w_axis.truncate(), shell)
}

fn is_breast_body_for_bone(dynamic: BodyFacts<'_>, bone_names: &[String]) -> bool {
    if dynamic.mode != PhysicsMode::Physics {
        return false;
    }
    let Some(dynamic_bone) = valid_bone(dynamic.bone_index, bone_names) else {
        return false;
    };
    is_breast_body(&[
        dynamic.name,
        dynamic.universal_name,
        bone_names[dynamic_bone].as_str(),
    ])
}

fn is_ancestor_torso_shell(
    dynamic: BodyFacts<'_>,
    shell: BodyFacts<'_>,
    bone_names: &[String],
    bone_parents: &[i32],
) -> bool {
    if shell.mode != PhysicsMode::FollowBone {
        return false;
    }
    let (Some(dynamic_bone), Some(shell_bone)) = (
        valid_bone(dynamic.bone_index, bone_names),
        valid_bone(shell.bone_index, bone_names),
    ) else {
        return false;
    };
    is_torso_shell(&[
        shell.name,
        shell.universal_name,
        bone_names[shell_bone].as_str(),
    ]) && is_ancestor(shell_bone, dynamic_bone, bone_parents)
}

fn valid_bone(index: i32, names: &[String]) -> Option<usize> {
    usize::try_from(index)
        .ok()
        .filter(|&index| index < names.len())
}

fn is_ancestor(ancestor: usize, child: usize, parents: &[i32]) -> bool {
    let mut current = child;
    for _ in 0..=parents.len() {
        if current == ancestor {
            return true;
        }
        let Some(&parent) = parents.get(current) else {
            return false;
        };
        let Ok(parent) = usize::try_from(parent) else {
            return false;
        };
        current = parent;
    }
    false
}

fn is_breast_body(names: &[&str]) -> bool {
    if contains_any(names, ACCESSORY_WORDS) {
        return false;
    }
    contains_any(names, BREAST_WORDS)
}

fn is_torso_shell(names: &[&str]) -> bool {
    if contains_any(names, LIMB_WORDS) || contains_any(names, ACCESSORY_WORDS) {
        return false;
    }
    contains_any(names, TORSO_WORDS)
}

fn contains_any(names: &[&str], words: &[&str]) -> bool {
    names.iter().any(|name| {
        let normalized = name.to_lowercase();
        words.iter().any(|word| normalized.contains(word))
    })
}

fn center_is_inside(point: Vec3, shell: BodyFacts<'_>) -> bool {
    let size = Vec3::from_array(shell.shape_size);
    if !point.is_finite() || !size.is_finite() {
        return false;
    }
    let inverse = shell.transform.inverse();
    if !inverse.is_finite() {
        return false;
    }
    let local = inverse.transform_point3(point);
    if !local.is_finite() {
        return false;
    }
    match shell.shape_name {
        "sphere" if size.x > 0.0 => local.length_squared() <= size.x * size.x,
        "capsule_y" if size.x > 0.0 && size.y >= 0.0 => {
            let y = local.y.clamp(-size.y * 0.5, size.y * 0.5);
            Vec3::new(local.x, local.y - y, local.z).length_squared() <= size.x * size.x
        }
        "box" if size.cmpgt(Vec3::ZERO).all() => local.abs().cmple(size).all(),
        _ => false,
    }
}

const BREAST_WORDS: &[&str] = &["breast", "bust", "chest", "乳", "胸", "おっぱい", "oppai"];

const TORSO_WORDS: &[&str] = &[
    "torso",
    "upper_body",
    "upper body",
    "upperbody",
    "spine",
    "chest",
    "breast",
    "bust",
    "上半身",
    "胴",
    "胸",
    "背",
    "背中",
    "おっぱい",
];

const ACCESSORY_WORDS: &[&str] = &[
    "accessory",
    "accessories",
    "tassel",
    "ribbon",
    "bow",
    "chain",
    "cloth",
    "skirt",
    "hair",
    "飾",
    "饰",
    "穂",
    "穗",
    "飘带",
    "衣带",
    "リボン",
    "チェーン",
    "髪",
    "装飾",
];

const LIMB_WORDS: &[&str] = &[
    "arm", "shoulder", "wrist", "elbow", "hand", "leg", "thigh", "shin", "foot", "knee", "head",
    "neck", "腕", "肩", "肘", "手", "腿", "足", "膝", "頭", "首", "ひざ",
];

#[cfg(test)]
mod tests {
    use super::{
        center_is_inside, select_embedded_body_pairs, BodyFacts, PhysicsMode,
        MIN_EMBEDDED_PENETRATION,
    };
    use crate::physics::bullet_ffi::ContactManifold;
    use glam::{Mat4, Vec3};

    fn body<'a>(
        name: &'a str,
        universal_name: &'a str,
        bone_index: i32,
        mode: PhysicsMode,
        shape_name: &'static str,
        shape_size: [f32; 3],
        position: Vec3,
    ) -> BodyFacts<'a> {
        BodyFacts {
            name,
            universal_name,
            bone_index,
            mode,
            transform: Mat4::from_translation(position),
            shape_name,
            shape_size,
        }
    }

    fn contact(a: usize, b: usize, depth: f32) -> (usize, usize, ContactManifold) {
        (
            a,
            b,
            ContactManifold {
                body_a: 10,
                body_b: 20,
                contact_count: 1,
                max_penetration_depth: depth,
                max_applied_impulse: 0.0,
                total_applied_impulse: 0.0,
                point_a: Vec3::ZERO,
                point_b: Vec3::ZERO,
                normal_on_b: Vec3::Y,
            },
        )
    }

    fn bones() -> (Vec<String>, Vec<i32>) {
        (
            vec![
                "上半身".into(),
                "上半身2".into(),
                "左胸".into(),
                "左胸下".into(),
                "左手".into(),
            ],
            vec![-1, 0, 1, 2, 0],
        )
    }

    #[test]
    fn accepts_deep_contact_with_ancestor_torso_shell() {
        let (names, parents) = bones();
        let bodies = [
            body(
                "左胸下",
                "",
                3,
                PhysicsMode::Physics,
                "capsule_y",
                [0.1, 0.4, 0.0],
                Vec3::ZERO,
            ),
            body(
                "上半身2_2",
                "",
                1,
                PhysicsMode::FollowBone,
                "capsule_y",
                [0.7, 1.5, 0.0],
                Vec3::ZERO,
            ),
        ];
        assert_eq!(
            select_embedded_body_pairs(&bodies, &names, &parents, &[contact(0, 1, 0.4)]),
            vec![(0, 1)]
        );
    }

    #[test]
    fn rejects_tassel_even_when_its_names_contain_chest_and_skirt_alias() {
        let (names, parents) = bones();
        let bodies = [
            body(
                "胸前穗_0_1",
                "Skirt_0_1",
                3,
                PhysicsMode::Physics,
                "capsule_y",
                [0.05, 0.08, 0.0],
                Vec3::ZERO,
            ),
            body(
                "上半身2_2",
                "",
                1,
                PhysicsMode::FollowBone,
                "capsule_y",
                [0.7, 1.5, 0.0],
                Vec3::ZERO,
            ),
        ];
        assert!(
            select_embedded_body_pairs(&bodies, &names, &parents, &[contact(0, 1, 0.4)]).is_empty()
        );
    }

    #[test]
    fn embedded_breast_disables_uncontacted_ancestor_shell_but_not_others() {
        let (names, parents) = bones();
        let bodies = [
            body(
                "左胸下",
                "",
                3,
                PhysicsMode::Physics,
                "capsule_y",
                [0.1, 0.4, 0.0],
                Vec3::ZERO,
            ),
            body(
                "上半身2_2",
                "",
                1,
                PhysicsMode::FollowBone,
                "capsule_y",
                [0.7, 1.5, 0.0],
                Vec3::ZERO,
            ),
            body(
                "胸壳",
                "",
                0,
                PhysicsMode::FollowBone,
                "sphere",
                [0.3, 0.0, 0.0],
                Vec3::new(5.0, 0.0, 0.0),
            ),
            body(
                "上半身壳",
                "",
                4,
                PhysicsMode::FollowBone,
                "sphere",
                [1.0, 0.0, 0.0],
                Vec3::ZERO,
            ),
            body(
                "胸前穗_0_1",
                "Skirt_0_1",
                3,
                PhysicsMode::Physics,
                "capsule_y",
                [0.05, 0.08, 0.0],
                Vec3::ZERO,
            ),
        ];

        assert_eq!(
            select_embedded_body_pairs(&bodies, &names, &parents, &[contact(0, 1, 0.4)]),
            vec![(0, 1), (0, 2)]
        );
    }

    #[test]
    fn rejects_mode_two_and_non_ancestor_or_limb_shells() {
        let (names, parents) = bones();
        let mode_two = [
            body(
                "左胸下",
                "",
                3,
                PhysicsMode::PhysicsWithBone,
                "capsule_y",
                [0.1, 0.4, 0.0],
                Vec3::ZERO,
            ),
            body(
                "上半身2",
                "",
                1,
                PhysicsMode::FollowBone,
                "capsule_y",
                [0.7, 1.5, 0.0],
                Vec3::ZERO,
            ),
        ];
        assert!(
            select_embedded_body_pairs(&mode_two, &names, &parents, &[contact(0, 1, 0.4)])
                .is_empty()
        );

        let hand = [
            body(
                "左胸下",
                "",
                3,
                PhysicsMode::Physics,
                "capsule_y",
                [0.1, 0.4, 0.0],
                Vec3::ZERO,
            ),
            body(
                "左手碰撞体",
                "",
                4,
                PhysicsMode::FollowBone,
                "sphere",
                [1.0, 1.0, 1.0],
                Vec3::ZERO,
            ),
        ];
        assert!(
            select_embedded_body_pairs(&hand, &names, &parents, &[contact(0, 1, 0.4)]).is_empty()
        );

        let unrelated = [
            body(
                "左胸下",
                "",
                3,
                PhysicsMode::Physics,
                "capsule_y",
                [0.1, 0.4, 0.0],
                Vec3::ZERO,
            ),
            body(
                "上半身壳",
                "",
                4,
                PhysicsMode::FollowBone,
                "capsule_y",
                [1.0, 1.0, 1.0],
                Vec3::ZERO,
            ),
        ];
        assert!(
            select_embedded_body_pairs(&unrelated, &names, &parents, &[contact(0, 1, 0.4)])
                .is_empty()
        );
    }

    #[test]
    fn requires_deep_real_contact() {
        let (names, parents) = bones();
        let bodies = [
            body(
                "左胸下",
                "",
                3,
                PhysicsMode::Physics,
                "capsule_y",
                [0.1, 0.4, 0.0],
                Vec3::ZERO,
            ),
            body(
                "上半身2",
                "",
                1,
                PhysicsMode::FollowBone,
                "capsule_y",
                [0.7, 1.5, 0.0],
                Vec3::ZERO,
            ),
        ];
        assert!(select_embedded_body_pairs(&bodies, &names, &parents, &[]).is_empty());
        assert!(select_embedded_body_pairs(
            &bodies,
            &names,
            &parents,
            &[contact(0, 1, MIN_EMBEDDED_PENETRATION - 0.01)],
        )
        .is_empty());
    }

    #[test]
    fn center_inside_supports_sphere_capsule_and_box() {
        let point = Vec3::new(0.0, 0.2, 0.0);
        assert!(center_is_inside(
            point,
            body(
                "",
                "",
                0,
                PhysicsMode::FollowBone,
                "sphere",
                [1.0, 0.0, 0.0],
                Vec3::ZERO
            )
        ));
        assert!(center_is_inside(
            point,
            body(
                "",
                "",
                0,
                PhysicsMode::FollowBone,
                "capsule_y",
                [0.5, 1.0, 0.0],
                Vec3::ZERO
            )
        ));
        assert!(center_is_inside(
            point,
            body(
                "",
                "",
                0,
                PhysicsMode::FollowBone,
                "box",
                [0.5, 0.5, 0.5],
                Vec3::ZERO
            )
        ));
        assert!(!center_is_inside(
            Vec3::new(0.8, 0.2, 0.0),
            body(
                "",
                "",
                0,
                PhysicsMode::FollowBone,
                "sphere",
                [0.5, 0.5, 0.5],
                Vec3::ZERO
            )
        ));
    }
}
