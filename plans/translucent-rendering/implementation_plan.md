# OpenGL 半透明性能优化与后端接口实施计划

2026-10-15，待审批。本文件只描述实施方案，本轮未开始接口或性能代码改造。仓库根目录的 `implementation_plan.md` 属于尾裙碰撞任务，保留原文；本计划独立存放。执行者先完整阅读根 `index.md`，检查当前 Git diff，保留既有物理与 SPA/SPH 修复。

## 目标与现状

用户报告半透明部件越多、动作越大，帧率越低，约为站立 70、走路 30、跑步 20 FPS。本任务先在现有 Minecraft/OpenGL 环境定位并降低实际帧时间，保持材质透明度、遮挡、Toon、SPA/SPH、眼发光、描边和光影兼容；同时建立适度的设备接口，让未来 Vulkan 等后端能替换资源与执行实现。

上述 FPS 是用户观察，尚无同条件分段计时。动作幅度不是当前排序复杂度的直接参数。`BaseModelInstance.update` 每次成功更新都会增加 geometry revision；`TranslucentDrawOrder` 已缓存透明候选拓扑与 alpha 覆盖，但仍可能回读 GPU 位置/UV，并每帧计算深度、排序、重建索引和材质 runs、上传索引、逐 run 绘制。首先测量这些成本与原生更新/物理时间，不能预先把 GPU 排序定为唯一修复。

现有 `RenderBackendRegistry`、`RenderModeManager` 和 `RenderCategory` 选择 CPU/GPU 蒙皮。`OpenGlModelInstance` 和 `GpuSkinningModelInstance` 均使用 OpenGL，后者是 OpenGL compute 蒙皮；`NativeRenderBackendPort` 是原生模型数据端口。当前不存在真正的图形 API 后端选择，也不存在 `ModelRendererBackend` 接口。`ModelInstance` 是 Minecraft 模型生命周期入口，仍接收 Entity/PoseStack/RenderScene。不能把 JNI 端口改名后称为已支持 Vulkan。

当前关键代码是 `render/material/TranslucentDrawOrder.java`、`SubMeshDrawHelper.java`、`MaterialRenderState.java`、`ModelMaterial.java`、两个 renderer、`render/shader/SkinningComputeShader.java`、`render/pipeline/RenderPerformanceProfiler.java` 及 `GpuTimerQueryPool.java`。先核对这些文件与 `TRANSLUCENT_PERFORMANCE_HANDOFF.md`，以当前工作树而非旧报告中的历史行号实施。

## 边界与抽象

采用两条独立选择轴：图形设备由实际游戏宿主提供，第一种实现是 OpenGL；蒙皮策略为 CPU、GPU_COMPUTE、AUTO，由设备能力和已有用户设置共同决定。透明排序与提交策略再按设备和当前 pass 的能力选择。当前 CPU/GPU 设置继续只表示蒙皮，不把它改成选择 OpenGL/Vulkan 的开关。

这一轮抽象模型绘制中的几何输入、材质数据、透明准备和提交边界，不构建通用图形引擎，也不逐条包装所有 `gl*` 函数。第一阶段保留现有模型外部接口、Minecraft 生命周期和可用的 OpenGL 实现。旧 GL 资源通过 OpenGL 适配层接入，新接口不继续传播整数 GL 名称；其他仍未迁移的 GL 通道明确属于旧实现，不能宣称全渲染器已可跨 API。

拟新增契约如下，名称可按仓库约定调整，职责必须保持：

| 契约 | 职责与输入输出 |
| --- | --- |
| `RenderDevice` | 当前设备身份、生命周期、能力与模型资源创建；按模型/帧/通道调用，不在三角形内做虚调用 |
| `RenderHostBridge` | 将 Minecraft 实体、GUI、手部、阴影 pass 转成当前设备可用的绘制作用域；OpenGL 实现负责 Blaze3D/Iris 协作 |
| `RenderCapabilities` | 描述 compute、storage、间接绘制、材质索引、可用纹理页、缓冲限制、采样和目标协议等实际能力 |
| `ModelRenderResources` | 实例拥有的几何、索引、材质表和排序工作缓冲；上传、失效、统计与释放集中处理 |
| `TransparencyBackend` | 接收拓扑、材质资格、当帧几何视图和 pass，准备并提交全局有序透明绘制；输出后端拥有的绘制结果及异步诊断票据 |
| `PreparedTransparency` | 标识后端已准备的当帧排序结果及依赖，容纳 CPU 有序 runs 或设备端索引/命令，普通调用方不强制读取 GPU 数据 |
| `FrameToken` / 类型化资源句柄 | 标识设备、上下文代次、资源范围和当前帧；不等同于 GLsync、GLint、VkBuffer 或 VkFence |

顶点布局、范围、索引类型、材质编号、矩阵、可见性、alpha、diffuse/specular、球面模式及 morph 保持为中立描述。裁剪深度范围、viewport 方向、正面绕序和渲染目标语义由设备/宿主提供；共享视锥策略不硬编码 OpenGL 坐标约定。CPU 材质描述与设备纹理资源分开；现有 `ModelMaterial.tex/sphereTex` 留在 OpenGL 兼容适配器中逐步迁移，不进入新的材质语义契约。纹理身份必须区分文件缓存键、采样语义与设备资源，保留共享引用计数和显存预算。

设备资源句柄带设备身份与代次，并声明借用/拥有。包装现有 VBO/纹理时不得重复删除；实例释放交由当前宿主渲染线程执行，设备负责在使用完成后真正回收。模型切换、异常构造、光影重载、上下文代次变化、退出和重复 dispose 都有明确行为。公共契约表达生产者/消费者用途与就绪依赖，例如 compute 写入后的索引用于绘制；具体 barrier、fence、队列及读回缓冲由后端实现。需要诊断读回时提供非阻塞 poll，热路径不隐式 await。

未来 Vulkan 的同步、资源布局和队列语义需要后端明确实现，不能把 `glMemoryBarrier` 位掩码直接放进公共接口。[Khronos 同步指南](https://docs.vulkan.org/guide/latest/synchronization.html) 与 [资源描述符规范](https://docs.vulkan.org/spec/latest/chapters/descriptorsets.html) 可作为未来实现核对依据，本轮不新增 Vulkan 运行依赖。

## OpenGL 实现与能力回退

新增 `render/device/opengl` 的设备/宿主适配，以及 `render/transparency/opengl` 的透明执行实现；公共契约与纯数据放在 `render/device`、`render/transparency`。初期由 `OpenGlLegacyTransparencyBackend` 委托现有排序和绘制，在同一 pass 内保持现有结果。CPU 拓扑/覆盖分类与深度键语义整理为可测试的纯逻辑，缓冲读回、上传和绘制留在 OpenGL 实现内。

OpenGL 加速使用当前上下文真实支持的能力。compute 与 SSBO 路线以 OpenGL 4.3 或对应已验证扩展为能力入口；基线 CPU 绘制继续覆盖原有低能力上下文。多绘制间接提交也需要独立检测，`gl_DrawID`、GPU 生成 draw count、bindless texture 等能力另行检查，不能从“支持 compute”推断全部可用。[Khronos compute 文档](https://wikis.khronos.org/opengl/Compute_Dispatch) 和 [绘制能力文档](https://wikis.khronos.org/opengl/Vertex_Rendering) 是能力核对来源。

设备能力之外，还必须核对当前 shader/pass 是否能消费材质表和所需输出。Toon 的已知程序可先支持批量变体；原版/Iris 外部程序没有同样的逐材质契约，继续兼容绘制。未知 alpha 公式保持候选分类的保守行为。GPU 蒙皮失败回退 CPU 蒙皮；GPU 排序或多材质提交不可用时回退现有全局 CPU 排序与连续有序 runs。保留用户的 GPU 蒙皮偏好，报告本次实际策略和原因，不静默长期改配置。

Iris 阴影 pass 保留其程序、目标和完整模型提交规则；当前不对阴影通道新增半透明排序。GUI/纸娃娃、世界和第一人称分别处理矩阵、viewport、动态索引与 pass 标识。光影输出布局、alpha pass、混合、深度补写、遮罩和描边作用域继续遵守现有逻辑。所有 OpenGL 路径在正常与异常退出恢复声明会修改的 GL 状态，并与 Blaze3D 缓存一致；不能每帧为方便抽象做全状态快照或调用 `glFinish`。

## 性能路径与正确性约束

先补限频聚合数据：每模型/场景的候选构建与复用次数、候选/可见三角形数、runs、实际 draw 数；回读次数/字节/CPU 等待；深度键、排序、索引/命令生成、上传、提交的 CPU 时间；对应 GPU compute/draw 时间；原生更新、物理及总帧时间中位/P95。GPU query 和统计读回延后非阻塞获取，不逐帧打印。采样关闭时避免额外热路径开销。

若回读或索引生成成为主要成本，再实现 OpenGL GPU 深度键、视锥筛选、稳定排序、索引和命令生成。排序保持当前远到近与原始三角形序号的平局顺序，明确处理无效几何和深度；可用两个 32 位整数表示深度键/稳定序号，不以 GPU 64 位整数扩展为默认前提。缓存仍按动态 EBO、UV、显隐、alpha 资格和设备代次失效。跨 dispatch 明确建立 storage 写入到 storage/索引/间接命令读取的可见性。

GPU 排序与多材质消费要作为联动条件：不能排序完又同步回读全部索引或 runs 给 CPU，也不能仅生成有序 EBO 后按材质一次绘制造成重新排序。先在独立生产 shader 变体探针中完成设备端结果，再在整条排序、材质消费与提交链闭合后启用快速路径。

材质表与批量提交优先处理已知 Toon 契约。表内包含每次绘制的有效颜色、高光、alpha、球面模式/乘加 morph、眼遮罩和纹理引用。每个有序三角形或连续 run 都带原材质编号；不能假设同一顶点只能属于一种材质。设备端命令必须让 shader 正确选择对应表项；OpenGL 4.3 可研究 baseInstance 配合实例属性，支持 draw parameters 的设备可使用单独变体。只有逐 draw 材质访问和测试通过后才启用多绘制。

纹理采用保持尺寸、像素和采样语义的 texture-array 页或其他已经验证的 OpenGL 绑定方案。先测实际模型的纹理尺寸/格式/采样组合和设备预算，不承诺任意贴图都能放进一个数组。不为合批统一缩放贴图、改变颜色空间或滤波；不同页、球面贴图和 Iris 已占用采样器共同计入预算。bindless 只是可选能力；sampler 数组动态索引必须符合实际 GLSL 规则，不能假设任意片段都能非一致索引。资源组合超出快速路径限制时，保留透明顺序进行有界切段或整模型兼容回退。

硬件剔除、混合和深度状态不能在一次 multi-draw 的各 draw 之间随意改变。计划优先将合法的逐材质差异转入已验证的 shader 表项；如用 shader 区分双面/单面，须固定 `gl_FrontFacing`、绕序和法线行为并验证成本。无法表达的状态只能在全局有序序列中按连续兼容段提交，禁止为省 draw 把所有同状态材质重新集中。

GPU 生成的实际 draw 数量不能引入新的每帧同步回读。支持设备端 count 的实现使用其能力；基础间接路径可用已知容量命令区和未使用的零 count 命令，测量命令扫描成本及内存上限；超出预算时回退。固定容量、命令重置和越界保护是实现契约，不以未初始化命令碰运气。

若实际瓶颈主要是像素层叠，应先处理正确的不透明覆盖、遮挡和重复 shader 工作，再重新评估这一阶段的收益。本计划不默认使用上一帧姿态、粗深度桶、按材质重排、统一 alpha cutout 或改变半透明颜色的近似 OIT。任何允许画面近似的后续方案单独评审。

## 分阶段实施与文件范围

第一阶段仅增加诊断并采集固定条件基线，记录站立/走路/跑步各阶段的帧时间与遮挡截图。第二阶段引入上述最小接口和 OpenGL 兼容适配，保持算法与 shader 行为，并用前后对照确认没有性能/画面回归。第三阶段按第一阶段证据实现 GPU 数据链及材质批量路径，在能力覆盖和整链验证闭合后启用；如果瓶颈属于其他阶段，调整优化内容并记录理由，不以抽象完成代替性能结果。第四阶段完成游戏验收与交付。

拟改动范围以 `common/src/main/java/com/shiroha/mmdskin/` 为基准：

| 文件或目录 | 改动职责 |
| --- | --- |
| `render/device/`、`render/transparency/`（新增） | 公共契约、资源视图、帧/pass 数据、纯排序语义与有序结果 |
| `render/device/opengl/`、`render/transparency/opengl/`（新增） | GL 资源所有权、宿主状态范围、兼容执行及能力受限的加速实现 |
| `render/backend/RenderBackendRegistry.java`、`mode/`、`factory/` | 在现有选择点明确设备和蒙皮两轴，保持配置与 CPU 回退 |
| `render/bootstrap/ClientRenderRuntime.java` | 在现有组合根注入设备/宿主实现并接入清理 |
| `render/backend/opengl/`、`render/backend/gpu/` | 将几何缓冲、当帧版本和 pass 接到新透明边界，保留已有主体/描边入口 |
| `render/material/TranslucentDrawOrder.java`、`SubMeshDrawHelper.java` | 抽离纯策略与 GL 执行，兼容包装避免一次移动所有代码 |
| `render/material/ModelMaterial.java`、`MaterialRenderState.java` | 分开材质语义与设备纹理引用，保留全部 morph/透明/球面参数 |
| `render/shader/` 与 `assets/mmdskin/shader/` | 按阶段增加 GPU 排序、命令生成及已知 Toon 多材质变体；旧变体继续可用 |
| `texture/runtime/` | 仅在纹理页/生命周期确需时扩展，不改变原图、采样与预算合同 |
| `render/pipeline/RenderPerformanceProfiler.java`、`GpuTimerQueryPool.java` | 子阶段、计数及非阻塞统计，避免与游戏帧号错误对齐 |
| 对应 `common/src/test/java`、`tools/translucent-performance/` | 接口行为、稳定排序、能力回退、真实 OpenGL 和资产对照 |
| `index.md`、性能交接记录 | 更新最终调用关系、能力/限制及验收证据 |

所有接口实现/调用点必须在实施前定向核对漂移。初期不移动整个 `render/backend/gpu` 目录、不重写全部 JNI，也不引入 Vulkan 工厂空实现冒充适配；若实际迁移需要扩展到其他核心通道，先更新本计划的范围与必要性。任何改动代码文件超过 1000 行必须拆分，GPU 排序阶段、资源管理和 shader 生成分别放入小文件。代码只写必要的简短中文注释。

实施用量按阶段估算：诊断与兼容抽象为中等改动；GPU 排序、纹理页和批量提交为较大改动，分阶段分配 Luna 子代理独立模块，主代理负责接口一致性、整合与验收。执行前根据实际清单重新估算，不为了控制用量省略关键验证。

## 验证、迁移与审批

纯逻辑测试覆盖稳定深度顺序、平局、视锥筛选、动态第一人称索引、UV/morph/alpha/可见性失效、相同主贴图但不同球面/眼遮罩材质的批次资格。接口测试覆盖设备错配、代次失效、借用资源不重复销毁、在途资源延后释放、异常和重复 dispose；用轻量假后端验证公共契约不依赖 LWJGL。CPU/GPU 排序比较原始三角形编号和材质序列，而非只比较 FPS 或几个中心像素。

OpenGL 探针覆盖 CPU/GPU 蒙皮、兼容/加速排序、能力缺失/编译失败回退、零候选/大容量/溢出、当前帧几何、正确屏障和命令重置；比对 MRT、深度、混合、透明洞、SPA/SPH、眼发光与状态恢复。测试包含共享顶点跨材质、双面/单面交错、材质 morph 和纹理页边界。计时测量使用已预热的实际完整链，不能把独立排序微基准当作游戏收益。

按项目 gradle-ide-parity 流程核对 JDK 21、共享缓存和 wrapper，再执行所改模块的测试、Fabric/NeoForge 编译和最终打包。匹配的 Java/shader/native 产物一起验证；仅改 Java/GL 资源契约时不制造不必要的 JNI 签名变化。设备适配阶段先验证兼容路径，GPU 阶段才增加相应探针。验证后不重复扩大无关测试范围。

游戏验收使用同一模型、动画、相机、分辨率、场景、光影与配置，固定站立/走路/跑步，记录帧时间中位/P95、各阶段成本、回读量、runs/draw 数，并对照睫毛/发丝、皮肤、丝袜、裙摆/长袖和珍珠的遮挡。还要检查世界、GUI/纸娃娃、第一人称、阴影 pass、光影切换和模型释放。CPU 蒙皮、无光影可用作诊断对照，正式结果以用户正常 OpenGL 配置为准。

将 CPU/GPU 策略和新透明实现分阶段验证。承诺保留画面语义，不能承诺某个未经测量的 FPS；接口完成、OpenGL 探针通过、游戏性能改善和未来 Vulkan 适配是四项不同结果。本轮 Vulkan 仅留下可替换边界，实际接入还需要目标 Minecraft/加载器真实提供的非 GL 宿主、目标/纹理资源、shader、同步和光影协议。

审批对象为本计划中的 OpenGL 基线测量、最小接口抽取与按证据实施加速的范围。依据用户提供的 AGENTS.md 第 8c 条：重大架构改造“必须先出实施计划（implementation_plan.md）并等待用户审批，不得直接动手”。审批前只交付该文档；审批后先执行第一阶段，不在缺乏基线证据时直接启用 GPU 快速路径。
