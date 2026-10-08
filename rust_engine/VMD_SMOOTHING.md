# VMD 平滑工具

工具用于减轻骨骼动画转折时的顿挫。核心实现位于 `rust_engine/src/vmd_smoothing/`，供命令行工具和模型/动作预览器共用。处理结果保留原动作时长；默认保护中心/根骨骼、腿、脚和 IK，保留表情、相机、灯光、阴影及 IK 开关数据。原始 VMD 保留，结果输出到独立文件或目录，已有目标文件不会自动覆盖。当前支持 VMD2，VMD1 会明确报错。

默认强度为 0.35，窗口半径为 2 帧，循环处理默认关闭。窗口半径是当前帧两侧各参与多少帧；增大窗口通常会更柔和，也会削弱短促动作的细节。

项目默认动作已于 2026-10-02 按预览器确认的设置统一处理：全部骨骼轨道（含 IK）、强度 0.6、半径 5、循环开启。实际配置保存在 `rust_engine/vmd-smoothing-project.json`，24 个结果已写入 `common/src/main/resources/assets/mmdskin/default_anim/`；其中 7 个静态动作文件字节未变。原始文件备份位于 `.tmp/vmd-smoothing-sync/original-20261002-d42b166a/`，校验及同步记录位于同级 `validation.json` 和 `sync-report.json`。重新调参时应使用原始备份作为输入，避免在已平滑的资源上重复处理；首尾不适合循环的轨道仍由工具自动回退到非循环处理。

足部和脚趾 IK 的位移另外保护原轨迹每个坐标轴的最小、最大值及其原时刻，保留前后步幅、侧向摆幅和抬脚高度范围。分段三次补偿连同相邻帧一起恢复滤波损失的幅度，结果限制在原位移范围内；整段首末姿态仍固定。识别依据为名称中的 IK 与常见足部名称（如足、脚、つま先、foot、toe、ankle）。普通骨骼和旋转仍采用原时间窗平滑。此处理不等于模型空间的自动锁脚。补偿使用端值和一阶变化量，数学定义参考 [Hermite 插值文档](https://docs.scipy.org/doc/scipy/reference/generated/scipy.interpolate.CubicHermiteSpline.html)。

## 预览和另存

运行 viewer，加载 PMX 模型及 VMD 动作，在左侧平滑面板调节强度、时间窗口与轨道范围，然后生成预览。原版和平滑版可以切换对比；每次生成都从原始动作重新计算。确认效果后点击“另存平滑 VMD”，输出可以在游戏或其他支持 VMD 的程序中使用。左侧面板可以滚动，目录批处理无需加载模型。

平滑面板支持多个独立组，每组分别设置名字、启用状态、骨骼范围、强度、窗口和循环选项。选择组后编辑其参数，可以新增、删除及上下移动组；修改后点击“生成预览”。“全部轨道（含 IK）”处理文件内所有骨骼轨道。每条轨道采用最后一个选中它的启用组参数，各组均从原动作计算；后组强度为零可以保护相应轨道。预览、另存和目录批处理共用全部启用组，组配置和当前组会保存。旧版单组配置自动迁移到第一个组。

也可运行 `viewer <模型.pmx> <动作.vmd> --smooth-preview`，启动后自动生成并显示平滑预览；循环动作再追加 `--smooth-loop`。两个开关放在资源路径后面，原始动作仍保留用于切换对比。

平滑始终保持整段动作的首帧和末帧原姿态，调整中间帧的位移和旋转；中间原关键帧可以改变。循环开关适用于走路、跑步等已确认的循环动作，跨首尾取样后仍保持原首末姿态，首尾附近的平滑权重逐渐减小，避免单独复原端点造成突变。普通片段保持关闭；持续向前位移或首尾明显不一致的动作不能仅靠开关变成自然循环。

默认上半身范围不包含腿部；“全部非 IK 轨道”也不会处理足 IK。要平滑 IK 驱动的腿部动作，在“手动选择轨道”中勾选文件实际包含的 `左足ＩＫ`、`右足ＩＫ`，再生成预览。手动选择允许处理 IK，其首末位置同样保留。足部 IK 和模型约束共同决定是否滑步，增大强度和窗口会改变中间脚部轨迹，可切换原版对比。FBX 动作仍可预览，平滑和保真导出入口针对 VMD。

## 命令行

在项目根目录运行帮助：

```powershell
cargo run --manifest-path rust_engine/Cargo.toml --bin vmd_smooth -- --help
```

单独处理默认跑步动作，下面的强度和窗口是可调整的示例：

```powershell
cargo run --manifest-path rust_engine/Cargo.toml --bin vmd_smooth -- --input common/src/main/resources/assets/mmdskin/default_anim/sprint.vmd --output .tmp/smoothed/sprint.vmd --strength 0.5 --radius 2 --scope upper --loop
```

批量处理目录及子目录，结果保持相对目录结构：

```powershell
cargo run --manifest-path rust_engine/Cargo.toml --bin vmd_smooth -- --input common/src/main/resources/assets/mmdskin/default_anim --output .tmp/smoothed-default --recursive --strength 0.5 --radius 2 --scope upper
```

批量处理混合动作目录时，不统一启用循环。`--scope all` 处理全部骨骼轨道，包括 IK；`--scope all-except-ik` 处理全部非 IK 骨骼，`--bone` 用实际骨骼名称指定处理轨道。输出列出成功、跳过、失败及警告；失败文件不阻止处理其余文件。嵌套在输入中的输出目录会排除，避免反复处理生成结果。

命令行多组处理使用 `--groups`，配置示例为 `rust_engine/vmd-smoothing-groups.example.json`。示例针对跑步，先给全体轨道基础平滑，再给足部 IK 和手肘单独设置参数：

```powershell
.\vmd-smooth.exe --input common/src/main/resources/assets/mmdskin/default_anim/sprint.vmd --output .tmp/sprint_grouped.vmd --groups rust_engine/vmd-smoothing-groups.example.json
```

配置根对象为 `groups` 数组，每组包含 `name`、`enabled`、`strength`、`radius`、`looped`、`selection` 和 `names`；`selection` 支持 `upper_body`、`all_except_ik`、`all`、`named`。最多 64 组，空数组保持原文件。`--groups` 整体指定平滑设置，与 `--strength`、`--radius`、`--loop`、`--scope`、`--bone` 不混用。

强度为零时保留原始文件字节。平滑采用原曲线采样、对称时间窗滤波和四元数旋转处理；恒定轨道保留原始稀疏记录，动态轨道烘焙后的骨骼帧数量可能增加。工具明确校验支持的 VMD 版本和数据边界，拒绝损坏文件，不使用会删除非骨骼数据的旧动画写出路径。

## 在 Rust 中调用

```rust
use mmd_engine::vmd_smoothing::{write_copy, SmoothingOptions, VmdDocument};
use std::path::Path;

let original = VmdDocument::load("sprint.vmd")?;
let result = original.smooth(&SmoothingOptions::default())?;
write_copy(Path::new("sprint_smoothed.vmd"), &result.bytes)?;
```

生成结果包含所选轨道数、处理前后关键帧数及警告。`smooth_bytes` 可以直接处理内存数据，`process_directory` 提供逐文件进度回调，窗口和渲染依赖不属于平滑模块。

分组调用使用 `VmdDocument::smooth_grouped` 或 `smooth_grouped_bytes`，批处理对应 `process_directory_grouped`、`smooth_file_grouped`。`parse_group_config` 和 `serialize_group_config` 共用上述 JSON 格式，`SmoothingGroup` 保存名字、启用状态和独立 `SmoothingOptions`；原单组 API 继续可用。
