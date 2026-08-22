//! PMX 头发物理参数规范化与约束保护。
//!
//! 提供头发各部位（后发、前发、侧发）的高精度识别，后发根部防向前翻折穿胸的几何约束，
//! 以及长发多节链条的累计角度预算控制与弹簧回正兜底。

use glam::{Quat, Vec3};
use mmd::pmx::rigid_body::{RigidBody as PmxRigidBody, RigidBodyMode};

use super::joint_parameters::JointParameters;
use super::mmd_rigid_body::is_tail_dynamic_part;

/// 宽松零弹簧头发根关节的判定阈值（45°）。
const WIDE_HAIR_ROOT_ANGLE: f32 = std::f32::consts::FRAC_PI_4;
/// 后发根部兜底旋转范围（±20°）。
const HAIR_ROOT_FALLBACK_ANGLE: f32 = 20.0_f32.to_radians();
/// 后发根部兜底旋转刚度（X: 俯仰, Y: 偏航, Z: 侧滚）。
const HAIR_ROOT_FALLBACK_STIFFNESS: [f32; 3] = [16.0, 10.0, 14.0];

/// 宽松零弹簧头发子链关节的判定阈值（30°）。
const WIDE_HAIR_CHAIN_ANGLE: f32 = 30.0_f32.to_radians();
/// 长发链单节兜底旋转范围（±14°）。
const HAIR_CHAIN_FALLBACK_ANGLE: f32 = 14.0_f32.to_radians();
/// 长发链单节兜底弹簧刚度。
const HAIR_CHAIN_FALLBACK_STIFFNESS: f32 = 10.0;

/// 判断刚体是否属于头发部件（支持日文、繁体中文、简体中文和英文命名）。
pub fn is_hair_body(body: &PmxRigidBody) -> bool {
    const HAIR_NAMES: &[&str] = &[
        // 日文
        "髪",
        "ツインテール",
        "ポニーテール",
        "おさげ",
        "アホ毛",
        // 繁体中文
        "髮",
        "馬尾",
        "雙馬尾",
        "辮",
        "長髮",
        "頭髮",
        "髮飾",
        // 简体中文
        "发",
        "马尾",
        "双马尾",
        "辫",
        "长发",
        "头发",
        "呆毛",
        // 英文与通用
        "hair",
        "twintail",
        "ponytail",
        "braid",
        "bangs",
        "ahoge",
        "毛",
    ];
    // 尾巴具有独立动态链，必须排除在头发分类之外
    if is_tail_dynamic_part(body) {
        return false;
    }
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    HAIR_NAMES
        .iter()
        .any(|part| local.contains(part) || universal.contains(part))
}

/// 判断刚体是否属于后发（背部披发、马尾、长发辫等）。
pub fn is_back_hair_body(body: &PmxRigidBody) -> bool {
    if !is_hair_body(body) {
        return false;
    }
    // 排除明确属于前发或侧发的部件
    if is_front_hair_body(body) || is_side_hair_body(body) {
        return false;
    }
    const BACK_HAIR_NAMES: &[&str] = &[
        // 日文
        "後髪",
        "後ろ",
        "うしろ",
        "ポニーテール",
        "ツインテール",
        "テール",
        // 繁体中文
        "後髮",
        "后髮",
        "馬尾",
        "雙馬尾",
        "長髮",
        // 简体中文
        "后发",
        "马尾",
        "双马尾",
        "长发",
        // 英文与通用
        "back",
        "rear",
        "ponytail",
        "twintail",
        "tail_hair",
        "hair_back",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    BACK_HAIR_NAMES
        .iter()
        .any(|part| local.contains(part) || universal.contains(part))
        || (!local.contains("前")
            && !local.contains("front")
            && !local.contains("横")
            && !local.contains("側")
            && !local.contains("侧")
            && !local.contains("side"))
}

/// 判断刚体是否属于前发（刘海）。
pub fn is_front_hair_body(body: &PmxRigidBody) -> bool {
    const FRONT_HAIR_NAMES: &[&str] = &[
        "前髪",
        "前髮",
        "前发",
        "front",
        "bangs",
        "まえがみ",
        "hair_front",
        "fronthair",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    FRONT_HAIR_NAMES
        .iter()
        .any(|part| local.contains(part) || universal.contains(part))
}

/// 判断刚体是否属于侧发（鬓角、麻花辫）。
pub fn is_side_hair_body(body: &PmxRigidBody) -> bool {
    const SIDE_HAIR_NAMES: &[&str] = &[
        "横髪",
        "横髮",
        "横发",
        "側髮",
        "侧发",
        "サイド",
        "side",
        "おさげ",
        "braid",
        "よこがみ",
        "hair_side",
        "sidehair",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    SIDE_HAIR_NAMES
        .iter()
        .any(|part| local.contains(part) || universal.contains(part))
}

/// 判断刚体是否属于头部或上半身跟骨碰撞体。
pub fn is_head_or_upper_body_collider(body: &PmxRigidBody) -> bool {
    const UPPER_NAMES: &[&str] = &[
        "頭",
        "头",
        "head",
        "首",
        "neck",
        "上半身",
        "chest",
        "bust",
        "胸",
        "肩",
        "shoulder",
        "spine",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    UPPER_NAMES
        .iter()
        .any(|part| local.contains(part) || universal.contains(part))
}

/// 提取一对刚体中的（静态跟骨刚体, 动态后发刚体, 静态刚体世界位置, 动态刚体世界位置）。
fn resolve_static_and_dynamic_back_hair<'a>(
    body_a: &'a PmxRigidBody,
    body_b: &'a PmxRigidBody,
    pos_a: Vec3,
    pos_b: Vec3,
) -> Option<(&'a PmxRigidBody, &'a PmxRigidBody, Vec3, Vec3)> {
    if body_a.mode == RigidBodyMode::Static
        && body_b.mode != RigidBodyMode::Static
        && is_back_hair_body(body_b)
    {
        Some((body_a, body_b, pos_a, pos_b))
    } else if body_b.mode == RigidBodyMode::Static
        && body_a.mode != RigidBodyMode::Static
        && is_back_hair_body(body_a)
    {
        Some((body_b, body_a, pos_b, pos_a))
    } else {
        None
    }
}

/// 为后发根部关节施加防向前穿胸的偏向几何约束。
///
/// 支持 (Static, Dynamic) 与 (Dynamic, Static) 双向刚体定义。
pub fn apply_back_hair_root_limit(
    parameters: &mut JointParameters,
    body_a: &PmxRigidBody,
    body_b: &PmxRigidBody,
    joint_position: Vec3,
    joint_rotation: Quat,
    pos_b: Vec3,
    pos_a: Vec3,
) {
    let Some((_static_body, _dynamic_body, static_pos, dynamic_pos)) =
        resolve_static_and_dynamic_back_hair(body_a, body_b, pos_a, pos_b)
    else {
        return;
    };

    let center_offset = dynamic_pos - joint_position;
    if center_offset.length_squared() <= 1e-6 {
        return;
    }

    // 从父级头部/跟骨位置指向后发质心的水平向量作为向后辐射方向
    let radial_offset = dynamic_pos - static_pos;
    let horizontal_radial = Vec3::new(radial_offset.x, 0.0, radial_offset.z);
    let outward = if horizontal_radial.length_squared() > 1e-6 {
        horizontal_radial.normalize()
    } else {
        let joint_horizontal = Vec3::new(center_offset.x, 0.0, center_offset.z);
        if joint_horizontal.length_squared() > 1e-6 {
            joint_horizontal.normalize()
        } else {
            return;
        }
    };

    // 评估关节局部 X 轴（Pitch）与 Z 轴（Roll）正向旋转对向外/向后位移的贡献得分
    let axis_x = joint_rotation * Vec3::X;
    let axis_z = joint_rotation * Vec3::Z;

    let score_x = axis_x.cross(center_offset).dot(outward);
    let score_z = axis_z.cross(center_offset).dot(outward);

    if score_x.abs() > 1e-4 {
        parameters.angular[0].restrict_inward_rotation(score_x > 0.0);
    }

    if score_z.abs() > 1e-4 {
        parameters.angular[2].restrict_inward_rotation(score_z > 0.0);
    }
}

/// 为未配置旋转弹簧且旋转范围过宽的后发根部关节施加兜底弹簧和安全限位。
///
/// 支持刚体 A/B 双向顺序。
pub fn apply_wide_back_hair_root_fallback(
    parameters: &mut JointParameters,
    body_a: &PmxRigidBody,
    body_b: &PmxRigidBody,
) -> bool {
    let is_static_dynamic = (body_a.mode == RigidBodyMode::Static
        && body_b.mode != RigidBodyMode::Static
        && is_back_hair_body(body_b))
        || (body_b.mode == RigidBodyMode::Static
            && body_a.mode != RigidBodyMode::Static
            && is_back_hair_body(body_a));

    if !is_static_dynamic || parameters.angular.iter().any(|axis| axis.spring_enabled) {
        return false;
    }

    // 检查是否有旋转轴范围明显过宽（>= 45°）
    let has_wide_axis = parameters
        .angular
        .iter()
        .any(|axis| axis.lower <= axis.upper && axis.upper - axis.lower >= WIDE_HAIR_ROOT_ANGLE);
    if !has_wide_axis {
        return false;
    }

    for (axis, stiffness) in parameters
        .angular
        .iter_mut()
        .zip(HAIR_ROOT_FALLBACK_STIFFNESS)
    {
        axis.clamp_symmetric_rotation(HAIR_ROOT_FALLBACK_ANGLE);
        axis.install_fallback_spring(stiffness);
    }

    true
}

/// 为长发链条中的动态子关节提供单节角度预算与回正弹簧兜底。
pub fn apply_hair_chain_fallback(
    parameters: &mut JointParameters,
    body_a: &PmxRigidBody,
    body_b: &PmxRigidBody,
) -> bool {
    if body_a.mode == RigidBodyMode::Static
        || body_b.mode == RigidBodyMode::Static
        || !is_hair_body(body_a)
        || !is_hair_body(body_b)
        || parameters.angular.iter().any(|axis| axis.spring_enabled)
    {
        return false;
    }

    let has_wide_axis = parameters
        .angular
        .iter()
        .any(|axis| axis.lower <= axis.upper && axis.upper - axis.lower >= WIDE_HAIR_CHAIN_ANGLE);
    if !has_wide_axis {
        return false;
    }

    for axis in &mut parameters.angular {
        if axis.lower <= axis.upper && axis.upper - axis.lower > 1e-6 {
            axis.clamp_symmetric_rotation(HAIR_CHAIN_FALLBACK_ANGLE);
            axis.install_fallback_spring(HAIR_CHAIN_FALLBACK_STIFFNESS);
        }
    }

    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmd::pmx::rigid_body::RigidBodyShape;

    fn test_pmx_body(name: &str, mode: RigidBodyMode, position: [f32; 3]) -> PmxRigidBody {
        PmxRigidBody {
            local_name: name.to_owned(),
            universal_name: String::new(),
            bone_index: -1,
            group: 0,
            un_collision_group_flag: 0,
            shape: RigidBodyShape::Sphere,
            size: [0.5; 3],
            position,
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
    fn hair_parts_are_accurately_recognized_across_locales() {
        // 日文
        let back_hair_jp = test_pmx_body("後髪_01", RigidBodyMode::Dynamic, [0.0, 10.0, -1.0]);
        let front_hair_jp = test_pmx_body("前髪_01", RigidBodyMode::Dynamic, [0.0, 10.0, 1.0]);
        let side_hair_jp = test_pmx_body("横髪_L", RigidBodyMode::Dynamic, [1.0, 10.0, 0.0]);
        let ponytail_jp = test_pmx_body("ポニーテール1", RigidBodyMode::Dynamic, [0.0, 12.0, -2.0]);

        // 繁体中文
        let back_hair_tc = test_pmx_body("後髮_01", RigidBodyMode::Dynamic, [0.0, 10.0, -1.0]);
        let front_hair_tc = test_pmx_body("前髮_01", RigidBodyMode::Dynamic, [0.0, 10.0, 1.0]);
        let side_hair_tc = test_pmx_body("側髮_L", RigidBodyMode::Dynamic, [1.0, 10.0, 0.0]);
        let ponytail_tc = test_pmx_body("左馬尾1-9", RigidBodyMode::Dynamic, [0.0, 12.0, -2.0]);
        let hair_deco_tc = test_pmx_body("左髮飾3", RigidBodyMode::Dynamic, [0.0, 12.0, -2.0]);

        // 简体中文
        let back_hair_sc = test_pmx_body("后发_01", RigidBodyMode::Dynamic, [0.0, 10.0, -1.0]);
        let front_hair_sc = test_pmx_body("前发_01", RigidBodyMode::Dynamic, [0.0, 10.0, 1.0]);

        assert!(is_hair_body(&back_hair_jp));
        assert!(is_back_hair_body(&back_hair_jp));
        assert!(!is_front_hair_body(&back_hair_jp));

        assert!(is_hair_body(&front_hair_jp));
        assert!(is_front_hair_body(&front_hair_jp));
        assert!(!is_back_hair_body(&front_hair_jp));

        assert!(is_hair_body(&side_hair_jp));
        assert!(is_side_hair_body(&side_hair_jp));
        assert!(is_hair_body(&ponytail_jp));

        // 繁体测试
        assert!(is_hair_body(&back_hair_tc));
        assert!(is_back_hair_body(&back_hair_tc));
        assert!(is_hair_body(&front_hair_tc));
        assert!(is_front_hair_body(&front_hair_tc));
        assert!(is_hair_body(&side_hair_tc));
        assert!(is_side_hair_body(&side_hair_tc));
        assert!(is_hair_body(&ponytail_tc));
        assert!(is_hair_body(&hair_deco_tc));

        // 简体测试
        assert!(is_hair_body(&back_hair_sc));
        assert!(is_back_hair_body(&back_hair_sc));
        assert!(is_hair_body(&front_hair_sc));
        assert!(is_front_hair_body(&front_hair_sc));
    }

    #[test]
    fn wide_zero_spring_back_hair_root_supports_reversed_rigid_body_order() {
        let head = test_pmx_body("頭", RigidBodyMode::Static, [0.0, 10.0, 0.0]);
        let back_hair = test_pmx_body("後髮_00", RigidBodyMode::Dynamic, [0.0, 9.0, -1.5]);

        let wide_angle = 60.0_f32.to_radians();
        let mut params = JointParameters::from_pmx(
            [0.0; 3],
            [0.0; 3],
            [-wide_angle; 3],
            [wide_angle; 3],
            [0.0; 3],
            [0.0; 3],
        );

        // 测试 A 为 Dynamic 后发, B 为 Static 头部
        let clamped = apply_wide_back_hair_root_fallback(&mut params, &back_hair, &head);
        assert!(clamped, "反向刚体顺序应能正常触发兜底");

        for (axis, expected_stiffness) in params.angular.iter().zip(HAIR_ROOT_FALLBACK_STIFFNESS) {
            assert!(axis.spring_enabled);
            assert!((axis.stiffness - expected_stiffness).abs() < 1e-5);
            assert!((axis.lower - (-HAIR_ROOT_FALLBACK_ANGLE)).abs() < 1e-5);
            assert!((axis.upper - HAIR_ROOT_FALLBACK_ANGLE).abs() < 1e-5);
        }
    }
}
