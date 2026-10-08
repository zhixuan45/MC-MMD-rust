use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use super::{
    smooth_bytes, smooth_grouped_bytes, SmoothingGroup, SmoothingOptions, SmoothingReport,
};

static TEMP_ID: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone)]
pub struct BatchProgress {
    pub completed: usize,
    pub total: usize,
    pub entry: BatchEntry,
}

#[derive(Debug, Clone)]
pub struct BatchEntry {
    pub input: PathBuf,
    pub output: PathBuf,
    pub status: BatchStatus,
}

#[derive(Debug, Clone)]
pub enum BatchStatus {
    Processed(SmoothingReport),
    Skipped(String),
    Failed(String),
}

#[derive(Debug, Clone, Default)]
pub struct BatchReport {
    pub entries: Vec<BatchEntry>,
}

impl BatchReport {
    /// 返回成功、跳过、失败数量。
    pub fn counts(&self) -> (usize, usize, usize) {
        self.entries.iter().fold((0, 0, 0), |mut counts, entry| {
            match &entry.status {
                BatchStatus::Processed(_) => counts.0 += 1,
                BatchStatus::Skipped(_) => counts.1 += 1,
                BatchStatus::Failed(_) => counts.2 += 1,
            }
            counts
        })
    }
}

/// 将完整数据写入同目录临时文件，再用硬链接原子提交且不覆盖目标。
pub fn write_copy(output: &Path, bytes: &[u8]) -> crate::Result<()> {
    let parent = output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    let name = output
        .file_name()
        .ok_or_else(|| crate::MmdError::Animation("输出路径缺少文件名".into()))?;
    let mut temp_path = None;
    let mut temp_file: Option<File> = None;
    for _ in 0..32 {
        let id = TEMP_ID.fetch_add(1, Ordering::Relaxed);
        let path = parent.join(format!(
            ".{}.{}.{}.tmp",
            name.to_string_lossy(),
            std::process::id(),
            id
        ));
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(file) => {
                temp_path = Some(path);
                temp_file = Some(file);
                break;
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error.into()),
        }
    }
    let temp_path =
        temp_path.ok_or_else(|| crate::MmdError::Animation("无法创建唯一临时文件".into()))?;
    let result = (|| {
        let mut file = temp_file.expect("临时文件已创建");
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        fs::hard_link(&temp_path, output)?;
        Ok::<(), std::io::Error>(())
    })();
    let _ = fs::remove_file(&temp_path);
    result.map_err(crate::MmdError::from)
}

pub fn smooth_file(
    input: &Path,
    output: &Path,
    options: &SmoothingOptions,
) -> crate::Result<SmoothingReport> {
    options.validate()?;
    let input_path = fs::canonicalize(input)?;
    let output_path = absolute_lexical(output)?;
    if input_path == output_path || (output.exists() && fs::canonicalize(output)? == input_path) {
        return Err(crate::MmdError::Animation(
            "输入与输出路径相同，拒绝覆盖源文件".into(),
        ));
    }
    let bytes = fs::read(&input_path)?;
    let result = smooth_bytes(&bytes, options)?;
    write_copy(&output_path, &result.bytes)?;
    Ok(result.report)
}

/// 使用分组参数处理单个文件。
pub fn smooth_file_grouped(
    input: &Path,
    output: &Path,
    groups: &[SmoothingGroup],
) -> crate::Result<SmoothingReport> {
    validate_groups(groups)?;
    let input_path = fs::canonicalize(input)?;
    let output_path = absolute_lexical(output)?;
    if input_path == output_path || (output.exists() && fs::canonicalize(output)? == input_path) {
        return Err(crate::MmdError::Animation(
            "输入与输出路径相同，拒绝覆盖源文件".into(),
        ));
    }
    let bytes = fs::read(&input_path)?;
    let result = smooth_grouped_bytes(&bytes, groups)?;
    write_copy(&output_path, &result.bytes)?;
    Ok(result.report)
}

pub fn process_directory(
    input: &Path,
    output: &Path,
    options: &SmoothingOptions,
    recursive: bool,
    mut on_progress: impl FnMut(BatchProgress),
) -> crate::Result<BatchReport> {
    options.validate()?;
    let input_root = fs::canonicalize(input)?;
    if !input_root.is_dir() {
        return Err(crate::MmdError::Animation("批处理输入必须是目录".into()));
    }
    let output_root = absolute_lexical(output)?;
    if input_root == output_root || (output.exists() && fs::canonicalize(output)? == input_root) {
        return Err(crate::MmdError::Animation("输入与输出目录相同".into()));
    }
    let excluded_output = absolute_lexical(&output_root)?;
    if input_root.starts_with(&excluded_output) {
        return Err(crate::MmdError::Animation(
            "输出目录不能是输入目录的祖先".into(),
        ));
    }
    let mut files = Vec::new();
    collect_vmds(&input_root, &excluded_output, recursive, &mut files)?;
    files.sort();
    let total = files.len();
    let mut report = BatchReport::default();
    for (index, file) in files.into_iter().enumerate() {
        let relative = file
            .strip_prefix(&input_root)
            .expect("扫描路径位于输入目录内");
        let target = output_root.join(relative);
        let status = if target.exists() {
            BatchStatus::Skipped("目标文件已存在".into())
        } else {
            match smooth_file(&file, &target, options) {
                Ok(value) => BatchStatus::Processed(value),
                Err(error) if target.exists() => {
                    BatchStatus::Skipped(format!("目标文件已存在：{error}"))
                }
                Err(error) => BatchStatus::Failed(error.to_string()),
            }
        };
        let entry = BatchEntry {
            input: file,
            output: target,
            status,
        };
        report.entries.push(entry.clone());
        on_progress(BatchProgress {
            completed: index + 1,
            total,
            entry,
        });
    }
    Ok(report)
}

/// 使用分组参数批处理目录中的 VMD 文件。
pub fn process_directory_grouped(
    input: &Path,
    output: &Path,
    groups: &[SmoothingGroup],
    recursive: bool,
    mut on_progress: impl FnMut(BatchProgress),
) -> crate::Result<BatchReport> {
    validate_groups(groups)?;
    let input_root = fs::canonicalize(input)?;
    if !input_root.is_dir() {
        return Err(crate::MmdError::Animation("批处理输入必须是目录".into()));
    }
    let output_root = absolute_lexical(output)?;
    if input_root == output_root || (output.exists() && fs::canonicalize(output)? == input_root) {
        return Err(crate::MmdError::Animation("输入与输出目录相同".into()));
    }
    let excluded_output = absolute_lexical(&output_root)?;
    if input_root.starts_with(&excluded_output) {
        return Err(crate::MmdError::Animation(
            "输出目录不能是输入目录的祖先".into(),
        ));
    }
    let mut files = Vec::new();
    collect_vmds(&input_root, &excluded_output, recursive, &mut files)?;
    files.sort();
    let total = files.len();
    let mut report = BatchReport::default();
    for (index, file) in files.into_iter().enumerate() {
        let relative = file
            .strip_prefix(&input_root)
            .expect("扫描路径位于输入目录内");
        let target = output_root.join(relative);
        let status = if target.exists() {
            BatchStatus::Skipped("目标文件已存在".into())
        } else {
            match smooth_file_grouped(&file, &target, groups) {
                Ok(value) => BatchStatus::Processed(value),
                Err(error) if target.exists() => {
                    BatchStatus::Skipped(format!("目标文件已存在：{error}"))
                }
                Err(error) => BatchStatus::Failed(error.to_string()),
            }
        };
        let entry = BatchEntry {
            input: file,
            output: target,
            status,
        };
        report.entries.push(entry.clone());
        on_progress(BatchProgress {
            completed: index + 1,
            total,
            entry,
        });
    }
    Ok(report)
}

fn validate_groups(groups: &[SmoothingGroup]) -> crate::Result<()> {
    if groups.len() > 64 {
        return Err(crate::MmdError::VmdParse("平滑组最多允许 64 组".into()));
    }
    for group in groups {
        if group.name.trim().is_empty() {
            return Err(crate::MmdError::VmdParse("平滑组名称不能为空".into()));
        }
        if group.enabled {
            group.options.validate()?;
        }
    }
    Ok(())
}

fn collect_vmds(
    dir: &Path,
    output_root: &Path,
    recursive: bool,
    files: &mut Vec<PathBuf>,
) -> crate::Result<()> {
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        let normalized = absolute_lexical(&path)?;
        if normalized.starts_with(output_root)
            || path
                .file_name()
                .is_some_and(|n| n.to_string_lossy().starts_with(".vmd_smooth_"))
        {
            continue;
        }
        let kind = entry.file_type()?;
        if kind.is_dir() && recursive {
            collect_vmds(&path, output_root, recursive, files)?;
        } else if kind.is_file()
            && path
                .extension()
                .is_some_and(|ext| ext.to_string_lossy().eq_ignore_ascii_case("vmd"))
        {
            files.push(fs::canonicalize(path)?);
        }
    }
    Ok(())
}

fn absolute_lexical(path: &Path) -> crate::Result<PathBuf> {
    let joined = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    let mut out = PathBuf::new();
    for component in joined.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    // 统一已存在祖先目录的符号链接和平台路径前缀。
    let mut ancestor = out.as_path();
    let mut tail = Vec::new();
    while !ancestor.exists() {
        let name = ancestor
            .file_name()
            .ok_or_else(|| crate::MmdError::Animation("无法解析输出路径".into()))?;
        tail.push(name.to_os_string());
        ancestor = ancestor
            .parent()
            .ok_or_else(|| crate::MmdError::Animation("无法解析输出路径".into()))?;
    }
    let mut resolved = fs::canonicalize(ancestor)?;
    for name in tail.iter().rev() {
        resolved.push(name);
    }
    Ok(resolved)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_dir() -> PathBuf {
        let id = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("mmd-batch-{}-{id}", std::process::id()));
        fs::create_dir_all(&path).unwrap();
        path
    }

    fn options() -> SmoothingOptions {
        SmoothingOptions {
            strength: 0.0,
            ..SmoothingOptions::default()
        }
    }

    fn valid_empty_vmd2() -> Vec<u8> {
        let mut bytes = vec![0; 50];
        bytes[..25].copy_from_slice(b"Vocaloid Motion Data 0002");
        bytes.extend_from_slice(&0_u32.to_le_bytes()); // 骨骼
        for _ in 0..5 {
            bytes.extend_from_slice(&0_u32.to_le_bytes());
        }
        bytes
    }

    #[test]
    fn write_copy_does_not_overwrite_existing_file() {
        let root = temp_dir();
        let target = root.join("out.vmd");
        fs::write(&target, b"keep").unwrap();
        assert!(write_copy(&target, b"replace").is_err());
        assert_eq!(fs::read(target).unwrap(), b"keep");
        fs::remove_dir_all(root).unwrap();
    }



    #[test]
    fn rejects_output_directory_that_contains_input() {
        let root = temp_dir();
        let input = root.join("input");
        fs::create_dir_all(&input).unwrap();
        assert!(process_directory(&input, &root, &options(), true, |_| {}).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}
