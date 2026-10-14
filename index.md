# MC-MMD-Rust 项目架构与核心索引

本项目是一个将 MMD（MikuMikuDance）及 VRM 模型生态深度整合进 Minecraft 的高表现力客户端/服务端模组。工程采用原生 Multiloader 构建体系，上层对接 Fabric Loom 与 NeoForge ModDevGradle，历史 Forge 1.20.1 代码保留作为移植参照；底层深度集成基于 Rust 与 C++（Bullet3）自研的高性能原生物理与蒙皮计算引擎 `rust_engine`。通过 JNI 句柄路由与 Direct ByteBuffer 零拷贝管道，在保证 Minecraft 原版管线与光影兼容性的同时，实现了高帧率、高拟真的次时代角色渲染与布料物理表现。

---

## 1. 全局架构与技术全景

代码库自底向上划分为三个清晰的架构层级：最底层的原生计算核心、中间层的平台中立业务与渲染中枢，以及最外层的加载器特化适配层。

原生层由 `rust_engine` 构成，使用纯 Rust 编写并在构建期静态链接 Bullet3 物理引擎。它不仅负责零拷贝解析 PMX、VRM 等资产，还承担骨骼层级变换、CCD-IK 解算、多轨并行动画插值、Rayon 多线程 CPU 蒙皮与 GPU Compute 蒙皮数据供给。特别是在物理系统上，项目确立了严格的数学边界：骨骼系统、相机空间与 OpenGL 渲染运行于标准右手坐标系；Bullet3 物理模拟与 MMD 规范则运行于左手坐标系。两界交互通过对合矩阵变换（Involution）进行严格的无损映射，确保刚体世界姿态与局部约束锚点在往返转换时不产生手性反转。

中间业务层以 `common` 模块为核心，基于六边形架构（Hexagonal Ports）组织。它对下将繁杂的 JNI 调用拆解为细粒度的运行时端口，屏蔽原生层细节并支持无环境 Mock；对横向统一调度双分支渲染管线（CPU 蒙皮与 GPU 计算着色器蒙皮），实施 Blaze3D 渲染状态保护、第一人称拓扑动态裁切、多人联机模型同步以及显存预算治理；对上为各平台加载器提供统一的客户端生命周期调度与渲染委托。

外层加载器包含参与当前构建的 `fabric`、`neoforge` 以及历史保留的 `forge` 代码。各端仅保留最小限度的平台特异性逻辑，利用 Fabric Loom、NeoForge ModDevGradle 与 Shadow 构建流水线，将网络载荷、按键绑定、生物渲染接管与 Mixin 注入精准桥接至 `common` 中枢。

```
+---------------------------------------------------------------------------------+
|                                 Minecraft Client                                |
|  [Fabric Loader]                  [NeoForge Loader]             [Forge (Legacy)] |
|   - MmdSkinFabricClient            - MmdSkinNeoForgeClient       - MmdSkinForge  |
|   - FabricClientRuntimeHooks       - NeoForgeClientEventHandler  - ClientSetup   |
+---------------------------------------------------------------------------------+
                                         |
                                         v
+---------------------------------------------------------------------------------+
|                                 common (Java)                                   |
|  - bridge.runtime: NativeRuntimeBridge & 细粒度 Port 契约                        |
|  - render: OpenGlModelRenderer (CPU) / GpuSkinningModelRenderer (Compute Shader)|
|  - render.shader: ToonShader 色阶着色 / 倒置法线描边 / SSBOBindings 状态治理      |
|  - player: FirstPersonManager 动态视锥裁切 / 玩家独立模型替换 / 3层动画状态机    |
|  - compat: TaCZ 枪械快照姿态 / Iris 光影状态感知 / Vivecraft VR 追踪驱动         |
|  - asset/model: ModelRepository / ManagedModel 统一生命周期池                   |
+---------------------------------------------------------------------------------+
                                         |  JNI 句柄路由 & Direct ByteBuffer 零拷贝
                                         v
+---------------------------------------------------------------------------------+
|                            rust_engine (mmd_engine)                             |
|  - jni_bridge: native_func.rs (159个导出接口, 全局句柄映射与并发读写锁)           |
|  - physics: MMDPhysics / Bullet3 动力学调度 / 惯性速度注入 / 尾巴气动模型         |
|  - physics.topology: skirt_cross_joints 裙摆环向保形弹簧网络合成                |
|  - physics.collider: body_collider_synthesis 躯干跟骨碰撞体补全与推离保护       |
|  - model/skeleton: MmdModel 运行时实体 / Rayon 并发蒙皮 / CCD-IK 解算器         |
|  - bullet_wrapper: C++ Bullet3 C-ABI 安全封装静态库 (bw_api)                     |
+---------------------------------------------------------------------------------+
```

---

## 2. 原生引擎层：rust_engine 核心机制

`rust_engine`（Crate 名为 `mmd_engine`）对外编译为动态库 `cdylib` 供 Java JNI 调用，同时提供 `rlib` 支持桌面原生测试与探针工具。项目参考了 Cygames Gallop 引擎（赛马娘）在物理装配上的时序解耦哲学，杜绝在运动姿态下构建刚体锚点，从根本上解决了传统 MMD 物理在复杂移动中模型撕裂与爆炸的顽疾。

### 核心源码组织与职责
- `rust_engine/src/physics/mmd_physics.rs`：Bullet3 刚体生命周期、固定步进及状态同步。`mmd_physics/diagnostics.rs` 保存遥测与日志，`motion_forces.rs` 处理移动惯性，`body_contacts.rs` 接入绑定姿态身体嵌入兼容过滤。速度限幅是保护措施，不能作为稳定性成立的证据。
- `rust_engine/src/physics/tail_forces.rs`：单实例尾巴闲置抬起包络和全向移动受力增强。闲置力度按 2026-10-05 用户确认在此前力度上乘 4，即首版的 8 倍；`tail_wave.rs` 按骨骼父子关系和链距离缓存各段延迟，闲置脉冲从尾根到尾尖错峰传播，尾尖最大延迟 0.6 秒。`motion_forces.rs` 逐段施加向上和等量后向力，脉冲播到尾尖后再结束，回落交给原有物理。移动增强启用时三个轴统一乘 1.5，关闭时停止尾巴专用风阻、升力与移动力矩，仅保留普通惯性。两个效果独立叠加，闲置抬起关闭时停止全链延迟脉冲。`MMDPhysics::set_tail_physics_options` 接收两个默认启用的开关，`set_tail_bone_names` 接收名称和父索引，缓存刚体名、英文名和关联骨骼名分类及波浪延迟，分类不随开关切换；排除马尾、头发与静态体。回归见 `tail_wave.rs`、`tail_forces.rs` 与 `mmd_physics/motion_forces/tests.rs`。
- `rust_engine/src/physics/embedded_body_contacts.rs` 与 `garment_contacts.rs`：身体深嵌入过滤和裙摆兼容均只用于 Stable/Relaxed；裙摆路径只补齐裙摆与 FollowBone 骨盆/大腿所需的双向组位，并显式禁碰由此额外开放的其它刚体对。模型运行时通过 `build_physics_with_bone_names` 提供直接绑定骨名，已知上身、头颈及手臂绑定的刚体不会仅因名称含下半身而获得裙摆补碰撞资格；不改变身体壳尺寸或 PMX 原有允许对。饰带、布料、头发、小腿、膝盖和脚部不会被扩展；Strict 与全局碰撞关闭保留 PMX 原始掩码。`examples/probe_back_skirt.rs` 提供后裙静止蒙皮对照，不能替代游戏验收。
- `rust_engine/src/physics/skirt_cross_joints.rs`：按纵向裙片链和深度补充缺失的环向关节。`skirt_cross_topology.rs` 根据共享部位分类、骨骼祖先和组件归属筛选链，有骨骼时不依赖 PMX 关节 A/B 的书写方向；不同 owner/family 或静态锚点集合分别生成。分支、环、无效坐标保守跳过，原始关节不删除。`MMD_SKIRT_CROSS_SYNTHESIS=off` 仅供关闭自动裙环的对照实验。回归见 `skirt_cross_joints_tests.rs`、`skirt_cross_joints_topology_tests.rs`、`skirt_cross_joints_bones_tests.rs` 和使用生产 Bullet frame 的 `skirt_cross_joint_anchor_tests.rs`；这些合成样例不替代真实资产验证。
- `rust_engine/src/physics/body_collider_synthesis.rs`：躯干碰撞体智能补全与推离保护。针对缺少胸腔或臀部碰撞壳的模型，自动在对应骨骼位置植入胶囊体或球体刚体；在生成臀部碰撞体前严格核对现有后界边界，防止重复合成顶翻后裙摆。在回写骨骼前，执行几何推离检测，将深入体内的动态骨骼强行推回体表。
- `rust_engine/src/physics/mmd_rigid_body.rs` 与 `mmd_joint.rs`：绑定姿态 offset、形状、碰撞掩码和关节参数映射。PMX 刚体旋转采用 Y-X-Z，关节采用 Bullet Z-Y-X，局部约束 frame 负责衔接。`STATIC_COLLISION_SHAPE_SCALE` 默认 0.70，按身体碰撞体分类收窄尺寸；`rebase_equilibrium` 在初始化运行姿态后记录弹簧零点。
  PMX 原始碰撞位直接作为 Bullet 允许掩码，不再取反；编辑器“不冲突群组”复选框对应原始位 0。合成碰撞体也遵守该语义。同组是否允许接触由该组位决定，不自动禁止同组碰撞。
- `rust_engine/src/physics/kinematic_target_filter.rs`：运动学目标静止死区滤波器。设定微小位移与旋转阈值，过滤角色待机时的浮点动画微噪，避免运动学刚体微振持续碰撞裙摆和长发。
- `rust_engine/src/model/runtime/`：`mod.rs` 保存 `MmdModel` 状态与构造；`animation.rs` 调度动画，`physics.rs` 调度物理，`tail_options.rs` 保存并转发每实例尾巴选项，`material_visibility.rs` 管理材质与第一人称索引，`render_data.rs` 提供 CPU/GPU 蒙皮数据，`head_eye.rs` 与 `vr.rs` 处理头眼和 VR。物理重建会重新应用实例尾巴选项。
  `issue_diagnostics.rs` 提供按类别读取的只读问题快照，通过独立 `jni_bridge/issue_diagnostics.rs` 导出，不改变物理或蒙皮求值。关闭相关日志时不发起诊断查询。
  `physics.rs` 的跳帧、无效时间和零步求值路径同样回写已有物理姿态；`physics_tests.rs` 验证蒙皮连续性和显式重同步请求。穿模修复验证见 `PHYSICS_CLIPPING_FIX_VALIDATION.md`，Oguri 资产交接标准见 `OGURI_PHYSICS_ASSET_STANDARD.md`。
- `rust_engine/src/skeleton/physics_writeback.rs`：按真实父子层级回写物理；模式 2 保留沿本帧父姿态传播的骨骼位置，模式 1 使用完整物理姿态，刷新非物理中间骨骼。
- `rust_engine/src/physics/bullet_ffi.rs` 与 `bullet_ffi/tests.rs`：安全封装和原生回归。求解诊断读取真实刚体姿态，渲染继续使用 MotionState 插值；初始接触建立后变更禁碰时，通过 `refresh_body_collision_filter` 释放旧接触缓存。物理析构先移除并释放 constraint/body/shape，再销毁 world；调试计数区分各阶段。`jni_bridge/resource_lifecycle_tests.rs` 独立串行验证 20 轮句柄删除、在途 Arc、重复删除和全部 Bullet 对象回到基线，不包含 JVM 或游戏切换验收。
- `rust_engine/src/jni_bridge/native_func.rs`：JNI 本地方法集中导出点，提供 159 个跨语言交互函数。

### JNI 桥接规范与数据交互契约
Java 端与 Rust 端的内存安全依靠句柄路由机制保障。Rust 端使用 `RwLock<HashMap<i64, Arc<Mutex<MmdModel>>>>` 集中托管所有活跃对象，Java 侧仅持有 64 位整型句柄，杜绝跨语言裸指针误操作。对于高频的顶点数据、法线、骨骼矩阵与第一人称索引缓冲，严格使用 Direct ByteBuffer 通过堆外内存地址批量传输（`ptr::copy_nonoverlapping`），完全消除 JNI 数组逐项拷贝的性能瓶颈与 GC 压力。此外，`SetPhysicsConfig` 接口支持在游戏内热重载物理参数，当检测到重力或拓扑稳定配置变更时，自动驱动模型在下一渲染帧平滑重建物理世界。

### 自动化测试与基准探针矩阵
`rust_engine` 的单元测试覆盖碰撞体补全、拓扑过滤、坐标转换、物理回写与 VR 解算。`examples/audit_pmx_physics.rs` 和 `probe_rin_physics.rs` 提供资产及关节审计；`probe_static_physics.rs` 隔离原始 PMX 刚体、碰撞和关节，`probe_model_physics.rs` 验证完整模型运行时的静止、转头、重置和不同渲染帧率。`probe_tail_physics.rs` 比较静止、行走/跑步恒速下尾链绝对角度、位移及约束误差，可用 `--with-animations` 加入运行目录的 idle/walk/sprint VMD。探针统计不能替代游戏画面验收；本次验证记录见 `PHYSICS_TAIL_VERIFICATION.md`。

---

## 3. 通用核心层：common 业务与渲染中枢

`common` 模块使用 Fabric Loom 提供 Minecraft 官方映射的编译环境，业务层不依赖 Architectury API，内聚了模组绝大部分的图形渲染、玩法机制与数据同步逻辑。NeoForge 编译、开发运行与 Shadow 发布包直接消费 common 的 Mojmap classes/resources 输出。

### 核心包组织划分
- `com.shiroha.mmdskin`：通用引导入口 `MmdSkin`、客户端总入口 `MmdSkinClient` 与跨平台原生库安全加载器 `NativeLibraryLoader`。
- `bridge.runtime`：接口隔离层。定义了 `NativeRuntimePort`、`NativeAnimationPort`、`NativeBoneOverridePort` 等细分接口，由 `NativeRuntimeBridge` 统一实现，在 JNI 边界前置完成非法浮点与畸变矩阵拦截。
- `render.backend`：统一模型实例抽象。下分 `render.backend.opengl`（基于版本脏标记更新的 CPU 蒙皮后端）与 `render.backend.gpu`（基于计算着色器的 GPU 蒙皮后端）。
- `render.shader`：赛璐珞渲染管线 `ToonShader`、计算着色器与 `SSBOBindings` 缓冲区状态治理。
- `render.outline.OutlineRenderPass`：CPU/GPU 共用的独立纯色描边通道，集中管理位置/法线/动态 UV 输入、描边配置、子网格遍历、GL_FRONT 与深度状态恢复。两个 renderer 适配自身缓冲、矩阵、现有模型根缩放与逐材质纹理；主材质绘制不调用描边逻辑。描边仅采样贴图 alpha 裁掉透明孔洞，RGB 仍由 OutlineColor 决定；无纹理材质关闭遮罩，正常/异常后恢复纹理单元和绑定。保留子网格可见性、材质有效 alpha、人脸过滤与全局 alpha。倒置外壳在正交 GUI 下按实际显示缩放换算世界基础宽度，使用固定视线，不把 GUI 图层深度当作世界距离；临时 GL_LESS 防止同深度薄片覆黑，随后恢复原深度函数。透视分支保留原计算。游戏内长袖与透明裁切部件仍待视觉验收；验证见 `tools/outline-probe/run.ps1` 与 `OUTLINE_RENDER_ISOLATION.md`。
- `player`：玩家交互核心。包含第一人称近视锥裁剪与双眼相机同帧求解（`FirstPersonManager`）、防走光评估（`AntiPeekEvaluator`）、独立模型替换与网络广播（`PlayerModelSelectionSyncService`）以及多层动画状态机（`AnimationStateManager`）。
- `asset` 与 `model.runtime`：模型文件扫描、异步加载协调器（`ModelLoadCoordinator`）与实体模型实例池（`ModelRepository`）。
- `compat`：外部生态软兼容层。反射适配 TaCZ 枪械瞄准姿态、Iris 光影感知、Vivecraft VR 追踪与车万女仆实体替换。

### 渲染管线与状态保护
模组实现了工业级的二阶段赛璐珞着色流程。第一阶段在主着色器中结合环境光照方向，将明暗计算为离散的色阶阶梯，并附加边缘光、高光染色与冷暖阴影；第二阶段基于倒置法线算法（Inverted Hull），开启正面剔除并将顶点沿法线向外微量扩张，精准勾勒出干净的二次元轮廓线。在 GPU 蒙皮路径下，蒙皮计算着色器在后台并行派发，结果直接作为 VBO 供 Blaze3D 绘制。为了消除频繁查询驱动状态引发的 CPU-GPU 同步停顿，`SSBOBindings` 在派发后直接清空解绑 0 到 15 号槽位。所有绘制调用均严格执行 Blaze3D 状态恢复契约，重置 VAO、剔除面、深度遮罩与混合模式，杜绝方块实体材质发黑或穿透。

Iris 阴影通道通过 `IrisCompat.isRenderingShadows()` 识别。该通道的实体 `PoseStack` 已包含光源视图，`BaseModelInstance.composeModelViewMatrix` 直接使用它，普通通道仍组合主相机矩阵。CPU/GPU 后端在阴影通道跳过 Toon 与描边，CPU 自定义 shader 模式也改用 Iris 当前阴影程序。两平台玩家 Mixin 与 `PlayerVanillaRenderPolicy` 的第一人称隐藏规则排除阴影通道，保证关闭第一人称身体显示后仍提交完整人物。矩阵回归见 `ShadowModelViewMatrixTest`。

Iris 与 Toon 并用由 `compat/iris/IrisToonMetadata` 读取当前包、维度和 ShaderKey，并委托 Iris 的 `ProgramFallbackResolver` 获取实际实体或手部程序。`render/shader/ToonOutputProfile` 校验已预处理的顶点/片段源码、输出布局及语义，首批适配 Complementary 4.7.1、BSL 8.4.02.2，允许已知缓冲重排。`ToonShaderBase` 为主体与描边缓存输出变体，处理线性颜色、BSL 的可选平方根编码、材质默认值与球面法线。Complementary 通过 `ComplementaryToonLighting` 复用当前设置预处理后的 GetLighting 和太阳方向，并通过 `ToonUniformBridge` 在 Iris apply 后同步真实环境色、天气、阴影等输入；方块/天空光仍分别传入，材质色阶保留，避免额外叠加白色高光。uniform 缓存关联 shader 实例，光照源码参与变体缓存键。BSL 8.x/10.x 通过 `BslToonLighting` 复用原包 GetLighting、阴影变形、太阳方向、天气和方块光；主世界、下界和末地分别沿用原包照明。输出协议已确认时，照明提取或编译失败会保留相同输出协议并采用 `GenericToonLighting`，可选标准 Iris 输入提供太阳方向、天空色调和天气，缺失/类型不符的输入重置为默认值。CPU/GPU 保留 Iris 的缓冲混合规则，并在绘制结束清理覆盖。未知契约或兼容程序编译失败由 `IrisToonCompat` 按包、程序和原因限次提示并保留聊天记录；受影响绘制仅对当前 pass 使用标准渲染，Toon 长期设置不变，不把未知 MRT 布局套入 vanilla 四输出 Toon 变体。uniform 同步失败限次记录并使用通用环境光，保留 Toon；本轮没有适配全部实体反射、法线贴图或自发光语义。

相关定向回归见 `ToonOutputProfileTest`；可选本机 OpenGL 探针为 `tools/iris-toon-probe/run.ps1`，只读实例中的光影包，使用 Iris 预处理器验证三维度的实体/手部与选项分支，再编译主体/描边并回读颜色、原色及法线。`LightingProbe` 编译原 Complementary 程序核对活动 uniform，再用独立来源程序模拟昼夜、洞穴、火把、雨天与阴影输入，在生产 Toon shader 中回读亮度；这些证据不替代完整 Iris 游戏链路和截图观感验收。本轮记录见 `TOON_LIGHTING_ROUND1.md`。

三渲二第一阶段重实现统一主体色阶、高光和边缘光，在 Complementary 阴影采样之后离散漫反射，移除额外连续上方向面明暗。设置在 vanilla/Complementary 下均参与最终颜色；`ToonControlsProbe` 回读 2～5 档平坦区、各风格参数及白色描边，`UniformBridgeProbe` 核对完整声明与输入类型。Linux 无窗口入口为 `tools/iris-toon-probe/run.sh`，保留现有 Windows 入口；实测和未验收项见 `TOON_LIGHTING_REIMPLEMENTATION.md`。第二阶段的 `render/outline/OutlineCoverage` 以 2×2 独立覆盖率采样、同格式深度复制与预乘 alpha 合成描边，只写主颜色槽；`OutlineGlState` 恢复 FBO、MRT 路由、viewport、VAO、纹理和逐槽混合等状态。GUI/世界保留原宽度、距离淡出、LESS 与面部过滤规则。两平台关闭选项、断开连接和退出时释放资源，shader 清理亦释放，尺寸/深度格式变化按需重建。`OutlineCoverageProbe` 回读斜边亚像素覆盖、墙体遮挡、alpha、MRT、异常恢复及三种 texture 深度格式和 viewport 切换。眼瞳发光为默认 0 的独立滑块，`EyeBoneMask` 按眼骨权重生成完整眼瞳顶点遮罩，`EyeEmissionBuffer` 在实例首次启用时上传并随模型释放，两端显存统计包含该缓冲；主体包含灰色虹膜、黑瞳和眼内高光，眼白由独立头骨权重排除，保留原纹理与 alpha。TDA 洛天依真实面部网格可用 `tools/iris-toon-probe/export_eye_asset.py` 与 `run.ps1 -EyeAssetData` 做固定条件离线回读。其他模型仍要求眼瞳与眼白权重可区分；发光在线性颜色空间相加；外围辉光与自动曝光仍由光影包决定。Complementary 5.x 与 4.x 共用旧式 shadow2D 的 core GLSL 转换，`ModernPackProbe` 编译真实 r5.9.3 的三维度实体、半透明实体、发光实体和手部，共 12 组主体/描边。

普通 Iris 实体路径在 `BaseModelInstance.setupShaderUniforms` 绑定游戏覆盖色与光照图（纹理单元 1/2），CPU/GPU 补齐整数 `iris_UV1=(0,10)`，不再用模型 lightMap 覆盖 Iris 输入。通用混合设置位于 Iris apply 之前，结束后清理程序覆盖并恢复阴影目标。`IrisEntityDiagnostics` 每程序记录一次光照、颜色乘数和属性/纹理信息；`OverlayProbe` 隔离验证覆盖色公式与常量坐标。关闭 Toon 后全黑的游戏实测和 Toon 整体偏亮的对照仍需新包验收。

`render/material/TranslucentDrawOrder` 缓存透明候选拓扑与唯一引用顶点，稳定 UV 时复用筛选结果；当帧仍重算视锥和深度，并用 CPU 并行排序保留全局三角形顺序。动态第一人称索引、UV、可见性、材质 alpha 资格和覆盖对象变化使缓存失效。`texture/runtime/TextureAlphaCoverage` 使用粗块及像素位图保守细化 UV 包围盒，保留线性采样边界、材质透明度和外部 Iris 程序回退。GPU 位置同步回读和逐分组绘制仍存在；TDA 系列只读审计、局部优化计划及验证记录位于 `Logs/translucent-perf-20261014/`，不能以微基准替代 BSL 游戏帧率验收。

无主贴图材质由 `render/material/MaterialTextureLoader` 统一加载：空路径通过既有 JNI `GetMaterialDiffuse` 读取材质 RGBA，生成实例独占的 1×1 纹理，供 CPU/GPU 与各着色路径采样；非空路径加载失败仍显示缺图提示。两个后端的生命周期与构造失败清理会释放默认纹理，共享文件纹理继续按引用计数回收。默认纹理计入模型显存统计。

`texture/runtime/TextureGpuLoader` 在解码、内存复制和 GL 上传异常时通过 `finally` 释放 native handle、临时 buffer 和失败的 GL texture；`TextureRepository` 对不存在的可选预加载路径跳过解码，运行时仍按既有规则回退。失败诊断携带 path、diffuse/light-map 类型和模型/材质上下文，并有界去重。

`tools/material-texture-probe/run.ps1` 使用隐藏 OpenGL 上下文验证材质颜色、透明度、像素解包状态与纹理释放，并对实际 Toon 着色器进行绘制回读；运行前需编译 `common`。探针不替代原模型的游戏画面验收。

三渲二设置新增“明暗过渡柔和度”滑块（0～200，默认 120）。`toonTransitionSoftness` 经公共配置与 `ToonRenderHelper` 上传到 CPU/GPU 共用主体，`toonDiffuseRamp` 同时控制材质色调与 BSL/Complementary 适配中的直射光分档。0 保留原抗锯齿过渡，增加数值按色阶间距扩宽明暗交界，100 以内保留各档平坦区，超过 100 继续柔化并归一化最暗/最亮端点，不修改阴影投射/采样或描边。`ConfigDataTest` 验证旧配置缺省、范围与非有限值；`ToonControlsProbe` 回读 2～5 档的 0/35/100/120/200 柔和度、100 内的平坦区、照度端点、alpha 和归零恢复，光影编译覆盖含 BSL 10.1.8。游戏观感由用户测试确认。

描边新增默认启用的 `toonOutlineFollowLighting`，Fabric/NeoForge 三渲二界面可关闭以保留旧纯色效果。`shader/toon_surface_lighting.glsl` 共用主体色阶与照明函数，`ToonOutlineLighting` 为描边独立缓存并同步相同 Iris 环境输入，关闭描边自身高光和边缘光，不接入眼瞳发光。原表面位置用于照明/阴影采样，外扩位置继续用于原轮廓过滤；透明裁切、宽度与覆盖率合成沿用现有逻辑。`LightingProbe` 对照主体/描边的昼夜、洞穴、火把、降雨与阴影，`ToonControlsProbe` 验证暗处、旧白色、黑色及 alpha；真实光影包编译探针拒绝把通用照明回退当成原包成功。游戏内亮边和 Bloom 仍需同条件开关对照；材质取色来源等待用户确认。

### 第一人称与玩法关键解算
为彻底根治第一人称视角穿帮与物品栏联动缺陷，系统确立了场景隔离原则：第一人称模式仅作为单次渲染作用域（`RenderScene.FIRST_PERSON`），背包预览与纸娃娃在独立作用域运行，彼此互不干扰。在主视角下，系统采用“拓扑候选预计算 + 动态近视锥几何裁切”，Rust 端动态剔除落入近视锥的三角形并回填动态 EBO，在保证手部完整可见的同时消除了低头看脖颈的空心断口。同时，第一人称相机锚点取模型双眼几何中点，在 Minecraft 计算相机矩阵前提前驱动动画解算，消除了相机视点与模型画面之间的一帧时序延迟。三层动画状态机（基础机动层、动作交互层、姿态叠加层）使得角色在骑乘、鞘翅飞行、持枪开镜与潜行时均能平滑过渡。

`player/runtime/FrontViewCameraManager` 为本地玩家 F5 前视准备独立的当帧 WORLD 姿态和最终蒙皮眼高，`FrontViewCameraSnapshot` 校验快照归属及有效高度。两端 `CameraMixin` 在原版初始位置设置后、镜头距离与墙体裁剪前只校准高度，后视、第一人称、VR、舞台和特殊卧姿不使用该快照；不修改瞳孔网格、眼追踪或 AO。正式世界与阴影绘制复用当帧姿态，下帧或 reset 丢弃；GUI 不复用。定向快照测试已通过，实际游戏的近景眼睑遮挡和墙体碰撞仍需验收，记录见 `Logs/eye-gaze-diagnosis-20261013/diagnosis.md`。

---

### 舞台相机与 VMD 镜头

`stage/client/network/StageNetworkPlaybackBroadcastAdapter` 保留邀请式会话。单机 `StageRole.NONE` 合法跳过，远端服务器或公开 LAN 中未加入会话则限次提示上下文；开始后缓存已公告的有效会话 ID，用于本地会话先清理后的幂等结束。单元测试验证发送参数与配对，双客户端接收和断线仍须游戏验收。

`rust_engine/src/animation/vmd_loader.rs` 将相机每组贝塞尔字节从 VMD 的 `[x1,x2,y1,y2]` 转成内部 `[x1,y1,x2,y2]`；骨骼插值布局不变。`motion_track.rs` 从逆旋转的前向与上方向提取 pitch/yaw/roll，镜头距离正负、跨零或目标点精度不再改变朝向。接近竖直时以已提取 pitch/yaw 定义的相机上方向计算 roll；分数帧仍先插值原始参数，保留作者设置的连续多圈旋转。

JNI 相机数据仍为 32 字节、旋转为弧度，`stage/client/camera/StageCameraTimeline` 转为公共层度数。Fabric 的 `CameraMixin` 经 `StageCameraOrientation` 后乘局部 Z 旋转并同步相机方向向量；NeoForge 使用原生三参数 `Camera.setRotation`，同时更新其 roll 字段。两端均在相机姿态中处理倾斜，让视图矩阵、视锥和粒子方向保持一致。

回归见 Rust `animation/camera_tests.rs`（完整 VMD 字节、六通道缓动、距离跨零、竖直姿态重建与多圈旋转），以及 Java `StageCameraOrientationTest`、`StageCameraTimelineTest`（角度单位、舞台锚点、双平台旋转约定和视图矩阵）。这些验证不替代 issue #68/#83 原始镜头文件的游戏画面对照。

---

## 4. 多平台适配层与构建体系

项目通过 Gradle、Fabric Loom 与 NeoForge ModDevGradle 驱动双加载器构建与原生依赖打包。

### 构建体系与流水线（build.gradle）
根构建脚本先准备固定 commit 的 Bullet3，再调用 Cargo 增量执行 `cargo build --release --lib`，避免已有旧动态库绕过重编译；产物同步到 `build/generated/native-resources/natives/<os-arch>/`。CI 通过 `-PnativeResourcesPath=build/ci-native-resources` 指定同次工作流汇集的原生库，此时不调用本机 Cargo；缺失或空原生库会失败。`settings.gradle` 统筹 `common`、`fabric` 与 `neoforge`。两端通过 Shadow 合并 common 与重定位 MP3 解码库；Fabric 再由 Loom 输出 Intermediary 包，NeoForge 保持 Mojmap。NeoForge 开发运行另用 `additionalRuntimeClasspath` 加入 jlayer。

`.github/workflows/build-mod.yml` 保留 Windows x64/ARM64、Linux x64/ARM64/LoongArch64/RISC-V64、macOS x64/ARM64、Android ARM64/AMD64 十个原生目标。`tools/ci/prepare_bullet.py` 读取根构建脚本中的固定版本；`tools/ci/native_ci.py` 校验二进制架构、汇集原生库并检查最终 Fabric/NeoForge Jar。Android 使用 NDK `c++_shared`，随原生库携带 `libc++_shared.so`。跨平台成功以实际 Actions 结果为准，本机 Windows 构建不代替其它平台验证。

### 平台入口与事件挂载差异
- Fabric 端：通用入口 `MmdSkinFabric` 注册网络载荷，客户端入口 `MmdSkinFabricClient` 挂载 `FabricClientRuntimeHooks`，监听 ClientTick、进退服与 HudRender 事件；按键通过 `KeyBindingHelper` 注册；生物实体替换通过 Fabric API 的 `EntityRendererRegistry` 统一替换。
- NeoForge 端：主入口 `MmdSkinNeoForge` 区分 Mod 声明总线与运行时总线。Mod 总线监听按键注册与 `EntityRenderersEvent`；运行时总线通过 `NeoForgeClientEventHandler` 订阅 ClientTick、死亡事件与 `RenderGuiEvent.Post`。生物模型替换则注册在 `RenderLivingEvent.Pre`，并在接管后取消原版渲染。
- 旧版 Forge（1.20.1）：采用经典 EventBus 体系，客户端通过 `@EventBusSubscriber` 静态监听注册，作为向后移植与多版本兼容的代码参照基线。

### 平台特化机制与 Mixin 软兼容
- 网络传输：全面拥抱 1.21.1 规范，采用 `CustomPacketPayload` 与 `StreamCodec` 契约体系。Fabric 使用 `PayloadTypeRegistry` 与 `ServerPlayNetworking`，NeoForge 使用 `RegisterPayloadHandlersEvent` 与 `PacketDistributor`，业务端通过 `ClientNetworkBindings` 统一抽象分发。
- TaCZ 枪械兼容：在各端 Mixin 中使用 `@Pseudo` 伪装注入，捕获枪械在第一人称功能节点（包括开镜 ADS 状态与双手翻转）下的最终姿态矩阵，随后由 `TaczFirstPersonPostRenderer` 统一接管 MMD 双臂姿态并阻止原版手臂重复绘制，全程无需硬编译依赖。NeoForge 的 `TaczGunItemRendererWrapperMixin` 同时支持旧 `renderFirstPerson` 与 TaCZ NeoForge Port 1.1.8-r2 的 `renderFirstPersonInner`，均按完整方法描述符在 HEAD 建帧、RETURN 提交；新版本外层入口在父类中，各已知版本只命中目标类内的一个入口。
- 原生库多平台装载：`NativeLibraryLoader` 在运行时精准嗅探 Windows、Linux、macOS 及 Android 移动容器（PojavLauncher 等），将动态库解压至带版本后缀的隔离目录并校验 SHA-256 指纹；载入后通过 JNI 核验版本号常量（`v1.10alpha`），防止因残留旧库引发 ABI 崩溃。

---

## 5. 核心开发接口与二次开发索引

为方便后续开发、调试以及外部扩展，模组暴露了清晰的编程接口与配置入口：

### 对外 API：com.shiroha.mmdskin.api.MmdSkinApi
- `getModelInfo(Player)`：安全获取指定玩家当前装配模型的骨骼总数、顶点数、材质分段及关键骨骼变换矩阵。
- `getUV(Player)`：读取实时的动态变形 UV 数据流。
- `setBoneOverride(Player, boneIndex, translation, rotation)`：允许外部程序化逻辑强行覆写指定骨骼的平移与四元数旋转，用于外部动作捕捉接入。
- `setExternalIkOverride(Player, boolean)`：接管或屏蔽模型的原生 IK 求解，允许外部算法主导肢体逆动力学。

### 本地文件与关键配置路径
- 客户端模型存储目录：`.minecraft/3d-skin/`（存放 PMX/VRM 模型文件夹及动作纹理）。
- 模组全局配置文件：`.minecraft/config/mmdskin.json`（图形渲染、物理仿真参数、性能预算）。
- 单模型独立配置：`.minecraft/config/mmdskin/model_configs/<规范化模型名>.json`（视线追踪范围、缩放、材质可见性和默认启用的 `tailIdleLiftEnabled`、`tailMovementBoostEnabled`）。旧配置缺省的尾巴字段启用，明确保存的 false 保留关闭。模型设置保存时同步到同名已加载实例，新实例加载时应用；Java `NativeScenePort.setTailPhysicsOptions` 经 JNI `SetTailPhysicsOptions` 更新原生实例。
- 模型设置界面：`ui/selector/ModelSettingsScreen` 绘制尾巴物理开关及其他模型选项；`ModelSettingsLayout` 统一滚动视口、裁剪命中和滚动条几何。设置内容支持滚轮、拖动滚动条和翻页键，标题与底部操作固定，小窗口不再压缩卡片文字。回归见 `ModelSettingsLayoutTest`。
- 玩家独立替换配置：持久化于 `ModelSelectorConfig`，支持在游戏内配置界面或 Alt 快捷轮盘中按玩家名/UUID 指定独立展示模型。
- 调试设置：Fabric/NeoForge 的“调试”分类新增模型变形、两侧部件物理、碰撞模式、描边与纸娃娃四项日志，配置字段为 `debugModelDeformationLog`、`debugSidePhysicsLog`、`debugCollisionModeLog`、`debugOutlinePaperDollLog`，默认关闭且独立于旧物理日志。`IssueDiagnosticOptions` 定义位标记，`ModelIssueDiagnostics` 每两秒转交摘要及接触、锚点、速度指标；首次、每 30 秒及手动请求输出完整明细，`-Dmmdskin.diagnostics.full=true` 保留旧明细频率。原生快照仍每两秒构造，减少日志量不代表采样开销已改善。`OutlineRenderDiagnostics` 记录各渲染场景绘制前状态。使用指南见 `ISSUE_DIAGNOSTICS_GUIDE.md`；抽样与有界样例不代替游戏画面证据。本轮审计记录见 `root_cause_report.md`，原日志分析工具见 `tools/latest-log-audit/`。

---
## 6. VMD 平滑与独立模型/动作预览工具

`rust_engine/src/vmd_smoothing/` 为独立的离线动画处理模块，负责原曲线采样、位移与四元数平滑、循环处理、保留原 VMD 非骨骼数据及安全副本输出。循环与非循环均保持原首末姿态，足部 IK 保护原位移范围和极值时刻。支持全部骨骼轨道（含 IK）和多个独立平滑组，后启用组覆盖先组，各轨道从原源计算；`config.rs` 读写分组 JSON。`rust_engine/src/bin/vmd_smooth.rs` 提供单文件、目录批处理和分组配置入口，使用说明见 `rust_engine/VMD_SMOOTHING.md`。

`rust_engine/src/bin/viewer/main.rs` 为同时预览模型和动作的桌面入口，通过 Cargo 的 `viewer` feature 编译。`gui.rs` 生成操作事件，`renderer.rs` 管理模型与动画渲染；`motion_edit.rs` 管理原始动作、平滑副本和后台任务，`smoothing_panel.rs` 提供平滑与批处理面板，`state.rs` 保存预览器参数。纯文件处理在线程中执行，模型动画层切换仍由主线程完成。

项目 24 个默认 VMD 已应用确认的平滑设置，配置保存在 `rust_engine/vmd-smoothing-project.json`（全部骨骼、强度 0.6、半径 5、循环开启）；备份与校验记录见 `rust_engine/VMD_SMOOTHING.md`。

*本索引文件由子代理全库代码扫描与架构分析自动化生成，后续按实际修改补充。*
