# BSL 单模型性能排查（2026-10-23）

本轮只排查与取证，没有修改生产代码、替换游戏安装包或改变物理参数。用户在运行中的游戏内完成了垂直同步、三渲二、小人偶和 BSL 的对照切换。

## 结论与证据边界

1. **已确认的回退问题：BSL 下关闭三渲二，会进入昂贵的逐三角形透明排序/绘制兼容路径。** 世界模型每次约 35,015 次透明 draw，小人偶另有约 6,054 次；主要成本是提交，不是排序算法。此问题解释了关闭三渲二后反而更慢，不能把它当作三渲二开启场景的根因。
2. **三渲二开启时，旧 CPU 透明排序不是当前世界模型的主要热点。** 世界透明队列已接管，日志中世界模型每次为 38 次不透明、40 次透明 draw，旧排序和几何回读计数为零。
3. **存在尚未量化的同步点及计时缺口。** 实际安装包在延后透明合成前，每帧同步读取 GPU 溢出标志；现有 CPU/GPU 模型绘制计时不覆盖这次延后合成及其读回等待。日志中的 `READBACK=0` 不能证明整条世界透明管线没有读回。
4. **物理是已测得的 CPU 成本，但没有重复求值证据。** BSL 三渲二基线的原生更新约 6.7–7.1 ms/帧，其中物理约 6.1–6.5 ms；世界与小人偶复用阴影阶段求值。单模型的 `native.parallelBatches=0` 不能直接解释为并行失效。
5. **约 30 FPS 的窗口存在限帧干扰。** ModernUI 配置明确设置窗口失焦时 30 FPS。因此 33.3 ms 平台不能直接认定为 GPU 瓶颈或垂直同步问题；本轮没有逐帧焦点记录。

**尚未闭合：三渲二 + BSL 前台场景的首要耗时阶段。** 已获得足够证据排除“继续优化旧 CPU 排序”作为该路径的优先方向，但不能用现有局部计时把余下帧时间全部归给透明 resolve、BSL 或物理。

## 实际运行版本与配置

- 实例：`D:/minecraft/.minecraft/versions/1.21.1-NeoForge_21.1.252`。
- 实际加载：`mmdskin-neoforge-1.10alpha-1.21.1-pr21-water-r6.jar`。
- 安装 JAR SHA-256：`df27c0cbd405c44428a02abbdc3f1759fc1cb46441954fabd149831a9c3f32fb`，与仓库 `neoforge/build/libs/` 下同名文件逐字节一致。
- Iris `1.8.14-beta.1+mc1.21.1`，BSL `10.1.8`，GPU Compute 蒙皮。
- 单个世界模型：`TDA 方舟指令[洛天依][杏辞] Ver 1.00`。启用小人偶时，另有同模型的 `PAPERDOLL` 绘制。
- 日志 viewport：2560 × 1494；RTX 5070 Ti Laptop GPU；Ryzen 9 7945HX。
- 基线：世界透明队列开、透明层数 4、描边开、小人偶 ALWAYS / FPS_60。
- 物理：60 Hz、solver iterations 30、最大子步 5。日志记录 470 个刚体、748 个约束。
- Minecraft 配置 `maxFps=120`；垂直同步初始开启，随后用户关闭。
- `debugGpuLog=true`，`debugTransparencyStages=true`；无需增加逐帧日志。

工作区在本轮开始时已有 Iris 水面/透明合成未提交改动，期间相关源文件继续发生变化。本报告以安装包字节码、安装包 shader 和游戏日志为准，不把后续工作区变化当作已运行修复。安装包 resolve shader 与当前工作区已有差异，当前工作区新增的空像素及单节点快路径未包含在本轮 `water-r6` 对照中。

## 游戏内对照

所有日期均为 2026-10-23，时区 Asia/Shanghai。下表的数值是指定时段内**各日志窗口中位数/P95 的范围**，不是合并后的总体分位数。HUD 边界帧间隔也不等于独立 GPU 帧耗时。对照切换时剔除菜单、重载和模型数量混合的明显过渡窗口，但没有逐帧焦点、相机或动作快照，不能视为严格同条件基准。

| 条件 | 日志时段 | 帧间隔中位数范围 | 各窗口 P95 范围 | 解读 |
| --- | --- | --- | --- | --- |
| BSL + 三渲二 + 小人偶，垂直同步开 | 22:46:50–22:47:15 | 33.326–33.594 ms | 35.862–36.869 ms | 约 30 FPS 平台，受同步/失焦限制的可能性尚存 |
| BSL + 三渲二 + 小人偶，垂直同步关 | 22:51:08–22:51:18 | 28.513–31.490 ms | 32.807–34.949 ms | 关闭垂直同步后仍有实际开销 |
| BSL + 普通渲染 + 小人偶，垂直同步关 | 22:51:53–22:52:14 | 74.404–78.393 ms | 85.920–90.483 ms | 绘制回退是明确热点，远高于 30 FPS 限制的帧间隔 |
| BSL + 三渲二，小人偶关、垂直同步关 | 22:52:49–22:53:44 | 26.967–33.945 ms | 30.192–50.735 ms | CPU 绘制减少，但剩余帧间隔仍明显；不能给出精确收益 |
| 三渲二开，BSL/小人偶/垂直同步关 | 22:54:39–22:55:14 | 18.249–33.226 ms | 22.878–38.725 ms | 有较快窗口，也有约 30 FPS 平台；不能把范围直接相减当作 BSL 固定成本 |

失焦限帧来自实例 `config/ModernUI/client.toml:29–37`：注释说明窗口失焦限帧含义，`framerateInactive=30`，`framerateMinimized=0`。没有修改这份配置，也没有实测焦点状态，故只能确认该限制存在，不能逐窗口断言它已经生效。

## 已定位的普通 Iris 路径回退

普通渲染最后一个透明统计窗口：22:52:14 前的 WORLD 记录，68 次调用。

| 指标（按调用归一化） | WORLD | PAPERDOLL |
| --- | ---: | ---: |
| 候选/可见三角形 | 57,009 | 10,970 |
| 透明 runs / draw | 35,014.74 | 6,054.13 |
| 几何读回 | 482,172 bytes | 482,172 bytes |
| 索引上传 | 684,108 bytes | 131,640 bytes |
| 批量程序拒绝 / 回退次数 | 1 / 1 | 1 / 1 |
| READBACK 平均耗时 | 2.372 ms | 2.590 ms |
| SORT 平均耗时 | 0.668 ms | 0.303 ms |
| INDEX_BUILD 平均耗时 | 3.545 ms | 0.705 ms |
| SUBMIT 平均耗时 | 31.142 ms | 5.261 ms |

同一阶段 `[MMD性能]` 的世界 body CPU 时间为 38.504–42.926 ms/帧，小人偶 body 为 9.925–10.807 ms/帧。这些计时包含/重叠于各自父阶段，不能再把表内阶段与 body 相加。

代码链：`SubMeshDrawHelper.draw` → `TransparencyPassPolicy.useSortedSubmission` → `TranslucentDrawOrder.prepare` → 当前普通 Iris 程序没有注册 `TranslucentBatchShader` → `BATCH_REJECT_PROGRAM` → CPU 兼容排序与每个 run 的 `glDrawElements`。默认 `mmdskin.fastPlainShaderTransparency` 未启用时允许 GPU 几何读回。

三角形排序打散材质连续性，形成大量 runs；候选缓存命中并不能减少当帧绘制次数。若后续修复此分支，应保留排序/透明与 Iris 材质语义，并单独评估有序批量提交方案；不能仅靠强行关闭透明或改回未排序绘制宣称修复。

## 三渲二开启路径的实际行为

基线 WORLD 记录为 38 次不透明、40 次透明绘制，SHADOW 为 13 次绘制。`WORLD` 的 `GPU_PREPARE=0` 和 `BATCH_SUBMISSIONS=0` 在此不是批处理缺失证据：共享世界队列已接管，不走旧透明排序/批量准备路径。它与普通渲染的 `BATCH_REJECT_PROGRAM=1` 必须区分。

小人偶在三渲二下另有约 321 次透明 draw、27 次 depth seal，属于多层透明流程。其 body CPU 约 0.58–0.62 ms/帧，关闭后为零。GPU 成本未单独按场景计量；本轮不能根据这组对照把小人偶认定为主要瓶颈。

基线每帧 WORLD、SHADOW、PAPERDOLL 各一个实例，但求值只发生在 SHADOW，WORLD/PAPERDOLL 的 reuse 计数匹配帧数。物理约每呈现帧两个 60 Hz 子步；普通渲染降到约 13 FPS 后每帧物理耗时同时上升，不能忽略帧时间增大带来的子步增加，把它另算成一个独立回归。

## 优先测量的遗漏阶段

**延后合成的同步读回。** 已对安装 JAR 执行 `javap -c -p`，确认 `WorldTransparencyPipeline.resolve(boolean)` 在 `glDrawArrays` 前调用 `glGetBufferSubData(..., 4, IntBuffer)` 读取溢出标志。该标志只影响警告；溢出尾部混合在 GPU 中完成。调用可能等待此前 GPU 写入完成，但本轮没有独立测得等待时长。

`GpuSkinningModelRenderer` 的 GPU query 在模型 `renderToon/renderNormal` 返回时结束；世界队列的 resolve 之后才由世界透明阶段触发。故 `gpuQuerydraw` 既不是整个游戏 GPU 帧时间，也没有覆盖延后 resolve；它的样本还混合多个场景调用。`draw.body.WORLD` 的小数值不能排除后续世界透明链开销。

**全屏存储与 resolve。** 安装包按 viewport 分配并清零每像素 28 bytes 的 metadata；本场景约 102.13 MiB，另有默认上限 128 MiB 的节点池。metadata 每帧清零约 3 GiB/s（以 30 FPS 估算），这只是字节量，不是已测瓶颈。安装包 shader 全屏读取 metadata 与深度，并通过链表反复选择最近节点；完整访问 k 个有效片元时具有二次扫描成本。不知道实际片元层数、分布和提前终止比例前，不能把复杂度等同于实测耗时。

**深度快照。** 当前流程可在模型准备、实体完成、方块实体完成、世界透明开始前复制深度。日志确认这些阶段成功，但没有每次复制的 CPU/GPU 时间。兼容性逻辑用于保证箱子、水和模型遮挡，应先计量，再证明哪些快照冗余。

**克隆程序状态搬运。** 每次 capture 会查询/上传源程序 uniform、保存/恢复 GL 状态并执行屏障。当前约 40 次透明调用；这是可测量项，但现有 body CPU 计时尚不支持把它列为首要原因。

下一步应先在保持渲染语义的条件下补充默认关闭、限频聚合的 CPU 与非阻塞 GPU 计时：metadata clear、各阶段 depth copy、capture、overflow readback、resolve、BSL 后续 composite，以及帧呈现/限帧等待。GPU query 独立于原有 model draw，避免嵌套；CPU 等待与 GPU 执行时间不能简单相加。采样时记录焦点状态或临时解除 ModernUI 后台限帧，然后在相同模型/动作/相机下重跑 BSL 开关对照。

若同步读回确认为热点，可独立评估异步小缓冲/围栏轮询来延后溢出警告，保留 GPU 溢出尾部算法；此处仅提出验证方向，本轮未实现或验证该优化。物理线程预算优化另行测量，保留 60 Hz、碰撞与约束语义，不通过关闭物理掩盖渲染问题。

## 本地证据与交付状态

取证目录：`build/diagnostics/bsl-single-20261023/`（构建目录，Git 忽略）。

- `baseline-evidence.log` / `baseline-summary.json`：基线来自 `logs/2026-10-23-6.log.gz`，避免游戏重启轮转后丢失。
- `vsync-off-*`、`toon-off-*`、`doll-off-*`、`bsl-off-*`：分次采集的日志快照和解析数据；同一文件包含此前窗口，按本报告时段筛选，不能把整文件当成单一条件。
- 日志快照每行保留原始日志行号；`phase-ranges.json` 保存五组所选时段、窗口数及范围。
- `installed-world-transparency.javap.txt` / `installed-gpu-renderer.javap.txt`：实际安装 JAR 的字节码。
- `installed-world-transparency-resolve.frag.glsl`：实际安装 shader。
- `capture-metadata.json`：安装路径、哈希与采集时配置快照，配置时点为小人偶关闭阶段。

核验了配置开关、实际绘制/求值计数、安装包哈希及字节码。没有生产修改，因此未进行构建或源码测试；未宣称修复、FPS 提升或视觉验收。本轮结束前最后确认的测试状态是三渲二开、BSL 关、小人偶关、垂直同步关；这些切换由用户操作，需要时可恢复原设置。
