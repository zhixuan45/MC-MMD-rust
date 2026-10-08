//! MMD 运行时模型

use crate::animation::{AnimationLayerManager, VmdAnimation};
use crate::model::hand_attachment::find_hand_attachment;
use crate::model::tacz_arm_targets::{
    apply_tacz_arm_targets, TaczArmApplyOutcome, TaczArmSolverCache, TaczArmTargets,
};
use crate::morph::MorphManager;
use crate::physics::MMDPhysics;
use crate::skeleton::BoneManager;
use crate::vr::{VrDebugState, VrIkSolver, VrTrackingFrame};
use crate::vrm_runtime::{
    pmx_controller_hand_tracking_calibration, resolve_java_tracking_frame_for_model,
    resolve_tracking_frame_for_model, vivecraft_body_tracking_calibration,
    vrm_controller_hand_tracking_calibration, ArmIkCalibration, BodyTrackingCalibration,
    HandTrackingCalibration, TrackedPose, VrmModelRuntimeState, VrmRuntimeInput, VrmRuntimeOutput,
    VrmTrackingInput,
};
use glam::{Mat4, Quat, Vec2, Vec3, Vec4};
use rayon::prelude::*;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::model::first_person_mesh::{
    build_first_person_mesh, refresh_first_person_mesh, FirstPersonMesh,
};
use crate::model::VrmExtensions;
use crate::model::{MmdMaterial, RuntimeVertex, SubMesh, VertexWeight};

mod animation;
mod head_eye;
mod material_visibility;
mod physics;
mod render_data;
mod tail_options;
#[cfg(test)]
mod tests;
mod vr;

thread_local! {
    /// 线程局部 PRNG 状态（xorshift32），避免多线程竞态
    static PRNG_STATE: std::cell::Cell<u32> = std::cell::Cell::new(0);
}

/// 线程安全的伪随机数生成（0.0 - 1.0），使用 xorshift32
fn rand_float() -> f32 {
    PRNG_STATE.with(|cell| {
        let mut s = cell.get();
        if s == 0 {
            s = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .subsec_nanos()
                | 1;
        }
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        cell.set(s);
        (s as f32) / (u32::MAX as f32)
    })
}

#[derive(Clone, Copy, Debug, Default)]
pub struct ModelVrDebugSnapshot {
    pub head_local_model: Vec3,
    pub body_anchor_model: Vec3,
    pub left_palm_target_model: Vec3,
    pub right_palm_target_model: Vec3,
    pub left_auto_wrist_offset_model: Vec3,
    pub right_auto_wrist_offset_model: Vec3,
    pub left_wrist_solved_model: Vec3,
    pub right_wrist_solved_model: Vec3,
    pub left_wrist_error_cm: f32,
    pub right_wrist_error_cm: f32,
}

/// MMD 运行时模型
pub struct MmdModel {
    // 静态数据
    pub name: String,
    pub vertices: Vec<RuntimeVertex>,
    pub indices: Vec<u32>,
    pub weights: Vec<VertexWeight>,
    pub materials: Vec<MmdMaterial>,
    pub submeshes: Vec<SubMesh>,
    pub texture_paths: Vec<String>,
    pub rigid_bodies: Vec<mmd::pmx::rigid_body::RigidBody>,
    pub joints: Vec<mmd::pmx::joint::Joint>,

    // 运行时数据
    pub update_positions: Vec<Vec3>,
    pub update_normals: Vec<Vec3>,
    pub update_uvs: Vec<Vec2>,
    /// JNI/渲染用平铺缓冲区（避免 Vec3/Vec2 内存对齐导致错位）
    pub update_positions_raw: Vec<f32>,
    pub update_normals_raw: Vec<f32>,
    pub update_uvs_raw: Vec<f32>,

    // 子系统
    pub bone_manager: BoneManager,
    pub morph_manager: MorphManager,

    // 动画层系统（支持多轨并行动画）
    animation_layer_manager: AnimationLayerManager,

    // 头部旋转
    head_angle_x: f32,
    head_angle_y: f32,
    head_angle_z: f32,
    head_bone_cached: Option<usize>, // 缓存头部骨骼索引（避免每帧查找）
    head_bone_searched: bool,        // 是否已搜索过头部骨骼

    // 眼球追踪（看向摄像头）
    eye_angle_x: f32,
    eye_angle_y: f32,
    eye_tracking_enabled: bool,
    eye_bone_left: Option<usize>,  // 缓存左眼骨骼索引
    eye_bone_right: Option<usize>, // 缓存右眼骨骼索引
    eye_max_angle: f32,            // 最大眼球角度（弧度）

    // 自动眨眼
    auto_blink_enabled: bool,
    blink_timer: f32,                 // 当前计时器
    blink_interval: f32,              // 眨眼间隔（秒）
    blink_duration: f32,              // 眨眼持续时间（秒）
    blink_phase: f32,                 // 眨眼进度 0-1（0=督开, 0.5=闭眼, 1=督开）
    is_blinking: bool,                // 是否正在眨眼
    blink_morph_index: Option<usize>, // 缓存眨眼 Morph 索引

    debug_logged: bool,

    /// VRM 模型标志（影响坐标系处理）
    is_vrm: bool,
    vrm_runtime_state: Option<Box<VrmModelRuntimeState>>,

    // 模型全局变换
    model_transform: Mat4,

    // 物理系统
    physics: Option<MMDPhysics>,
    physics_enabled: bool,
    /// 尾巴物理选项（每模型独立）。
    tail_idle_lift: bool,
    tail_movement_boost: bool,
    /// LOD 暂停恢复后需先按当前骨骼姿态重同步，避免约束追赶旧刚体。
    physics_resync_pending: bool,
    /// 全局物理构建配置变化后在下一次模型更新时安全重建。
    physics_rebuild_pending: bool,
    /// 骨骼变换缓冲区（避免每帧堆分配）
    physics_bone_transforms_buf: Vec<Mat4>,
    /// 标记物理体已提供骨骼平移的位置对齐集合。
    physics_position_aligned_bones: HashSet<usize>,

    // 材质可见性控制（用于脱外套等功能）
    material_visible: Vec<bool>,
    user_material_visible: Vec<bool>,

    // GPU 蒙皮数据缓冲区
    /// 骨骼索引（ivec4 格式，每顶点 4 个索引）
    bone_indices: Vec<i32>,
    /// 骨骼权重（vec4 格式，每顶点 4 个权重）
    bone_weights: Vec<f32>,
    /// 原始顶点位置（未蒙皮，用于 GPU 蒙皮）
    original_positions: Vec<f32>,
    /// 原始法线（未蒙皮，用于 GPU 蒙皮）
    original_normals: Vec<f32>,

    // GPU Morph 数据缓冲区
    /// 顶点 Morph 偏移数据（密集格式：morph_count * vertex_count * 3）
    gpu_morph_offsets: Vec<f32>,
    /// Morph 权重数组（用于 GPU）
    gpu_morph_weights: Vec<f32>,
    /// 顶点 Morph 索引映射（GPU Morph 索引 -> MorphManager 索引）
    vertex_morph_indices: Vec<usize>,
    /// 顶点 Morph 数量
    vertex_morph_count: usize,
    /// GPU Morph 数据是否已初始化
    gpu_morph_initialized: bool,

    // GPU UV Morph 数据缓冲区
    /// UV Morph 偏移数据（密集格式：uv_morph_count * vertex_count * 2）
    gpu_uv_morph_offsets: Vec<f32>,
    /// UV Morph 权重数组（用于 GPU）
    gpu_uv_morph_weights: Vec<f32>,
    /// UV Morph 索引映射（GPU UV Morph 索引 -> MorphManager 索引）
    uv_morph_indices: Vec<usize>,
    /// UV Morph 数量
    uv_morph_count: usize,
    /// GPU UV Morph 数据是否已初始化
    gpu_uv_morph_initialized: bool,

    /// Group/Flip Morph 递归展开后的有效权重缓冲区（每帧复用，避免分配）
    effective_weights_buf: Vec<f32>,

    /// 材质 Morph 结果展平缓存（避免每帧分配）
    material_morph_results_flat_cache: Vec<f32>,

    // VPD 骨骼姿势覆盖（骨骼索引 -> (位移, 旋转)）
    vpd_bone_overrides: HashMap<usize, (Vec3, Quat)>,
    external_ik_overrides: HashMap<String, bool>,

    // ======== 第一人称模式 ========
    /// 第一人称模式是否启用
    first_person_enabled: bool,
    /// 头部骨骼索引缓存（模型加载后计算一次）
    head_bone_index: Option<usize>,
    /// 每个子网格是否属于头部（基于顶点位置自动检测）
    head_submesh_flags: Vec<bool>,
    /// 头部检测是否已初始化
    head_detection_initialized: bool,
    /// 第一人称专用索引及子网格范围；None 表示安全回退到原始布局。
    first_person_mesh: Option<FirstPersonMesh>,
    /// 仅保存第一人称动态候选顶点的本帧位置，容量在模型生命周期内复用。
    first_person_position_scratch: Vec<Vec3>,
    /// 用户设置的材质可见性备份（进入第一人称前保存，退出时恢复）
    /// 旧眼位接口使用的单一眼睛骨骼索引（両目 > 目 > 单侧眼）
    eye_bone_index: Option<usize>,
    /// 左/右目的索引（桌面第一人称相机优先使用动画后中点）
    eye_bone_pair: Option<(usize, usize)>,

    // ======== VR 手部模式 ========
    /// VR 手部渲染模式：0=全身, 1=仅左手, 2=仅右手
    vr_hand_mode: u8,
    /// 每个子网格的手部归属标记：0=身体, 1=左手, 2=右手
    hand_submesh_flags: Vec<u8>,
    /// 手部检测是否已初始化
    hand_detection_initialized: bool,
    /// 进入手部模式前的材质可见性备份

    // ======== VR 联动 ========
    /// VR 模式是否启用
    vr_enabled: bool,
    /// VR 追踪数据（3 追踪点 × 7 float = 21）
    vr_tracking_data: [f32; 21],
    /// 结构化 VR 追踪帧（共享 runtime 主通道）
    vr_tracking_frame: Option<VrTrackingFrame>,
    /// VR 手臂 IK 强度 (0.0~1.0)
    vr_ik_strength: f32,
    /// VR IK 求解器（缓存骨骼索引）
    vr_ik_solver: VrIkSolver,
    /// 最新一帧 VR 调试遥测
    vr_debug_state: VrDebugState,
    /// TaCZ 第一人称腕链缓存，骨名仅首次解析。
    tacz_arm_solver_cache: TaczArmSolverCache,
    /// TaCZ 第三人称仅首次解析左右上臂骨索引。

    // ======== 矩阵插值过渡 ========
    /// 缓存的蒙皮矩阵（过渡开始时的状态）
    transition_matrices: Vec<Mat4>,
    /// 过渡进度（0.0 - 1.0）
    transition_progress: f32,
    /// 过渡时长（秒）
    transition_duration: f32,
    /// 是否正在过渡
    is_transitioning: bool,
}

impl MmdModel {
    pub fn new() -> Self {
        Self {
            name: String::new(),
            vertices: Vec::new(),
            indices: Vec::new(),
            weights: Vec::new(),
            materials: Vec::new(),
            submeshes: Vec::new(),
            texture_paths: Vec::new(),
            rigid_bodies: Vec::new(),
            joints: Vec::new(),
            update_positions: Vec::new(),
            update_normals: Vec::new(),
            update_uvs: Vec::new(),
            update_positions_raw: Vec::new(),
            update_normals_raw: Vec::new(),
            update_uvs_raw: Vec::new(),
            bone_manager: BoneManager::new(),
            morph_manager: MorphManager::new(),
            animation_layer_manager: AnimationLayerManager::new(4), // 默认4层
            head_angle_x: 0.0,
            head_angle_y: 0.0,
            head_angle_z: 0.0,
            head_bone_cached: None,
            head_bone_searched: false,
            eye_angle_x: 0.0,
            eye_angle_y: 0.0,
            eye_tracking_enabled: false,
            eye_bone_left: None,
            eye_bone_right: None,
            eye_max_angle: 0.35, // 默认约 20 度
            auto_blink_enabled: false,
            blink_timer: 0.0,
            blink_interval: 4.0,  // 默认 4 秒眨一次
            blink_duration: 0.15, // 眨眼持续 0.15 秒
            blink_phase: 0.0,
            is_blinking: false,
            blink_morph_index: None,
            debug_logged: false,
            is_vrm: false,
            vrm_runtime_state: None,
            model_transform: Mat4::IDENTITY,
            physics: None,
            physics_enabled: false,
            tail_idle_lift: true,
            tail_movement_boost: true,
            physics_resync_pending: false,
            physics_rebuild_pending: false,
            physics_bone_transforms_buf: Vec::new(),
            physics_position_aligned_bones: HashSet::new(),
            material_visible: Vec::new(),
            user_material_visible: Vec::new(),
            bone_indices: Vec::new(),
            bone_weights: Vec::new(),
            original_positions: Vec::new(),
            original_normals: Vec::new(),
            gpu_morph_offsets: Vec::new(),
            gpu_morph_weights: Vec::new(),
            vertex_morph_indices: Vec::new(),
            vertex_morph_count: 0,
            gpu_morph_initialized: false,
            gpu_uv_morph_offsets: Vec::new(),
            gpu_uv_morph_weights: Vec::new(),
            uv_morph_indices: Vec::new(),
            uv_morph_count: 0,
            gpu_uv_morph_initialized: false,
            effective_weights_buf: Vec::new(),
            material_morph_results_flat_cache: Vec::new(),
            vpd_bone_overrides: HashMap::new(),
            external_ik_overrides: HashMap::new(),
            vr_hand_mode: 0,
            hand_submesh_flags: Vec::new(),
            hand_detection_initialized: false,
            vr_enabled: false,
            vr_tracking_data: [0.0; 21],
            vr_tracking_frame: None,
            vr_ik_strength: 1.0,
            vr_ik_solver: VrIkSolver::new(),
            vr_debug_state: VrDebugState::default(),
            tacz_arm_solver_cache: TaczArmSolverCache::default(),
            transition_matrices: Vec::new(),
            transition_progress: 0.0,
            transition_duration: 0.0,
            is_transitioning: false,
            first_person_enabled: false,
            head_bone_index: None,
            head_submesh_flags: Vec::new(),
            head_detection_initialized: false,
            first_person_mesh: None,
            first_person_position_scratch: Vec::new(),
            eye_bone_index: None,
            eye_bone_pair: None,
        }
    }

    /// 获取顶点数量
    pub fn vertex_count(&self) -> usize {
        self.vertices.len()
    }

    /// 获取索引数量
    pub fn index_count(&self) -> usize {
        self.indices.len()
    }

    /// 获取第一人称索引数量；0 表示渲染端应复用原始 EBO。
    pub fn first_person_index_count(&mut self) -> usize {
        self.ensure_first_person_mesh();
        self.first_person_mesh
            .as_ref()
            .map_or(0, |mesh| mesh.indices.len())
    }

    /// 获取材质数量
    pub fn material_count(&self) -> usize {
        self.materials.len()
    }

    /// 获取子网格数量
    pub fn submesh_count(&self) -> usize {
        self.submeshes.len()
    }
}

fn java_tracking_input_from_packet(packet: &[f32; 21]) -> VrmTrackingInput {
    VrmTrackingInput {
        head: java_tracked_pose_from_slice(&packet[0..7]),
        right_hand: java_tracked_pose_from_slice(&packet[7..14]),
        left_hand: java_tracked_pose_from_slice(&packet[14..21]),
    }
}

fn java_tracked_pose_from_slice(values: &[f32]) -> TrackedPose {
    let rotation = Quat::from_xyzw(values[3], values[4], values[5], values[6]);
    let valid = rotation.length_squared() > 1e-6;
    TrackedPose {
        position: Vec3::new(values[0], values[1], values[2]),
        orientation: if valid {
            rotation.normalize()
        } else {
            Quat::IDENTITY
        },
        valid,
    }
}

fn normalized_material_visibility(source: &[bool], material_count: usize) -> Vec<bool> {
    if source.len() == material_count {
        return source.to_vec();
    }
    let mut visible = vec![true; material_count];
    for (index, value) in source.iter().copied().enumerate().take(material_count) {
        visible[index] = value;
    }
    visible
}

impl Default for MmdModel {
    fn default() -> Self {
        Self::new()
    }
}

/// 计算单个顶点的蒙皮
fn compute_vertex_skinning(
    position: Vec3,
    normal: Vec3,
    weight: &VertexWeight,
    matrices: &[Mat4],
) -> (Vec3, Vec3) {
    match weight {
        VertexWeight::Bdef1 { bone } => {
            let m = matrices
                .get(*bone as usize)
                .copied()
                .unwrap_or(Mat4::IDENTITY);
            let pos = m.transform_point3(position);
            let norm = m.transform_vector3(normal).normalize_or_zero();
            (pos, norm)
        }
        VertexWeight::Bdef2 { bones, weight } => {
            let m0 = matrices
                .get(bones[0] as usize)
                .copied()
                .unwrap_or(Mat4::IDENTITY);
            let m1 = matrices
                .get(bones[1] as usize)
                .copied()
                .unwrap_or(Mat4::IDENTITY);
            let w0 = *weight;
            let w1 = 1.0 - w0;

            let pos = m0.transform_point3(position) * w0 + m1.transform_point3(position) * w1;
            let norm = (m0.transform_vector3(normal) * w0 + m1.transform_vector3(normal) * w1)
                .normalize_or_zero();
            (pos, norm)
        }
        VertexWeight::Bdef4 { bones, weights } => {
            let mut pos = Vec3::ZERO;
            let mut norm = Vec3::ZERO;

            for i in 0..4 {
                let m = matrices
                    .get(bones[i] as usize)
                    .copied()
                    .unwrap_or(Mat4::IDENTITY);
                let w = weights[i];
                pos += m.transform_point3(position) * w;
                norm += m.transform_vector3(normal) * w;
            }

            (pos, norm.normalize_or_zero())
        }
        VertexWeight::Sdef {
            bones,
            weight,
            c: _,
            r0: _,
            r1: _,
        } => {
            // SDEF 球面变形
            let m0 = matrices
                .get(bones[0] as usize)
                .copied()
                .unwrap_or(Mat4::IDENTITY);
            let m1 = matrices
                .get(bones[1] as usize)
                .copied()
                .unwrap_or(Mat4::IDENTITY);
            let w0 = *weight;
            let w1 = 1.0 - w0;

            // 简化实现：退化为 BDEF2
            let pos = m0.transform_point3(position) * w0 + m1.transform_point3(position) * w1;
            let norm = (m0.transform_vector3(normal) * w0 + m1.transform_vector3(normal) * w1)
                .normalize_or_zero();
            (pos, norm)
        }
        VertexWeight::Qdef { bones, weights } => {
            // QDEF 与 BDEF4 相同处理
            let mut pos = Vec3::ZERO;
            let mut norm = Vec3::ZERO;

            for i in 0..4 {
                let m = matrices
                    .get(bones[i] as usize)
                    .copied()
                    .unwrap_or(Mat4::IDENTITY);
                let w = weights[i];
                pos += m.transform_point3(position) * w;
                norm += m.transform_vector3(normal) * w;
            }

            (pos, norm.normalize_or_zero())
        }
    }
}
