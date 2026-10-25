//! 为真正缺失的身体区域补充跟骨碰撞体；保留模型原生支持，避免初始嵌入。

use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyMode, RigidBodyShape};

use super::bullet_ffi::{BulletRigidBody, BulletShape, BulletWorld};
use super::mmd_rigid_body::{
    effective_collision_shape_size_with_static_scale, is_lower_body_collider, MmdRigidBodyData,
    PhysicsMode,
};

mod lower_body;
mod upper_body;

#[derive(Clone, Copy)]
enum BodyRegion {
    Upper,
    Neck,
    Lower,
}

impl BodyRegion {
    fn names(self) -> &'static [&'static str] {
        match self {
            Self::Upper => &[
                "上半身",
                "upperbody",
                "upper_body",
                "chest",
                "bust",
                "spine",
                "胸",
            ],
            Self::Neck => &["首", "neck", "頸", "颈"],
            Self::Lower => &[
                "下半身",
                "腰",
                "pelvis",
                "hips",
                "hip",
                "waist",
                "lowerbody",
                "body_blocker",
                "glutes",
                "buttock",
                "臀",
                "尻",
            ],
        }
    }

    fn matches(self, name: &str) -> bool {
        let name = name.to_lowercase();
        self.names().iter().any(|part| name_matches(&name, part))
    }

    fn minimum_radius(self) -> f32 {
        match self {
            Self::Upper => 0.4,
            Self::Neck => 0.2,
            Self::Lower => 0.45,
        }
    }
}

fn name_matches(name: &str, part: &str) -> bool {
    if !part.chars().all(|c| c.is_ascii_alphanumeric()) {
        return name.contains(part);
    }
    name.split(|c: char| !c.is_ascii_alphanumeric())
        .any(|token| {
            token
                .strip_prefix(part)
                .is_some_and(|suffix| suffix.chars().all(|c| c.is_ascii_digit()))
        })
}

fn valid_shape(body: &PmxRigidBody) -> bool {
    body.size
        .iter()
        .chain(&body.position)
        .chain(&body.rotation)
        .all(|v| v.is_finite())
        && match body.shape {
            RigidBodyShape::Sphere => body.size[0] > 0.0,
            RigidBodyShape::Capsule => body.size[0] > 0.0 && body.size[1] >= 0.0,
            RigidBodyShape::Box => body.size.iter().all(|v| *v > 0.0),
        }
}

fn support_radius(body: &PmxRigidBody) -> f32 {
    match body.shape {
        RigidBodyShape::Sphere | RigidBodyShape::Capsule => body.size[0],
        RigidBodyShape::Box => body.size[0].min(body.size[2]),
    }
}

fn accessory_name(name: &str) -> bool {
    let name = name.to_lowercase();
    // Explicit body blockers can belong to a tail rig and still support the body.
    if name.contains("body_blocker") {
        return false;
    }
    [
        "anchor",
        "アンカー",
        "锚",
        "飾",
        "饰",
        "accessory",
        "ribbon",
        "蝴蝶",
        "袖",
        "sleeve",
        "tail",
        "尻尾",
        "hair",
        "髪",
        "髮",
        "发",
        "braid",
        "shoe",
        "鞋",
    ]
    .iter()
    .any(|part| name.contains(part))
}

fn has_region(bodies: &[PmxRigidBody], names: &[impl AsRef<str>], region: BodyRegion) -> bool {
    bodies.iter().any(|body| {
        if body.mode != RigidBodyMode::Static
            || !valid_shape(body)
            || support_radius(body) < region.minimum_radius()
            || accessory_name(&body.local_name)
            || accessory_name(&body.universal_name)
        {
            return false;
        }
        let bone_name = usize::try_from(body.bone_index)
            .ok()
            .and_then(|i| names.get(i))
            .map(|name| name.as_ref());
        // Recognized bones take priority over misleading rigid-body template names.
        if let Some(name) = bone_name {
            if accessory_name(name) {
                return false;
            }
            if [BodyRegion::Upper, BodyRegion::Neck, BodyRegion::Lower]
                .into_iter()
                .any(|r| r.matches(name))
            {
                return region.matches(name);
            }
        }
        region.matches(&body.local_name) || region.matches(&body.universal_name)
    })
}

#[cfg(all(test, feature = "legacy-physics"))]
fn has_upper_body_collider(bodies: &[PmxRigidBody]) -> bool {
    has_region(bodies, &[] as &[String], BodyRegion::Upper)
}

#[cfg(all(test, feature = "legacy-physics"))]
fn has_lower_body_collider(bodies: &[PmxRigidBody]) -> bool {
    has_region(bodies, &[] as &[String], BodyRegion::Lower)
}

/// Positions are right-handed model-space bone positions. PMX/Bullet uses -Z.
/// Fill only a missing chest, neck or pelvis. An existing lower-body collider
/// already supplies support: never add a second fixed-offset glutes collider.
pub(crate) struct BodyColliderFillPlan {
    upper: Vec<PmxRigidBody>,
    lower: Vec<PmxRigidBody>,
}

/// Only inspect PMX metadata and compose candidate data. No Bullet objects are allocated.
pub(crate) fn detect_missing_body_colliders(
    bodies: &[PmxRigidBody],
    names: &[impl AsRef<str>],
    positions: &[[f32; 3]],
) -> BodyColliderFillPlan {
    BodyColliderFillPlan {
        upper: upper_body::detect(bodies, names, positions),
        lower: lower_body::detect(bodies, names, positions),
    }
}

/// Apply each detected region independently, checking actual shapes before admission.
pub(crate) fn apply_body_collider_fill(
    plan: BodyColliderFillPlan,
    authored: &[PmxRigidBody],
    fill_upper: bool,
    fill_lower: bool,
) -> Vec<PmxRigidBody> {
    if super::config::get_config().debug_log {
        log::info!("[Bullet3][BODY_COLLIDER_FILL_PLAN] upper_candidates={} lower_candidates={} apply_upper={} apply_lower={}",
            plan.upper.len(), plan.lower.len(), fill_upper, fill_lower);
    }
    let mut result = Vec::new();
    if fill_upper {
        result.extend(upper_body::apply(plan.upper, authored));
    }
    if fill_lower {
        result.extend(lower_body::apply(plan.lower, authored));
    }
    result
}

fn apply_candidates(candidates: Vec<PmxRigidBody>, authored: &[PmxRigidBody]) -> Vec<PmxRigidBody> {
    candidates.into_iter().filter(|candidate| {
        if candidate_is_clear(candidate, authored) == Some(true) { return true; }
        if super::config::get_config().debug_log {
            log::info!("[Bullet3][BODY_COLLIDER_FILL_SKIP] name={} reason=initial_overlap_or_unavailable_probe", candidate.local_name);
        }
        false
    }).collect()
}

struct ColliderSpec {
    region: BodyRegion,
    name: &'static str,
    bones: &'static [&'static str],
    shape: RigidBodyShape,
    size: [f32; 3],
    rotation: [f32; 3],
}

fn detect_region(
    spec: ColliderSpec,
    bodies: &[PmxRigidBody],
    names: &[impl AsRef<str>],
    positions: &[[f32; 3]],
) -> Option<PmxRigidBody> {
    if has_region(bodies, names, spec.region) {
        return None;
    }
    let index = find_bone_index(names, spec.bones)?;
    let pos = positions
        .get(index)
        .filter(|p| p.iter().all(|v| v.is_finite()))?;
    let candidate = PmxRigidBody {
        local_name: spec.name.to_owned(),
        universal_name: spec.name.to_owned(),
        bone_index: index as i32,
        group: 0,
        un_collision_group_flag: u16::MAX,
        shape: spec.shape,
        size: spec.size,
        position: [pos[0], pos[1], -pos[2]],
        rotation: spec.rotation,
        mass: 0.0,
        move_attenuation: 0.0,
        rotation_attenuation: 0.0,
        repulsion: 0.0,
        friction: 0.5,
        mode: RigidBodyMode::Static,
    };
    Some(candidate)
}

fn find_bone_index(names: &[impl AsRef<str>], candidates: &[&str]) -> Option<usize> {
    for exact in [true, false] {
        for candidate in candidates {
            if let Some(index) = names.iter().position(|name| {
                let name = name.as_ref().to_lowercase();
                !accessory_name(&name)
                    && if exact {
                        name == *candidate
                    } else {
                        name_matches(&name, candidate)
                    }
            }) {
                return Some(index);
            }
        }
    }
    None
}

// Field order keeps shapes alive until their bodies are destroyed.
struct ProbeBody {
    body: BulletRigidBody,
    _shape: BulletShape,
}

fn probe_body(body: &PmxRigidBody, size: [f32; 3]) -> Option<ProbeBody> {
    let data = MmdRigidBodyData::from_pmx(body, None, size);
    let shape = MmdRigidBodyData::create_shape(body, size)?;
    let body = data.create_rigid_body(body, &shape)?;
    Some(ProbeBody {
        body,
        _shape: shape,
    })
}

fn candidate_is_clear(candidate: &PmxRigidBody, authored: &[PmxRigidBody]) -> Option<bool> {
    // Runtime scaling also requires a colliding garment; a tail/accessory-only
    // model keeps the full-size pelvis. Probe an envelope covering both that
    // unscaled shape and any configured enlargement instead of assuming shrinkage.
    let size = effective_collision_shape_size_with_static_scale(
        candidate,
        super::config::get_config().static_collider_scale.max(1.0),
        is_lower_body_collider(candidate),
    );
    let mut probes = vec![probe_body(candidate, size)?];
    for body in authored
        .iter()
        .filter(|body| body.mode != RigidBodyMode::Static && valid_shape(body))
    {
        probes.push(probe_body(body, body.size)?);
    }
    // Declared after probes, so the world is destroyed before referenced bodies.
    let world = BulletWorld::new(0.0, 0.0, 0.0)?;
    for (i, probe) in probes.iter().enumerate() {
        let (group, mask) = if i == 0 { (1, 2) } else { (2, 1) };
        world.add_rigid_body(&probe.body, group, mask);
    }
    world.detect_collisions();
    Some(
        !world
            .contact_manifolds()
            .iter()
            .any(|c| c.contact_count > 0 && c.max_penetration_depth > 1e-4),
    )
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

#[cfg(all(test, feature = "legacy-physics"))]
#[path = "body_collider_synthesis_tests.rs"]
mod tests;
