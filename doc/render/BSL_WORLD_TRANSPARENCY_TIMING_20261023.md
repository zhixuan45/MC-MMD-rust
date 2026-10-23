# BSL 世界透明分段计时与异步读回（2026-10-23）

本轮延续 `BSL_SINGLE_MODEL_PERFORMANCE_20261023.md`，补齐世界透明队列的计时，并提供局部同步点修复。已有水面队列、透明 shader 快路径等工作区改动保留在构建中，不将它们归为本轮新增。

## 新增计时

开启模组“GPU 日志”时，每隔 `performanceLogIntervalSeconds` 输出 `[MMD世界透明性能]` 单行 JSON。启动参数 `-Dmmdskin.profileWorldTransparency=true/false` 可显式覆盖。

| 阶段 | 覆盖内容 |
| --- | --- |
| `STORAGE_CLEAR` | 清零每像素透明 metadata |
| `DEPTH_INITIAL` | 模型入队时的初始深度副本 |
| `DEPTH_ENTITIES` | 实体完成阶段深度副本 |
| `DEPTH_OPAQUE` | 方块实体完成阶段深度副本 |
| `DEPTH_PRE_TRANSLUCENT` | 世界透明开始前深度副本 |
| `DEPTH_RESOLVE` | 无光影路径 resolve 前的深度副本 |
| `CAPTURE_STATE` | 单次 capture 克隆程序的 uniform/GL 状态设置，仅 CPU |
| `WATER_READBACK` | 原生水面完成后读取 3 个 uint 的同步等待，仅 CPU |
| `OVERFLOW_SYNC_READBACK` | 原同步溢出标志读回，仅 CPU，对照开关启用时执行 |
| `OVERFLOW_ASYNC_ISSUE` | 新增异步溢出标志复制与围栏提交 |
| `OVERFLOW_ASYNC_POLL` | 先前帧围栏检查与已完成数据读取，仅 CPU |
| `RESOLVE` | 延后透明合成完整 CPU/GPU scope，包含其内部操作 |
| `RESOLVE_DRAW` | 全屏 resolve draw 本身 |

CPU 是最近 120 个世界帧中每帧累计时间，未执行该阶段的帧记零；GPU 是最近 120 次已完成操作的时间戳差。`calls` 是本次日志间隔的 scope 调用次数，不能拿它除以最近窗口均值。两个窗口不是相同样本集合。

GPU 使用 `GL_TIMESTAMP` 成对查询，不嵌套或覆盖宿主的 `GL_TIME_ELAPSED`。结果只在结束查询 available 时读取，最多保留 64 对；耗尽时跳过 GPU 样本，CPU 仍计时。GPU scope 可能包含驱动调度、屏障及命令间空隙，不能直接等同于 shader 核心计算时间。`RESOLVE` 与内部阶段有重叠，不能求和；CPU/GPU 也不能相加作为总帧时间。

`frames/focusedFrames` 表示日志间隔帧数及其中窗口焦点在前台的帧数；`windowFrames/windowFocusedFrames` 对应 CPU 最近窗口。判断前台场景优先选两组计数分别相等的窗口。异步 GPU 样本完成时间较晚，不严格对应当前 CPU 帧；日志目前没有完整 BSL composite 或整帧呈现的 GPU 计时。

## 局部修复

默认将仅用于警告的 overflow 读回改为三个 4-byte GPU 缓冲与围栏。每帧先复制当帧 overflow，再在后续世界帧用 `glClientWaitSync(..., 0, 0)` 轮询；未完成就跳过，不等待、不调用 `glFinish`。缓冲占满时丢弃告警样本，渲染继续。GPU 溢出 weighted tail 算法不变，警告可能延后出现。

原生水面 header 会决定是否执行合成，仍保留同步读取并独立计时。若 header 已包含 overflow，则直接使用，不再重复读回。保留 `-Dmmdskin.syncWorldTransparencyOverflow=true` 启动参数切回原同步 overflow 路径，便于同场景对照。

## 构建与安装

- 构建使用 Zulu JDK 21、Gradle 8.11.1 和已有缓存/守护进程。
- `:common:test :fabric:compileJava :neoforge:compileJava` 通过；本次测试报告记录 25 个测试，0 失败、0 错误。
- `:neoforge:shadowJar :fabric:remapJar` 通过。
- 打包时通过 `nativeResourcesPath` 复用原安装 JAR 的 DLL，避免构建缓存中的不同原生库影响性能对照。
- NeoForge 源包：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1-bsl-perf-r7.jar`。
- SHA-256：`c5445e10f2c5cdcf231d56d024c2ef8799de2b94ce81dfb98af8b8d67698c752`。
- 已覆盖实例原文件 `D:/minecraft/.minecraft/versions/1.21.1-NeoForge_21.1.252/mods/mmdskin-neoforge-1.10alpha-1.21.1-pr21-water-r6.jar`，保留原文件名避免重复安装同 ID 模组，安装后哈希一致。
- DLL SHA-256 与旧包一致：`0a56effc445859ff3ac758c6ff30dff96ea70f4638e0c35ea72e81579c520848`。
- 旧包备份：`build/diagnostics/bsl-perf-r7/before/mmdskin-water-r6.jar`。

## OpenGL 验证

`tools/translucent-performance/run-world-transparency-timing-probe.ps1` 通过：有界队列、队列满时跳过、源数据随后修改不影响已复制样本、GL buffer binding 恢复、零/非零溢出结果、查询与宿主 query 共存、GPU 样本读取、采样切换、查询池耗尽与释放。

现有 `BlockEntityTransparencyGlProbe` 在异步与同步 overflow 两种路径均通过，使用实际 BSL 10.1.8 profile，覆盖箱子前后遮挡、低 alpha、孔洞、MRT/GL 状态恢复、多 PMX 顺序及有/无光影路径。这些探针不是游戏 FPS 或实际模型视觉验收。

证据目录：`build/diagnostics/bsl-perf-r7/`。包含构建日志、探针日志、安装哈希、原生 DLL 哈希、修改前 pipeline 快照及本轮 pipeline 差异；构建目录受 Git 忽略。

## 运行采样

r7 前台采样已确认：23:08:34–23:09:04 的焦点窗口均为 120/120，`WATER_READBACK` CPU 窗口均值 10.975–12.053 ms，`RESOLVE_DRAW` GPU 中位数约 0.201–0.274 ms；metadata clear GPU 中位数约 0.402 ms。最大已测阻塞位于水面 header 同步读取，不能归因于 resolve shader 本身。该读取等待此前 GPU 工作完成，尚未单独细分此前所有 BSL GPU pass。

### 水面 GPU 判定修复方案（r8）

按用户提出的像素交叉方向：水面节点在 `beforeWater` 分配快照之后追加，因此未溢出时 `pixel.head > beforeWater` 能识别当前水面批次实际贡献过的像素。水面阶段只合成这些像素，再由 GPU 清除已消费的像素头和尾部累计量；人物独有像素留到晚期，后续新节点仍可正常追加。空水面批次不提前消费人物像素。溢出时无法从节点头识别水面尾部，若该批次分配计数变化则保留原有全像素合成兜底。纯水体像素仍要恢复其原生颜色，不能直接跳过。

该方案不改 metadata 布局，不在 CPU 读取判定结果，不关闭水面捕获；保留同步旧路径作对照。验证重点是空批次、部分像素交叉、连续水面批次、后续模型入队、溢出和 GL/MRT 状态恢复。后续只使用新安装包的日志，剔除模型加载、shader 重载、菜单和焦点混合窗口；不与上一轮未补齐计时的 r6 数字直接相减宣称收益。
