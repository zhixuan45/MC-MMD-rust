//! 按父子层级回写物理，模式 2 的位置沿当前父姿态传播。
use super::BoneSet;
use glam::Mat4;
use std::collections::HashSet;

impl BoneSet {
    pub(super) fn build_physics_writeback_order(&mut self) {
        fn visit(index: usize, bones: &BoneSet, visited: &mut [bool], order: &mut Vec<usize>) {
            if visited[index] {
                return;
            }
            visited[index] = true;
            if let Ok(parent) = usize::try_from(bones.links[index].parent_index) {
                if parent < bones.links.len() {
                    visit(parent, bones, visited, order);
                }
            }
            order.push(index);
        }
        let mut order = Vec::with_capacity(self.links.len());
        let mut visited = vec![false; self.links.len()];
        for &index in &self.sorted_indices {
            visit(index, self, &mut visited, &mut order);
        }
        self.physics_writeback_order = order;
        self.physics_transform_buf.resize(self.links.len(), None);
    }

    /// 回写父骨骼后再求子骨骼位置，不依赖刚体列表顺序。
    pub fn apply_physics_transforms(
        &mut self,
        transforms: &[(usize, Mat4)],
        position_aligned_bones: &HashSet<usize>,
    ) {
        if self.needs_hierarchy_update {
            self.build_hierarchy();
        }
        self.physics_transform_buf.fill(None);
        self.physics_bone_indices.clear();
        for &(index, transform) in transforms {
            if index < self.links.len() {
                self.physics_transform_buf[index] = Some(transform);
                self.physics_bone_indices.insert(index);
            }
        }
        for order in 0..self.physics_writeback_order.len() {
            let index = self.physics_writeback_order[order];
            let bone = &self.links[index];
            let inherited = match usize::try_from(bone.parent_index) {
                Ok(parent) if parent < self.links.len() => {
                    self.links[parent].local_to_world * bone.local_to_parent
                }
                _ => bone.local_to_parent,
            };
            if let Some(mut transform) = self.physics_transform_buf[index] {
                if position_aligned_bones.contains(&index) {
                    // 保留本帧层级传播得到的位置，旋转仍来自 Bullet。
                    transform.w_axis = inherited.w_axis;
                }
                self.set_global_transform_physics(index, transform);
            } else {
                self.links[index].local_to_world = inherited;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::skeleton::BoneLink;
    use glam::{Quat, Vec3};

    fn chain() -> BoneSet {
        let mut bones = BoneSet::new();
        for i in 0..3 {
            let mut bone = BoneLink::new(format!("bone{i}"));
            bone.parent_index = i - 1;
            bone.initial_position = Vec3::Y * i as f32;
            bones.add_bone(bone);
        }
        bones.build_hierarchy();
        bones
    }




    #[test]
    fn mode2_writeback_ignores_body_order_and_transform_level_order() {
        let mut bones = BoneSet::new();
        let mut child = BoneLink::new("child".into());
        child.parent_index = 1;
        child.transform_level = -1;
        child.initial_position = Vec3::Y;
        bones.add_bone(child);
        bones.add_bone(BoneLink::new("parent".into()));
        bones.build_hierarchy();
        let parent = Mat4::from_quat(Quat::from_rotation_z(std::f32::consts::FRAC_PI_2));
        bones.apply_physics_transforms(&[(0, Mat4::IDENTITY), (1, parent)], &HashSet::from([0]));
        assert!(bones
            .get_global_transform(0)
            .w_axis
            .truncate()
            .abs_diff_eq(-Vec3::X, 1e-5));
    }
}
