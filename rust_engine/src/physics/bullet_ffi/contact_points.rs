use super::{ffi, BulletRigidBody, BulletWorld};
use glam::Vec3;

pub const MAX_CONTACT_QUERY_TARGETS: usize = 256;
pub const MAX_CONTACT_QUERY_CAPACITY: usize = 4096;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContactPointQueryError {
    TooManyTargets,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContactSamplePhase {
    Unavailable,
    CollisionDetection,
    PhysicsStep,
}

#[derive(Debug, Clone, Copy)]
pub struct ContactPoint {
    pub body_a: usize,
    pub body_b: usize,
    pub point_a: Vec3,
    pub point_b: Vec3,
    pub normal_on_b: Vec3,
    /// Bullet 的有符号接触距离；负数表示穿透。
    pub distance: f32,
    pub applied_normal_impulse: f32,
}

impl ContactPoint {
    /// 返回 Bullet 法向冲量作用于指定刚体的方向。
    pub fn impulse_direction_for_body(&self, body: usize) -> Option<Vec3> {
        if body == self.body_a {
            Some(self.normal_on_b)
        } else if body == self.body_b {
            Some(-self.normal_on_b)
        } else {
            None
        }
    }
}

#[derive(Debug, Clone)]
pub struct ContactPointQuery {
    pub points: Vec<ContactPoint>,
    pub total_hits: usize,
    pub truncated: bool,
    pub step_serial: u64,
    /// 实际执行的子步数，已按 max_substeps 限幅。
    pub substeps: usize,
    /// Bullet 返回的限幅前子步请求数。
    pub requested_substeps: usize,
    pub phase: ContactSamplePhase,
}

impl BulletWorld {
    /// 读取目标刚体相关接触；目标为空时读取全世界穿透点。
    pub fn contact_points_for(
        &self,
        targets: &[&BulletRigidBody],
        capacity: usize,
    ) -> Result<ContactPointQuery, ContactPointQueryError> {
        if targets.len() > MAX_CONTACT_QUERY_TARGETS {
            return Err(ContactPointQueryError::TooManyTargets);
        }
        let output_len = capacity.min(MAX_CONTACT_QUERY_CAPACITY);
        let mut raw = vec![ffi::BW_ContactPoint::default(); output_len];
        let target_ptrs: Vec<_> = targets.iter().map(|body| body.as_ptr()).collect();
        let mut total_hits = 0;
        let mut sample_info = ffi::BW_ContactSampleInfo::default();
        let written = unsafe {
            ffi::bw_world_copy_contact_points(
                self.ptr,
                if target_ptrs.is_empty() {
                    std::ptr::null()
                } else {
                    target_ptrs.as_ptr()
                },
                target_ptrs.len() as i32,
                if raw.is_empty() {
                    std::ptr::null_mut()
                } else {
                    raw.as_mut_ptr()
                },
                output_len as i32,
                &mut total_hits,
                &mut sample_info,
            )
        };
        let written = written.max(0).min(output_len as i32) as usize;
        raw.truncate(written);
        let points = raw
            .into_iter()
            .map(|point| ContactPoint {
                body_a: point.body_a as usize,
                body_b: point.body_b as usize,
                point_a: Vec3::from_array(point.point_a),
                point_b: Vec3::from_array(point.point_b),
                normal_on_b: Vec3::from_array(point.normal_on_b),
                distance: point.distance,
                applied_normal_impulse: point.applied_normal_impulse,
            })
            .collect::<Vec<_>>();
        let total_hits = total_hits.max(0) as usize;
        Ok(ContactPointQuery {
            truncated: total_hits > points.len(),
            points,
            total_hits,
            step_serial: sample_info.step_serial,
            substeps: sample_info.substeps.max(0) as usize,
            requested_substeps: sample_info.requested_substeps.max(0) as usize,
            phase: match sample_info.phase {
                1 => ContactSamplePhase::CollisionDetection,
                2 => ContactSamplePhase::PhysicsStep,
                _ => ContactSamplePhase::Unavailable,
            },
        })
    }
}
