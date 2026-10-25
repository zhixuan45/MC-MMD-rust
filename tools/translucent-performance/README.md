# 透明渲染性能诊断工具

## 有序批量提交复验（Windows）

先用 JDK 21 执行 `./gradlew.bat --offline -I tools/translucent-performance/validation.gradle :common:classes :common:writeTranslucentProbeClasspath`，再运行 `tools/translucent-performance/run-batch-probe.ps1`。探针比较原 GLSL 330 Toon 与材质表程序的颜色、生产入口的深度，检查纹理窗口、共享顶点跨材质、球面/morph/眼部发光、单双面、回退及状态/资源恢复；然后执行原有排序缓存与 GPU timer 探针。若仅验证 Java 且需保留已经生成的 native，可给 Gradle 加 `-x :prepareNativeResources`；这不会编译 Rust，不能代表验证了未构建的原生改动。

vanilla Toon 的 GL 4.3 路径新增 `BATCH_SUBMISSIONS`、`BATCH_RUNS`、`BATCH_FALLBACK`。`RUNS` 是排序后的逻辑分段，`DRAW_TRANSLUCENT` 是实际 draw，二者不再要求相等。每窗口最多绑定 8 张主/球面纹理；超出按原透明顺序切窗。SUBMIT 包含窗口规划、材质表构造/上传和绘制，新增表字节计入 `UPLOAD_BYTES`。旧日志缺少新计数时汇总为零。可用 JVM 参数 `-Dmmdskin.translucentBatch=false` 强制旧提交作为对照，重启客户端后生效。

本目录包含日志汇总器和可复验探针源码，不包含第三方模型、生成图片或采样结果。性能/资产历史与限制见 [`doc/archive/handoffs/TRANSLUCENT_PERFORMANCE_HANDOFF.md`](../../doc/archive/handoffs/TRANSLUCENT_PERFORMANCE_HANDOFF.md)。旧报告中的 FPS 都是用户给出的约数，不能混成同一 MMD 路径的前后数据。

## 汇总游戏日志

`summarize.py` 只用 Python 标准库。它读取包含 `[MMD透明性能]` 的日志行，验证紧随标记的 schemaVersion 1 单行 JSON；无关日志会忽略。`--phase` 可重复，每项为 `阶段名=日志路径`，从而把站立、走路、跑步的独立采样标注并合并进同一报告：

```sh
python3 tools/translucent-performance/summarize.py \
  --phase standing=Logs/standing.log \
  --phase walking=Logs/walking.log \
  --phase running=Logs/running.log \
  --format json --output Logs/translucent-summary.json

python3 tools/translucent-performance/summarize.py \
  --phase standing=Logs/standing.log \
  --phase walking=Logs/walking.log \
  --phase running=Logs/running.log \
  --format markdown --output Logs/translucent-summary.md
```

运行标准库测试：`cd tools/translucent-performance && python3 -m unittest -v test_summarize`。

单一阶段或未分阶段的多日志也可用重复 `--log PATH`，这时输出阶段名为 `unlabeled`。未知 phase 标签会原样保留并提示。坏 JSON、缺失字段、类型错误或不支持的 schema 都会报告 `文件:行号`、返回非零状态，并且不写摘要；没有性能记录也会失败，不能生成空报告伪装采样成功。

Counters 是每条日志时间间隔内累计值，报告会对同一组的 total 求和，并用累计 render samples 计算平均每 sample。stage 的 `avgNs` 是间隔均值（未执行该阶段的调用按零计入），报告按各日志行 `samples` 加权得到总体间隔均值。render call median/P95 和每个 stage 的 median/P95 只取文件顺序中该组最新一条日志的 `latestWindow` 值，连同该窗口样本数显示。它们不是跨日志聚合分位数，更不能把已汇总的中位/P95 再平均成真实分位数。时间字段单位均为 ns；字节字段为 bytes，其余计数按名称表示。

日志里的 render call 是模型作用域调用统计，不是游戏帧时间分布。摘要不能单独证明任何性能改善；游戏帧时间、固定条件前后对照和画面验收仍需分别记录。

## 固定条件采样步骤

在模组设置的“调试”分类开启“GPU 日志”并保存，立即开始采集，无需开启 HUD 或其它日志。该开关对应 `.minecraft/config/mmdskin/config.json` 的 `debugGpuLog`，默认关闭；日志按 `performanceLogIntervalSeconds` 汇总（默认 5 秒）。每轮开始前关闭 GPU 日志并保存，再开启并保存，以清空旧窗口；HUD 保持开启时也会清空。结束时至少等一个完整日志间隔输出后再保存日志。`[MMD性能]` 行记录 HUD 绘制边界的帧间隔、原生更新及异步 GPU 查询统计，`summarize.py` 仅处理 `[MMD透明性能]` 模型调用记录。通用 `performanceProfilingEnabled` 和 HUD 不会单独开启 GPU 诊断日志。

每个 phase 使用同一客户端构建、模型文件/版本、GPU 蒙皮设置、Toon/BSL 包及选项、分辨率/窗口模式、相机/距离、世界位置、时间/天气和可见实体数量。关闭会改变场景的后台操作；先进入目标姿势并预热 30 秒，再保持约 60 秒采样。依次单独采集站立、恒速走路、恒速跑步，每种至少重复三轮。每轮保存原始游戏日志、HUD/游戏帧时间记录、配置和遮挡截图；截图应覆盖睫毛/发丝、皮肤、白丝、裙摆或长袖、珍珠等透明边界。不要在一轮内切换蒙皮、光影或模型。

至少在报告旁填写：

```text
构建/提交：
模型名、资产版本、SHA-256：
硬件与驱动：
Minecraft / Loader / Iris 版本：
GPU 蒙皮、Toon、光影包与选项：
分辨率、世界位置、相机距离：
phase / 预热时长 / 采样时长 / 重复轮次：
游戏帧时间 median/P95（独立帧样本）：
汇总 JSON/Markdown 路径：
截图路径：
异常与未验收项：
```

## 探针

阴影像素 alpha：运行 `./tools/translucent-performance/run-shadow-alpha-probe.ps1`，默认读取本机实例的 Iris 和 BSL v10.1.8；可通过 `-Instance`、`-BslPack` 指定路径。需先编译 common 并生成上述 classpath。每次使用独立输出目录，编译失败不运行旧 class。96 组对照包含逐像素 alpha / 实心投影、CPU / 生产 GPU compute 位置及 UV morph、颜色属性绑定、真实 BSL 阴影片段和 Iris alpha-test 表达式，并检查异常恢复及普通渲染透明度隔离。完整场景边界见 `plans/toon-style-and-paperdoll/shadow-alpha-r4.md`。

2026-10-16 增加多模型优化验证：Windows 下先用 JDK 21 运行下列任务，再执行 `./tools/translucent-performance/run-batch-probe.ps1`。脚本使用本地已有的 Windows LWJGL natives，不下载依赖。GL 上下文不可用会明确失败，不跳过验证。

```powershell
./gradlew.bat --offline -I tools/translucent-performance/validation.gradle `
  :common:test :common:writeTranslucentProbeClasspath -x :prepareNativeResources
./tools/translucent-performance/run-batch-probe.ps1
```

- `BatchGlProbe` 对照旧 CPU 提交、CPU 合批及 GPU 间接合批的颜色与深度，保留多材质、共享顶点、球面／morph／眼发光、纹理溢出、剔除、回退和 GL 状态验证。额外用 10 张原始纹理、筛选后仅剩一个候选窗口的数据，验证生产入口确实进入 GPU 路径，颜色／深度与 CPU 对照一致。
- `MaterialBindingGlProbe` 验证提交状态去重后的 Toon 颜色／高光、Iris 风格颜色 attribute／modulator 分支、alpha pass，以及球面模式／因子／纹理切换与宿主状态恢复。使用合成 shader，不等于真实 BSL 场景验收；CPU 提交优化记录见 `plans/translucent-rendering/cpu_submission_report_2026-10-16.md`。
- `GpuPreparationGlProbe` 以 CPU 为 oracle，直接回读探针的 GPU 索引、材质行与 command，25 组用例覆盖裁剪、全部裁掉、平局、NaN／正负零、128 项 tile 边界、容量变化、SSBO range／program 恢复与清理。只有验证探针读取这些结果，生产快速路径没有结果回读。
- `StaticBufferGlProbe` 验证不可变缓冲的内容去重、不同语义隔离、只读视图、引用／记账转移、最后释放、重新获取、开关和 GL 状态，并在独立 JVM 以预算 0 验证独立分配回退。
- `CacheGlProbe` 和 `GpuTimerGlProbe` 覆盖兼容准备、CPU／GPU 位置来源、缓存失效、动态索引与查询所有权；新增显式 CPU 索引的 position／limit、版本／长度失效、未对齐长度拒绝，以及世界／第一人称／GUI 三缓存交替保留和释放。
- `PaperDollGlProbe` 覆盖真实 GL 预乘 alpha 合成颜色（容差 2）、目标深度隔离、刷新／命中／键失效／尺寸变化、失败后重试、状态与资源恢复，以及 stencil、logic、sRGB、MRT 和独立 blend 状态的保守回退。

游戏中新增 `GPU_PREPARE`（GPU 准备次数）、`PREPARED_REUSE`（透明准备结果复用次数）和 `GPU_WINDOW_FALLBACK`（候选纹理窗口不满足 GPU 准备条件的次数），汇总器全部支持，旧日志缺少字段按零处理。原始／动态索引有已知 CPU 数据及版本时直接消费，未知来源仍可能读 EBO，不能要求所有 `READBACK_CALLS` 为 0。世界、第一人称、GUI 各保留一个有界透明缓存。分场景实例／求值／启用物理求值／复用统计在 `[MMD性能]` 中，汇总器当前只处理 `[MMD透明性能]`。纯 GPU 排序没有独立计时，不能把兼容路径的 CPU `SORT` 降低解释为零 GPU 排序成本。逐项启动开关、实施范围和 1／2／4／8 实体测试矩阵见 `plans/multi-model-performance/implementation_plan.md`。

## 原生统计与多模型并行探针

使用 JDK 21 和项目已有 Cargo 缓存，先生成 classpath 并构建 release DLL：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --offline -I tools/translucent-performance/validation.gradle :common:classes :common:writeTranslucentProbeClasspath -x :prepareNativeResources
cargo build --offline --release --lib --manifest-path rust_engine/Cargo.toml
.\tools\translucent-performance\run-native-probe.ps1
cargo test --offline --manifest-path rust_engine/Cargo.toml --lib evaluation_profile
```

`NativeProfileProbe` 在 `build/translucent-performance/evaluation-probe.pmx` 生成单骨骼／单动态刚体 PMX，通过项目解析器和真实 JNI 加载，不读取或修改用户模型。验证 64 字节统计协议、非法 buffer 不执行求值、未对齐 direct buffer 边界、CPU／GPU 求值字段及实际 Bullet 计数；四个独立模型各执行 48 次并行采样求值，与常规串行基准的最终骨骼矩阵逐字节比较。极短阶段可低于平台计时分辨率，不能要求每次 IK 都大于零。Rust 定向测试另验证 0 步、子步截断、可变步进以及采样和并行不改变独立世界结果。

`CpuParallelPmxProbe` 读取一个现有真实 PMX，配置相同的固定物理参数并加载独立句柄，比较顺序 CPU 求值、多个 Java worker 同时求值、单模型 Rayon 预算求值的骨骼矩阵、CPU 顶点和法线。它使用固定 `1/60` 秒步长，不读取旧岛屿计数。运行前生成探针 classpath 并构建 release DLL，然后传入 PMX 路径：

```powershell
./gradlew.bat --offline -I tools/translucent-performance/validation.gradle `
  :common:classes :common:writeTranslucentProbeClasspath -x :prepareNativeResources
cargo build --offline --release --lib --manifest-path rust_engine/Cargo.toml
./tools/translucent-performance/run-cpu-parallel-pmx-probe.ps1 `
  -Pmx 'path/to/real-model.pmx'
```

探针禁用 blink 与 tail idle 附加力，避免非必要的动画随机输入；所有模型仍使用 PMX 自带物理、相同重力、频率、子步上限与 solver 迭代配置。它验证独立模型调用之间的结果一致性，不替代 Minecraft 中的渲染线程上传、帧预算或真实帧率 A/B。

脚本默认使用 `rust_engine/target/release/mmd_engine.dll`，可用 `-Dll PATH` 指定；若有另存的旧 DLL，可用 `-LegacyDll PATH` 在独立 JVM 验证缺失采样入口时返回 false、不会求值、随后常规更新仍然有效。打包任务会覆盖生成资源目录的 DLL，应在覆盖前另存旧版本，不能把新 DLL 当作旧版本探针输入。完整库测试可运行 `cargo test --offline --manifest-path rust_engine/Cargo.toml --lib -- --test-threads=1`；2026-10-16 发现默认并行测试的两项物理断言独立／串行可通过，详见完成报告中的测试隔离限制。

生产并行预处理覆盖当前 pass 中合格、已加载且句柄独立的远端玩家、生物和女仆 CPU/GPU 模型；输入准备和完成回调在渲染线程，后台只执行 JNI，全部汇合后才继续上传和绘制。世界与 Iris 阴影预处理沿用同一批处理边界。`-Dmmdskin.parallelEvaluation=false` 关闭并行，关闭 `mmdskin.reuseEvaluation` 也禁用预处理。本地玩家、VR、TaCZ、舞台与外部姿态保留常规路径。多任务时每个任务的内部预算为 1，避免与单模型 Rayon 嵌套过度竞争。`native.parallelBatches`／`native.parallelModels` 确认批处理命中，`native.batchExecuteJoin` 反映执行与汇合的调用线程时间；各实例累计 native 阶段时间代表工作量，不等于主线程阻塞。实际 Bullet 调用／执行子步／请求子步与旧 `physicsEnabledEvaluations` 字段含义不同。

纸娃娃默认每帧刷新；设置中可选 60／30 Hz 离屏缓存。此模式降低刷新频率，需要和默认模式分别采样。手持任意物品或启用光影包时回退每帧；特殊 GL 目标／状态、超过 128 MiB 预算等情况也保守回退。`scene.PAPERDOLL.reuses` 代表姿态复用，应结合离屏刷新／命中计数判断收益，不能混为同一缓存。隐藏、切换模型、改变布局／模式、重载和退出都需要游戏验证。

`SortBenchmark.java` 是独立 JDK 微基准；它比较导出的静态键排序结果，不包含 Minecraft 线程竞争、GPU 同步、材质提交或完整画面，也不是游戏收益证据。`audit_assets.py` 和 `export_sort_keys.py` 需要 NumPy/Pillow，只读资产并输出静态姿势模拟；这些依赖只用于原有资产工具，不用于日志汇总器。

`CacheGlProbe.java` 使用项目 runtimeClasspath 和 OpenGL 上下文验证生产 `TranslucentDrawOrder`，覆盖 CPU/GPU 位置输入、当帧排序、平局稳定性、零候选、动态 EBO、UV revision 失效、球面材质分段及缓冲绑定恢复。它不启动 Minecraft、不读取模型资产、不绘制原模型或 BSL 场景；通过仅证明探针覆盖的数据路径。

先编译 common，再用临时 Gradle init script 生成项目 runtimeClasspath：

```sh
./gradlew --offline -I tools/translucent-performance/validation.gradle \
  :common:classes :common:writeTranslucentProbeClasspath
```

上述任务输出 `build/translucent-performance/classpath.txt`。Linux 下 `run-cache-gl-probe.sh [classpath.txt]` 会用临时目录编译探针，默认读取该文件；有 `$DISPLAY` 时直接运行，无显示器时先使用 `xvfb-run`。Mesa surfaceless 环境可显式传 `--egl [classpath.txt]`，并设置 `LWJGL_EGL_JAR` 指向已存在的 `lwjgl-egl-3.3.3.jar`；这只用于探针，不加入模组依赖。没有显示器/Xvfb、且提供了 EGL jar 时，脚本也会自动选择 EGL pbuffer。脚本不下载依赖。要求 JDK 21、Gradle 生成的真实 runtimeClasspath、Linux 对应的 LWJGL natives，以及可用 OpenGL 4.3 驱动/软件实现。脚本不会自动运行 Gradle。Windows 可按同一 classpath 使用 `javac`/`java` 运行 `CacheGlProbe.java`，并提供对应 LWJGL natives 与隐藏 GLFW 环境。

日志/探针结果应写入本地诊断目录，模型目录保持只读。探针失败、上下文不可用或字段不完整时保留实际错误，不用伪造结果填补记录。
