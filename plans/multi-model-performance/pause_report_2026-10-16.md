# 多模型与纸娃娃优化暂停报告

记录时间：2026-10-16 00:45，Asia/Shanghai。按用户要求暂停实现，恢复工作时先读取本文，再阅读 `implementation_plan.md`。本文记录当前工作树，未提交 Git。

## 用户反馈与当前目标

- 用户确认优先改善多人／多个模型同时显示时的帧率，不是加载耗时。
- 用户对上一版实测反馈：帧数波动更小，提高约 10–20 FPS。该反馈没有附带固定条件的前后统计，不能归因到某一个优化，也不代表本次未打包改动已经验收。
- 用户此前测得关闭纸娃娃约 110 FPS，开启纸娃娃改善有限。后续日志显示纸娃娃姿态复用已经命中，透明准备和绘制仍有成本。
- 本次剩余目标：透明准备缺口、纸娃娃可选刷新缓存、原生阶段统计、独立模型并行更新及相应验证。

## 已写入代码的内容

### 上一阶段已有实现

同帧精确原生求值复用、GPU 透明准备和间接合批、纸娃娃有条件复用世界完整姿态／蒙皮输出、不可变 GPU 缓冲共享、稳定调度与滞回。阶段性验证详见原实施计划。

### 本轮已经编译过的 Java 改动

- `TranslucentDrawOrder.Inputs.withIndices(...)` 提供原始／动态索引 CPU 数据及版本，减少已知数据的 GL 回读；未知动态索引仍保守刷新。
- GPU 透明纹理窗口按实际 alpha 筛选后的候选建立；新增 `GPU_WINDOW_FALLBACK` 诊断。
- `ViewTranslucentOrders` 分开保存完整世界、第一人称和 GUI 的有界透明准备缓存，避免不同视图反复冲掉唯一缓存。
- 主体／描边按场景拆分 CPU 计时。
- `ScreenPriority` 引入屏幕投影面积、相机方向及上帧入选滞回，只用于优先级，不新增模型硬裁剪。
- `PaperDollFrameCache` 实现一个带独立深度的全分辨率 RGBA8 离屏缓存，预算上限 128 MiB；`PaperDollRefreshClock` 管理刷新时间和失效。
- 纸娃娃设置增加每帧／60 Hz／30 Hz；默认每帧，光影包下回退。模型、布局、模式、隐藏、资源重载和退出接入失效／释放；Fabric、NeoForge 配置与三种语言文本已接入。

上述纸娃娃离屏功能只通过编译及刷新时钟单元测试，尚未通过真实 GL 合成测试或游戏画面验收。

### 本轮原生统计改动

- `rust_engine/src/evaluation_profile.rs` 用线程局部采样记录动画、IK、物理、CPU 蒙皮的独立时间；关闭时不读取阶段时钟。
- 实际动画执行路径接入计时；Bullet 新增只读步进结果接口，记录实际调用／执行子步／请求子步。
- JNI `UpdateModelProfiled` 使用 64 字节 direct buffer 返回版本及 7 个统计字段；先验证缓冲，再求值。
- Java bridge 增加可选接口检测，旧 DLL 缺少入口时回退原更新路径。
- 原有两条模型更新 JNI 在获取模型 Arc 后释放全局注册表读锁，再执行独立模型求值。
- Rust 定向测试已通过；尚未构建、同步和验证 release DLL。Java 对接及新增日志尚未重新编译。

### 刚写完、尚未编译的并行更新

- `NativeEvaluationBatch`：最多 3 个后台线程，调用线程执行最后一个任务；按句柄去重，全部汇合后才执行完成回调；常规异常仍等待其余任务结束。
- `BaseModelInstance`：拆开渲染线程输入准备、JNI 执行、渲染线程完成统计及缓存发布。
- `PlayerWorldEvaluationPrepass`：在世界实体绘制循环前准备已加载的远端玩家 GPU 模型。首版保守排除本地玩家、VR、舞台、外部姿态、TaCZ 和光影包；不触发额外模型加载。
- 两个平台 `LevelRendererMixin` 接入可选注入点，客户端退出关闭线程池。
- 新开关 `mmdskin.parallelEvaluation=false` 可以关闭；当前实现默认启用，尚未完成验证，不能作为可交付版本。

## 日志证据与限制

已分析日志：`neoforge/run/client/logs/latest.log` 中 2026-10-16 00:10:43–00:12:55 区间。日志文件会被后续启动覆盖，本文记录的是已分析区间，不保证恢复时该文件仍相同。

- 纸娃娃原生求值为 0、持续复用，说明姿态共享路径已生效。
- 方舟洛天依模型纸娃娃调用约 5.05 ms，候选处理约 2.3 ms、索引构建约 0.88 ms、回读约 0.58–0.93 ms；该场景 `GPU_PREPARE=0`。
- 第一人称也有约 2.3 ms 候选处理和约 684 KB 索引回读；已有 GPU 准备命中记录。
- 上述数据支持修复索引来源和视图缓存切换；按实际候选构造窗口能否让该模型进入 GPU 路径仍需新日志确认。
- `avgVisible` 全程为 1，仍没有真实 2／4／8 个独立世界模型的扩展数据。该字段也不是实际绘制实例数。

## 验证状态与产物

暂停前只核对记录和文件，没有继续修改业务代码、执行测试或打包。

| 范围 | 已有结果 | 适用边界 |
| --- | --- | --- |
| common 单元测试 | XML 汇总 196 项，失败 0、错误 0、跳过 0；最新报告时间 2026-10-16 00:36:37 | 包含此前 Java 与纸娃娃时钟改动；不覆盖随后写入的原生统计 Java 对接和并行实现 |
| Rust profiling 定向测试 | `cargo test --offline --manifest-path rust_engine/Cargo.toml --lib evaluation_profile`：2 项通过 | 只覆盖 TLS／嵌套／异常恢复，不能证明真实物理结果或并行正确性 |
| 之前真实 GL 探针 | 合批颜色／深度对照、GPU 准备 25 组、静态缓冲共享及已有缓存探针通过 | 未重新覆盖本轮候选窗口、索引来源和纸娃娃离屏功能 |
| Python 汇总器 | 之前 5 项通过 | 新 `GPU_WINDOW_FALLBACK` 尚需加入统计及测试 |
| Fabric Jar | `fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar`，00:05:44 | 旧阶段产物，不包含随后本轮新增改动 |
| NeoForge Jar | `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`，00:05:42 | 同上 |

当前没有包含全部新增代码的新 Jar／release DLL。已有 Jar 不能用于验收本轮完整实现。

## 恢复后的实施顺序

1. 先审查并编译最新 Java 和两端 Mixin。核对原版 `renderLevel` 的 partial tick 参数、注入签名、实体筛选及动画输入代次；已导出字节码在 `build/translucent-performance/LevelRenderer.bytecode.txt`。
2. 修复已发现的开关边界：并行预求值依赖同帧缓存消费，关闭 `mmdskin.reuseEvaluation` 时目前可能重复求值，应禁用预处理或明确消费 prepared 结果。检查预处理与正式绘制之间是否有动画 setter 导致代次再次变化。
3. 为批处理增加有意义的测试：任务真实重叠、所有任务汇合后才完成、完成回调在调用线程、失败仍汇合、重复句柄去重、拒绝提交回退、同步路径。核查原生全局共享状态、独立 Bullet 世界和生命周期安全，再做原生并行／确定性验证。
4. 检查纸娃娃缓存的预乘 alpha、手持物品 RenderType、Blaze3D 状态缓存及 MRT／独立 draw buffer 状态。当前缓存计数在绘制成功前递增，应改为成功后记账；不支持的目标保守回退。补真实 GL 探针覆盖半透明合成、深度隔离、刷新／命中／键失效／尺寸变化、异常恢复及状态恢复。检查中文／日文文本编码。
5. 扩展透明探针：显式 CPU 索引同 revision 复用／新 revision 失效、buffer 有效长度、视图交替缓存，以及原始材质超过 8 槽但候选可放入单窗口的真实 GL CPU 对照。补汇总器新计数和兼容测试。
6. 原生统计补真实步进次数和非法 buffer 不求值验证。检查根 Gradle 原生资源任务和 loader 路径，再构建 release DLL 并核验打包内容；保留工作树中的独立头发物理改动，明确新 DLL 会包含当前 Rust 工作树内容。
7. 运行必要 Java／Python／Rust／真实 GL 验证，打包两个平台，记录新产物校验值；把最终实现和验证边界更新到主计划及工具说明。
8. 游戏验收仍需固定条件采集 1／2／4／8 模型、相同／不同资产、纸娃娃关闭／每帧／60／30 Hz，并覆盖第一／第三人称、手持物品、暂停、切换模型、重载及兼容回退。并行累计 native 工作时间不能当主线程阻塞，需结合 `native.batchExecuteJoin` 判断。

## 工作环境与继续命令

- 工作目录：`C:\tmp\mc-mmd-rust`。JDK 21：`C:\Program Files\Zulu\zulu-21`，系统默认 Java 17 不适合直接用于本项目构建。
- 本轮使用 `gradle-ide-parity` 技能，环境探测已完成。恢复时先延续现有构建路径，不清理缓存。
- Gradle／Cargo 有时因沙箱无法访问缓存／锁文件而失败；按实际错误申请对应命令提升权限，不绕过审批。
- 工作树有大量用户／其他工作留下的头发物理改动；不得 reset、stash、覆盖或把全部差异视为本性能任务所有。
- 不创建新分支、提交、推送或自动安排明日任务；用户未要求这些操作。

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --offline -I tools/translucent-performance/validation.gradle :common:test :fabric:remapJar :neoforge:shadowJar -x :prepareNativeResources
.\tools\translucent-performance\run-batch-probe.ps1
python -m unittest discover -s tools/translucent-performance -p test_summarize.py
cargo test --offline --manifest-path rust_engine/Cargo.toml --lib evaluation_profile
```

以上 Gradle 命令故意跳过原生资源任务，仅用于 Java 验证，不能产出包含新 JNI 的完整交付物。完成原生构建后需按实际资源路径重新打包和校验。
