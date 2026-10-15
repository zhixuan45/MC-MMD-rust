# 半透明渲染实施记录

## 2026-10-16 多模型优化续作

已实现输入精确匹配的透明准备缓存，以及 GL 4.3、自有 vanilla Toon、单纹理窗口条件下的 GPU 深度／裁剪／稳定排序／索引与材质行收集和 indirect 提交；该路径不回读位置或排序结果。其它程序与多窗口保留兼容路径。纸娃娃新增本帧世界姿态／完整 GPU 蒙皮输出的条件复用，同资产不可变 GPU 缓冲新增有预算的引用共享。实现范围、逐项对照开关、诊断口径和测试见 `../multi-model-performance/implementation_plan.md`。下文为此前各阶段记录，所述“尚未实现”仅对应当时状态；当前游戏内多人收益仍待测量。

## 2026-10-15 本地基线后的提交碎片修复

用户在 NeoForge 开发环境完成第一阶段采样后，授权优先修改透明提交。原始日志为 `neoforge/run/client/logs/2026-10-15-1.log.gz`；后续一轮从 21:31 开始记录到 `latest.log`。WORLD 样本每模型 render 调用约 10970 个透明候选、6164 次透明 draw，SUBMIT 均值约 6.08 ms；纸娃娃有相近的提交碎片。这是修复前的模型调用成本，不是游戏帧时间。

- `TranslucentBatchPlan` 直接遍历现有排序 runs，构造连续纹理窗口、材质表和逐三角形材质行号。每窗口最多 8 张原始纹理，主贴图与球面贴图共同计数；超出只按原顺序切窗，不按材质重排，不改变排序和平局顺序。
- `TranslucentBatchSubmission` 在模型排序器的生命周期内管理两个 SSBO。已知 vanilla Toon 的透明 pass 每窗口只执行一次索引绘制，用 fragment `gl_PrimitiveID` 读取材质行；保留当帧 EBO、alpha、材质 morph、SPH/SPA、眼部发光和单双面状态。纹理保留原大小、格式、过滤和 wrap。混合单双面材质由片元朝向过滤保持原剔除语义。
- `ToonShaderBase` 只为自己的 vanilla 输出变体尝试 GL 4.3 shader，失败则重编译原 GLSL 330。光影包输出变体、外部程序、低能力设备、主纹理使用自定义 sampler 或超出 SSBO 上限时保留原提交。CPU/GPU 蒙皮和纸娃娃都复用公共入口；阴影、不透明、深度补写和描边保留原链路。支持 `-Dmmdskin.translucentBatch=false` 对照旧提交。
- 批量路径恢复活动纹理、纹理、sampler、剔除状态、SSBO indexed range 与 generic binding，finally 关闭批量 shader 分支。模型释放清理新增内存和显存，资源统计包含新增表。
- 新增 `BATCH_SUBMISSIONS`（命中的透明 pass 数）、`BATCH_RUNS`（批量处理的逻辑 run 数）、`BATCH_FALLBACK`（排序后使用旧提交的次数）。`DRAW_TRANSLUCENT` 保持实际 draw 口径，`RUNS` 保持逻辑 run 口径。SUBMIT 包含规划、材质求值、表上传及绘制；`UPLOAD_BYTES` 包含新增表。新增上传耗时计入 SUBMIT，不能只看原 UPLOAD 阶段。汇总器接受历史日志并输出新指标。

验证：177 项 common 测试通过，Fabric/NeoForge 编译通过。Windows 真实 OpenGL 的 `BatchGlProbe` 对比原 GLSL 330 与新程序：36 个逻辑 run、10 张主纹理及 2 张球面纹理切为 6 次 draw，所有像素通道差异不超过 1/255。覆盖共享顶点跨材质、SPH/SPA、morph、眼部发光、纹理溢出、正反面剔除和 winding、完整生产入口颜色/深度、状态恢复、回退及重复清理。原 CPU/GPU 输入缓存与排序、稳定平局、异常恢复和 GPU timer 生命周期探针通过。探针未绘制用户完整模型，不代表游戏性能或光影包画面已验收。

最终本地 `:common:test :fabric:remapJar :neoforge:shadowJar --offline -x :prepareNativeResources` 打包通过，两个发布 Jar 均包含新增批量 class、shader 和现有 Windows native；没有构建或验证工作区中独立进行的 Rust/物理修改。最终 class 输出再次通过 Windows GL 探针。日志汇总器 5 项测试通过，包括新增批量计数和历史日志兼容；Windows 沙箱的临时目录权限问题通过在沙箱外运行同一测试命令解决。变更通过 Git diff 空白检查。

未实现设备接口抽取、GPU 排序或取消 GPU 位置回读，也未修改纸娃娃独立动画实例。纸娃娃后续方案见 `paperdoll_optimization.md`。重启开发客户端后先关闭纸娃娃，核对 WORLD 的 `BATCH_SUBMISSIONS > 0`、实际 draw 与 SUBMIT；再开启纸娃娃单独对照。保持模型、Toon、相机与分辨率，观察发丝、睫毛、透明服饰、单双面、眼部发光和描边。

## 第一阶段首次交付记录（历史）

2026-10-15，代码基线 `60d24e6`。用户授权审查通过后下发多个 Luna 子代理开始实现；已由四个 Luna 子代理实施，主代理审查、修复并验证。初始工作树干净，未修改物理、JNI、贴图或 shader。

## 审查结论与阶段边界

未发现阻止按阶段实施的关键设计问题。全局透明顺序、原始平局顺序、当前帧几何、逐材质状态、Iris/阴影回退和非阻塞诊断约束均保留。计划的 GPU 排序、纹理页与多材质提交必须在完整链路和实际瓶颈证据齐备后启用。

当前交付第一阶段的诊断代码及采集工具，真实游戏基线尚未取得。第二阶段设备接口抽取和第三阶段 GPU 加速尚未开始；没有宣称 Vulkan 可用或游戏 FPS 已改善。归档交接记录的路径已修正；当前检出没有原计划引用的 AGENTS.md 或 gradle-ide-parity 技能文件，本次使用现有 Wrapper、JDK 21 和环境缓存验证。

## 已实现

- `TranslucentDiagnostics` 为 CPU/GPU renderer 建立按模型句柄、蒙皮策略和场景区分的作用域；区分世界、第一人称、库存、纸娃娃、物品和阴影。正常退出和嵌套异常均恢复父作用域，采样代次变化丢弃旧结果。
- 排序器记录材质分类、候选匹配/构建、深度键、排序、索引/runs 生成及上传时间；记录三处真实回读的次数、字节和 CPU 耗时。每次真实候选构建/复用均计数，包含全视锥外的调用。
- `SubMeshDrawHelper` 在准备完成后计量提交时间，按不透明、混合、深度补写和阴影统计实际执行的 draw 数；保持原绘制顺序和 GL 状态恢复。
- Fabric/NeoForge 设置的“调试”分类新增默认关闭的“GPU 日志”（`debugGpuLog`），保存后生效，独立于 HUD、通用性能采样和其它日志。开启即可采集 GPU 查询、透明渲染诊断与 HUD 绘制边界的帧间隔；切换时清空旧窗口，即使 HUD 仍开启也不混入旧采样。GPU 查询独立异步入窗口；池耗尽或宿主 query 活跃时跳过，不等待 GPU，不结束宿主 query。采样代次及上下文变化丢弃旧结果，两个加载器复用现有 `Minecraft.close` 钩子清理查询池。HUD 分别显示查询均值和样本数。
- 新日志汇总器按显式站立/走路/跑步标签处理 `[MMD透明性能]` JSON，按行报告坏数据，对计数求和并计算每调用均值；阶段均值按调用数加权，分位数仅保留最新窗口。采集说明和离屏 GL 探针见 `tools/translucent-performance/README.md`。

## 数据口径

`[MMD透明性能]` 的一个 sample 是一次模型 render 调用，不能当成游戏帧。最多保留 128 个模型/场景/蒙皮聚合键，关闭采样时不创建新作用域、不读时钟。阶段 `avgNs` 覆盖整个日志间隔，跳过的阶段按零计入；中位/P95 使用 nearest-rank，覆盖最近最多 120 次调用，`windowSamples` 标明窗口大小。超出键容量的新键在该间隔不纳入，容量测试不代表大场景性能验收。

回读 CPU 时间包含 barrier、驱动调用及可能的等待，不能解释为独立的 GPU 等待时间。GPU draw query 围绕现有 renderer 的绘制作用域，可能包含准备、提交间隙和描边，不等于纯像素处理时间，也不与取回结果时的 CPU 帧对齐。GPU 查询窗口目前按 compute/draw 全局汇总，不能还原逐模型 GPU 帧成本。原生更新时间包含原有动画、物理和蒙皮求值；本轮没有独立拆出物理耗时。`presentedFrame` 使用 HUD 回调边界的间隔，未测量交换链实际呈现时刻。

## 验证

- 通用 Java：172 项测试，0 失败、0 错误、0 跳过；包含窗口统计、间隔/窗口口径、真实作用域异常恢复、代次清理和容量回归，以及 GPU 日志配置缺省/持久化、与 HUD/通用性能采样的独立性、HUD 保持开启时切换日志清空窗口的回归。
- 日志工具：4 项 Python 标准库测试通过；shell 语法与 Git diff 空白检查通过。
- 真实 OpenGL：Mesa surfaceless EGL 4.3 上下文通过 CPU/GPU 输入、当前帧排序、稳定平局、空候选、动态 EBO、UV 失效、球面材质分段和异常绑定恢复检查；GPU timer 的关闭采样、仅日志启用、同池/跨池嵌套、宿主所有权、代次切换和重复释放检查通过。探针没有绘制原始完整模型或 BSL 世界。
- 原生库：现有构建流程完成 Release 构建，无 Rust 源码变更。
- Fabric：`:common:test :fabric:remapJar --offline` 成功；发布 Jar 包含新诊断 class，内置 Linux native 与当前 Release 文件逐字节一致。
- NeoForge：编译和 `shadowJar` 打包成功；发布 Jar 同样包含诊断类、关闭钩子和匹配的 Linux native。首次 `createMinecraftArtifacts` 被 `launchermeta.mojang.com` 的网络策略 403 阻塞，随后使用 NeoForm Runtime 1.0.13 的官方 `--launcher-meta-uri file:///workspace/.cloud-setup/gradle/caches/fabric-loom/mojang_versions_manifest.json` 参数完成本地清单预处理。其它文件从允许的 Mojang/Maven 地址正常获取；四个输出路径与实际 Gradle 任务配置逐一核对。

最后的双平台打包命令为 `:fabric:remapJar :neoforge:shadowJar -x :neoforge:createMinecraftArtifacts --offline`。NeoForge 的预处理已由同版本官方 CLI 完整执行，后续编译和打包由 Gradle 执行；当前网络策略下默认任务的远程清单请求仍受限，没有修改仓库构建脚本或网络策略。CLI 日志位于本地 `build/translucent-performance/neoform-cli.log`，两个发布 Jar 的 SHA-256 和内容校验位于 `build/translucent-performance/artifact-verification.json`。

本机构建使用 JDK 21.0.12.1、Gradle Wrapper 8.11.1 和 `/workspace/.cloud-setup/gradle` 缓存。首次 JRE 缺编译器及新 JDK 缺环境代理信任的问题已解决；没有禁用 TLS 校验。探针的 EGL jar 和 Linux natives 仅存于环境缓存，没有增加模组运行依赖。

## 剩余验收

按采集说明，在目标游戏中固定模型、动作、相机、分辨率、场景、光影和配置，采集站立/走路/跑步的原始日志及帧间隔中位/P95，对照透明遮挡截图。还需检查 GUI、第一人称、阴影、光影切换和退出释放。当前本机 Jar 仅包含 Linux 原生库；Windows 游戏验收应由现有构建流程配套生成 Windows native。

基线取得后再选择具体性能路径，并按计划继续接口抽取和加速；构建与离屏探针通过不代替这项游戏验收。
