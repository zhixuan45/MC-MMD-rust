use egui::{ComboBox, RichText, Ui};
use mmd_engine::vmd_smoothing::BoneSelection;
use rfd::FileDialog;

use super::motion_edit::{MotionEdit, MotionEditAction};

pub struct SmoothingPanel;

impl SmoothingPanel {
    pub fn show(
        ui: &mut Ui,
        motion: &mut MotionEdit,
        can_preview: bool,
    ) -> (bool, Vec<MotionEditAction>) {
        let mut changed = false;
        let mut actions = Vec::new();
        ui.separator();
        ui.label(RichText::new("VMD 平滑").strong());
        if let Some(path) = motion.source_path() {
            ui.small(format!("平滑源（已成功加载）: {}", path.display()));
        } else {
            ui.small("请先成功加载 VMD；输入框中的未加载路径不会作为平滑源。");
        }
        ui.add_enabled_ui(!motion.is_busy(), |ui| {
            let mut selected = motion.active_group_index();
            ui.horizontal(|ui| {
                ui.label("平滑组（后面的组优先）");
                if ui
                    .add_enabled(motion.groups().len() < 64, egui::Button::new("＋ 添加组"))
                    .clicked()
                {
                    motion.add_group();
                    selected = motion.active_group_index();
                    changed = true;
                }
                if ui
                    .add_enabled(motion.groups().len() > 1, egui::Button::new("删除组"))
                    .clicked()
                {
                    motion.remove_active_group();
                    selected = motion.active_group_index();
                    changed = true;
                }
            });
            let group_rows: Vec<_> = motion
                .groups()
                .iter()
                .enumerate()
                .map(|(index, group)| (index, group.name.clone(), group.enabled))
                .collect();
            for (index, group_name, group_enabled) in group_rows {
                ui.horizontal(|ui| {
                    if ui
                        .selectable_label(selected == index, format!("{}.", index + 1))
                        .clicked()
                    {
                        changed |= selected != index;
                        selected = index;
                    }
                    let mut enabled = group_enabled;
                    if ui.checkbox(&mut enabled, "启用").changed() {
                        motion.set_group_enabled(index, enabled);
                        changed = true;
                    }
                    let mut name = group_name;
                    if ui
                        .add(egui::TextEdit::singleline(&mut name).desired_width(110.0))
                        .changed()
                    {
                        motion.set_group_name(index, name);
                        changed = true;
                    }
                });
            }
            motion.set_active_group_index(selected);
            ui.horizontal(|ui| {
                if ui
                    .add_enabled(selected > 0, egui::Button::new("↑ 上移"))
                    .clicked()
                {
                    motion.move_active_group(-1);
                    changed = true;
                }
                if ui
                    .add_enabled(
                        selected + 1 < motion.groups().len(),
                        egui::Button::new("↓ 下移"),
                    )
                    .clicked()
                {
                    motion.move_active_group(1);
                    changed = true;
                }
                ui.small("同一轨道由最后一个匹配的启用组处理");
            });
            ui.label(format!(
                "当前编辑：{}",
                motion.groups()[motion.active_group_index()].name
            ));
            changed |= ui
                .add(
                    egui::Slider::new(&mut motion.options_mut().strength, 0.0..=1.0)
                        .text("强度")
                        .step_by(0.01),
                )
                .changed();
            changed |= ui
                .add(
                    egui::Slider::new(&mut motion.options_mut().radius, 1..=30)
                        .text("窗口半径（帧）"),
                )
                .changed();
            changed |= ui
                .checkbox(&mut motion.options_mut().looped, "循环动作")
                .on_hover_text("跨首尾取样，但保持原动作的首帧和末帧姿态。")
                .changed();
            ui.small("保持整段动作首尾姿态，平滑中间帧的位移和旋转。");
            ui.small("足 IK 保留原位移幅度，避免步幅和抬脚高度缩小。");

            let track_names = motion.bone_names().to_vec();
            let selection = &mut motion.options_mut().selection;
            let selection_label = match selection {
                BoneSelection::UpperBody => "上半身（不含腿与 IK）",
                BoneSelection::AllExceptIk => "全部非 IK 轨道",
                BoneSelection::All => "全部轨道（含 IK）",
                BoneSelection::Named(_) => "手动选择轨道",
            };
            ComboBox::from_label("处理轨道")
                .selected_text(selection_label)
                .show_ui(ui, |ui| {
                    if ui
                        .selectable_label(
                            matches!(&*selection, BoneSelection::UpperBody),
                            "上半身（不含腿与 IK）",
                        )
                        .clicked()
                    {
                        *selection = BoneSelection::UpperBody;
                        changed = true;
                    }
                    if ui
                        .selectable_label(
                            matches!(&*selection, BoneSelection::All),
                            "全部轨道（含 IK）",
                        )
                        .clicked()
                    {
                        *selection = BoneSelection::All;
                        changed = true;
                    }
                    if ui
                        .selectable_label(
                            matches!(&*selection, BoneSelection::AllExceptIk),
                            "全部非 IK 轨道",
                        )
                        .clicked()
                    {
                        *selection = BoneSelection::AllExceptIk;
                        changed = true;
                    }
                    if ui
                        .selectable_label(
                            matches!(&*selection, BoneSelection::Named(_)),
                            "手动选择轨道",
                        )
                        .clicked()
                    {
                        if !matches!(&*selection, BoneSelection::Named(_)) {
                            *selection = BoneSelection::Named(Vec::new());
                        }
                        changed = true;
                    }
                });

            if let BoneSelection::Named(names) = selection {
                ui.label("选择文件中实际存在的骨骼：");
                ui.small("手动选择可包含腿部及左右足 IK。");
                egui::ScrollArea::vertical()
                    .max_height(120.0)
                    .show(ui, |ui| {
                        for name in &track_names {
                            let mut selected = names.contains(name);
                            if ui.checkbox(&mut selected, name).changed() {
                                if selected && !names.contains(name) {
                                    names.push(name.clone());
                                }
                                if !selected {
                                    names.retain(|item| item != name);
                                }
                                changed = true;
                            }
                        }
                    });
            }
        });

        ui.small("修改组参数、顺序或骨骼选择后，请点击“生成预览”更新效果。");
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    can_preview && !motion.is_busy(),
                    egui::Button::new("生成预览"),
                )
                .clicked()
            {
                motion.start_preview();
            }
            if ui
                .add_enabled(
                    motion.has_preview() && !motion.is_busy(),
                    egui::Button::new("切换原版/平滑版"),
                )
                .clicked()
            {
                if let Some(action) = motion.toggle_preview() {
                    actions.push(action);
                }
            }
            if ui
                .add_enabled(
                    motion.has_source() && !motion.is_busy(),
                    egui::Button::new("恢复原版"),
                )
                .clicked()
            {
                if let Some(action) = motion.restore() {
                    actions.push(action);
                }
            }
        });

        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    motion.has_preview() && !motion.is_busy(),
                    egui::Button::new("另存平滑 VMD"),
                )
                .clicked()
            {
                if let Some(path) = FileDialog::new()
                    .add_filter("VMD 动作", &["vmd"])
                    .set_file_name("smoothed.vmd")
                    .save_file()
                {
                    motion.start_save(path);
                }
            }
            if ui
                .add_enabled(!motion.is_busy(), egui::Button::new("选择目录批量处理"))
                .clicked()
            {
                if let Some(input) = FileDialog::new()
                    .set_title("选择 VMD 输入目录")
                    .pick_folder()
                {
                    if let Some(output) = FileDialog::new().set_title("选择输出目录").pick_folder()
                    {
                        motion.start_batch(input, output);
                    }
                }
            }
        });
        let mut recursive = motion.batch_recursive();
        if ui.checkbox(&mut recursive, "批量递归子目录").changed() {
            motion.set_batch_recursive(recursive);
        }
        if !motion.status().is_empty() {
            ui.label(motion.status());
        }
        if let Some(report) = motion.report() {
            ui.small(format!(
                "轨道 {}；关键帧 {} → {}",
                report.selected_tracks, report.original_keys, report.output_keys
            ));
            for warning in &report.warnings {
                ui.colored_label(egui::Color32::YELLOW, warning);
            }
        }
        if !motion.batch_lines().is_empty() {
            egui::CollapsingHeader::new(format!("批处理明细（{} 项）", motion.batch_lines().len()))
                .default_open(false)
                .show(ui, |ui| {
                    egui::ScrollArea::vertical()
                        .max_height(150.0)
                        .show(ui, |ui| {
                            for line in motion.batch_lines() {
                                ui.label(line);
                            }
                        });
                });
        }
        (changed, actions)
    }
}
