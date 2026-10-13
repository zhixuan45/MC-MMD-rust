//! 下半身缺失支持：只补骨盆，保留原生下半身支持，避免重复臀部胶囊。
use super::{detect_region, BodyRegion, ColliderSpec, PmxRigidBody, RigidBodyShape};

pub(super) fn detect(
    bodies: &[PmxRigidBody],
    names: &[impl AsRef<str>],
    positions: &[[f32; 3]],
) -> Vec<PmxRigidBody> {
    detect_region(
        ColliderSpec {
            region: BodyRegion::Lower,
            name: "Synthesized_Pelvis_Collider",
            bones: &[
                "腰",
                "waist",
                "pelvis",
                "hips",
                "hip",
                "下半身",
                "lowerbody",
            ],
            shape: RigidBodyShape::Capsule,
            size: [0.70, 0.60, 0.0],
            rotation: [0.0, 0.0, std::f32::consts::FRAC_PI_2],
        },
        bodies,
        names,
        positions,
    )
    .into_iter()
    .collect()
}

/// Admission uses the shared exact-shape check; the caller controls this region independently.
pub(super) fn apply(candidates: Vec<PmxRigidBody>, authored: &[PmxRigidBody]) -> Vec<PmxRigidBody> {
    super::apply_candidates(candidates, authored)
}
