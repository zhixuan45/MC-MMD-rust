//! 身体跟骨碰撞体自动合成与物理回写前动态骨骼几何推离保护。
//!
//! 1. 为缺失上身/胸部碰撞刚体的模型（如 Grass Wonder 等）自动合成保守的跟骨碰撞体；
//! 2. 在物理模拟回写骨骼前，对所有动态骨骼（头发、披发、袖子、飘带等）执行几何推离，
//!    彻底防止末端在极限动作下穿透胸腔与身躯内部。

use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyMode, RigidBodyShape};

use super::mmd_rigid_body::{MmdRigidBodyData, PhysicsMode};

/// 上半身碰撞体保守胶囊体半径（单位：MMD 局部单位）。
pub const SYNTHESIZED_CHEST_RADIUS: f32 = 0.85;
/// 上半身碰撞体胶囊体高度。
pub const SYNTHESIZED_CHEST_HEIGHT: f32 = 1.1;
/// 头部碰撞球保守半径。
pub const SYNTHESIZED_HEAD_RADIUS: f32 = 0.75;
/// 颈部碰撞球保守半径。
pub const SYNTHESIZED_NECK_RADIUS: f32 = 0.45;

/// 检测模型中是否已经具有有效的胸部/上半身跟骨碰撞体。
pub fn has_upper_body_collider(rigid_bodies: &[PmxRigidBody]) -> bool {
    const UPPER_NAMES: &[&str] = &["胸", "chest", "bust", "上半身", "upperbody", "spine"];
    rigid_bodies.iter().any(|rb| {
        if rb.mode != RigidBodyMode::Static {
            return false;
        }
        let local = rb.local_name.to_lowercase();
        let universal = rb.universal_name.to_lowercase();
        let max_dim = match rb.shape {
            RigidBodyShape::Sphere => rb.size[0],
            RigidBodyShape::Box => rb.size[0].max(rb.size[1]).max(rb.size[2]),
            RigidBodyShape::Capsule => rb.size[0].max(rb.size[1]),
        };
        // 尺寸需大于 0.4，排除仅用于关节锚定的微小 anchor 点
        max_dim >= 0.4
            && UPPER_NAMES
                .iter()
                .any(|name| local.contains(name) || universal.contains(name))
    })
}

/// 为缺失上身碰撞体的模型自动合成保守的跟骨碰撞刚体。
pub fn synthesize_missing_body_colliders(
    rigid_bodies: &[PmxRigidBody],
    bone_names: &[impl AsRef<str>],
    bone_positions: &[[f32; 3]],
) -> Vec<PmxRigidBody> {
    if has_upper_body_collider(rigid_bodies) {
        return Vec::new();
    }

    let mut synthesized = Vec::new();

    // 1. 查找胸部/上半身骨骼
    let chest_bone_idx = find_bone_index(
        bone_names,
        &["上半身2", "上半身", "chest", "spine", "胸", "upperbody"],
    );
    if let Some(idx) = chest_bone_idx {
        let pos = bone_positions.get(idx).copied().unwrap_or([0.0; 3]);
        synthesized.push(PmxRigidBody {
            local_name: "Synthesized_Chest_Collider".to_owned(),
            universal_name: "Synthesized_Chest_Collider".to_owned(),
            bone_index: idx as i32,
            group: 2,
            un_collision_group_flag: 0x0000, // 掩码 0xFFFF：允许与所有动态刚体碰撞
            shape: RigidBodyShape::Capsule,
            size: [SYNTHESIZED_CHEST_RADIUS, SYNTHESIZED_CHEST_HEIGHT, 0.0],
            position: pos,
            rotation: [0.0; 3],
            mass: 0.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.5,
            mode: RigidBodyMode::Static,
        });
    }

    // 2. 查找颈部骨骼
    let neck_bone_idx = find_bone_index(bone_names, &["首", "neck", "頸"]);
    if let Some(idx) = neck_bone_idx {
        let pos = bone_positions.get(idx).copied().unwrap_or([0.0; 3]);
        synthesized.push(PmxRigidBody {
            local_name: "Synthesized_Neck_Collider".to_owned(),
            universal_name: "Synthesized_Neck_Collider".to_owned(),
            bone_index: idx as i32,
            group: 2,
            un_collision_group_flag: 0x0000,
            shape: RigidBodyShape::Sphere,
            size: [SYNTHESIZED_NECK_RADIUS, 0.0, 0.0],
            position: pos,
            rotation: [0.0; 3],
            mass: 0.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.5,
            mode: RigidBodyMode::Static,
        });
    }

    synthesized
}

fn find_bone_index(bone_names: &[impl AsRef<str>], candidates: &[&str]) -> Option<usize> {
    for candidate in candidates {
        for (i, name) in bone_names.iter().enumerate() {
            let lower = name.as_ref().to_lowercase();
            if lower == *candidate || lower.contains(candidate) {
                return Some(i);
            }
        }
    }
    None
}

/// 身体碰撞体在世界空间中的几何描述。
#[derive(Debug, Clone, Copy)]
pub struct BodyColliderSphere {
    pub center: Vec3,
    pub radius: f32,
}

#[derive(Debug, Clone, Copy)]
pub struct BodyColliderCapsule {
    pub start: Vec3,
    pub end: Vec3,
    pub radius: f32,
}

/// 从当前刚体运行时数据和骨骼变换中提取身体跟骨碰撞几何体。
pub fn extract_body_colliders_from_data(
    rigid_bodies: &[MmdRigidBodyData],
    bone_transforms: &[Mat4],
) -> (Vec<BodyColliderSphere>, Vec<BodyColliderCapsule>) {
    const UPPER_OR_SYNTH_NAMES: &[&str] = &[
        "胸",
        "chest",
        "bust",
        "上半身",
        "upperbody",
        "spine",
        "首",
        "neck",
        "頭",
        "head",
        "synthesized",
        "body_blocker",
    ];

    let mut spheres = Vec::new();
    let mut capsules = Vec::new();

    for rb in rigid_bodies {
        if rb.physics_mode != PhysicsMode::FollowBone {
            continue;
        }
        let bone_idx = rb.bone_index;
        if bone_idx < 0 || (bone_idx as usize) >= bone_transforms.len() {
            continue;
        }

        let name_lower = rb.name.to_lowercase();
        let is_upper_body = UPPER_OR_SYNTH_NAMES
            .iter()
            .any(|part| name_lower.contains(part));
        if !is_upper_body {
            continue;
        }

        let bone_matrix = bone_transforms[bone_idx as usize];
        let rb_pos = Vec3::from_array(rb.raw_position);

        match rb.shape_name {
            "Sphere" => {
                let radius = rb.shape_size[0];
                if radius >= 0.35 {
                    let center = bone_matrix.transform_point3(rb_pos);
                    spheres.push(BodyColliderSphere { center, radius });
                }
            }
            "Capsule" => {
                let radius = rb.shape_size[0];
                let height = rb.shape_size[1];
                if radius >= 0.35 {
                    let half_h = height * 0.5;
                    let start = bone_matrix.transform_point3(rb_pos + Vec3::new(0.0, -half_h, 0.0));
                    let end = bone_matrix.transform_point3(rb_pos + Vec3::new(0.0, half_h, 0.0));
                    capsules.push(BodyColliderCapsule { start, end, radius });
                }
            }
            "Box" => {
                let max_dim = rb.shape_size[0].max(rb.shape_size[1]).max(rb.shape_size[2]);
                if max_dim >= 0.4 {
                    let center = bone_matrix.transform_point3(rb_pos);
                    spheres.push(BodyColliderSphere {
                        center,
                        radius: max_dim * 0.55,
                    });
                }
            }
            _ => {}
        }
    }

    (spheres, capsules)
}

/// 对动态骨骼执行物理回写前的几何推离保护。
///
/// 当动态骨骼（头发、披发、袖子、衣服等）由于大幅运动落入身体碰撞体内部时，
/// 强制将其平滑推离到碰撞体表面半径处。
pub fn push_out_dynamic_bone_position(
    mut world_pos: Vec3,
    spheres: &[BodyColliderSphere],
    capsules: &[BodyColliderCapsule],
) -> Vec3 {
    // 1. 球体碰撞推离
    for sphere in spheres {
        let offset = world_pos - sphere.center;
        let dist_sq = offset.length_squared();
        let r = sphere.radius;
        if dist_sq < r * r && dist_sq > 1e-8 {
            let dist = dist_sq.sqrt();
            let normal = offset / dist;
            world_pos = sphere.center + normal * r;
        }
    }

    // 2. 胶囊体碰撞推离
    for capsule in capsules {
        let segment = capsule.end - capsule.start;
        let seg_len_sq = segment.length_squared();
        let closest = if seg_len_sq <= 1e-6 {
            capsule.start
        } else {
            let t = ((world_pos - capsule.start).dot(segment) / seg_len_sq).clamp(0.0, 1.0);
            capsule.start + segment * t
        };

        let offset = world_pos - closest;
        let dist_sq = offset.length_squared();
        let r = capsule.radius;
        if dist_sq < r * r && dist_sq > 1e-8 {
            let dist = dist_sq.sqrt();
            let normal = offset / dist;
            world_pos = closest + normal * r;
        }
    }

    world_pos
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_pmx_body(
        name: &str,
        mode: RigidBodyMode,
        shape: RigidBodyShape,
        size: [f32; 3],
    ) -> PmxRigidBody {
        PmxRigidBody {
            local_name: name.to_owned(),
            universal_name: String::new(),
            bone_index: 0,
            group: 0,
            un_collision_group_flag: 0,
            shape,
            size,
            position: [0.0; 3],
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode,
        }
    }

    #[test]
    fn detects_missing_upper_body_collider_when_absent() {
        let only_skirt_colliders = vec![
            test_pmx_body(
                "left_thigh_skirt_collider",
                RigidBodyMode::Static,
                RigidBodyShape::Capsule,
                [1.0, 4.0, 0.0],
            ),
            test_pmx_body(
                "Sp_Hi_Tail0_B_00_body_blocker",
                RigidBodyMode::Static,
                RigidBodyShape::Sphere,
                [1.0, 0.0, 0.0],
            ),
        ];
        assert!(!has_upper_body_collider(&only_skirt_colliders));
    }

    #[test]
    fn detects_upper_body_collider_when_present() {
        let with_chest = vec![test_pmx_body(
            "上半身",
            RigidBodyMode::Static,
            RigidBodyShape::Capsule,
            [0.8, 1.2, 0.0],
        )];
        assert!(has_upper_body_collider(&with_chest));
    }

    #[test]
    fn synthesizes_colliders_for_missing_model() {
        let rigid_bodies = vec![test_pmx_body(
            "Sp_He_Hair2_L_00_anchor",
            RigidBodyMode::Static,
            RigidBodyShape::Sphere,
            [0.1, 0.0, 0.0],
        )];
        let bone_names = vec!["センター", "下半身", "上半身", "首", "頭"];
        let bone_positions = vec![
            [0.0, 0.0, 0.0],
            [0.0, 8.0, 0.0],
            [0.0, 12.0, 0.0],
            [0.0, 15.0, 0.0],
            [0.0, 16.5, 0.0],
        ];

        let synthesized =
            synthesize_missing_body_colliders(&rigid_bodies, &bone_names, &bone_positions);
        assert_eq!(synthesized.len(), 2, "应合成胸部和颈部两个跟骨碰撞体");
        assert_eq!(synthesized[0].bone_index, 2);
        assert_eq!(synthesized[1].bone_index, 3);
        assert_eq!(synthesized[0].mode, RigidBodyMode::Static);
    }

    #[test]
    fn push_out_moves_penetrated_point_to_surface() {
        let spheres = vec![BodyColliderSphere {
            center: Vec3::new(0.0, 10.0, 0.0),
            radius: 1.0,
        }];
        let capsules = vec![];

        let inside = Vec3::new(0.0, 10.0, 0.5);
        let pushed = push_out_dynamic_bone_position(inside, &spheres, &capsules);

        assert!((pushed - Vec3::new(0.0, 10.0, 1.0)).length() < 1e-5);
    }
}
