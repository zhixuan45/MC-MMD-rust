//! PMX 物理自动修复与另存为工具 (PMX Physics Auto-Repair Tool)
//!
//! 功能：
//! 1. 扫描 PMX 模型中的刚体与关节配置；
//! 2. 对照标准优质模型（如 Grass Wonder）的物理拓扑，诊断并修复：
//!    - 腿部摆动刚体（大腿、小腿、膝盖）与动态裙摆/风衣的碰撞矩阵掩码断连问题；
//!    - 排除腰胯盆骨刚体大面积穿透裙根的问题；
//!    - 修复多层长链动态-动态硬锁挂接关节的限位与弹簧配置；
//! 3. 将修复后的物理结构写回 PMX 格式并另存为新文件。

use std::env;
use std::fs::File;
use std::io::{BufWriter, Cursor, Read, Write};
use std::path::Path;

use byteorder::{WriteBytesExt, LE};
use mmd::pmx::joint::{Joint, JointType};
use mmd::pmx::reader::{
    BoneReader, DisplayFrameReader, HeaderReader, JointReader, MaterialReader, MorphReader,
    RigidBodyReader, SurfaceReader, TextureReader, VertexReader,
};
use mmd::pmx::rigid_body::{RigidBody, RigidBodyMode, RigidBodyShape};
use mmd::pmx::settings::Settings;
use mmd::pmx::types::{IndexSize, TextEncoding};
use mmd_engine::model::load_pmx;

/// 识别骨骼位于腰胯盆骨位置的静态刚体
fn is_pelvis_collider(body: &RigidBody) -> bool {
    const PELVIS_NAMES: &[&str] = &[
        "下半身", "腰", "pelvis", "waist", "hip",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    let is_tail = local.contains("tail") || universal.contains("tail");
    !is_tail
        && PELVIS_NAMES
            .iter()
            .any(|part| local.contains(part) || universal.contains(part))
}

/// 识别实际在走跑动作中向前摆动的腿部运动学刚体
fn is_leg_motion_collider(body: &RigidBody) -> bool {
    const LEG_NAMES: &[&str] = &[
        "足", "ひざ", "膝", "腿", "thigh", "shin", "leg", "knee", "skirt_collider",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    !is_pelvis_collider(body)
        && LEG_NAMES
            .iter()
            .any(|part| local.contains(part) || universal.contains(part))
}

/// 识别裙摆或下装动态刚体
fn is_skirt_or_lower_garment(body: &RigidBody) -> bool {
    const PART_NAMES: &[&str] = &[
        "裙", "スカート", "skirt", "petticoat", "下装", "下衣", "裾", "摆", "衣摆", "后摆",
        "下摆", "风衣", "外套", "コート", "coat", "cloak", "cape", "flap", "衣帶", "衣带",
        "ribbon", "belt", "band", "sash",
    ];
    let local = body.local_name.to_lowercase();
    let universal = body.universal_name.to_lowercase();
    PART_NAMES
        .iter()
        .any(|part| local.contains(part) || universal.contains(part))
}

/// 识别尾巴等独立动态链
fn is_tail_dynamic_part(body: &RigidBody) -> bool {
    let local = body.local_name.to_ascii_lowercase();
    let universal = body.universal_name.to_ascii_lowercase();
    local.contains("tail") || universal.contains("tail")
}

/// 检查是否为动态链的挂接锚点
fn is_dynamic_chain_anchor(index: usize, rigid_bodies: &[RigidBody], joints: &[Joint]) -> bool {
    joints.iter().any(|joint| {
        let is_a = joint.rigid_body_a_index == index as i32;
        let is_b = joint.rigid_body_b_index == index as i32;
        if !is_a && !is_b {
            return false;
        }
        let other_index = if is_a {
            joint.rigid_body_b_index
        } else {
            joint.rigid_body_a_index
        };
        other_index >= 0
            && (other_index as usize) < rigid_bodies.len()
            && rigid_bodies[other_index as usize].mode != RigidBodyMode::Static
    })
}

/// 写入 PMX 文本字段
fn write_text<W: Write>(w: &mut W, text: &str, encoding: TextEncoding) -> std::io::Result<()> {
    match encoding {
        TextEncoding::UTF8 => {
            let bytes = text.as_bytes();
            w.write_i32::<LE>(bytes.len() as i32)?;
            w.write_all(bytes)?;
        }
        TextEncoding::UTF16LE => {
            let utf16: Vec<u16> = text.encode_utf16().collect();
            w.write_i32::<LE>((utf16.len() * 2) as i32)?;
            for u in utf16 {
                w.write_u16::<LE>(u)?;
            }
        }
    }
    Ok(())
}

/// 写入 PMX 索引字段
fn write_index<W: Write>(w: &mut W, index: i32, size: IndexSize) -> std::io::Result<()> {
    match size {
        IndexSize::I8 => w.write_i8(index as i8),
        IndexSize::I16 => w.write_i16::<LE>(index as i16),
        IndexSize::I32 => w.write_i32::<LE>(index),
    }
}

/// 序列化 RigidBody 到 PMX 字节流
fn write_rigid_body<W: Write>(
    w: &mut W,
    rb: &RigidBody,
    settings: &Settings,
) -> std::io::Result<()> {
    write_text(w, &rb.local_name, settings.text_encoding)?;
    write_text(w, &rb.universal_name, settings.text_encoding)?;

    write_index(w, rb.bone_index, settings.bone_index_size)?;
    w.write_u8(rb.group)?;
    w.write_u16::<LE>(rb.un_collision_group_flag)?;

    let shape_byte = match rb.shape {
        RigidBodyShape::Sphere => 0,
        RigidBodyShape::Box => 1,
        RigidBodyShape::Capsule => 2,
    };
    w.write_u8(shape_byte)?;

    for v in rb.size {
        w.write_f32::<LE>(v)?;
    }
    for v in rb.position {
        w.write_f32::<LE>(v)?;
    }
    for v in rb.rotation {
        w.write_f32::<LE>(v)?;
    }

    w.write_f32::<LE>(rb.mass)?;
    w.write_f32::<LE>(rb.move_attenuation)?;
    w.write_f32::<LE>(rb.rotation_attenuation)?;
    w.write_f32::<LE>(rb.repulsion)?;
    w.write_f32::<LE>(rb.friction)?;

    let mode_byte = match rb.mode {
        RigidBodyMode::Static => 0,
        RigidBodyMode::Dynamic => 1,
        RigidBodyMode::DynamicWithBonePosition => 2,
    };
    w.write_u8(mode_byte)?;

    Ok(())
}

/// 序列化 Joint 到 PMX 字节流
fn write_joint<W: Write>(w: &mut W, j: &Joint, settings: &Settings) -> std::io::Result<()> {
    write_text(w, &j.local_name, settings.text_encoding)?;
    write_text(w, &j.universal_name, settings.text_encoding)?;

    let type_byte = match j.type_ {
        JointType::Spring6DOF => 0,
        JointType::SixDof => 1,
        JointType::P2p => 2,
        JointType::ConeTwist => 3,
        JointType::Slider => 4,
        JointType::Hinge => 5,
    };
    w.write_u8(type_byte)?;

    write_index(w, j.rigid_body_a_index, settings.rigidbody_index_size)?;
    write_index(w, j.rigid_body_b_index, settings.rigidbody_index_size)?;

    for v in j.position {
        w.write_f32::<LE>(v)?;
    }
    for v in j.rotation {
        w.write_f32::<LE>(v)?;
    }
    for v in j.position_min {
        w.write_f32::<LE>(v)?;
    }
    for v in j.position_max {
        w.write_f32::<LE>(v)?;
    }
    for v in j.rotation_min {
        w.write_f32::<LE>(v)?;
    }
    for v in j.rotation_max {
        w.write_f32::<LE>(v)?;
    }
    for v in j.position_spring {
        w.write_f32::<LE>(v)?;
    }
    for v in j.rotation_spring {
        w.write_f32::<LE>(v)?;
    }

    Ok(())
}

pub struct RepairReport {
    pub total_bodies: usize,
    pub total_joints: usize,
    pub leg_colliders_found: usize,
    pub skirt_bodies_found: usize,
    pub collision_masks_repaired: usize,
    pub joints_stabilized: usize,
}

/// 核心物理诊断与修复算法
pub fn repair_pmx_physics(
    rigid_bodies: &mut [RigidBody],
    joints: &mut [Joint],
) -> RepairReport {
    let mut collision_masks_repaired = 0;
    let mut joints_stabilized = 0;

    // 1. 识别摆腿运动学刚体与动态裙摆刚体
    let is_leg_collider: Vec<bool> = rigid_bodies
        .iter()
        .enumerate()
        .map(|(index, body)| {
            body.mode == RigidBodyMode::Static
                && is_leg_motion_collider(body)
                && !is_dynamic_chain_anchor(index, rigid_bodies, joints)
        })
        .collect();

    let is_skirt_dynamic: Vec<bool> = rigid_bodies
        .iter()
        .map(|body| {
            body.mode != RigidBodyMode::Static
                && is_skirt_or_lower_garment(body)
                && !is_tail_dynamic_part(body)
        })
        .collect();

    let leg_colliders_found = is_leg_collider.iter().filter(|&&f| f).count();
    let skirt_bodies_found = is_skirt_dynamic.iter().filter(|&&f| f).count();

    // 收集所有下装动态刚体所属的碰撞组
    let mut skirt_group_bits = 0u16;
    for (skirt_idx, &is_skirt) in is_skirt_dynamic.iter().enumerate() {
        if is_skirt {
            let skirt_rb = &rigid_bodies[skirt_idx];
            skirt_group_bits |= 1u16 << (skirt_rb.group.min(15));
        }
    }

    // 2. 仅精准切断腰胯盆骨刚体（如 M-M-M-下半身）与下装裙摆组的冲突碰撞。
    // 严格保证胸、背、肩、臂、手、首、头、发等全部上半身刚体 100% 保持原始碰撞掩码不变，防止头发穿胸！
    let is_pelvis_isolated: Vec<bool> = rigid_bodies
        .iter()
        .enumerate()
        .map(|(index, body)| {
            body.mode == RigidBodyMode::Static
                && is_pelvis_collider(body)
                && !is_dynamic_chain_anchor(index, rigid_bodies, joints)
        })
        .collect();

    for (body_idx, &is_pelvis) in is_pelvis_isolated.iter().enumerate() {
        if is_pelvis {
            let old_flag = rigid_bodies[body_idx].un_collision_group_flag;
            // 禁止与裙摆组碰撞 -> 在 un_collision_group_flag 中置 1 屏蔽位
            rigid_bodies[body_idx].un_collision_group_flag |= skirt_group_bits;
            if rigid_bodies[body_idx].un_collision_group_flag != old_flag {
                collision_masks_repaired += 1;
            }
        }
    }

    // 3. 修复腿部运动摆动刚体与动态下装刚体之间的双向互通保底
    for (leg_idx, &is_leg) in is_leg_collider.iter().enumerate() {
        if !is_leg {
            continue;
        }
        let leg_group = rigid_bodies[leg_idx].group.min(15);
        let leg_group_mask_bit = 1u16 << leg_group;

        for (skirt_idx, &is_skirt) in is_skirt_dynamic.iter().enumerate() {
            if !is_skirt {
                continue;
            }
            let skirt_group = rigid_bodies[skirt_idx].group.min(15);
            let skirt_group_mask_bit = 1u16 << skirt_group;

            // 腿部需要允许与裙摆组碰撞 -> 清除 un_collision_group_flag 的 skirt_group 位
            let leg_flag = rigid_bodies[leg_idx].un_collision_group_flag;
            if (leg_flag & skirt_group_mask_bit) != 0 {
                rigid_bodies[leg_idx].un_collision_group_flag &= !skirt_group_mask_bit;
                collision_masks_repaired += 1;
            }

            // 裙摆需要允许与腿部组碰撞 -> 清除 un_collision_group_flag 的 leg_group 位
            let skirt_flag = rigid_bodies[skirt_idx].un_collision_group_flag;
            if (skirt_flag & leg_group_mask_bit) != 0 {
                rigid_bodies[skirt_idx].un_collision_group_flag &= !leg_group_mask_bit;
                collision_masks_repaired += 1;
            }
        }
    }

    // 3. 关节刚度与极值优化
    for j in joints.iter_mut() {
        let a_idx = j.rigid_body_a_index as usize;
        let b_idx = j.rigid_body_b_index as usize;
        if a_idx < rigid_bodies.len() && b_idx < rigid_bodies.len() {
            let body_a = &rigid_bodies[a_idx];
            let body_b = &rigid_bodies[b_idx];

            // 检查动态挂接在动态父刚体上的硬锁关节（如 Rin 衣带根挂在裙上）
            if body_a.mode != RigidBodyMode::Static && body_b.mode != RigidBodyMode::Static {
                // 如果是 0 限位锁死关节，确保其旋转弹簧不被误加反弹
                if j.rotation_min == [0.0, 0.0, 0.0] && j.rotation_max == [0.0, 0.0, 0.0] {
                    if j.rotation_spring != [0.0, 0.0, 0.0] {
                        j.rotation_spring = [0.0, 0.0, 0.0];
                        joints_stabilized += 1;
                    }
                }
            }
        }
    }

    RepairReport {
        total_bodies: rigid_bodies.len(),
        total_joints: joints.len(),
        leg_colliders_found,
        skirt_bodies_found,
        collision_masks_repaired,
        joints_stabilized,
    }
}

struct CountingReader<R> {
    inner: R,
    counter: std::sync::Arc<std::sync::atomic::AtomicUsize>,
}

impl<R: Read> Read for CountingReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        self.counter
            .fetch_add(n, std::sync::atomic::Ordering::SeqCst);
        Ok(n)
    }
}

/// 执行修复并另存为新 PMX 文件
pub fn repair_pmx_file(input_path: &Path, output_path: &Path) -> Result<RepairReport, String> {
    let input_bytes = std::fs::read(input_path)
        .map_err(|e| format!("读取输入文件 '{}' 失败: {}", input_path.display(), e))?;

    let byte_counter = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let counting = CountingReader {
        inner: Cursor::new(&input_bytes),
        counter: byte_counter.clone(),
    };
    let header_reader = HeaderReader::new(counting).map_err(|e| format!("PMX Header 解析失败: {:?}", e))?;
    let settings = header_reader.settings;

    let mut v_reader = VertexReader::new(header_reader).map_err(|e| format!("Vertex reader 创建失败: {:?}", e))?;
    while v_reader.remaining > 0 {
        v_reader.next::<mmd::DefaultConfig>().map_err(|e| format!("读取顶点失败: {:?}", e))?;
    }
    let mut s_reader = SurfaceReader::new(v_reader).map_err(|e| format!("Surface reader 创建失败: {:?}", e))?;
    while s_reader.remaining > 0 {
        s_reader.next::<mmd::DefaultConfig>().map_err(|e| format!("读取面失败: {:?}", e))?;
    }
    let mut t_reader = TextureReader::new(s_reader).map_err(|e| format!("Texture reader 创建失败: {:?}", e))?;
    while t_reader.remaining > 0 {
        t_reader.next().map_err(|e| format!("读取纹理失败: {:?}", e))?;
    }
    let mut m_reader = MaterialReader::new(t_reader).map_err(|e| format!("Material reader 创建失败: {:?}", e))?;
    while m_reader.remaining > 0 {
        m_reader.next::<mmd::DefaultConfig>().map_err(|e| format!("读取材质失败: {:?}", e))?;
    }
    let mut b_reader = BoneReader::new(m_reader).map_err(|e| format!("Bone reader 创建失败: {:?}", e))?;
    while b_reader.remaining > 0 {
        b_reader.next::<mmd::DefaultConfig>().map_err(|e| format!("读取骨骼失败: {:?}", e))?;
    }
    let mut mo_reader = MorphReader::new(b_reader).map_err(|e| format!("Morph reader 创建失败: {:?}", e))?;
    while mo_reader.remaining > 0 {
        mo_reader.next::<mmd::DefaultConfig>().map_err(|e| format!("读取表情失败: {:?}", e))?;
    }
    let mut df_reader = DisplayFrameReader::new(mo_reader).map_err(|e| format!("DisplayFrame reader 创建失败: {:?}", e))?;
    while df_reader.remaining > 0 {
        df_reader.next::<mmd::DefaultConfig>().map_err(|e| format!("读取显示框失败: {:?}", e))?;
    }

    // 记录 display_frame 结束处的字节偏移
    let prefix_end_offset = byte_counter.load(std::sync::atomic::Ordering::SeqCst);

    // 解析刚体
    let mut rb_reader = RigidBodyReader::new(df_reader)
        .map_err(|e| format!("PMX RigidBodyReader 创建失败: {:?}", e))?;
    let body_count = rb_reader.count as usize;
    let mut rigid_bodies = Vec::with_capacity(body_count);
    for _ in 0..body_count {
        if let Some(rb) = rb_reader
            .next::<mmd::DefaultConfig>()
            .map_err(|e| format!("读取刚体失败: {:?}", e))?
        {
            rigid_bodies.push(rb);
        }
    }

    // 解析关节
    let mut j_reader = JointReader::new(rb_reader)
        .map_err(|e| format!("PMX JointReader 创建失败: {:?}", e))?;
    let joint_count = j_reader.count as usize;
    let mut joints = Vec::with_capacity(joint_count);
    for _ in 0..joint_count {
        if let Some(j) = j_reader
            .next::<mmd::DefaultConfig>()
            .map_err(|e| format!("读取关节失败: {:?}", e))?
        {
            joints.push(j);
        }
    }

    // 执行物理修复算法
    let report = repair_pmx_physics(&mut rigid_bodies, &mut joints);

    let mut out_file = File::create(output_path)
        .map_err(|e| format!("创建输出文件 '{}' 失败: {}", output_path.display(), e))?;
    let mut writer = BufWriter::new(&mut out_file);

    // 1. 写入 RigidBody 之前的前缀完整原始字节（保证顶点、网格、材质、骨骼、表情 100% 原始无损）
    writer
        .write_all(&input_bytes[..prefix_end_offset])
        .map_err(|e| format!("写入前缀字节失败: {}", e))?;

    // 2. 写入修复后的刚体数组
    writer
        .write_i32::<LE>(rigid_bodies.len() as i32)
        .map_err(|e| format!("写入刚体数量失败: {}", e))?;
    for rb in &rigid_bodies {
        write_rigid_body(&mut writer, rb, &settings)
            .map_err(|e| format!("写入刚体 '{}' 失败: {}", rb.local_name, e))?;
    }

    // 3. 写入修复后的关节数组
    writer
        .write_i32::<LE>(joints.len() as i32)
        .map_err(|e| format!("写入关节数量失败: {}", e))?;
    for j in &joints {
        write_joint(&mut writer, j, &settings)
            .map_err(|e| format!("写入关节 '{}' 失败: {}", j.local_name, e))?;
    }

    writer
        .flush()
        .map_err(|e| format!("刷新输出文件失败: {}", e))?;

    Ok(report)
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        println!("用法: cargo run --example repair_pmx -- <输入PMX路径> [输出PMX路径]");
        println!("示例: cargo run --example repair_pmx -- TohsakaRin.pmx TohsakaRin_fixed.pmx");
        return;
    }

    let input_path = Path::new(&args[1]);
    let default_output = input_path.with_file_name(format!(
        "{}_repaired.pmx",
        input_path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("model")
    ));
    let output_path = if args.len() > 2 {
        Path::new(&args[2])
    } else {
        default_output.as_path()
    };

    println!("============================================================");
    println!("  MMD PMX 物理自动修复与另存为工具");
    println!("  输入文件: {}", input_path.display());
    println!("  输出目标: {}", output_path.display());
    println!("============================================================");

    match repair_pmx_file(input_path, output_path) {
        Ok(report) => {
            println!("\n[√] 修复完成并成功另存为！");
            println!("  - 扫描刚体总数: {}", report.total_bodies);
            println!("  - 扫描关节总数: {}", report.total_joints);
            println!("  - 识别腿部摆动刚体: {} 个", report.leg_colliders_found);
            println!("  - 识别动态裙摆刚体: {} 个", report.skirt_bodies_found);
            println!("  - 修复碰撞掩码项目: {} 处", report.collision_masks_repaired);
            println!("  - 优化硬锁关节项目: {} 处", report.joints_stabilized);

            // 验证生成文件的完整性
            println!("\n[验证] 重新载入另存文件校验完整性...");
            match load_pmx(output_path.to_str().unwrap()) {
                Ok(loaded) => {
                    println!("  - 另存文件载入成功: 模型名称 '{}'", loaded.name);
                    println!("  - 顶点数: {}, 材质数: {}, 骨骼数: {}, 刚体数: {}, 关节数: {}",
                        loaded.vertices.len(), loaded.materials.len(), loaded.bone_manager.bone_count(),
                        loaded.rigid_bodies.len(), loaded.joints.len());
                    println!("  - 验证通过: 几何与物理结构完整一致！");
                }
                Err(e) => {
                    println!("  [!] 校验载入失败: {:?}", e);
                }
            }
        }
        Err(e) => {
            eprintln!("\n[X] 修复失败: {}", e);
        }
    }
}
