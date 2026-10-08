use std::{path::PathBuf, sync::mpsc, thread};

use mmd_engine::vmd_smoothing::{
    batch::{process_directory_grouped, write_copy, BatchProgress, BatchReport},
    SmoothingGroup, SmoothingOptions, SmoothingReport, VmdDocument,
};

enum WorkerEvent {
    Preview(u64, Result<(Vec<u8>, SmoothingReport), String>),
    Batch(BatchProgress),
    BatchDone(Result<BatchReport, String>),
    SaveDone(Result<PathBuf, String>),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WorkerKind {
    Preview,
    Batch,
    Save,
}

pub enum MotionEditAction {
    ApplyPreview(Vec<u8>),
    RestoreOriginal(Vec<u8>),
}

pub struct MotionEdit {
    source_path: Option<PathBuf>,
    source_bytes: Option<Vec<u8>>,
    bone_names: Vec<String>,
    preview_bytes: Option<Vec<u8>>,
    preview_active: bool,
    groups: Vec<SmoothingGroup>,
    active_group_index: usize,
    epoch: u64,
    busy: bool,
    worker_kind: Option<WorkerKind>,
    receiver: Option<mpsc::Receiver<WorkerEvent>>,
    status: String,
    report: Option<SmoothingReport>,
    batch_lines: Vec<String>,
    batch_recursive: bool,
}

impl MotionEdit {
    pub fn new(options: SmoothingOptions) -> Self {
        Self::new_with_groups(
            vec![SmoothingGroup {
                name: "默认平滑组".into(),
                enabled: true,
                options,
            }],
            0,
        )
    }

    pub fn new_with_groups(groups: Vec<SmoothingGroup>, active_group_index: usize) -> Self {
        let groups = if groups.is_empty() {
            vec![default_group()]
        } else {
            groups.into_iter().take(64).collect()
        };
        Self {
            source_path: None,
            source_bytes: None,
            bone_names: Vec::new(),
            preview_bytes: None,
            preview_active: false,
            active_group_index: active_group_index.min(groups.len() - 1),
            groups,
            epoch: 0,
            busy: false,
            worker_kind: None,
            receiver: None,
            status: String::new(),
            report: None,
            batch_lines: Vec::new(),
            batch_recursive: false,
        }
    }

    pub fn options(&self) -> &SmoothingOptions {
        &self.groups[self.active_group_index].options
    }
    pub fn options_mut(&mut self) -> &mut SmoothingOptions {
        &mut self.groups[self.active_group_index].options
    }
    pub fn groups(&self) -> &[SmoothingGroup] {
        &self.groups
    }
    pub fn active_group_index(&self) -> usize {
        self.active_group_index
    }
    pub fn set_active_group_index(&mut self, index: usize) {
        if index < self.groups.len() {
            self.active_group_index = index;
        }
    }
    pub fn add_group(&mut self) -> bool {
        if self.groups.len() >= 64 {
            return false;
        }
        self.groups.push(SmoothingGroup {
            name: format!("平滑组 {}", self.groups.len() + 1),
            enabled: true,
            options: SmoothingOptions {
                selection: mmd_engine::vmd_smoothing::BoneSelection::Named(Vec::new()),
                ..SmoothingOptions::default()
            },
        });
        self.active_group_index = self.groups.len() - 1;
        true
    }
    pub fn remove_active_group(&mut self) -> bool {
        if self.groups.len() <= 1 {
            return false;
        }
        self.groups.remove(self.active_group_index);
        self.active_group_index = self.active_group_index.min(self.groups.len() - 1);
        true
    }
    pub fn move_active_group(&mut self, offset: isize) -> bool {
        let target = self.active_group_index as isize + offset;
        if !(0..self.groups.len() as isize).contains(&target) {
            return false;
        }
        self.groups.swap(self.active_group_index, target as usize);
        self.active_group_index = target as usize;
        true
    }
    pub fn set_group_name(&mut self, index: usize, name: String) {
        if !name.trim().is_empty() {
            if let Some(group) = self.groups.get_mut(index) {
                group.name = name;
            }
        }
    }
    pub fn set_group_enabled(&mut self, index: usize, enabled: bool) {
        if let Some(group) = self.groups.get_mut(index) {
            group.enabled = enabled;
        }
    }
    pub fn bone_names(&self) -> &[String] {
        &self.bone_names
    }
    pub fn source_path(&self) -> Option<&std::path::Path> {
        self.source_path.as_deref()
    }
    pub fn has_source(&self) -> bool {
        self.source_bytes.is_some()
    }
    pub fn has_preview(&self) -> bool {
        self.preview_bytes.is_some()
    }
    pub fn is_busy(&self) -> bool {
        self.busy
    }
    pub fn source_epoch(&self) -> u64 {
        self.epoch
    }
    pub fn status(&self) -> &str {
        &self.status
    }
    pub fn report(&self) -> Option<&SmoothingReport> {
        self.report.as_ref()
    }
    pub fn batch_lines(&self) -> &[String] {
        &self.batch_lines
    }
    pub fn batch_recursive(&self) -> bool {
        self.batch_recursive
    }
    pub fn set_batch_recursive(&mut self, value: bool) {
        self.batch_recursive = value;
    }

    // 只有成功加载的 VMD 才能建立平滑源。
    pub fn set_source(&mut self, path: PathBuf, bytes: Vec<u8>) -> Result<(), String> {
        self.expire_preview();
        self.source_path = None;
        self.source_bytes = None;
        self.bone_names.clear();
        let document = match VmdDocument::from_bytes(&bytes) {
            Ok(document) => document,
            Err(error) => {
                let message = error.to_string();
                self.status = format!("VMD 平滑源校验失败: {message}");
                return Err(message);
            }
        };
        self.source_path = Some(path);
        self.source_bytes = Some(document.original_bytes().to_vec());
        self.bone_names = document.bone_names();
        self.status = format!("源动作已载入，共 {} 条骨骼轨道", self.bone_names.len());
        Ok(())
    }

    pub fn invalidate_for_model_change(&mut self) {
        self.expire_preview();
    }

    pub fn clear_source(&mut self) {
        self.expire_preview();
        self.source_path = None;
        self.source_bytes = None;
        self.bone_names.clear();
        self.status.clear();
    }

    fn expire_preview(&mut self) {
        self.epoch = self.epoch.wrapping_add(1);
        self.preview_bytes = None;
        self.preview_active = false;
        self.report = None;
        if self.worker_kind == Some(WorkerKind::Preview) {
            self.receiver = None;
            self.busy = false;
            self.worker_kind = None;
        }
    }

    pub fn start_preview(&mut self) {
        if self.busy {
            return;
        }
        let Some((source, groups, epoch)) = self.preview_job_snapshot() else {
            self.status = "请先成功加载一个 VMD 动作".into();
            return;
        };
        if let Some(error) = groups
            .iter()
            .filter(|group| group.enabled)
            .find_map(|group| group.options.validate().err())
        {
            self.status = format!("参数无效: {error}");
            return;
        }
        let (tx, rx) = mpsc::channel();
        self.receiver = Some(rx);
        self.busy = true;
        self.worker_kind = Some(WorkerKind::Preview);
        self.status = "正在生成平滑预览…".into();
        thread::spawn(move || {
            let result = mmd_engine::vmd_smoothing::smooth_grouped_bytes(&source, &groups)
                .map(|result| (result.bytes, result.report))
                .map_err(|error| error.to_string());
            let _ = tx.send(WorkerEvent::Preview(epoch, result));
        });
    }

    pub fn start_batch(&mut self, input: PathBuf, output: PathBuf) {
        if self.busy {
            return;
        }
        if let Some(error) = self
            .groups
            .iter()
            .filter(|group| group.enabled)
            .find_map(|group| group.options.validate().err())
        {
            self.status = format!("参数无效: {error}");
            return;
        }
        let groups = self.groups.clone();
        let recursive = self.batch_recursive;
        let (tx, rx) = mpsc::channel();
        self.receiver = Some(rx);
        self.busy = true;
        self.worker_kind = Some(WorkerKind::Batch);
        self.batch_lines.clear();
        self.status = "批量处理已启动".into();
        thread::spawn(move || {
            let result =
                process_directory_grouped(&input, &output, &groups, recursive, |progress| {
                    let _ = tx.send(WorkerEvent::Batch(progress));
                })
                .map_err(|error| error.to_string());
            let _ = tx.send(WorkerEvent::BatchDone(result));
        });
    }

    pub fn start_save(&mut self, path: PathBuf) {
        if self.busy {
            return;
        }
        let Some(bytes) = self.preview_bytes.as_deref().map(ToOwned::to_owned) else {
            self.status = "请先生成平滑预览".into();
            return;
        };
        let (tx, rx) = mpsc::channel();
        self.receiver = Some(rx);
        self.busy = true;
        self.worker_kind = Some(WorkerKind::Save);
        self.status = "正在写入副本…".into();
        thread::spawn(move || {
            let result = write_copy(&path, &bytes)
                .map(|()| path)
                .map_err(|error| error.to_string());
            let _ = tx.send(WorkerEvent::SaveDone(result));
        });
    }

    pub fn poll(&mut self) -> Vec<MotionEditAction> {
        let mut actions = Vec::new();
        let mut events = Vec::new();
        let mut disconnected = false;
        if let Some(receiver) = &self.receiver {
            loop {
                match receiver.try_recv() {
                    Ok(event) => events.push(event),
                    Err(mpsc::TryRecvError::Empty) => break,
                    Err(mpsc::TryRecvError::Disconnected) => {
                        disconnected = true;
                        break;
                    }
                }
            }
        } else {
            return actions;
        }
        for event in events {
            match event {
                WorkerEvent::Preview(epoch, result) => {
                    if epoch != self.epoch {
                        continue;
                    }
                    self.busy = false;
                    self.receiver = None;
                    self.worker_kind = None;
                    match result {
                        Ok((bytes, report)) => {
                            self.status = format!(
                                "预览生成完成：{} 条轨道，{} → {} 个关键帧",
                                report.selected_tracks, report.original_keys, report.output_keys
                            );
                            self.report = Some(report);
                            self.preview_bytes = Some(bytes.clone());
                            actions.push(MotionEditAction::ApplyPreview(bytes));
                        }
                        Err(error) => self.status = format!("平滑失败: {error}"),
                    }
                    break;
                }
                WorkerEvent::Batch(progress) => {
                    let detail = format!(
                        "{}/{} {} → {}: {}",
                        progress.completed,
                        progress.total,
                        progress.entry.input.display(),
                        progress.entry.output.display(),
                        batch_status(&progress.entry.status)
                    );
                    self.batch_lines.push(detail);
                    self.status = format!("批量处理中 {}/{}", progress.completed, progress.total);
                }
                WorkerEvent::BatchDone(result) => {
                    self.busy = false;
                    self.receiver = None;
                    self.worker_kind = None;
                    match result {
                        Ok(report) => {
                            let (ok, skipped, failed) = report.counts();
                            self.status =
                                format!("批量完成：成功 {ok}，跳过 {skipped}，失败 {failed}");
                            self.batch_lines = report
                                .entries
                                .iter()
                                .map(|entry| {
                                    format!(
                                        "{} → {}: {}",
                                        entry.input.display(),
                                        entry.output.display(),
                                        batch_status(&entry.status)
                                    )
                                })
                                .collect();
                        }
                        Err(error) => self.status = format!("批量处理失败: {error}"),
                    }
                    break;
                }
                WorkerEvent::SaveDone(result) => {
                    self.busy = false;
                    self.receiver = None;
                    self.worker_kind = None;
                    self.status = match result {
                        Ok(path) => format!("已另存副本: {}", path.display()),
                        Err(error) => format!("另存失败: {error}"),
                    };
                    break;
                }
            }
        }
        if disconnected && self.receiver.is_some() {
            self.receiver = None;
            self.worker_kind = None;
            self.busy = false;
            self.status = "后台任务异常终止，未收到完成结果".into();
        }
        actions
    }

    pub fn toggle_preview(&self) -> Option<MotionEditAction> {
        self.preview_bytes.as_ref().map(|bytes| {
            if self.preview_active {
                MotionEditAction::RestoreOriginal(self.source_bytes.clone().unwrap_or_default())
            } else {
                MotionEditAction::ApplyPreview(bytes.clone())
            }
        })
    }

    pub fn mark_preview_active(&mut self, active: bool) {
        self.preview_active = active;
    }

    pub fn restore(&self) -> Option<MotionEditAction> {
        self.source_bytes
            .as_ref()
            .map(|bytes| MotionEditAction::RestoreOriginal(bytes.clone()))
    }
}

impl MotionEdit {
    fn preview_job_snapshot(&self) -> Option<(Vec<u8>, Vec<SmoothingGroup>, u64)> {
        self.source_bytes
            .clone()
            .map(|source| (source, self.groups.clone(), self.epoch))
    }
}

fn default_group() -> SmoothingGroup {
    SmoothingGroup {
        name: "默认平滑组".into(),
        enabled: true,
        options: SmoothingOptions::default(),
    }
}

fn batch_status(status: &mmd_engine::vmd_smoothing::batch::BatchStatus) -> String {
    use mmd_engine::vmd_smoothing::batch::BatchStatus;
    match status {
        BatchStatus::Processed(report) => {
            let warnings = if report.warnings.is_empty() {
                String::new()
            } else {
                format!("；提示：{}", report.warnings.join("；"))
            };
            format!(
                "成功，{} 轨道 {} → {} 键{}",
                report.selected_tracks, report.original_keys, report.output_keys, warnings
            )
        }
        BatchStatus::Skipped(reason) => format!("跳过：{reason}"),
        BatchStatus::Failed(reason) => format!("失败：{reason}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;



    #[test]
    fn every_parameter_snapshot_uses_original_bytes() {
        let mut edit = MotionEdit::new(SmoothingOptions::default());
        edit.source_bytes = Some(vec![10, 20, 30]);
        let first = edit.preview_job_snapshot().unwrap();
        edit.options_mut().strength = 0.75;
        let second = edit.preview_job_snapshot().unwrap();
        assert_eq!(first.0, second.0);
        assert_eq!(second.0, vec![10, 20, 30]);
        assert_ne!(first.1[0].options.strength, second.1[0].options.strength);
    }



    #[test]
    fn source_change_invalidates_same_frame_preview_actions() {
        let mut edit = MotionEdit::new(SmoothingOptions::default());
        edit.source_bytes = Some(vec![1]);
        edit.preview_bytes = Some(vec![2]);
        let queued_epoch = edit.source_epoch();
        assert!(edit.toggle_preview().is_some());
        edit.clear_source();
        assert_ne!(queued_epoch, edit.source_epoch());
    }
}
