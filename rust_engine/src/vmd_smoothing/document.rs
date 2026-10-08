use std::{
    collections::{HashMap, HashSet},
    fs,
    path::Path,
};

use crate::{
    animation::{BoneMotionTrack, MotionTrack, VmdFile},
    MmdError, Result,
};

use super::{filter, SmoothingGroup, SmoothingOptions, SmoothingReport, SmoothingResult};

const HEADER_V2: &[u8] = b"Vocaloid Motion Data 0002";
const HEADER_SIZE: usize = 50;
const BONE_COUNT_SIZE: usize = 4;
const BONE_RECORD_SIZE: usize = 111;
const MAX_RECORDS: usize = 4_000_000;
const MAX_OUTPUT_KEYS: usize = 1_000_000;

#[derive(Debug, Clone)]
struct BoneRecord {
    start: usize,
    name: String,
}

/// 验证过结构的 VMD2 文档，保留原始数据供无损重建。
#[derive(Debug, Clone)]
pub struct VmdDocument {
    original_bytes: Vec<u8>,
    bone_records: Vec<BoneRecord>,
    tail_start: usize,
}

impl VmdDocument {
    /// 读取并严格验证 VMD2。
    pub fn from_bytes(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < HEADER_SIZE + BONE_COUNT_SIZE {
            return Err(parse_error("文件头或骨骼计数不完整"));
        }
        if &bytes[..HEADER_V2.len()] != HEADER_V2 {
            let version = if bytes.starts_with(b"Vocaloid Motion Data file") {
                "VMD1 不受支持，请转换为 VMD2"
            } else {
                "无效的 VMD 文件头"
            };
            return Err(parse_error(version));
        }

        let bone_count = read_count(bytes, HEADER_SIZE, "骨骼")?;
        let bone_bytes = checked_bytes(bone_count, BONE_RECORD_SIZE, "骨骼")?;
        let tail_start = checked_end(
            HEADER_SIZE + BONE_COUNT_SIZE,
            bone_bytes,
            bytes.len(),
            "骨骼区段",
        )?;
        let mut bone_records = Vec::with_capacity(bone_count);

        for index in 0..bone_count {
            let start = HEADER_SIZE + BONE_COUNT_SIZE + index * BONE_RECORD_SIZE;
            validate_bone_record(bytes, start)?;
            let raw_name = &bytes[start..start + 15];
            let end = raw_name
                .iter()
                .position(|byte| *byte == 0)
                .unwrap_or(raw_name.len());
            let (name, _, had_errors) = encoding_rs::SHIFT_JIS.decode(&raw_name[..end]);
            if had_errors {
                return Err(parse_error("骨骼名包含无效 Shift-JIS 字节"));
            }
            bone_records.push(BoneRecord {
                start,
                name: name.into_owned(),
            });
        }

        validate_tail(bytes, tail_start)?;
        Ok(Self {
            original_bytes: bytes.to_vec(),
            bone_records,
            tail_start,
        })
    }

    /// 从文件加载。
    pub fn load(path: impl AsRef<Path>) -> Result<Self> {
        let bytes = fs::read(path).map_err(MmdError::Io)?;
        Self::from_bytes(&bytes)
    }

    /// 返回文档中出现过的骨骼名，按首次出现顺序排列。
    pub fn bone_names(&self) -> Vec<String> {
        let mut seen = HashSet::new();
        self.bone_records
            .iter()
            .filter_map(|record| {
                seen.insert(record.name.clone())
                    .then(|| record.name.clone())
            })
            .collect()
    }

    /// 返回未经修改的原文件字节。
    pub fn original_bytes(&self) -> &[u8] {
        &self.original_bytes
    }

    /// 从原始快照平滑所选轨道并保留其余字节。
    pub fn smooth(&self, options: &SmoothingOptions) -> Result<SmoothingResult> {
        self.smooth_grouped(&[SmoothingGroup {
            name: "default".into(),
            enabled: true,
            options: options.clone(),
        }])
    }

    /// 按组顺序确定轨道参数，所有结果都基于原始动作计算。
    pub fn smooth_grouped(&self, groups: &[SmoothingGroup]) -> Result<SmoothingResult> {
        if groups.len() > 64 {
            return Err(parse_error("平滑组最多允许 64 组"));
        }
        for group in groups {
            if group.name.trim().is_empty() {
                return Err(parse_error("平滑组名称不能为空"));
            }
            if group.enabled {
                group.options.validate()?;
            }
        }
        if groups.is_empty() {
            return Ok(SmoothingResult {
                bytes: self.original_bytes.clone(),
                report: SmoothingReport::default(),
            });
        }

        let vmd = VmdFile::load_from_bytes(&self.original_bytes)?;
        let names = self.bone_names();
        let mut owners = HashMap::new();
        for name in &names {
            for (index, group) in groups.iter().enumerate() {
                if group.enabled && filter::selected(name, &group.options.selection) {
                    owners.insert(name.clone(), index);
                }
            }
        }

        let original_keys = self
            .bone_records
            .iter()
            .filter(|record| owners.contains_key(&record.name))
            .count();
        let mut estimated_keys = 0_usize;
        for name in &names {
            let Some(index) = owners.get(name) else {
                continue;
            };
            let options = &groups[*index].options;
            if options.strength == 0.0 || options.radius == 0 {
                continue;
            }
            if let Some(track) = vmd.motion.bone_tracks.get(name) {
                if is_constant_track(track) {
                    continue;
                }
                let first = track.keyframes.keys().next().copied().unwrap_or(0);
                // 非完整动作轨道会回退到自身范围，估算也按该范围计数。
                let count = track.max_frame_index().saturating_sub(first) as usize + 1;
                estimated_keys = estimated_keys.saturating_add(count);
                if estimated_keys > MAX_OUTPUT_KEYS {
                    return Err(parse_error("平滑结果超过一百万个骨骼关键帧"));
                }
            }
        }

        let mut replacements = HashMap::new();
        let mut report = SmoothingReport {
            selected_tracks: owners.len(),
            original_keys,
            ..Default::default()
        };
        for name in &names {
            let Some(index) = owners.get(name) else {
                continue;
            };
            let options = &groups[*index].options;
            if options.strength == 0.0 || options.radius == 0 {
                report.output_keys += self
                    .bone_records
                    .iter()
                    .filter(|record| record.name == *name)
                    .count();
                continue;
            }
            let Some(track) = vmd.motion.bone_tracks.get(name) else {
                continue;
            };
            // 恒定姿态保持原始记录，避免把长时间持姿烘焙成重复帧。
            if is_constant_track(track) {
                report.output_keys += self
                    .bone_records
                    .iter()
                    .filter(|record| record.name == *name)
                    .count();
                continue;
            }
            if track.keyframes.len() <= 1 {
                report.output_keys += self
                    .bone_records
                    .iter()
                    .filter(|record| record.name == *name)
                    .count();
                continue;
            }
            let start_frame = track.keyframes.keys().next().copied().unwrap_or(0);
            let poses = filter::smooth_track(
                name,
                &vmd.motion,
                start_frame,
                track.max_frame_index(),
                vmd.motion.duration(),
                options,
                &mut report.warnings,
            )?;
            if report.output_keys.saturating_add(poses.len()) > MAX_OUTPUT_KEYS {
                return Err(parse_error("平滑结果超过一百万个骨骼关键帧"));
            }
            let template = self
                .bone_records
                .iter()
                .find(|record| record.name == *name)
                .ok_or_else(|| parse_error("内部错误：缺少骨骼记录模板"))?;
            let raw_template =
                &self.original_bytes[template.start..template.start + BONE_RECORD_SIZE];
            let records = poses
                .iter()
                .map(|pose| filter::encode_pose(raw_template, pose))
                .collect::<Vec<_>>();
            report.output_keys += records.len();
            replacements.insert(name.clone(), records);
        }

        let mut output =
            Vec::with_capacity(self.original_bytes.len() + report.output_keys * BONE_RECORD_SIZE);
        output.extend_from_slice(&self.original_bytes[..HEADER_SIZE]);
        let count_offset = output.len();
        output.extend_from_slice(&0_u32.to_le_bytes());
        let mut emitted = HashSet::new();
        let mut output_count = 0_usize;
        for record in &self.bone_records {
            if let Some(replacement) = replacements.get(&record.name) {
                if emitted.insert(record.name.clone()) {
                    for raw in replacement {
                        output.extend_from_slice(raw);
                        output_count += 1;
                    }
                }
            } else {
                output.extend_from_slice(
                    &self.original_bytes[record.start..record.start + BONE_RECORD_SIZE],
                );
                output_count += 1;
            }
        }
        let count =
            u32::try_from(output_count).map_err(|_| parse_error("骨骼关键帧数量超出 VMD2 范围"))?;
        output[count_offset..count_offset + BONE_COUNT_SIZE].copy_from_slice(&count.to_le_bytes());
        output.extend_from_slice(&self.original_bytes[self.tail_start..]);
        Ok(SmoothingResult {
            bytes: output,
            report,
        })
    }
}

fn is_constant_track(track: &BoneMotionTrack) -> bool {
    let Some(first) = track.keyframes.values().next() else {
        return true;
    };
    track.keyframes.values().all(|key| {
        key.translation == first.translation
            && (key.orientation == first.orientation || key.orientation == -first.orientation)
    })
}

fn validate_tail(bytes: &[u8], mut offset: usize) -> Result<()> {
    let morph_count = read_count(bytes, offset, "表情")?;
    offset += 4;
    let morph_bytes = checked_bytes(morph_count, 23, "表情")?;
    let morph_end = checked_end(offset, morph_bytes, bytes.len(), "表情区段")?;
    for start in (offset..morph_end).step_by(23) {
        validate_f32(bytes, start + 19, "表情权重")?;
    }
    offset = morph_end;
    if offset == bytes.len() {
        return Ok(());
    }

    let camera_count = read_count(bytes, offset, "相机")?;
    offset += 4;
    let camera_bytes = checked_bytes(camera_count, 61, "相机")?;
    let camera_end = checked_end(offset, camera_bytes, bytes.len(), "相机区段")?;
    for start in (offset..camera_end).step_by(61) {
        for field in [4, 8, 12, 16, 20, 24, 28] {
            validate_f32(bytes, start + field, "相机数值")?;
        }
    }
    offset = camera_end;
    if offset == bytes.len() {
        return Ok(());
    }

    let light_count = read_count(bytes, offset, "光照")?;
    offset += 4;
    let light_bytes = checked_bytes(light_count, 28, "光照")?;
    let light_end = checked_end(offset, light_bytes, bytes.len(), "光照区段")?;
    for start in (offset..light_end).step_by(28) {
        for field in [4, 8, 12, 16, 20, 24] {
            validate_f32(bytes, start + field, "光照数值")?;
        }
    }
    offset = light_end;
    if offset == bytes.len() {
        return Ok(());
    }

    let shadow_count = read_count(bytes, offset, "阴影")?;
    offset += 4;
    let shadow_bytes = checked_bytes(shadow_count, 9, "阴影")?;
    let shadow_end = checked_end(offset, shadow_bytes, bytes.len(), "阴影区段")?;
    for start in (offset..shadow_end).step_by(9) {
        validate_f32(bytes, start + 5, "阴影距离")?;
    }
    offset = shadow_end;
    if offset == bytes.len() {
        return Ok(());
    }

    let ik_count = read_count(bytes, offset, "IK")?;
    offset += 4;
    for _ in 0..ik_count {
        checked_end(offset, 9, bytes.len(), "IK 帧头")?;
        let entry_count = read_count(bytes, offset + 5, "IK 轨道")?;
        let entries = checked_bytes(entry_count, 21, "IK 轨道")?;
        offset = checked_end(offset + 9, entries, bytes.len(), "IK 帧数据")?;
    }
    Ok(())
}

fn validate_bone_record(bytes: &[u8], start: usize) -> Result<()> {
    for field in [19, 23, 27, 31, 35, 39, 43] {
        validate_f32(bytes, start + field, "骨骼变换")?;
    }
    let q = [31, 35, 39, 43].map(|field| read_f32(bytes, start + field));
    let norm2 = q.iter().map(|value| value * value).sum::<f32>();
    if !norm2.is_finite() || norm2 <= 1.0e-12 {
        return Err(parse_error("骨骼关键帧包含无效四元数"));
    }
    Ok(())
}

fn read_count(bytes: &[u8], offset: usize, section: &str) -> Result<usize> {
    let end = checked_end(offset, 4, bytes.len(), &format!("{}计数", section))?;
    let count = u32::from_le_bytes(bytes[offset..end].try_into().unwrap()) as usize;
    if count > MAX_RECORDS {
        return Err(parse_error(&format!("{}关键帧数量超出限制", section)));
    }
    Ok(count)
}

fn checked_bytes(count: usize, stride: usize, section: &str) -> Result<usize> {
    let size = count
        .checked_mul(stride)
        .ok_or_else(|| parse_error(&format!("{}区段长度溢出", section)))?;
    if size > 1_000_000_000 {
        return Err(parse_error(&format!("{}区段过大", section)));
    }
    Ok(size)
}

fn checked_end(start: usize, length: usize, total: usize, section: &str) -> Result<usize> {
    let end = start
        .checked_add(length)
        .ok_or_else(|| parse_error(&format!("{}偏移溢出", section)))?;
    if end > total {
        return Err(parse_error(&format!("{}数据不完整", section)));
    }
    Ok(end)
}

fn validate_f32(bytes: &[u8], offset: usize, field: &str) -> Result<()> {
    if !read_f32(bytes, offset).is_finite() {
        return Err(parse_error(&format!("{}包含非有限数值", field)));
    }
    Ok(())
}

fn read_f32(bytes: &[u8], offset: usize) -> f32 {
    f32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn parse_error(message: &str) -> MmdError {
    MmdError::VmdParse(message.to_string())
}
