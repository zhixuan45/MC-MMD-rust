# 四杏辞多核覆盖与提交开销复核

日期：2026-10-17。此次只补充调查与计划，没有修改多核调度生产代码。用户线索是多个模型同时显示时可能存在单线程瓶颈，GPU 也没有跑满。当前证据确认了跨实例并行未覆盖和大量兼容提交；尚无逐核 CPU 利用率、线程 CPU 运行时间或等待栈采样，不能宣称已经实测到某个核心 100%。

## 新日志与版本边界

本次使用 17:58:20 保存的游戏日志快照，而非凌晨样本。原文件为 `D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\logs\latest.log`，快照和解析结果在 `Logs/multicore-audit-20261017/`，包括 `latest.log`、`source_manifest.json`、`metrics.json`、`analyze_current.py`。快照 960218 字节，SHA-256 为 `6e920053a5c5f54156b702eaaf7b290536736942b7afdb1708f4a701676d1dcb`。复核时没有重跑会覆盖快照的采集脚本。

磁盘游戏包与工作区产物的 SHA-256 均为 `D9D256956CA0FC1E88DA534B4FD9DFD70C2A1920920223AA85C11014013D5F84`，修改时间为 17:33:33，早于此次运行样本。无需再以用户构建时间不正确解释问题；磁盘哈希仍不是进程加载版本的独立证明。本快照未检出 `GL_INVALID_`，这不能替代头发、脸部及过膝袜的视觉验收。

读取时配置为 BSL 10.1.8、VSync 开启、最大帧率 120、物理 60 Hz、GPU 日志开启。配置仅代表当时磁盘状态，不证明整段运行没有切换。源码参照为 `f25beb1` 加当前工作区的透明正确性修复。

## 四实例的直接证据

选取 `scene.WORLD.avgInstances=4.00` 的连续窗口 17:50:37.931—17:50:42.931，原始行 804、813，共 2 条总性能记录、149 帧。样本较短且没有固定相机、动作的保证，只用于定位负担，不作为优化收益对照。

| 项目 | 结果 |
| --- | --- |
| 各自 120 帧窗口的帧时中位数 | 65.672—65.968 ms，约 15 FPS |
| WORLD 主体绘制经过时间 | 32.172 ms/帧 |
| 原生更新经过时间 | 26.958 ms/帧 |
| 其中物理 | 26.108 ms/帧，已包含在原生更新内 |
| 并行批次数 | 0；全快照 200 条总性能记录也均为 0 |
| SHADOW 求值次数 | 596 |
| WORLD 求值／复用次数 | 0／596 |
| Bullet step／实际子步 | 590／2372，约 4.02 子步/step |

阴影是这段样本的首次求值阶段，WORLD 缓存正在正常复用。不能将约四个物理子步解释为阴影重复求值四次。低帧率下的固定步补算会增加每渲染帧的物理工作，当前物理计时还包含同步及回写，不能全部归到 Bullet 求解器本身。

相邻透明记录使用 17:50:36.164—17:50:46.177 中的 WORLD/GPU_COMPUTE，原始行 797—821，按每个模型的 `samples` 加权；表中均为每次模型调用的均值。

| 句柄 | samples | 透明 draw | SUBMIT | READBACK | SORT |
| --- | --- | --- | --- | --- | --- |
| 17 | 237 | 6251.48 | 6.209 ms | 1.452 ms | 0.296 ms |
| 22 | 236 | 5521.53 | 5.476 ms | 0.569 ms | 0.286 ms |
| 24 | 236 | 5804.16 | 5.620 ms | 0.609 ms | 0.271 ms |
| 26 | 203 | 5619.57 | 5.486 ms | 0.814 ms | 0.248 ms |

四者名称均为杏辞，`BATCH_SUBMISSIONS` 和 `GPU_PREPARE` 均为 0。四个各自均值相加约 2.32 万次透明 draw、22.79 ms SUBMIT，说明提交碎片仍很重。透明日志和总帧日志独立刷新，samples 也不同，不能把这组和上表硬拼成逐帧时间线或完整帧时预算。SUBMIT 包括主体材质提交，GL 调用内可能含驱动等待；READBACK 也不是纯 GPU 执行时间。排序每模型约 0.25—0.30 ms，没有证据支持优先继续做排序微优化。

## 多核实现与实际覆盖

以下 Java 路径相对 `common/src/main/java/com/shiroha/mmdskin/`。

`render/pipeline/NativeEvaluationBatch.java:20` 已有最多三个 worker，最后一个任务在调用线程执行，所有 JNI 任务结束后才发布结果。因此不能说项目完全没有多核优化。问题在任务来源：`player/render/PlayerWorldEvaluationPrepass.java:34` 显式跳过阴影，第 50 行只遍历玩家，第 51 行排除本地玩家，第 67 行要求至少两个合格远端玩家。加载模型数门槛并没有把生物替换或女仆模型替换加入候选。女仆应通过 `MaidMMDModelManager` 的 UUID 绑定和正式女仆渲染器姿态入口单独收集，不能只按普通 mob 处理。

本轮补查女仆源码：`render/entity/MobReplacementService.getReplacementModelName` 已明确排除女仆；NeoForge `MaidRenderEventHandler`、Fabric `EntityMaidRendererMixin` 走独立 `MaidMMDRenderer`。`compat/maid/runtime/MaidMMDModelManager.getModel` 会扫描模型可用性并可能调用仓库 acquire，不适合作为不加载的预处理入口，需增加按当前 `ModelRequestKey.maid` 校验的已加载查询。`MaidMMDRenderer` 的基础动画切换和临时头部角度缩放也与普通生物解析不同，共享准备必须保留这些输入并确保还原实体字段。`RenderPriorityService` 已将独立绑定女仆纳入物理优先候选，本次扩展应保留该覆盖，不能再次漏掉女仆物理预算。

`render/backend/BaseModelInstance.java:385` 在本帧精确缓存命中时直接返回空任务。阴影已串行完成求值后，再到 WORLD 扫描就无法追回这部分并行机会。日志未给出句柄对应的实体 UUID，不能为每个句柄补造实体身份，但并行计数为零、首次求值在 SHADOW 都是直接记录。

`rust_engine/src/jni_bridge/evaluation_profile.rs:18` 克隆实例 Arc 后释放全局注册表读锁，第 22 行才取得实例锁。未发现全局模型注册表锁包住完整求值、强制所有实例串行的问题。`rust_engine/bullet_wrapper/bw_api.cpp:304` 为每个物理世界单独创建 dispatcher、broadphase、`btSequentialImpulseConstraintSolver` 和 dynamics world；当前没有配置单世界多线程求解器。优先让独立实例同时求值，能够沿用现有实例隔离边界。

CPU 蒙皮已有 Rayon（`rust_engine/src/model/runtime/animation.rs:66,85`），透明排序已有 `Arrays.parallelSort`（`render/material/TranslucentDrawOrder.java:485`）。此次实际使用 GPU 蒙皮，`native.cpuSkinning` 为零；这些已有并行路径不能代替物理并行，也不能证明当前单模型主要工作已得到多核加速。计划中的外层自动 worker 上限为 `min(max(0, C - 2), max(0, N - 1))`，其中 C 为 JVM 可用逻辑处理器数，N 为实际需要求值的独立任务数；调用线程也参与执行。保留当前 C−2 的预算政策但去掉固定最多 3 个的限制，不把它直接改成更大的常数。该公式尚未写入生产代码；N=1 时外层 worker 为零，仍应允许通过独立策略启用内部并行。

另一个独立缺口是 `render/shader/ToonShaderBase.java:114` 仍仅为 vanilla 自有 Toon 注册批量主体程序。BSL 自有 Toon 未进入这一能力路径，兼容提交继续在渲染线程逐 run 调用 `glDrawElements`（`render/material/SubMeshDrawHelper.java:140`）。增大原生 worker 数不会自动减少这些提交或 GPU 位置回读。

## 单模型内部并行的源码结论

用户随后明确要求一个模型也能受益。本轮只读追踪了实际动画、骨骼、物理和固定版本 Bullet 调用链，没有新增实现或性能测量。`tick_animation_internal` 依次执行动画/Morph、物理前骨骼和 IK、物理、物理后骨骼及最终矩阵；CPU 模式最后调用 Rayon 顶点蒙皮。两个旧 tick 入口 `return` 后的长实现不可达，不计作当前重复执行。GPU 模式跳过 CPU 顶点蒙皮，仍需要骨骼矩阵。

| 可研究阶段 | 可并行边界与限制 |
| --- | --- |
| 最终骨骼蒙皮矩阵、输出重置 | 最终输入稳定后可按独立输出分区；任务规模小，需测派发成本 |
| 动画轨道插值采样 | 可研究只读采样到复用缓冲，仍按原层顺序应用 mask、别名、过渡和混合 |
| 父子/append 变换、IK | 有跨骨骼依赖，不能直接并行骨骼列表 |
| Morph | Group/Flip 展开和多个 Morph 会累加同一目标，不能直接并行写入 |
| Bullet 碰撞/求解 | 当前为顺序 world；MT 需构建、调度器、world、solver 及兼容子步联动 |
| 物理渲染姿态和骨骼回写 | 流形共享刚体、模式 2 依赖父姿态，保留现有有序修复 |

源码入口分别为 `rust_engine/src/animation/animation_layer.rs::evaluate_normalized`、`animation/vmd_loader.rs::evaluate`、`morph/manager.rs::apply_morphs`、`skeleton/bone_set.rs::update_transforms/end_update`、`skeleton/physics_writeback.rs::apply_physics_transforms`。这些只确认依赖边界，不证明每个候选都有可见收益。当前优先调查物理子阶段，不把增加小型矩阵内核直接等同于解决约 26 ms 的合计物理负担。

现有 `evaluation_profile.rs` 的物理计时包含 `model/runtime/physics.rs` 中骨骼收集、运动学同步/外力、`mmd_physics.rs::step_simulation`、渲染姿态同步和骨骼回写，以及 runtime 的物理后骨骼更新。`step_simulation` 也含速度限幅和可选诊断，不是纯 Bullet 求解。下一步先细分这些排他阶段及 island/接触规模；现有 JNI 返回固定 64 字节，详细统计需独立版本/能力接口，不静默扩容。

固定版本 Bullet 本地源码在 `rust_engine/deps/bullet3/src/`。`BulletDynamics/Dynamics/btDiscreteDynamicsWorldMt.h` 提供 island 并行和逐体阶段，`btSimulationIslandManagerMt.cpp::parallelIslandDispatch` 明确区分多个小岛与一个大岛的负载；岛由实际关节/接触形成，不能假定头发、裙子天然独立。大岛内部并行也有分批和派发开销，必须以真实模型测量。`BW_KinematicStepWorld` 还自定义固定子步的运动学插值，以及四次流形接触投影后的 MotionState 兜底；流形可能共享刚体，不能直接并行投影循环。

`rust_engine/build.rs` 枚举了部分 Mt.cpp 和 LinearMath 顶层的 `btThreads.cpp`，但没有递归枚举 TaskScheduler 子目录，也未显式配置 `BT_THREADSAFE` 或运行时 scheduler；不据此推断所有环境 CXXFLAGS 的值。`LinearMath/btThreads.cpp::btSetTaskScheduler` 使用全局 scheduler 且要求线程索引 0；`TaskScheduler/btTaskScheduler.cpp::parallelFor` 的 anti-nesting 锁会让嵌套调用顺序执行，不能据此认定任意多个 JNI 调用者重入安全。`BT_MAX_THREAD_COUNT` 是 64；线程索引缓存还要求受控的线程回收策略，不能各 Java worker 和每个物理世界独立按整机建池。

计划新增原生阶段预算：总执行预算初值 `T=max(1,C−1)`，N=1 时按内核粒度允许内部并行；N>=2 时优先外层独立实例并发，并用真正顺序内核避免嵌套扩张。Rayon 使用复用专用池，Bullet MT 则先验证协调线程、全局索引和生命周期；两类内部工作按阶段互斥，排序保持求值全部 join 之后执行。该政策尚未实现，不是已证明的最优方案；不要求提高总 CPU 百分比作为完成标准。MT 探针不过关的平台保持顺序 Bullet，单模型物理收益继续标为待验证。

单模型验收必须保留当前 GPU 蒙皮，分别记录实际内部活跃线程、子阶段经过时间和整帧 median/P95，并以相同 dt/子步负载对照。玩家、生物替换和独立女仆替换均覆盖；阴影首次求值后 WORLD 仍精确复用。MT 需对比解算姿态、MotionState、模式 2、锚点、关节限位、接触穿透、暂停/传送和资源释放，不用浮点逐位一致或仅无崩溃作为判据。线程搬移或 CPU 蒙皮加速不能替代 GPU 模式的单模型性能验收。

## 下一步与验收范围

GPU 利用率低与 CPU 侧串行求值、绘制提交或同步等待相符，但仅凭利用率不能区分三者，也不能排除 VSync。下一次固定同场景的 1/2/4 杏辞，优先采样渲染线程 CPU 运行时间与等待栈，同时保留现有分阶段日志；VSync 与诊断开关分别做单变量对照。当前未取得游戏进程线程样本，保留为未知。

具体实施范围见 [BSL 提交与单模型、多实例求值计划](shadow-bsl/implementation_plan.md)。沿用有界线程池，将合格独立实例的首次求值覆盖到实际需要它的 pass，再按物理细分结果实施单模型内部候选，并单独减少 BSL 透明提交；GL、实体读取和完成发布继续遵守现有渲染线程边界。保持物理频率和视觉规则，以同条件画面及帧时验收。主贴图自定义 sampler 的安全回退已有实际颜色差回归，不能为合批解除该保护。

本次没有新增构建或重复运行既有 GL/Java 测试，因为仅修改调查与计划。此前已核对快照哈希、重新计算选定窗口和透明加权统计；新增单模型范围只追踪相关源码，没有重采样。此前 Gemini 只读复核受线程数上限阻断；复用旧代理及核查女仆、单模型物理和调度器的 Gemini 调用均返回 429，没有新的子代理复核结果。女仆、自动 worker 及单模型内部预算的修改范围和定向验收已补入计划，生产实现仍待审批。
