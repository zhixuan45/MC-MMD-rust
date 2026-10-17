# 杏辞单模型与多实例：BSL 提交及多核求值计划

日期：2026-10-17。状态：用户已批准实施，并明确允许物理并行；当前正在实现和定向验证，游戏效果尚未验收。基线为 `feat/1.21.1-neoforge` / `f25beb1` 加工作区已完成的透明正确性修复。本计划接续上一轮优化，保留其已完成记录，不启动新的图形设备抽象或 Vulkan 移植。

目标是在 BSL、三渲二、描边及现有物理设置下，降低模型的提交和求值负担。除玩家、生物替换和女仆替换的跨实例并行外，纳入单模型内部并行：只有一个有效实例且使用 GPU 蒙皮时，也必须独立验证其 CPU 热点是否获得实际并行收益。性能结果以相同条件的游戏测量为准，不预设 FPS 数字。用户要求先修复的 Gradle 启动错误已处理，证据见 [Gradle 修复记录](../gradle_repair_2026-10-17.md)。

## 已确认的依据与仍需验证的部分

历史数据与统计口径见 [本次调查](../investigation_2026-10-17.md)，当前包之后的运行证据见 [多核覆盖与提交复核](../multicore_audit_2026-10-17.md)。新快照四实例连续窗口为 17:50:37.931—17:50:42.931，共 149 帧，窗口帧时中位数为 65.672—65.968 ms。原生更新约 26.958 ms/帧，其中物理约 26.108 ms，二者不能相加；并行批次数为零。SHADOW 求值 596 次、WORLD 复用 596 次，没有四次重复求值。相邻透明窗口四模型各自均值相加约 2.32 万次 draw、22.79 ms SUBMIT，合批和 GPU 透明准备均为零；两类日志独立刷新，不拼成逐帧预算。

源码确认两个覆盖缺口：`ToonShaderBase` 只为 vanilla 自有 Toon 注册批量材质程序；`PlayerWorldEvaluationPrepass` 只收集远端玩家，且显式跳过阴影。扩大玩家预处理的扫描集合，仍无法追回已经在阴影中串行完成的求值。

这些证据说明可优化的具体路径，但未证明全部掉帧均由它们导致。17:58 快照晚于当前 17:33 Jar；游戏 mods 和构建产物 SHA 已匹配，磁盘哈希仍不能独立证明进程加载版本。新四实例窗口很短且没有固定镜头，两个模型的历史样本又是杏辞与芒种混合，都不能作为优化收益 A/B。逐核/线程 CPU 运行时间、描边 GPU 成本、驱动等待和 VSync 的影响仍未独立测量，GPU 没跑满本身不能判定纯 CPU 算力瓶颈。

前置透明正确性工作已经完成：排序 EBO 绑定、GPU 晚期回退的完整 CPU 重建、vanilla 逐像素混合 alpha、sampler 数组逐元素恢复和 `ProgramSamplerLayout` 安全槽分配。详见 `doc/render/MIXED_ALPHA_ORDER_FIX.md`。主贴图自定义 sampler 的回退保护已通过实际颜色差对照验证，后续继续保留；这些完成项不计作本计划的 BSL 性能收益。

## 阶段一：让已适配的 BSL 自有 Toon 使用有序透明合批

调用链保持为：Iris apply → 自有 Toon 变体与 `ToonUniformBridge` 同步 → 材质/球面绑定作用域 → `TranslucentDrawOrder.prepare` → CPU 或 GPU 准备 → `TranslucentBatchSubmission` → 作用域恢复。

只扩大本模组能够生成并验证的 BSL Toon 主体程序。继续按实际 `GL_CURRENT_PROGRAM` 查注册表，不将外部 Iris 实体/阴影程序自动登记为可合批程序。输出槽、颜色编码、原包照明、alpha、球面层及眼发光沿用现有适配。批量变体编译或链接失败时使用同一 BSL 输出协议的普通变体，不能因此改成 vanilla 输出。

不能仅删除 vanilla 条件并继续绑定纹理槽 0–7。在已有 `ProgramSamplerLayout` 安全基础上补充程序活动 sampler 的位置、类型和数组元素元数据缓存，随程序删除/重建失效；在光照 uniform 同步后读取实际绑定单元，保留主贴图、覆盖色、光照图、BSL 阴影输入以及球面作用域实际占用。排除批处理自身 sampler 的默认值，防止把它们误当成外部占用。布局枚举不放在每个透明 run 中。

批量窗口仍维持现有最多 8 张纹理的上限。依据可用单元生成不超过上限的容量及映射；容量不足时切分连续窗口，无法保持采样语义时回退。主材质非默认 sampler 的现有安全条件先保留；不通过强制解绑原 sampler 获得合批。不同 sampler 类型的别名、数组和硬件阶段限制需在真实 GL 探针中覆盖。

实施前先记录目标 BSL pass 的实际拒绝原因和主贴图 sampler，不能把“程序未注册”当成唯一回退条件。若自定义 sampler 保护使目标场景持续回退，阶段一不能称为完成性能验收；保留原采样语义并单独报告该限制，不以消除保护换取计数命中。

`TranslucentDrawOrder` 与提交器共用一次准备结果，包含程序/资源代次、纹理槽映射、窗口容量和拒绝原因。必须在 GPU dispatch 前确认后续可以提交：单窗口满足全部条件才使用 GPU 准备；多窗口采用 CPU 排序加有序合批。若准备后能力失效，重新建立完整 CPU 准备结果再回退，不能在 `deviceActive=true`、CPU runs 为空时直接返回失败而漏画。

合批保持既有三角形深度顺序和平局顺序，只对连续结果切窗，不按材质重新排序。保留 `packEntity`、未知覆盖语义和动态索引的保护。检查批量 SSBO 绑定与新程序输入是否冲突，并在退出/异常时恢复修改过的 sampler uniform、纹理、sampler 对象、活动纹理单元、SSBO 范围、indirect、alpha pass 和剔除状态；不修改共享纹理参数。

预计涉及文件如下，路径相对 `common/src/main/`：

| 文件 | 修改职责 |
| --- | --- |
| `java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java` | 允许通过验证的 BSL 自有主体批量变体，保留编译回退 |
| `java/com/shiroha/mmdskin/render/shader/TranslucentBatchShader.java` | 注册程序能力及生命周期，管理批量 uniform 元数据 |
| `java/com/shiroha/mmdskin/render/material/ProgramSamplerLayout.java`（已存在） | 在现有安全槽分配上缓存元数据，保留自定义 sampler 回退 |
| `java/com/shiroha/mmdskin/render/material/SphereTextureBinding.java` | 接入共同的采样器布局检查，保持球面绑定语义 |
| `java/com/shiroha/mmdskin/render/material/TranslucentBatchPlan.java` | 按实际可用容量切连续窗口 |
| `java/com/shiroha/mmdskin/render/material/TranslucentBatchSubmission.java` | 消费准备时确定的槽映射，提交并恢复状态 |
| `java/com/shiroha/mmdskin/render/material/TranslucentDrawOrder.java` | GPU 准备前能力确认与完整回退 |
| `java/com/shiroha/mmdskin/render/pipeline/TranslucentDiagnostics.java` | 有界聚合合批/GPU 准备拒绝原因 |

只有现有 GLSL 接口确实不能消费新槽映射时才修改 `resources/assets/mmdskin/shader/toon_batch_materials.glsl`；不改材质计算公式。阶段一先独立构建和验证，再进入调度修改，以便区分收益与回归。

## 阶段二：在首次需要姿态的实体 pass 前批量求值（玩家、生物替换与女仆替换）

保留已有 `NativeEvaluationBatch` 的渲染线程准备、独立 JNI 执行、全部 join、渲染线程发布边界。世界入口继续由两平台 `LevelRendererMixin` 调用；为 Iris 阴影增加可选入口，让合格远端玩家、普通生物替换、女仆独立替换在首次使用姿态前进入同一批次。不同实例的姿态与物理仍独立持有，不因模型文件相同而共享模拟结果。

worker 自动预算以 JVM 可用逻辑处理器数 `C = max(1, Runtime.getRuntime().availableProcessors())` 为依据。沿用当前为调用线程与其它游戏工作留出两个执行位置的策略，去掉最多 3 个的固定上限：后台预算 `B = max(0, C - 2)`，本批调度 worker 数 `W = min(B, max(0, N - 1))`；`N` 是过滤、精确缓存判断和句柄去重之后真正需要求值的任务数。调用线程也参与 JNI 求值，所以总并发不超过 `min(N, B + 1)`。这是一项初始调度政策，不代表已测得最优线程占用率。

| 可用 CPU 逻辑线程 C | 后台预算 B | 2 个有效任务的 worker 上限 | 4 个有效任务的 worker 上限 | 8 个有效任务的 worker 上限 |
| --- | --- | --- | --- | --- |
| 1–2 | 0 | 0 | 0 | 0 |
| 4 | 2 | 1 | 2 | 2 |
| 8 | 6 | 1 | 3 | 6 |
| 16 | 14 | 1 | 3 | 7 |

使用复用的有界线程池，最大后台并发由 CPU 预算决定；按需建立线程并允许长时间闲置后回收，不每帧新建/销毁线程池。每批至多派发 `W` 个 worker 任务，worker 和调用线程从同一批任务中领取尚未执行的实例，避免把某个固定实例永久留给主线程而造成负载不均。线程池生命周期保持客户端范围，退出关闭。若运行中检测到可用线程数变化，只在上一批全部 join 后调整上限。没有有效任务时不派发，单任务或 CPU 预算为零时同步执行；提交拒绝时安全转由调用线程领取，任何实例只执行一次。诊断区分 CPU 预算、调度 worker 数与实测峰值活跃数，不把线程池已创建数当成本批并发数。

本机 Iris `1.8.14-beta.1+mc1.21.1` 的字节码已确认 `ShadowRenderer.renderEntities` 在阴影视锥准备后遍历候选，再逐个提交实体。拟在两平台增加 `@Pseudo`、`remap=false` 的兼容 Mixin，使用精确描述符和可选注入；通过软类型参数避免 common 对 Iris 的硬依赖。注入前后用实际游戏检查应用情况，无 Iris 或方法不匹配时继续原同步路径，并限次报告兼容钩子未命中。不能在 `renderShadows` HEAD 使用尚未准备的视锥。

阴影候选使用该入口已准备的 frustum、相机坐标、实体分发器 `shouldRender` 与 spectator 规则，不套用 WORLD 的区块和可见性筛选。冻结实体使用入口 partial tick，其余使用该版 Iris 的 real tick delta；普通 WORLD 使用原有 `DeltaTracker` 规则。`renderPlayerEntity` 的本地玩家特殊分支本轮保持原路径，不重复执行全实体批处理。

候选收集只查询已加载模型，不调用 acquire。远端玩家沿用现有选择、动画状态与待处理信号逻辑；普通生物复用 `MobReplacementService` 与 `RenderPriorityService.shouldUseMobReplacement`，再以 `ModelRequestKey.mob` 调用 `ModelRepository.peek`，使用 `EntityAnimationResolver` 取得与正式绘制相同的动画和 yaw。

女仆有独立入口：NeoForge 的 `MaidRenderEventHandler` 与 Fabric 的 `EntityMaidRendererMixin` 最终调用 `MaidMMDRenderer`；`MobReplacementService` 已明确排除女仆。女仆候选沿用 `MaidModelBindingResolver` 的“本地明确选择 → 会话远端绑定 → 原版”顺序，按 `ModelRequestKey.maid(UUID, modelName)` 查询。为 `MaidMMDModelManager` 增加 `peekLoadedModel(UUID)` 一类只查询已加载模型的入口，校验请求键、有效句柄和当前仓库归属，不触发目录扫描或模型加载，不复用可能调用 acquire 的 `getModel()`；绑定切换、解绑、F3+T、释放后的旧引用不得入队。

从 `MaidMMDRenderer` 提取小型共享准备帮助类，正式绘制和预处理使用相同动画状态、平台传入的 yaw、partial tick 和头部输入。女仆现有模型缩放会临时调整 `getXRot()/xRotO`，提前求值必须经过相同的渲染线程作用域并在 `finally` 恢复实体字段，再启动任何 worker；不能直接套普通生物的 `EntityAnimationResolver`，也不能把半调整的实体交给线程池。NeoForge 当前传 `entity.getYRot()`，Fabric 使用 renderer 传入的 `entityYaw`，分别核对对应调用点，不能统一猜测为同一种角度。模型整体缩放、`entityTrans` 和根矩阵仍在正式绘制时处理。新增准备入口不得让动画切换或输入 revision 在同帧无意义地增加两次。

女仆沿用现有 `RenderPriorityService` 中的可见/物理优先集合和 `WorldRenderPolicy`，不能因为新增候选流程而把女仆漏出物理预算，也不把普通生物专用的替换资格额外套到女仆正式渲染。候选须满足相应平台实际能够接管的条件；YSM/GeckoLib 等提前接管、女仆兼容未启用或无法确定最终渲染归属时走原路径并记录原因。按实体 UUID 选择唯一替换来源，再按 native handle 去重，不能按模型名合并多个女仆的模拟。

渲染线程完成所有候选的实体读取、动画切换、头眼/位置输入与物理预算判定后才启动 worker。只有已加载、支持现有并行接口、句柄不同且真正需要求值的实例才入队；不足两个有效任务时保持安全同步。继续排除本地玩家、VR、TaCZ、舞台、外部姿态、第一人称 prepared pose 和不兼容后端，不承诺这些实例获得并行收益。任一候选被拒绝不影响其它合格候选。

准备任务携带真实的统计场景 `RenderPerformanceProfiler.Scene.WORLD/SHADOW`，修正 `PreparedEvaluation.complete` 当前硬编码 WORLD 的归属。头眼与世界位置的业务同步仍使用 `RenderScene.WORLD` 语义，不能将光源空间矩阵或阴影相机位置写进物理/眼追踪输入。物理频率、子步上限、阻尼、碰撞与 LOD 预算保持现有值。

完成后继续使用实例、渲染帧和输入 revision 精确复用：阴影完成的结果在 WORLD 输入一致时命中；动画、外部输入、重置或资源代次改变必须失效。禁止只按实体 tick 去重。每批全部 join 后才能读取结果、上传骨骼或 compute；失败实例不发布成功缓存，不自动重复推进可能部分执行的物理，沿现有异常处理上报。采用 worker 领取任务后，需逐实例保存执行结果，保证某实例异常时继续执行、等待其它实例；不能把同一 worker 上的其它成功实例一并误判为失败或遗漏。所有已领取任务结束后才统一发布成功结果，不能遗留跨帧 JNI 写入。

预计涉及路径如下：

| 文件 | 修改职责 |
| --- | --- |
| `common/.../player/render/PlayerWorldEvaluationPrepass.java` | 保留入口兼容，复用共同的候选/执行流程 |
| `common/.../render/pipeline/NativeEvaluationBatch.java` | CPU 与有效任务数决定 worker，复用有界池、逐实例异常处理 |
| `common/.../render/pipeline/NativeEvaluationWorkerBudget.java`（拟新增） | 独立计算 CPU 预算和本批 worker 上限，便于边界验证 |
| `common/.../render/pipeline/WorldEntityEvaluationPrepass.java`（拟新增） | 玩家、普通生物、女仆的逐 pass 候选与去重 |
| `common/.../compat/maid/runtime/MaidMMDModelManager.java` | 不加载、不扫描的女仆当前绑定查询 |
| `common/.../compat/maid/render/MaidMMDRenderer.java`、`MaidRenderPreparation.java`（后者拟新增） | 正式绘制与预处理共用女仆动画及姿态输入 |
| `common/.../render/backend/BaseModelInstance.java` | 准备接口携带真实统计场景、保持输入/缓存契约 |
| `common/.../render/pipeline/RenderPerformanceProfiler.java` | 分场景批次、实体类别、worker、prepare/join 与拒绝计数 |
| `common/.../render/policy/RenderPriorityService.java` | 核对女仆物理预算与既有优先集合，按需要共享查询结果 |
| `fabric/.../mixin/fabric/LevelRendererMixin.java`、`neoforge/.../mixin/neoforge/LevelRendererMixin.java` | WORLD 入口接入共同候选流程 |
| `fabric/.../mixin/fabric/compat/EntityMaidRendererMixin.java`、`neoforge/.../neoforge/maid/MaidRenderEventHandler.java` | 对齐女仆替换资格、实际 yaw 与共享准备入口 |
| 两平台各自的 `compat/iris/IrisShadowEvaluationMixin.java`（拟新增）及 Mixin JSON | 可选阴影入口和兼容检测 |

表中 `...` 为现有 `src/main/java/com/shiroha/mmdskin` 包前缀。优先保持 `MobReplacementRenderer` 和 `EntityAnimationResolver` 原有接口；若必须提取共享准备函数，先核对正式绘制所有调用点及 revision 不重复变更。阶段二本身预计无需新增 JNI 或 Rust 接口；阶段三的内部预算和详细诊断需另行增加版本化原生能力，不能沿用这一范围判断。

## 阶段三：单模型内部并行与原生线程预算

跨实例的 `W = 0` 只表示本批没有外层后台任务，不表示单模型内部必须串行。当前 CPU 顶点蒙皮和 UV 拷贝已有 Rayon，但本次日志使用 GPU 蒙皮，CPU 蒙皮计时为零。有效调用链是 `tick_animation_internal` → 动画层/Morph → 物理前骨骼/IK → 物理更新 → 物理后骨骼 → 最终矩阵，CPU 模式才继续顶点蒙皮。不能把旧入口 `return` 后的不可达实现当成另一条运行路径，也不能将整个模型移到一个后台线程就称为单模型加速。

先补采样，区分物理中的骨骼收集、运动学同步与外力、Bullet 碰撞/求解、速度限幅与诊断、渲染姿态预测和接触修正、骨骼回写及物理后更新。现有约 26.108 ms/帧是四实例合计的物理阶段，包含同步和回写，不能当成单模型或纯求解器耗时。诊断至少记录刚体、约束、接触流形和有效 simulation island 的规模分布；细分碰撞/求解只在启用诊断时进入 C++ 钩子。保留现有 64 字节/8 个 u64 的 JNI 统计布局，详细统计另用带版本和容量校验的接口，旧 DLL 缺少能力时安全使用旧路径。采样关闭不读时钟，不逐刚体输出日志；内部 worker 的统计通过本次调用的结果归并，不能假定调用线程的 thread-local 采样会自动传给 worker。

按实测热点选择独立内核，优先保持原来的发布顺序。骨骼最终蒙皮矩阵生成、独立输出重置和只读动画轨道插值采样是候选；轨道结果需用复用缓冲保存，再按既有动画层顺序处理别名、mask、转场和姿态覆盖。任务数量及每份工作不足时顺序执行，粒度阈值由目标模型测量确定，不按骨骼数直接承诺收益。GPU 模式也会生成骨骼矩阵，因此需单独测量这条实际路径，而非只验证 CPU 顶点蒙皮。

父子全局变换、append 变换和 IK 有依赖，不直接对骨骼列表 `par_iter`；若细分采样证明有必要，再构建含全部依赖的工作分层。Morph 的 Group/Flip 展开与多个 Morph 可能累加同一目标，不能直接同时写入；任何按输出分区的候选都要保留每个目标的原累加顺序。物理模式 2 的回写依赖本帧父姿态，继续按真实父先子后顺序执行。上述顺序部分留在调用链中，不以跳过功能换取并行比例。

若 Bullet 碰撞/求解是主要负担，再实施可切换的 MT 实验。固定版本 `63c4d67e337017f9d8b298c900e9aabdb69296e7` 已提供 `btCollisionDispatcherMt`、`btDiscreteDynamicsWorldMt`、island solver pool 与大岛内部求解器，但当前 `BW_KinematicStepWorld` 继承顺序 world，创建的也是顺序 dispatcher/solver。`build.rs` 没有显式配置 `BT_THREADSAFE`，未递归编入 `LinearMath/TaskScheduler`；部分 Mt.cpp 被枚举到不等于启用了 MT。需统一检查编译宏、线程支持源码、链接及运行时能力，不能只替换 solver 或增加 worker 数。

MT 原型需提取并共用 `BW_KinematicStepWorld` 的运动学子步插值和渲染姿态处理，再接入 MT world/dispatcher，保留固定步、补步数量、关节参数、碰撞过滤、重同步和暂停恢复。`syncRenderStates` 的四次流形接触投影会多次写同一刚体的 MotionState，有顺序依赖，继续串行；不能把它当成天然独立的流形数组。先测真实 island 分布：多个岛可以并行求解，一个由接触或关节连成的大岛需另测内部求解器开销；不通过拆断关节或取消碰撞制造独立岛。

CPU 初始总执行预算沿用阶段二留量：`T = max(1, C - 1)`，其中 C 是 JVM 可用逻辑线程数，经新原生能力传入，原生再校验范围和实际可用资源。它是本模组求值的运行预算，不承诺占满全部 CPU，也不等于池中已创建线程数。最初使用互斥的执行模式，避免在每个 Java worker 内再次开满一整机线程：

| 本批状态 | 外层求值 | 单实例内部 |
| --- | --- | --- |
| N = 0 | 不派发 | 不启动 |
| N = 1 且 T > 1，内核规模足够 | 一个模型求值 | 允许内部并行，阶段活跃执行数不超过 T |
| N = 1 且 T > 1，内核规模不足 | 一个模型求值 | 顺序执行 |
| N >= 2 且 T > 1 | 按阶段二领取任务，总并发不超过 min(N, T) | 使用真正顺序内核，避免外层并发乘以内层并发 |
| N > 0 且 T = 1 | 顺序执行 | 顺序执行 |

表中的 N 仍是过滤、缓存和句柄去重之后的有效任务数，不能使用场景中模型总数。单模型的内部执行宽度还受可分工作数及内核粒度约束。Rayon 使用客户端范围复用的专用池，内部路径进入该池，顺序路径调用普通迭代内核，不用修改全局环境变量或反复重建全局池控制线程数。若池内执行由 worker 承担，调用者等待期间不计作活跃计算线程；若调度器让调用者参与，则后台数至多为执行宽度减一。不同独立内核按动画依赖顺序启动并在返回前全部完成。排序发生在求值批次 join 之后，保留当前稳定排序语义；Java 公共池中的其它模组工作不在本模组可控预算内，另用线程采样观察竞争。

Bullet 的任务调度器是进程内全局对象，`btSetTaskScheduler` 要求 Bullet 的线程索引 0，内置调度器用 anti-nesting 锁避免嵌套派发；这不足以证明任意 Java/JNI 线程可同时重入。MT 启用前先用原生探针固定一个协调入口完成初始化，并核实 WORLD、SHADOW、模型加载/重建和释放等全部调用者的线程索引及调度归属。只在所有求值、池任务和世界操作完成的边界改变模式、预算或关闭调度器；不在逐模型 worker 中替换全局 scheduler，不为这一目的把未证明安全的 FFI 类型改成 `Sync`。

当前 Bullet 的线程索引上限为 64；Java 调用线程、原生协调线程和 MT worker 的有效索引需统一核算，不能分别按 64 建池。尤其不能把阶段二允许的部分线程闲置回收策略直接套到参与 Bullet 索引的线程：固定版本的索引缓存依赖受控线程生命周期，反复只回收部分线程可能使索引重复。先验证参与线程稳定、预算变化和完整销毁/重建的安全方案，再开放 MT 运行切换；无法保证这些条件的平台保留顺序 Bullet 能力。高于 64 个 CPU 逻辑线程时对 Bullet 参与集合限幅并记录原因，其它数据内核仍按自己的可用预算处理。能力探针不过关不默认启用 MT，也不将仅跨实例加速记作单模型物理加速完成。

阶段三需要在现有渲染线程准备与全部完成后发布的边界上增加只读执行策略，覆盖玩家、生物替换和独立女仆替换。只有一个模型落入原同步入口时，也应按资格取得内部预算，不将内部能力绑定到“至少两个远端玩家”的门槛。本地玩家、VR、TaCZ 等阶段二排除对象继续保持原契约，未单独验证前不承诺其获得新增并行。异步工作不能越过帧边界或与同句柄的下一次输入、释放并发；策略/能力不支持时在执行前选择顺序路径，不在已部分推进物理后重跑整次更新。

预计原生联动位置如下；MT 行只在细分采样和调度探针支持时实施，结果与取舍保留在同目录审计中。

| 路径 | 责任 |
| --- | --- |
| `common/.../NativeFunc.java`、原生能力适配及 `NativeEvaluationBatch` | 版本化内部执行策略、能力回退及外层模式选择 |
| `rust_engine/src/jni_bridge/evaluation_profile.rs`、`evaluation_profile.rs` | 独立版本的细分诊断，保留旧 64 字节接口 |
| `rust_engine/src/model/runtime/animation.rs`、`physics.rs` | 按真实更新链记录子阶段，传递只读本次策略 |
| `rust_engine/src/evaluation_parallelism.rs`（拟新增） | 专用 Rayon 池、预算/粒度与顺序内核选择 |
| `rust_engine/src/skeleton/bone_set.rs` 等实测热点文件 | 只对无依赖输出或只读采样增加内核，保留顺序发布 |
| `rust_engine/src/physics/mmd_physics.rs`、`bullet_ffi.rs` | 细分物理诊断与原生能力，不增加未经证明的共享可变访问 |
| `rust_engine/bullet_wrapper/bw_api.cpp/.h` 及新物理 world/调度帮助文件 | 共用运动学插值、MT 原型、全局生命周期和安全回退 |
| `rust_engine/build.rs` 及必要的平台原生构建配置 | 固定版本线程支持源码、宏与链接一致性 |

`bw_api.cpp` 当前约 942 行、`mmd_physics.rs` 约 976 行，新增 world/调度和诊断优先放在专用文件；被修改文件超过 1000 行必须先按职责拆分。阶段三预估额外 700–1400 行生产/诊断代码、400–800 行定向探针和测试，实际范围取决于热点与 MT 能力验证；不为凑齐候选而同时重写所有动画/Morph/IK 内核。

## 诊断、代码量与交付顺序

沿用现有调试开关和窗口聚合，不增加逐帧逐 run 日志。记录真实 pass、玩家/普通生物/女仆类别、实际入队/完成数、CPU 线程预算、调度 worker 数与峰值活跃数，以及未加载、预算停更、缓存命中、特殊姿态、兼容钩子缺失、程序未注册、采样器容量不足等理由。采样关闭不读取性能时钟或创建日志明细。并行 native 累计工作时间与主线程 prepare/join 经过时间分列，不能把前者当串行阻塞相加。

阶段一、二纳入女仆共享准备和自动 worker 策略后，预估生产代码新增/修改约 800–1200 行，测试和探针约 400–650 行，分布在约 22–30 个生产/配置文件及相关验证文件；阶段三为额外估计，不能视为已包含。这是实施前估计，不是完成量，各阶段分别复核实际差异。当前实例基类约 636 行、透明排序类约 740 行，新逻辑优先放入专用小类；任何被修改文件若超过 1000 行，按职责拆分后再交付。新增注释用简短中文。获准实施后按 Java/原生模块委派独立修改任务，禁止子代理派生；若代理仍不可用，明确报告并按模块分批窄验证。

曾按用户要求尝试委派深度跟踪，但遇到 429 和线程数上限。本次补充女仆范围、单模型物理及调度器复核时使用 Gemini，重试仍以 429 结束，没有产生可采用的分析结果；主代理继续核对实际源码，不把失败委派当成审查通过。

实施先补最小诊断及阶段三的物理细分，采集当前包基线，再完成阶段一的合批/GL 验证和阶段二的候选/时序验证，随后按真实热点实施阶段三及独立验证，最后联合打包与游戏 A/B。沿用 `mmdskin.translucentBatch`、`mmdskin.gpuTranslucent`、`mmdskin.parallelEvaluation` 对照开关；单模型内部并行和 MT 实验另设独立对照，不改变原开关语义。编译恢复已经完成，不以继续修改 Gradle 配置作为性能工作的前置条件；阶段三涉及原生构建时再做必要调整。

## 验证与完成判据

纯 Java 回归优先运行 `TranslucentBatchPlanTest`、`TranslucentDrawOrderCacheTest`、`NativeEvaluationBatchTest`、`FrameEvaluationCacheTest`、`WorldRenderPolicyTest` 和相关 Toon profile/BSL 测试。新增测试覆盖：有限纹理容量下的连续顺序；阴影先求值到 WORLD 的复用；输入变化失效；真实场景统计；同一 worker 内有实例失败后其它实例仍执行、全部等待后才发布。不为简单字段赋值堆砌测试。

自动预算独立覆盖 CPU 线程数 1/2/3/4/8/16/64/128、有效任务数 0/1/2/4/8/64 的组合，验证 `W <= C - 2`（低线程数归零）、`W <= N - 1`、调用线程参与和无固定 3 worker 上限。执行测试验证混合耗时任务不会重复或遗漏、重复句柄不增加并发、线程池复用、关闭/拒绝后的同步回退，以及 CPU 预算变更只能发生在已 join 的边界。阶段三再验证 N=1 可以进入内部并行、N>=2 的外层模式关闭嵌套内核、活动执行数遵守 T、低粒度顺序回退，以及旧 DLL 能力缺失、内部任务异常、暂停/退出和反复切换预算的生命周期。Rayon 与 Bullet 使用互斥阶段预算；公共排序池的外部竞争通过线程采样单独记录。

女仆定向验证覆盖本地/远端绑定优先级、原版选择、未加载、无效句柄、解绑、切换模型、资源重载、同名模型多 UUID、重复候选和不同实例句柄；确认只读查询不 acquire、不扫描目录。共享准备验证 Idle/Walk/Sprint/Ride/Sleep/Die 状态、头部缩放作用域正常及异常退出恢复、正式绘制与预处理输入一致且不重复切换动画。检查女仆进入既有物理预算；未安装女仆模组或被 YSM 等接管时不提前推进 MMD。

真实 GL 探针扩展 `tools/translucent-performance` 与 `tools/iris-toon-probe`：用实际 BSL 10.1.8 三维度源码编译，自有 Toon 普通/合批结果对照颜色、alpha、深度、SPH/SPA、眼发光；覆盖 sampler 数组、非零 sampler、槽位紧张、多窗、能力不足、异常与资源重建，检查绑定和 uniform 恢复。专门强制“GPU 准备可用但提交条件失效”，验证完整 CPU 重建回退而非漏画。阶段二用四实例 JNI 串行/并行在相同时间步比较最终骨骼结果；未改原生代码时无需无理由重跑全库 Rust 测试。

阶段三对独立数据内核验证顺序/并行输出及原发布顺序。MT 单独用相同资产、初态、dt 序列与运动学输入，比较真实解算姿态、MotionState、骨骼重建、关节锚点/限位误差、接触穿透、模式 2 父子传播及实际子步，覆盖固定步以下/以上 dt、低 FPS 补步、暂停冻结、传送重置与世界销毁。多线程求解顺序可能造成浮点差异，报告偏差分布及物理约束指标，不要求与顺序求解逐位一致；验收容差先从顺序基线和现有回归约束建立，不能事后放宽。检查一个大岛与多个岛、线程索引上限及线程回收/切换，开启相应原生内存/线程检查；不以“未崩溃”代替数值和视觉验收。涉及原生修改时运行相关 Rust/C++ 回归与 JNI 生命周期探针，并核对新的 DLL 实际被打入 Jar。

使用 Zulu JDK 21、Gradle 8.11.1 和现有共享暖缓存。先窄编译，再完成相关测试与 Fabric `remapJar`、NeoForge `shadowJar/assemble`；核对 Jar 内 common 类、两平台 Mixin 配置、Fabric refmap、shader 和正确原生库。检查 `git diff --check`。不执行 clean、不清缓存、不结束 IDEA daemon，不动 `doc/security` 的其它任务文件。

游戏验收使用用户版本目录，记录新旧 Jar SHA、DLL SHA、Iris/BSL 版本、分辨率、相机、模型映射和配置。先建立 1/2/4 个杏辞的相同资产样本，分别使用纯普通生物替换、纯女仆替换、远端玩家和三类混合场景，避免只验证玩家。再在同世界、同镜头、同距离、同动作和稳定加载后比较基线、各阶段单独启用和联合启用；女仆动作切换与绑定切换另做正确性场景。单模型分别测 GPU/CPU 蒙皮，GPU 模式必须保留，不能为命中已有 Rayon 改测 CPU 模式；区分独立内核和 Bullet MT 的开销与收益。分别验证无光影的 WORLD 首次求值与 BSL 阴影首次求值后 WORLD 复用。同步采样渲染线程及原生 worker 的 CPU 运行时间和等待栈，区分物理计算、驱动/同步等待与帧率限制，不能仅凭总 CPU/GPU 百分比调整线程数。各条件固定预热后连续采集至少 60 秒，出现加载或转场则重新取样；日志逐帧计数/窗口统计按原口径报告，不能拼接窗口分位数。若主代理无法操作游戏，交付可复现采样步骤和包，明确标为待用户游戏验收。

阶段一应观察到已支持的 BSL pass 合批命中、透明实际 draw 明显减少；只有候选纹理单窗且能力满足时要求 `GPU_PREPARE` 命中和位置回读消失。阶段二应在至少两个合格玩家、生物替换或女仆替换实例的首次求值 pass 看到并行批次；诊断同时记录实体类别和实际 worker 数，SHADOW/WORLD 总求值与复用合理，不能只凭 WORLD 缓存计数认定并行成功。同时观察 prepare/join、原生工作、物理实际子步、主体提交及帧时 median/P95；低帧率补步变化单独解释。

阶段三单独报告一个有效实例时的内部任务数、实际并发、子阶段经过时间及整帧 median/P95。必须在相同子步工作量的探针中先证明热点经过时间缩短，再确认游戏收益且画面/物理无回归；只有线程被创建、单模型被移入后台或 CPU 模式蒙皮变快都不能关闭 GPU 场景的单模型要求。若当前模型岛结构或剩余顺序比例使内部并行无收益，保留顺序默认并明确报告未完成的性能目标，不将小内核加速推算为整帧加速。

保持阴影、透明薄片/袖口、遮挡、球面高光、光照与描边观感；抽查单模型、无光影、关闭 Toon、第一/第三人称、暂停/冻结、模型切换、F3+T 和进退世界。VSync 与诊断开关另做单变量对照。只有实际游戏数据显示命中、收益及无明显退化时，才称性能问题已完成验收；构建与隐藏探针通过只说明各自验证范围。

审批依据：用户提供的 AGENTS 规则 8c 要求“涉及……核心模块拆分合并、设计模式变更、或整体技术方案选型时，必须先出实施计划（implementation_plan.md）并等待用户审批”。本计划新增跨平台阴影调度入口、单模型原生预算及条件性的 Bullet MT 路径，改变跨层准备/提交和线程生命周期契约，按该规则先提交评审。用户已通过“先开始实现然后我看看效果，同时应该允许物理并行的情况”批准本计划；本次按阶段实施并保留各项验证边界。
