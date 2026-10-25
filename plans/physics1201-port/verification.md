# 构建、演算与安装记录

分支 `codex/physics1201-port`。主线基线 `9333b83197ea443e78db517896e00fc24e15be06`；上游 1.20.1 / 1.20-vr 基线 `5fe18f8fb741d25c31c89cdb22643609917446a0`；Bullet `63c4d67e337017f9d8b298c900e9aabdb69296e7`。

## 实现边界

默认物理使用导入的上游刚体、关节、世界、运动学同步、惯性、初始化、重置、步进和骨骼回写。类型 2 在运行时映射为 1，原始 PMX 不修改。上游源码适配仅含独立 C ABI 前缀、共享模式枚举、只读名称/姿态诊断与宿主 API 包装；原始来源 blob 见 source-provenance.json，许可证随导入源码保留。

主线 Java、渲染、UI、动画播放及其他资源保留。打包与之前主线实验包对比：823 个非 native 文件逐项 SHA-256 完全相同，无新增、删除或变化。旧物理源保留作为 legacy-physics 显式参考，但默认模型不创建旧世界。

旧自动补碰撞体、裙摆补关节、M- 头发限制/弹簧、尾巴增强、渲染预测和并行世界不参与默认演算。旧增强配置不生效，上游支持的重力、步频、子步、惯性和速度上限等映射现有配置。上游 solver 为 10；高级接触/关节诊断不可用，日志明确注明。原旧后端策略测试仅在 legacy-physics 下运行，不列为本实验通过项。

## 执行结果

- cargo check --offline --locked --lib：通过。
- cargo test --offline --locked --lib --test upstream1201 -- --test-threads=1：112 项库测试通过、2 项忽略；2 项新接入测试通过。新测试覆盖实际 Bullet 步进、2/1 同行为、MotionState 回写、原始拓扑、即时重置及旧世界未分配。
- cargo build --offline --locked --release --lib：通过（54.80 秒）。有未使用旧参考模块的编译警告。
- Zulu JDK 21 + wrapper Gradle 8.11.1，:neoforge:shadowJar -PnativeResourcesPath=<stage> --offline：通过（49 秒）。
- 使用当前实例的 TDA 方舟指令[洛天依][杏辞] Ver 1.00.pmx，以及用户给出的损坏名称动作目录；两个含骨骼轨道的 VMD 各执行 9746 步，dt=1/60，所有骨骼矩阵保持有限值。511 轨道动作长度 4872 帧，另一个动作仅 1 轨道/0 帧；camera.vmd 跳过。
- PMX 原始模式数量 41/407/22；运行时 41 运动学、429 物理、0 位置对齐模式，470 刚体/714 关节。原始模式不改变，无补体或补关节。
- 演算仅验证链路、拓扑及有限值，不能证明视觉稳定。类型 2 骨骼单步位移最大值为 14.05 / 23.86 模型单位（含初始化/动作切换），没有据此宣称抖动修复。
- git diff --check：通过。

## 安装

2026-10-24 23:36:42 +08:00，确认游戏未运行，备份原包后安装至当前 1.21.1-NeoForge_21.1.252 实例。安装 JAR 与 release DLL/包内 DLL 的 SHA-256 已核对。详细路径和哈希见 installation.json。旧包保存在 codex-backups/physics1201-port/20261024-233642/，可恢复。

未启动游戏，未完成游戏视觉验收。请使用同一模型和动作，重点比较左右极限转头时的长发搭肩、末端抖动及衣物碰撞。

构建和演算原始日志位于 build/physics1201-port/；可复用探针为 rust_engine/examples/probe_mode_override_model.rs。


## PR 验收范围（2026-10-25）

用户反馈：当前实验包除 teio-1 尾巴闲置抬起以外，其余物理行为测试未见问题，同意先发起 PR。

本次提交保留完整上游物理链、2 视为 1、原始刚体/关节拓扑及非物理功能。后续修正包括：玩家坐标原样以方块传入惯性计算；惯性力采用 F=m*a，不再除以渲染 dt；舞台动作开始时的物理重置受配置开关控制（默认开启）。这些修改与首次移植的非 native 资源完全相同结论不同：舞台开关有明确的 Java/UI/语言资源变更。

尾巴闲置抬起仍未验收。其分类、波形倍率、逐节延迟和偏心施力试验均不包含在 PR 内；默认上游后端继续不施加尾巴附加力。legacy-physics 参考实现保留原行为。尾巴问题单独跟进，不把代码探针或构建通过记为视觉验收。


本次 PR 检查：cargo test --offline --locked --lib --test upstream1201 -- --test-threads=1，113 项库测试通过、2 项忽略；新增惯性单步回归后，upstream1201 接入测试 3 项通过。Zulu JDK 21 / Gradle 8.11.1，:common:test :fabric:compileJava :neoforge:shadowJar --offline 通过（1m 9s）。导入 C++ 的行尾空白已清理。主线已快进至 bc3e3f4，未改动主线既有渲染成果。本次只创建 PR，不执行合并或安装。
