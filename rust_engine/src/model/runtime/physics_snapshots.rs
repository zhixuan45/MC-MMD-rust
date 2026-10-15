//! 向独立探针暴露只读快照，不泄漏 Bullet 指针或所有权。
use super::MmdModel;
use crate::physics::{PhysicsBodySnapshot, PhysicsContactPointSnapshotBatch};

impl MmdModel {
    pub fn physics_contact_points_matching(
        &self,
        needle: &str,
        limit: usize,
    ) -> PhysicsContactPointSnapshotBatch {
        self.physics
            .as_ref()
            .map_or_else(PhysicsContactPointSnapshotBatch::default, |physics| {
                physics.contact_points_matching(needle, limit)
            })
    }

    pub fn physics_body_snapshots_matching(&self, needle: &str) -> Vec<PhysicsBodySnapshot> {
        self.physics
            .as_ref()
            .map_or_else(Vec::new, |physics| physics.body_snapshots_matching(needle))
    }
}
