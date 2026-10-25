//! 裙摆横向环形保形弹簧网络（Cross-Joints / Ring Constraints）自动合成。
//!
//! 在部分 PMX 资产（如游戏导出模型、长裙角色）中，裙摆通常仅包含上下纵向关节（Vertical Joints），
//! 缺乏横向相邻裙片之间的连接约束。在重力或大幅奔跑动作下，各纵向单链独立摆动容易出现：
//! 1. 侧后裙片因缺乏环向拉力而向内塌陷凹折（畸变为尖锐橄榄形）；
//! 2. 前侧（FL/FR）长裙在非水平下摆模型中因高低差被误切断环状网络导致割裂凹陷；
//! 3. 大幅步态下裙摆被直接拉扯撕开。
//!
//! 该模块基于纵向关节拓扑追溯裙片链条（Chains），在各深度层级提取完整的方位角闭环，
//! 补齐 6DOF 弹性环向连接，形成稳固的网格保形结构。

use glam::{EulerRot, Mat4, Quat, Vec3};
use mmd::pmx::joint::{Joint as PmxJoint, JointType};
use mmd::pmx::rigid_body::RigidBody as PmxRigidBody;
#[cfg(all(test, feature = "legacy-physics"))]
use mmd::pmx::rigid_body::RigidBodyMode;

#[cfg(all(test, feature = "legacy-physics"))]
use super::mmd_rigid_body::is_skirt_or_lower_garment;

/// 裙摆横向连接允许的相对位移容差（单位：MMD 局部单位）。
pub const SKIRT_CROSS_LINEAR_LIMIT: [f32; 3] = [-0.06, -0.06, -0.06];
pub const SKIRT_CROSS_LINEAR_LIMIT_MAX: [f32; 3] = [0.06, 0.06, 0.06];

/// 裙摆横向连接允许的相对角度偏转容差（弧度，约 ±8.6°）。
pub const SKIRT_CROSS_ANGULAR_LIMIT: [f32; 3] = [-0.15, -0.15, -0.15];
pub const SKIRT_CROSS_ANGULAR_LIMIT_MAX: [f32; 3] = [0.15, 0.15, 0.15];

/// 裙摆横向位移弹簧刚度。
pub const SKIRT_CROSS_LINEAR_STIFFNESS: [f32; 3] = [35.0, 35.0, 35.0];

/// 裙摆横向旋转保形弹簧刚度。
pub const SKIRT_CROSS_ANGULAR_STIFFNESS: [f32; 3] = [18.0, 18.0, 18.0];

/// 为缺少横向环形约束的裙摆自动合成水平保形 6DOF 弹簧关节。
#[cfg(all(test, feature = "legacy-physics"))]
pub fn synthesize_missing_skirt_cross_joints(
    rigid_bodies: &[PmxRigidBody],
    existing_joints: &[PmxJoint],
) -> Vec<PmxJoint> {
    synthesize_missing_skirt_cross_joints_with_bones(rigid_bodies, existing_joints, &[], &[])
}

/// 骨骼层级用于区分纵向链与服装组件；原生 PMX 关节保持原样。
pub fn synthesize_missing_skirt_cross_joints_with_bones(
    rigid_bodies: &[PmxRigidBody],
    existing_joints: &[PmxJoint],
    bone_names: &[String],
    bone_parents: &[i32],
) -> Vec<PmxJoint> {
    // 仅供 A/B 取证；不改原生关节、碰撞、重力与动画。
    if std::env::var("MMD_SKIRT_CROSS_SYNTHESIS").is_ok_and(|value| value == "off") {
        return Vec::new();
    }
    super::skirt_cross_topology::grouped_chains(
        rigid_bodies,
        existing_joints,
        bone_names,
        bone_parents,
    )
    .into_iter()
    .flat_map(|chains| synthesize_group(rigid_bodies, existing_joints, &chains))
    .collect()
}

fn synthesize_group(
    rigid_bodies: &[PmxRigidBody],
    existing_joints: &[PmxJoint],
    chains: &[Vec<usize>],
) -> Vec<PmxJoint> {
    if chains.len() < 3 {
        return Vec::new();
    }

    // 4. 按拓扑深度提取各层环状刚体集合（确保长裙、前短后长裙等每条链均完整参与对应层级）
    let max_depth = chains.iter().map(|c| c.len()).max().unwrap_or(0);
    let mut layers: Vec<Vec<usize>> = Vec::new();
    for depth in 0..max_depth {
        let mut layer_nodes: Vec<usize> = Vec::new();
        for chain in chains {
            if depth < chain.len() {
                layer_nodes.push(chain[depth]);
            }
        }
        if layer_nodes.len() >= 3 {
            layers.push(layer_nodes);
        }
    }

    // 5. 对每一层计算几何中心、按方位角顺时针排序，并为相邻刚体对生成 6DOF 约束
    let mut synthesized = Vec::new();
    for (l_idx, layer) in layers.iter().enumerate() {
        let n = layer.len();
        if n < 3 {
            continue;
        }

        // 计算当前层中心
        let mut center_x = 0.0;
        let mut center_z = 0.0;
        for &idx in layer {
            center_x += rigid_bodies[idx].position[0];
            center_z += rigid_bodies[idx].position[2];
        }
        center_x /= n as f32;
        center_z /= n as f32;

        let mut sorted_layer: Vec<(usize, f32)> = layer
            .iter()
            .map(|&idx| {
                let pos = &rigid_bodies[idx].position;
                let angle = (pos[0] - center_x).atan2(pos[2] - center_z);
                (idx, angle)
            })
            .collect();
        sorted_layer.sort_by(|a, b| a.1.total_cmp(&b.1));

        for i in 0..n {
            let a_idx = sorted_layer[i].0;
            let b_idx = sorted_layer[(i + 1) % n].0;

            // 检查原模型中是否已配置该横向刚体对的关节
            let has_joint = existing_joints.iter().any(|j| {
                (j.rigid_body_a_index == a_idx as i32 && j.rigid_body_b_index == b_idx as i32)
                    || (j.rigid_body_a_index == b_idx as i32
                        && j.rigid_body_b_index == a_idx as i32)
            });

            if !has_joint {
                let pos_a = Vec3::from_array(rigid_bodies[a_idx].position);
                let pos_b = Vec3::from_array(rigid_bodies[b_idx].position);
                let mid_pos = (pos_a + pos_b) * 0.5;
                let chord = pos_b - pos_a;
                if !chord.is_finite()
                    || !chord.length_squared().is_finite()
                    || chord.length_squared() < 1e-4
                {
                    continue;
                }

                // 构造横向弦轴局部坐标系：X 轴指向相邻刚体，Y 轴向上，Z 轴沿外法线方向
                let dir_x = chord.normalize();
                // 弦轴接近竖直时换参考轴，保持三个轴正交。
                let dir_y = if dir_x.dot(Vec3::Y).abs() > 0.99 {
                    Vec3::Z
                } else {
                    Vec3::Y
                };
                let dir_z = dir_x.cross(dir_y).normalize();
                let adjusted_dir_y = dir_z.cross(dir_x).normalize();

                let rot_mat = Mat4::from_cols(
                    dir_x.extend(0.0),
                    adjusted_dir_y.extend(0.0),
                    dir_z.extend(0.0),
                    Vec3::ZERO.extend(1.0),
                );
                let rot_quat = Quat::from_mat4(&rot_mat);
                let (euler_z, euler_y, euler_x) = rot_quat.to_euler(EulerRot::ZYX);
                let rot_euler = [euler_x, euler_y, euler_z];

                let pmx_joint = PmxJoint {
                    local_name: format!("Synthesized_Skirt_Cross_L{}_{}_{}", l_idx, a_idx, b_idx),
                    universal_name: format!(
                        "Synthesized_Skirt_Cross_L{}_{}_{}",
                        l_idx, a_idx, b_idx
                    ),
                    type_: JointType::Spring6DOF,
                    rigid_body_a_index: a_idx as i32,
                    rigid_body_b_index: b_idx as i32,
                    position: mid_pos.to_array(),
                    rotation: rot_euler,
                    position_min: SKIRT_CROSS_LINEAR_LIMIT,
                    position_max: SKIRT_CROSS_LINEAR_LIMIT_MAX,
                    rotation_min: SKIRT_CROSS_ANGULAR_LIMIT,
                    rotation_max: SKIRT_CROSS_ANGULAR_LIMIT_MAX,
                    position_spring: SKIRT_CROSS_LINEAR_STIFFNESS,
                    rotation_spring: SKIRT_CROSS_ANGULAR_STIFFNESS,
                };
                synthesized.push(pmx_joint);
            }
        }
    }

    synthesized
}

#[cfg(all(test, feature = "legacy-physics"))]
#[path = "skirt_cross_joints_tests.rs"]
mod tests;

#[cfg(all(test, feature = "legacy-physics"))]
#[path = "skirt_cross_joints_topology_tests.rs"]
mod topology_tests;

#[cfg(all(test, feature = "legacy-physics"))]
#[path = "skirt_cross_joints_bones_tests.rs"]
mod bones_tests;

#[cfg(all(test, feature = "legacy-physics"))]
#[path = "skirt_cross_joint_anchor_tests.rs"]
mod anchor_tests;
