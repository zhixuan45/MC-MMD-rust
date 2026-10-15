//! MMD 关节（约束）封装
//!
//! 移植自 babylon-mmd 的关节构建逻辑。
//! 使用 Bullet3 btGeneric6DofSpringConstraint。

use glam::{Mat4, Quat, Vec3};

use mmd::pmx::joint::Joint as PmxJoint;
use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyMode};

use super::bullet_ffi::{BulletConstraint, BulletRigidBody, BT_CONSTRAINT_STOP_ERP};
use super::hair_parameters::{
    apply_back_hair_root_limit, apply_hair_chain_fallback, apply_wide_back_hair_root_fallback,
};
use super::joint_parameters::JointParameters;
use super::mmd_rigid_body::{is_tail_dynamic_part, mmd_physics_rotation};

/// 沿用既有 MMD Bullet 实现的限位纠偏比例，避免混入未经验证的参数实验。
const JOINT_STOP_ERP: f32 = 0.475;
/// 宽松零弹簧裙根的兼容阈值；正常模型通常远小于该范围并自带恢复弹簧。
const WIDE_SKIRT_ROOT_ANGLE: f32 = std::f32::consts::FRAC_PI_3;
/// Rin 一类异常裙根直接采用与正常模型接近的 ±16° 旋转范围。
const SKIRT_ROOT_FALLBACK_ANGLE: f32 = 16.0_f32.to_radians();
/// 低于常见优质模型的裙根刚度，既抵抗静止下垂，又尽量保留摆动空间。
const SKIRT_ROOT_FALLBACK_STIFFNESS: [f32; 3] = [16.0, 6.0, 12.0];
/// 细密裙链必须按整列限制累计角度，而不能只收紧单个宽限位关节。
/// Rin 的九层裙链采用 16° + 8° + 7 * 5°，整列最大偏转约为 59°。
const SKIRT_CHAIN_FIRST_ANGLE: f32 = 8.0_f32.to_radians();
const SKIRT_CHAIN_LATER_ANGLE: f32 = 5.0_f32.to_radians();
const SKIRT_CHAIN_FIRST_STIFFNESS: f32 = 12.0;
const SKIRT_CHAIN_LATER_STIFFNESS: f32 = 10.0;

/// MMD 关节数据
pub struct MmdJointData {
    /// 关节名称
    pub name: String,
    /// 刚体 A 索引
    pub rigid_body_a_index: i32,
    /// 刚体 B 索引
    pub rigid_body_b_index: i32,
    /// 关节在刚体 A 局部空间中的 frame，供运行时诊断锚点误差。
    pub frame_a: Mat4,
    /// 关节在刚体 B 局部空间中的 frame，供运行时诊断锚点误差。
    pub frame_b: Mat4,
    /// Bullet3 约束
    pub constraint: Option<BulletConstraint>,
}

impl MmdJointData {
    /// 从 PMX 关节数据创建 Bullet3 6DOF 弹簧约束
    ///
    /// 移植自 babylon-mmd buildPhysics() 关节部分。
    /// 欧拉角使用 XYZ intrinsic（等价 saba btMatrix3x3::setEulerZYX）。
    pub fn from_pmx(
        pmx_joint: &PmxJoint,
        rb_a: &BulletRigidBody,
        rb_b: &BulletRigidBody,
        pmx_rb_a: &PmxRigidBody,
        pmx_rb_b: &PmxRigidBody,
        rb_a_initial_transform: Mat4,
        rb_b_initial_transform: Mat4,
    ) -> Self {
        let position = Vec3::new(
            pmx_joint.position[0],
            pmx_joint.position[1],
            pmx_joint.position[2],
        );

        // 关节沿用 Bullet setEulerZYX 对应的 X-Y-Z intrinsic 组合。
        let rotation = joint_rotation(pmx_joint.rotation);

        let joint_transform = Mat4::from_rotation_translation(rotation, position);
        let mut parameters = JointParameters::from_pmx(
            pmx_joint.position_min,
            pmx_joint.position_max,
            pmx_joint.rotation_min,
            pmx_joint.rotation_max,
            pmx_joint.position_spring,
            pmx_joint.rotation_spring,
        );
        let _clamped_wide_root =
            apply_wide_skirt_root_fallback(&mut parameters, pmx_rb_a, pmx_rb_b);
        apply_wide_skirt_chain_fallback(&mut parameters, pmx_rb_a, pmx_rb_b);

        // 默认保留作者头发约束；额外单向限位会在手臂接触时形成卡楔。
        let hair_joint_control = std::env::var("MMD_HAIR_JOINT_COMPAT").unwrap_or_default();
        apply_hair_joint_control(
            &mut parameters,
            pmx_rb_a,
            pmx_rb_b,
            position,
            rotation,
            rb_a_initial_transform,
            rb_b_initial_transform,
            &hair_joint_control,
        );

        // frameA = rbA_initialTransform.inverse() * jointTransform
        // frameB = rbB_initialTransform.inverse() * jointTransform
        let frame_a = rb_a_initial_transform.inverse() * joint_transform;
        let frame_b = rb_b_initial_transform.inverse() * joint_transform;

        if pmx_joint.local_name.starts_with("Synthesized_Skirt_Cross_")
            && super::config::get_config().debug_log
        {
            let (error, anchor_a, anchor_b) = joint_anchor_position_error(
                rb_a_initial_transform,
                frame_a,
                rb_b_initial_transform,
                frame_b,
            );
            log::info!("[Bullet3][SKIRT_CROSS_CREATED] phase=pmx_bind_before_runtime_pose space=bullet_left_model joint={:?} a={} name_a={:?} bone_a={} mode_a={:?} group_a={} display_group_a={} mask_a=0x{:04X} b={} name_b={:?} bone_b={} mode_b={:?} group_b={} display_group_b={} mask_b=0x{:04X} position_a={:?} position_b={:?} local_a={:?} local_b={:?} anchor_a={:?} anchor_b={:?} anchor_error={} material=unavailable_not_body_owned",
                pmx_joint.local_name, pmx_joint.rigid_body_a_index, pmx_rb_a.local_name, pmx_rb_a.bone_index, pmx_rb_a.mode, pmx_rb_a.group, pmx_rb_a.group as u16 + 1, pmx_rb_a.un_collision_group_flag,
                pmx_joint.rigid_body_b_index, pmx_rb_b.local_name, pmx_rb_b.bone_index, pmx_rb_b.mode, pmx_rb_b.group, pmx_rb_b.group as u16 + 1, pmx_rb_b.un_collision_group_flag,
                pmx_rb_a.position, pmx_rb_b.position, frame_a.w_axis.truncate(), frame_b.w_axis.truncate(), anchor_a, anchor_b, error);
        }

        // 创建 Bullet3 6DOF 弹簧约束
        let constraint = BulletConstraint::new_6dof_spring(rb_a, rb_b, frame_a, frame_b, true);

        // 配置约束参数（仅在创建成功时）
        if let Some(ref c) = constraint {
            for axis in 0..6 {
                c.set_param(BT_CONSTRAINT_STOP_ERP, JOINT_STOP_ERP, axis);
            }

            c.set_linear_lower_limit(
                parameters.linear[0].lower,
                parameters.linear[1].lower,
                parameters.linear[2].lower,
            );
            c.set_linear_upper_limit(
                parameters.linear[0].upper,
                parameters.linear[1].upper,
                parameters.linear[2].upper,
            );
            c.set_angular_lower_limit(
                parameters.angular[0].lower,
                parameters.angular[1].lower,
                parameters.angular[2].lower,
            );
            c.set_angular_upper_limit(
                parameters.angular[0].upper,
                parameters.angular[1].upper,
                parameters.angular[2].upper,
            );

            for (axis, axis_parameters) in parameters
                .linear
                .iter()
                .chain(parameters.angular.iter())
                .enumerate()
            {
                if axis_parameters.spring_enabled {
                    c.set_stiffness(axis as i32, axis_parameters.stiffness);
                }
                c.enable_spring(axis as i32, axis_parameters.spring_enabled);
            }

            // 构建阶段先建立默认平衡点；运行姿态提交后会再次刷新，避免首帧弹簧预载。
            c.set_equilibrium_point();
        }

        Self {
            name: pmx_joint.local_name.clone(),
            rigid_body_a_index: pmx_joint.rigid_body_a_index,
            rigid_body_b_index: pmx_joint.rigid_body_b_index,
            frame_a,
            frame_b,
            constraint,
        }
    }

    /// 将当前两端刚体的相对姿态设为弹簧零点。
    ///
    /// 约束先于运行姿态初始化创建，因此刚体重置后必须重新取样 equilibrium；
    /// 否则弹簧会把无碰撞、锚点重合的初态主动拉回旧绑定姿态。
    pub(crate) fn rebase_equilibrium(&self) {
        if let Some(constraint) = self.constraint.as_ref() {
            constraint.set_equilibrium_point();
        }
    }
}

/// 仅在显式对照时应用旧头发兜底，不改默认作者参数。
fn apply_hair_joint_control(
    parameters: &mut JointParameters,
    body_a: &PmxRigidBody,
    body_b: &PmxRigidBody,
    position: Vec3,
    rotation: Quat,
    bind_a: Mat4,
    bind_b: Mat4,
    control: &str,
) {
    // legacy 只作同输入 A/B；两个分项保留用于定位，不写入用户配置。
    let legacy_root = matches!(control, "legacy" | "authored-chain");
    let legacy_chain = matches!(control, "legacy" | "authored-root");
    let clamped_root =
        legacy_root && apply_wide_back_hair_root_fallback(parameters, body_a, body_b);
    if legacy_chain {
        apply_hair_chain_fallback(parameters, body_a, body_b);
    }
    if legacy_root && !clamped_root {
        apply_back_hair_root_limit(
            parameters,
            body_a,
            body_b,
            position,
            rotation,
            bind_b.w_axis.truncate(),
            bind_a.w_axis.truncate(),
        );
    }
}

/// 将 PMX 关节欧拉角转换为 Bullet setEulerZYX 等价旋转。
fn joint_rotation(rotation: [f32; 3]) -> Quat {
    // 关节使用 Bullet Z-Y-X；不能套用刚体的 Y-X-Z 顺序。
    mmd_physics_rotation(rotation)
}

fn apply_wide_skirt_root_fallback(
    parameters: &mut JointParameters,
    body_a: &PmxRigidBody,
    body_b: &PmxRigidBody,
) -> bool {
    if body_a.mode != RigidBodyMode::Static
        || body_b.mode == RigidBodyMode::Static
        // Only the explicitly named legacy dynamic grid uses this fallback.
        || skirt_grid_position(body_b).is_none()
        || parameters.angular.iter().any(|axis| axis.spring_enabled)
    {
        return false;
    }

    // Rin 一类模型会给裙根约 ±90° 的范围，却完全不提供旋转弹簧。
    // 只在至少一个有效轴明显过宽时兜底，避免修改正常的柔性零弹簧部件。
    let has_wide_axis = parameters
        .angular
        .iter()
        .any(|axis| axis.lower <= axis.upper && axis.upper - axis.lower >= WIDE_SKIRT_ROOT_ANGLE);
    if !has_wide_axis {
        return false;
    }

    for (axis, stiffness) in parameters
        .angular
        .iter_mut()
        .zip(SKIRT_ROOT_FALLBACK_STIFFNESS)
    {
        axis.clamp_symmetric_rotation(SKIRT_ROOT_FALLBACK_ANGLE);
        axis.install_fallback_spring(stiffness);
    }

    true
}

fn apply_wide_skirt_chain_fallback(
    parameters: &mut JointParameters,
    body_a: &PmxRigidBody,
    body_b: &PmxRigidBody,
) -> bool {
    if body_a.mode == RigidBodyMode::Static
        || body_b.mode == RigidBodyMode::Static
        || parameters.angular.iter().any(|axis| axis.spring_enabled)
    {
        return false;
    }

    let Some((parent_row, parent_column)) = skirt_grid_position(body_a) else {
        return false;
    };
    let Some((child_row, child_column)) = skirt_grid_position(body_b) else {
        return false;
    };

    // 仅处理同列向下的纵向裙链；横向裙环和装饰物关节保留 PMX 原始参数。
    if child_row != parent_row + 1 || child_column != parent_column {
        return false;
    }

    let angle = if child_row == 1 {
        SKIRT_CHAIN_FIRST_ANGLE
    } else {
        SKIRT_CHAIN_LATER_ANGLE
    };
    let stiffness = if child_row == 1 {
        SKIRT_CHAIN_FIRST_STIFFNESS
    } else {
        SKIRT_CHAIN_LATER_STIFFNESS
    };

    // Rin 的纵向主轴为 X。这里按整条零弹簧链分配累计角度预算；即使后段
    // 单节原始范围不足 60°，也必须参与预算，否则九节叠加后仍可整体翻折。
    let axis = &mut parameters.angular[0];
    if axis.lower > axis.upper || axis.upper - axis.lower <= 1e-6 {
        return false;
    }
    axis.clamp_symmetric_rotation(angle);
    axis.install_fallback_spring(stiffness);
    true
}

fn skirt_grid_position(body: &PmxRigidBody) -> Option<(u32, u32)> {
    // This parser authorizes a specific legacy workaround, not garment
    // classification. Exporter aliases and mode-2 cloth keep authored limits.
    if body.mode != RigidBodyMode::Dynamic || !is_skirt_body(body) {
        return None;
    }
    let mut parts = body.local_name.rsplit('_');
    let column = parts.next()?.parse().ok()?;
    let row = parts.next()?.parse().ok()?;
    let prefix = parts.next()?;
    (parts.next().is_none() && prefix.eq_ignore_ascii_case("skirt")).then_some((row, column))
}

fn is_skirt_body(body: &PmxRigidBody) -> bool {
    const PART_NAMES: &[&str] = &[
        "裙",
        "スカート",
        "skirt",
        "petticoat",
        "下装",
        "下衣",
        "裾",
        "摆",
        "衣摆",
        "衣擺",
        "后摆",
        "後擺",
        "下摆",
        "下擺",
        "下襬",
        "风衣",
        "外套",
        "コート",
        "coat",
        "cloak",
        "cape",
        "燕尾",
        "flap",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    // 尾巴具有独立动态链与旋转空间，不得作为裙摆应用单向内翻锁零或裙根硬弹簧
    if is_tail_dynamic_part(body) {
        return false;
    }
    PART_NAMES
        .iter()
        .any(|part| local.contains(part) || universal.contains(part))
}

/// 计算两个刚体上的关节锚点在 Bullet 世界空间中的距离。
pub(crate) fn joint_anchor_position_error(
    body_a_transform: Mat4,
    frame_a: Mat4,
    body_b_transform: Mat4,
    frame_b: Mat4,
) -> (f32, Vec3, Vec3) {
    let anchor_a = (body_a_transform * frame_a).w_axis.truncate();
    let anchor_b = (body_b_transform * frame_b).w_axis.truncate();
    (anchor_a.distance(anchor_b), anchor_a, anchor_b)
}

#[cfg(test)]
mod tests {
    use super::{
        apply_wide_skirt_chain_fallback, apply_wide_skirt_root_fallback,
        joint_anchor_position_error, joint_rotation, MmdJointData, JOINT_STOP_ERP,
    };
    use crate::physics::bullet_ffi::{BulletRigidBody, BulletShape, RigidBodyInfo};
    use glam::{Mat4, Quat, Vec3};
    use mmd::pmx::{
        joint::{Joint, JointType},
        rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape},
    };

    fn test_body(shape: &BulletShape) -> BulletRigidBody {
        BulletRigidBody::new(
            &RigidBodyInfo {
                mass: 1.0,
                linear_damping: 0.0,
                angular_damping: 0.0,
                friction: 0.5,
                restitution: 0.0,
                additional_damping: false,
                is_kinematic: false,
                disable_deactivation: true,
                no_contact_response: false,
                initial_transform: Mat4::IDENTITY,
            },
            shape,
        )
        .expect("应能创建关节测试刚体")
    }

    fn test_pmx_body(name: &str, mode: RigidBodyMode) -> RigidBody {
        RigidBody {
            local_name: name.to_owned(),
            universal_name: String::new(),
            bone_index: -1,
            group: 0,
            un_collision_group_flag: 0,
            shape: RigidBodyShape::Box,
            size: [0.5; 3],
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
    fn authored_skirt_root_springs_are_preserved() {
        let anchor = test_pmx_body("下半身", RigidBodyMode::Static);
        let skirt = test_pmx_body("裙_0_0", RigidBodyMode::Dynamic);
        let mut parameters = crate::physics::joint_parameters::JointParameters::from_pmx(
            [0.0; 3],
            [0.0; 3],
            [-0.28, -0.1, -0.2],
            [0.28, 0.1, 0.2],
            [0.0; 3],
            [26.0, 10.0, 20.8],
        );

        assert!(!apply_wide_skirt_root_fallback(
            &mut parameters,
            &anchor,
            &skirt
        ));

        assert_eq!(parameters.angular[0].stiffness, 26.0);
        assert_eq!(parameters.angular[1].stiffness, 10.0);
        assert_eq!(parameters.angular[2].stiffness, 20.8);
    }
}

#[cfg(test)]
#[path = "skirt_joint_tests.rs"]
mod skirt_joint_tests;

#[cfg(test)]
#[path = "hair_joint_tests.rs"]
mod hair_joint_tests;
