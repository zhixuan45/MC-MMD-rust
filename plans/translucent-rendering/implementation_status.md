# 半透明渲染实施记录

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
