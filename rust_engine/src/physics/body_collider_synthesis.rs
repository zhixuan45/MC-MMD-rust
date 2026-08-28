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
#[allow(dead_code)]
pub const SYNTHESIZED_HEAD_RADIUS: f32 = 0.75;
/// 颈部碰撞球保守半径。
pub const SYNTHESIZED_NECK_RADIUS: f32 = 0.45;
/// 骨盆/下半身碰撞体保守胶囊体半径。
pub const SYNTHESIZED_PELVIS_RADIUS: f32 = 0.70;
/// 骨盆/下半身碰撞体胶囊体横向跨度。
pub const SYNTHESIZED_PELVIS_HEIGHT: f32 = 0.60;
/// 臀部后弧面保形胶囊体保守半径。
pub const SYNTHESIZED_GLUTES_RADIUS: f32 = 0.45;
/// 臀部后弧面保形胶囊体横向跨度。
pub const SYNTHESIZED_GLUTES_WIDTH: f32 = 0.50;

/// 检测模型中是否包含裙摆物理刚体。
pub fn has_skirt_rigid_bodies(rigid_bodies: &[PmxRigidBody]) -> bool {
    const SKIRT_PARTS: &[&str] = &[
        "裙", "スカート", "skirt", "petticoat", "下装", "下衣", "裾", "摆", "衣摆", "后摆", "下摆", "cloak", "cape",
    ];
    rigid_bodies.iter().any(|rb| {
        if rb.mode == RigidBodyMode::Static {
            return false;
        }
        let local = rb.local_name.to_lowercase();
        let universal = rb.universal_name.to_lowercase();
        SKIRT_PARTS.iter().any(|part| local.contains(part) || universal.contains(part))
    })
}

/// 检测模型中是否已具有有效的臀部后弧面跟骨支撑碰撞体。
pub fn has_posterior_glutes_collider(rigid_bodies: &[PmxRigidBody], waist_bone_idx: usize) -> bool {
    const GLUTES_PARTS: &[&str] = &[
        "glutes", "butt", "buttock", "尻", "臀", "hip_rear", "hip_back",
    ];
    rigid_bodies.iter().any(|rb| {
        if rb.mode != RigidBodyMode::Static {
            return false;
        }
        let local = rb.local_name.to_lowercase();
        let universal = rb.universal_name.to_lowercase();
        if GLUTES_PARTS.iter().any(|part| local.contains(part) || universal.contains(part)) {
            return true;
        }
        if rb.bone_index == waist_bone_idx as i32 {
            let max_dim = match rb.shape {
                RigidBodyShape::Sphere => rb.size[0],
                RigidBodyShape::Box => rb.size[0].max(rb.size[1]).max(rb.size[2]),
                RigidBodyShape::Capsule => rb.size[0].max(rb.size[1]),
            };
            let rear_extent = rb.position[2] + max_dim;
            if rear_extent >= 0.8 && !local.contains("tail") && !universal.contains("tail") {
                return true;
            }
        }
        false
    })
}

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

/// 检测模型中是否已经具有有效的下半身/骨盆跟骨碰撞体（排除了仅用于尾巴等特定部件的微小锚点刚体）。
pub fn has_lower_body_collider(rigid_bodies: &[PmxRigidBody]) -> bool {
    const LOWER_NAMES: &[&str] = &[
        "下半身",
        "腰",
        "pelvis",
        "hip",
        "waist",
        "lowerbody",
        "thigh",
        "body_blocker",
        "blocker",
    ];
    rigid_bodies.iter().any(|rb| {
        if rb.mode != RigidBodyMode::Static {
            return false;
        }
        let local = rb.local_name.to_lowercase();
        let universal = rb.universal_name.to_lowercase();
        let is_blocker = local.contains("blocker") || universal.contains("blocker");
        // 排除尾巴链上的微小锚点刚体（但保留身体阻挡体 body_blocker）
        if (local.contains("tail") || universal.contains("tail")) && !is_blocker {
            return false;
        }
        let max_dim = match rb.shape {
            RigidBodyShape::Sphere => rb.size[0],
            RigidBodyShape::Box => rb.size[0].max(rb.size[1]).max(rb.size[2]),
            RigidBodyShape::Capsule => rb.size[0].max(rb.size[1]),
        };
        // 尺寸需大于等于 0.45，排除仅为骨骼线的极细碰撞体和微小锚点
        max_dim >= 0.45
            && LOWER_NAMES
                .iter()
                .any(|name| local.contains(name) || universal.contains(name))
    })
}

/// 为缺失上身或下身碰撞体的模型自动合成保守的跟骨碰撞刚体。
pub fn synthesize_missing_body_colliders(
    rigid_bodies: &[PmxRigidBody],
    bone_names: &[impl AsRef<str>],
    bone_positions: &[[f32; 3]],
) -> Vec<PmxRigidBody> {
    let mut synthesized = Vec::new();

    // 1. 上半身碰撞体合成（胸部与颈部）
    if !has_upper_body_collider(rigid_bodies) {
        // 查找胸部/上半身骨骼
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
                group: 0, // PMX 组 0（通用身体组），允许与全部动态刚体碰撞
                un_collision_group_flag: 0x0000,
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

        // 查找颈部骨骼
        let neck_bone_idx = find_bone_index(bone_names, &["首", "neck", "頸"]);
        if let Some(idx) = neck_bone_idx {
            let pos = bone_positions.get(idx).copied().unwrap_or([0.0; 3]);
            synthesized.push(PmxRigidBody {
                local_name: "Synthesized_Neck_Collider".to_owned(),
                universal_name: "Synthesized_Neck_Collider".to_owned(),
                bone_index: idx as i32,
                group: 0,
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
    }

    // 2. 下半身/骨盆碰撞体合成：必须优先锁定到「腰」骨骼，避免下半身动画旋转时导致裙摆鼓起或内陷
    if !has_lower_body_collider(rigid_bodies) {
        let pelvis_bone_idx = find_bone_index(
            bone_names,
            &["腰", "waist", "pelvis", "hips", "hip", "下半身", "lowerbody"],
        );
        if let Some(idx) = pelvis_bone_idx {
            let pos = bone_positions.get(idx).copied().unwrap_or([0.0; 3]);
            synthesized.push(PmxRigidBody {
                local_name: "Synthesized_Pelvis_Collider".to_owned(),
                universal_name: "Synthesized_Pelvis_Collider".to_owned(),
                bone_index: idx as i32,
                group: 0, // PMX 组 0（通用身体组），绝不被裙摆 mask 过滤
                un_collision_group_flag: 0x0000,
                shape: RigidBodyShape::Capsule,
                size: [SYNTHESIZED_PELVIS_RADIUS, SYNTHESIZED_PELVIS_HEIGHT, 0.0],
                position: pos,
                rotation: [0.0, 0.0, std::f32::consts::FRAC_PI_2], // 横向胶囊体匹配骨盆宽
                mass: 0.0,
                move_attenuation: 0.0,
                rotation_attenuation: 0.0,
                repulsion: 0.0,
                friction: 0.5,
                mode: RigidBodyMode::Static,
            });
        }
    }

    // 3. 裙摆臀部后弧面支撑碰撞体合成：
    // 当模型具有动态裙摆且缺少后部臀部碰撞体时，为腰部合成平滑横向胶囊体支撑，
    // 把 BL/BR 侧后裙片从内部平滑托起，防止在重力下过度向内凹折形成尖锐橄榄形。
    if has_skirt_rigid_bodies(rigid_bodies) {
        let waist_bone_idx = find_bone_index(
            bone_names,
            &["腰", "waist", "pelvis", "hips", "hip", "下半身", "lowerbody"],
        );
        if let Some(idx) = waist_bone_idx {
            if !has_posterior_glutes_collider(rigid_bodies, idx) {
                let pos = bone_positions.get(idx).copied().unwrap_or([0.0; 3]);
                synthesized.push(PmxRigidBody {
                    local_name: "Synthesized_Glutes_Collider".to_owned(),
                    universal_name: "Synthesized_Glutes_Collider".to_owned(),
                    bone_index: idx as i32,
                    group: 0,
                    un_collision_group_flag: 0x0000,
                    shape: RigidBodyShape::Capsule,
                    size: [SYNTHESIZED_GLUTES_RADIUS, SYNTHESIZED_GLUTES_WIDTH, 0.0],
                    position: [pos[0], pos[1] - 0.15, (-pos[2]) + 0.55],
                    rotation: [0.0, 0.0, std::f32::consts::FRAC_PI_2],
                    mass: 0.0,
                    move_attenuation: 0.0,
                    rotation_attenuation: 0.0,
                    repulsion: 0.0,
                    friction: 0.5,
                    mode: RigidBodyMode::Static,
                });
            }
        }
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
#[allow(dead_code)]
#[derive(Debug, Clone, Copy)]
pub struct BodyColliderSphere {
    pub center: Vec3,
    pub radius: f32,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Copy)]
pub struct BodyColliderCapsule {
    pub start: Vec3,
    pub end: Vec3,
    pub radius: f32,
}

/// 从当前刚体运行时数据和骨骼变换中提取身体跟骨碰撞几何体。
#[allow(dead_code)]
pub fn extract_body_colliders_from_data(
    rigid_bodies: &[MmdRigidBodyData],
    bone_transforms: &[Mat4],
) -> (Vec<BodyColliderSphere>, Vec<BodyColliderCapsule>) {
    const BODY_COLLIDER_PARTS: &[&str] = &[
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
        "腰",
        "下半身",
        "足",
        "ひざ",
        "膝",
        "腿",
        "thigh",
        "shin",
        "leg",
        "hip",
        "pelvis",
        "waist",
        "synthesized",
        "skirt_collider",
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
        let is_body_part = BODY_COLLIDER_PARTS
            .iter()
            .any(|part| name_lower.contains(part));
        if !is_body_part {
            continue;
        }

        // 正确通过刚体在骨骼空间的 offset 矩阵与当前骨骼世界变换合成刚体世界空间姿态
        let bone_right = bone_transforms[bone_idx as usize];
        let bone_left = super::inv_z(bone_right);
        let body_left = rb.compute_body_matrix(bone_left);
        let body_right = super::inv_z(body_left);
        let (_, body_rot, center) = body_right.to_scale_rotation_translation();

        match rb.shape_name {
            "Sphere" => {
                let radius = rb.shape_size[0];
                if radius >= 0.35 {
                    spheres.push(BodyColliderSphere { center, radius });
                }
            }
            "Capsule" => {
                let radius = rb.shape_size[0];
                let height = rb.shape_size[1];
                if radius >= 0.35 {
                    let half_h = height * 0.5;
                    let local_axis_y = body_rot * Vec3::Y;
                    let start = center - local_axis_y * half_h;
                    let end = center + local_axis_y * half_h;
                    capsules.push(BodyColliderCapsule { start, end, radius });
                }
            }
            "Box" => {
                let max_dim = rb.shape_size[0].max(rb.shape_size[1]).max(rb.shape_size[2]);
                if max_dim >= 0.4 {
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
#[allow(dead_code)]
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
#[path = "body_collider_synthesis_tests.rs"]
mod tests;
