//! 独立 VMD 平滑接口。

pub mod batch;
mod config;
mod document;
mod filter;

use crate::{MmdError, Result};
pub use batch::{
    process_directory, process_directory_grouped, smooth_file, smooth_file_grouped, write_copy,
    BatchEntry, BatchProgress, BatchReport, BatchStatus,
};
pub use config::{parse_group_config, serialize_group_config};
pub use document::VmdDocument;

/// 选择需要平滑的骨骼轨道。
#[derive(Debug, Clone)]
pub enum BoneSelection {
    /// 按常见 MMD 名称筛选上半身与躯干。
    UpperBody,
    /// 处理除名称疑似 IK 控制骨骼外的轨道。
    AllExceptIk,
    /// 处理所有骨骼轨道，包括 IK。
    All,
    /// 精确匹配给定轨道名。
    Named(Vec<String>),
}

/// 一组独立的轨道选择与平滑参数。
#[derive(Debug, Clone)]
pub struct SmoothingGroup {
    pub name: String,
    pub enabled: bool,
    pub options: SmoothingOptions,
}

/// 时间窗与轨道选择参数。
#[derive(Debug, Clone)]
pub struct SmoothingOptions {
    /// 原姿态与平滑姿态的混合量。
    pub strength: f32,
    /// 时间窗半径，单位为 VMD 帧。
    pub radius: u32,
    /// 是否跨首尾取样；首末姿态仍保持原样。
    pub looped: bool,
    /// 参与处理的骨骼轨道。
    pub selection: BoneSelection,
}

impl Default for SmoothingOptions {
    fn default() -> Self {
        Self {
            strength: 0.35,
            radius: 2,
            looped: false,
            selection: BoneSelection::UpperBody,
        }
    }
}

impl SmoothingOptions {
    /// 检查参数范围，避免无界采样。
    pub fn validate(&self) -> Result<()> {
        if !self.strength.is_finite() || !(0.0..=1.0).contains(&self.strength) {
            return Err(MmdError::VmdParse(
                "平滑强度必须是 0 到 1 之间的有限数值".into(),
            ));
        }
        if self.radius > 120 {
            return Err(MmdError::VmdParse("平滑窗口半径不能超过 120 帧".into()));
        }
        if let BoneSelection::Named(names) = &self.selection {
            if names.iter().any(|name| name.trim().is_empty()) {
                return Err(MmdError::VmdParse("指定骨骼名不能为空".into()));
            }
        }
        Ok(())
    }
}

/// 平滑前后的关键帧统计与适用性提示。
#[derive(Debug, Clone, Default)]
pub struct SmoothingReport {
    pub selected_tracks: usize,
    pub original_keys: usize,
    pub output_keys: usize,
    pub warnings: Vec<String>,
}

/// VMD 输出数据与处理报告。
#[derive(Debug, Clone)]
pub struct SmoothingResult {
    pub bytes: Vec<u8>,
    pub report: SmoothingReport,
}

/// 从内存中的 VMD 生成平滑副本。
pub fn smooth_bytes(bytes: &[u8], options: &SmoothingOptions) -> Result<SmoothingResult> {
    VmdDocument::from_bytes(bytes)?.smooth(options)
}

/// 按组顺序确定每条轨道的最终所有者，再从原文件独立计算。
pub fn smooth_grouped_bytes(bytes: &[u8], groups: &[SmoothingGroup]) -> Result<SmoothingResult> {
    VmdDocument::from_bytes(bytes)?.smooth_grouped(groups)
}

#[cfg(test)]
mod group_tests;
#[cfg(test)]
mod tests;
