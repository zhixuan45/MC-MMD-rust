//! 上半身缺失支持：胸部和颈部各自判断，使用共享的形状交叠保护。
use super::{detect_region, BodyRegion, ColliderSpec, PmxRigidBody, RigidBodyShape};

pub(super) fn detect(
    bodies: &[PmxRigidBody],
    names: &[impl AsRef<str>],
    positions: &[[f32; 3]],
) -> Vec<PmxRigidBody> {
    [
        ColliderSpec {
            region: BodyRegion::Upper,
            name: "Synthesized_Chest_Collider",
            bones: &["上半身2", "上半身", "chest", "spine", "胸", "upperbody"],
            shape: RigidBodyShape::Capsule,
            size: [0.85, 1.1, 0.0],
            rotation: [0.0; 3],
        },
        ColliderSpec {
            region: BodyRegion::Neck,
            name: "Synthesized_Neck_Collider",
            bones: &["首", "neck", "頸", "颈"],
            shape: RigidBodyShape::Sphere,
            size: [0.45, 0.0, 0.0],
            rotation: [0.0; 3],
        },
    ]
    .into_iter()
    .filter_map(|spec| detect_region(spec, bodies, names, positions))
    .collect()
}

/// Admission uses the shared exact-shape check; the caller controls this region independently.
pub(super) fn apply(candidates: Vec<PmxRigidBody>, authored: &[PmxRigidBody]) -> Vec<PmxRigidBody> {
    super::apply_candidates(candidates, authored)
}
