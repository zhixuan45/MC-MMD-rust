
# mc-mmd-rust 架构与接口索引 (极简扁平 YAML 格式，严禁画任何流程图)
# AI指引: 优先读取前 35 行路由区锁定模块 tag，再定向检索对应 tag 直达代码块


index:
  project: "mc-mmd-rust"
  tech_stack: ["Rust 2021 / C++ Bullet3", "Java 21 (Fabric / NeoForge)", "OpenGL 3.3+ / Direct ByteBuffer"]
  entrypoints: ["rust_engine/src/lib.rs", "common/src/main/java/com/shiroha/mmdskin/MmdSkin.java"]
  summary: "Minecraft 高性能 MMD/VRM 渲染与物理模组。Rust+Bullet3 零拷贝物理解算与蒙皮，Java 调度渲染与加载器适配。"
  modules_map:
    - id: "engine"
      tag: "@engine"
      path: "rust_engine"
      summary: "Bullet3 刚体关节动力学、尾巴气动、裙摆/碰撞体合成、Rayon 蒙皮与 JNI 导出"
    - id: "bridge"
      tag: "@bridge"
      path: "common/src/main/java/com/shiroha/mmdskin/bridge"
      summary: "JNI 原生跨语言接口隔离、句柄映射管理与堆外 Direct ByteBuffer 零拷贝管道"
    - id: "render"
      tag: "@render"
      path: "common/src/main/java/com/shiroha/mmdskin/render"
      summary: "CPU/GPU 双分支渲染后端、Toon 赛璐珞色阶着色器、倒置法线描边与透明分层合批"
    - id: "player"
      tag: "@player"
      path: "common/src/main/java/com/shiroha/mmdskin/player"
      summary: "第一人称视锥几何裁切、同帧双眼相机解算、玩家独立模型替换与多层动画状态机"
    - id: "platform"
      tag: "@platform"
      path: "fabric,neoforge,forge"
      summary: "Fabric/NeoForge 加载器生命周期装配、网络 Payload 注册、事件监听与 Mixin 接管"
    - id: "compat"
      tag: "@compat"
      path: "common/src/main/java/com/shiroha/mmdskin/compat"
      summary: "TaCZ 枪械瞄准姿态伪装接入、Iris 光影环境感知/照明适配与 Vivecraft VR 追踪"


# 模块详情与核心代码块直达清单 (按需定向查阅，禁止全量读取)


modules:
  - id: "engine"
    tag: "@engine"
    path: "rust_engine"
    summary: "Bullet3 刚体关节动力学、尾巴气动、裙摆/碰撞体合成、Rayon 蒙皮与 JNI 导出"
    files:
      - file: "rust_engine/src/jni_bridge/native_func.rs"
        desc: "JNI 本地函数导出中枢与 64 位句柄路由池"
        symbols:
          - "Java_com_shiroha_mmdskin_NativeFunc_createModel - 从 DirectBuffer 加载 PMX/VRM 并注册模型句柄"
          - "Java_com_shiroha_mmdskin_NativeFunc_updateModel - 驱动指定句柄的模型物理仿真与动画插值步进"
          - "Java_com_shiroha_mmdskin_NativeFunc_destroyModel - 安全析构 Rust 模型实体与 Bullet3 物理世界"
          - "Java_com_shiroha_mmdskin_NativeFunc_setTailPhysicsOptions - 动态启闭尾巴闲置抬起与移动增强物理"
          - "Java_com_shiroha_mmdskin_NativeFunc_setSolverIterations - 热更新 Bullet 求解器迭代次数 (默认10)"
      - file: "rust_engine/src/physics/mmd_physics.rs"
        desc: "Bullet3 刚体与约束生命周期管理、时序解耦与左/右手对合坐标映射"
        symbols:
          - "MMDPhysics::new(model: &PmxModel) -> Self - 从 PMX 资产装配物理世界并记录弹簧平衡位"
          - "MMDPhysics::step_simulation(&mut self, delta_time: f32) - 执行固定步长物理动力学推进与接触求解"
          - "MMDPhysics::set_tail_physics_options(&mut self, idle_lift: bool, boost: bool) - 更新尾巴动力学开关"
          - "MMDPhysics::set_solver_iterations(&mut self, iterations: i32) - 调整 Bullet 全局求解迭代"
      - file: "rust_engine/src/physics/tail_forces.rs"
        desc: "尾巴闲置抬起包络脉冲传播与全向移动受力增强"
        symbols:
          - "apply_tail_idle_lift - 沿骨骼链错峰向尾尖传播抬起脉冲 (最大延迟0.6秒)"
          - "apply_tail_movement_boost - 三轴统一 1.5 倍惯性力矩与专用风阻升力增强"
      - file: "rust_engine/src/physics/skirt_cross_joints.rs"
        desc: "纵向裙片骨骼链之间的环向保形弹簧网络拓扑自动补齐"
        symbols:
          - "synthesize_skirt_cross_joints - 依据骨骼祖先与深度自动构建横向约束，防止裙摆翻卷"
      - file: "rust_engine/src/physics/body_collider_synthesis.rs"
        desc: "躯干跟骨碰撞体自动补全与动态骨骼体内穿透几何推离"
        symbols:
          - "synthesize_torso_colliders - 在胸腔与臀部自动植入胶囊体/球体物理壳"
          - "push_out_embedded_bones - 检测深入体表的骨骼并强行推回外壳表面"
      - file: "rust_engine/src/model/runtime/mod.rs"
        desc: "MmdModel 运行时实体管理、动画多轨插值与多线程蒙皮"
        symbols:
          - "MmdModel::update(&mut self, delta_time: f32) - 驱动单帧动画更新、物理调度与骨骼回写"
          - "MmdModel::compute_skinning_cpu(&self) - Rayon 多线程并行 CPU 蒙皮顶点求值"

  - id: "bridge"
    tag: "@bridge"
    path: "common/src/main/java/com/shiroha/mmdskin/bridge"
    summary: "JNI 原生跨语言接口隔离、句柄映射管理与堆外 Direct ByteBuffer 零拷贝管道"
    files:
      - file: "common/src/main/java/com/shiroha/mmdskin/NativeFunc.java"
        desc: "JNI 底层本地方法声明入口"
        symbols:
          - "static native long createModel(ByteBuffer buffer, int size) - 传递堆外内存地址创建原生模型"
          - "static native void updateModel(long handle, float delta) - 驱动原生模型步进"
          - "static native void destroyModel(long handle) - 释放原生句柄资源"
          - "static native void setTailPhysicsOptions(long handle, boolean idleLift, boolean boost) - 更新尾巴开关"
          - "static native void setSolverIterations(int iterations) - 设置求解器迭代次数"
      - file: "common/src/main/java/com/shiroha/mmdskin/NativeLibraryLoader.java"
        desc: "跨平台原生动态库解压、SHA-256 指纹校验与 ABI 版本核验加载器"
        symbols:
          - "loadLibrary(): void - 自动嗅探 Win/Linux/macOS/Android 环境加载 libmmd_engine"
          - "verifyVersion(): void - 校验 Rust 端导出的 ABI 版本号一致性"
      - file: "common/src/main/java/com/shiroha/mmdskin/bridge/runtime/NativeRuntimeBridge.java"
        desc: "六边形端口统一委托实现，在 JNI 边界前置拦截非法浮点与畸变矩阵"
        symbols:
          - "class NativeRuntimeBridge implements NativeRuntimePort - 隔离层中枢实体"
          - "update(long handle, float deltaSeconds): void - 校验步长并驱动原生更新"
          - "writeBoneOverride(long handle, int boneIndex, Matrix4f transform): void - 覆写特定骨骼变换"

  - id: "render"
    tag: "@render"
    path: "common/src/main/java/com/shiroha/mmdskin/render"
    summary: "CPU/GPU 双分支渲染后端、Toon 赛璐珞色阶着色器、倒置法线描边与透明分层合批"
    files:
      - file: "common/src/main/java/com/shiroha/mmdskin/render/backend/BaseModelInstance.java"
        desc: "统一模型渲染抽象基类，管理单调纳秒时钟与模型视图矩阵组合"
        symbols:
          - "render(PoseStack poseStack, MultiBufferSource bufferSource, int light): void - 渲染管线总入口"
          - "composeModelViewMatrix(PoseStack poseStack): Matrix4f - 组合实体姿态与主相机/光源矩阵"
      - file: "common/src/main/java/com/shiroha/mmdskin/render/backend/opengl/OpenGlModelRenderer.java"
        desc: "基于 CPU 蒙皮与脏标记版本控制的 OpenGL 渲染器"
        symbols:
          - "renderModel(RenderScene scene, BaseModelInstance instance): void - 批量绑定材质并执行绘制"
      - file: "common/src/main/java/com/shiroha/mmdskin/render/backend/gpu/GpuSkinningModelRenderer.java"
        desc: "基于 GLSL Compute Shader 的 GPU 蒙皮后端渲染器"
        symbols:
          - "dispatchSkinning(BaseModelInstance instance): void - 调度 SSBO 与计算着色器并行变换顶点"
      - file: "common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java"
        desc: "三渲二赛璐珞着色器基类，处理色阶分档、明暗平滑过渡与 Iris 环境光同步"
        symbols:
          - "bind(): void - 激活着色程序并传递光照色温与漫反射色阶阈值"
          - "setTransitionSoftness(float softness): void - 调节明暗交界平滑度 (0~200)"
      - file: "common/src/main/java/com/shiroha/mmdskin/render/outline/OutlineRenderPass.java"
        desc: "倒置法线纯色外壳描边独立渲染通道，支持动态视线换算与正交 GUI 适配"
        symbols:
          - "renderOutline(PoseStack poseStack, BaseModelInstance instance): void - 挤出法线并执行正面剔除"
      - file: "common/src/main/java/com/shiroha/mmdskin/render/material/ToonTransparencyLayers.java"
        desc: "半透明材质 4 层深度剥离与多纹理窗口合批调度"
        symbols:
          - "renderTransparencyPass(...): void - 逐层混合半透明面片并规避深度覆黑"

  - id: "player"
    tag: "@player"
    path: "common/src/main/java/com/shiroha/mmdskin/player"
    summary: "第一人称视锥几何裁切、同帧双眼相机解算、玩家独立模型替换与多层动画状态机"
    files:
      - file: "common/src/main/java/com/shiroha/mmdskin/player/runtime/FirstPersonManager.java"
        desc: "第一人称主视角检测、双眼几何中心相机锚定与近视锥三角形动态裁切"
        symbols:
          - "isFirstPerson(): boolean - 检测当前是否处于第一人称渲染通道"
          - "updateCameraPosition(Camera camera, float tickDelta): void - 提前同步相机眼高消除一帧延迟"
      - file: "common/src/main/java/com/shiroha/mmdskin/player/sync/PlayerModelSelectionSyncService.java"
        desc: "玩家个人模型装配记录管理与多人联机广播同步"
        symbols:
          - "selectModel(UUID playerUuid, String modelName): void - 绑定玩家模型并下发广播包"
          - "getModelForPlayer(UUID playerUuid): ManagedModel - 获取指定玩家当前的激活模型"
      - file: "common/src/main/java/com/shiroha/mmdskin/player/animation/AnimationStateManager.java"
        desc: "基础机动层、动作交互层、姿态叠加层三层动作混合状态机"
        symbols:
          - "updateAnimationState(Player player, float delta): void - 融合移动/潜行/骑乘与持枪姿态"

  - id: "platform"
    tag: "@platform"
    path: "fabric,neoforge,forge"
    summary: "Fabric/NeoForge 加载器生命周期装配、网络 Payload 注册、事件监听与 Mixin 接管"
    files:
      - file: "fabric/src/main/java/com/shiroha/mmdskin/fabric/MmdSkinFabricClient.java"
        desc: "Fabric 客户端总入口，挂载生命周期钩子、按键映射与实体渲染器替换"
        symbols:
          - "onInitializeClient(): void - 注册 ClientTick、HudRender 与生物渲染委托"
      - file: "neoforge/src/main/java/com/shiroha/mmdskin/neoforge/MmdSkinNeoForgeClient.java"
        desc: "NeoForge 客户端总线注册，接管生物渲染与客户端事件"
        symbols:
          - "onClientSetup(FMLClientSetupEvent): void - 初始化客户端环境与按键"
          - "onRenderLiving(RenderLivingEvent.Pre): void - 拦截原版玩家与生物绘制并重定向至 MMD 管线"
      - file: "common/src/main/java/com/shiroha/mmdskin/player/sync/ClientNetworkBindings.java"
        desc: "1.21.1 跨平台统一 CustomPacketPayload 网络协议编解码分发"
        symbols:
          - "registerClientPayloads(): void - 编解码模型切换与物理状态同步载荷"

  - id: "compat"
    tag: "@compat"
    path: "common/src/main/java/com/shiroha/mmdskin/compat"
    summary: "TaCZ 枪械瞄准姿态伪装接入、Iris 光影环境感知/照明适配与 Vivecraft VR 追踪"
    files:
      - file: "common/src/main/java/com/shiroha/mmdskin/compat/tacz/TaczFirstPersonPostRenderer.java"
        desc: "TaCZ 第一人称瞄准 ADS 与翻转手臂姿态提取接管"
        symbols:
          - "captureGunPose(PoseStack poseStack): void - 拦截枪械矩阵并直接覆写 MMD 手臂骨骼"
      - file: "common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisCompat.java"
        desc: "Iris 光影状态嗅探、阴影通道识别与 MRT 缓冲重排"
        symbols:
          - "isShaderPackInUse(): boolean - 检测当前是否开启光影包"
          - "isRenderingShadows(): boolean - 检测是否为 Iris 阴影 pass 并跳过 Toon 与描边"
      - file: "common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisToonMetadata.java"
        desc: "解析光影包 ShaderKey 与输出布局协议"
        symbols:
          - "resolveProfile(ShaderKey key): ToonOutputProfile - 校验并重定向着色器输出槽位"

# 核心业务调用链速查 (单行文本链式表达，严禁画流程图)


workflows:
  - name: "model_loading"
    chain: "ModelRepository.loadModel -> DirectByteBuffer(PMX/VRM) -> NativeFunc.createModel -> rust_engine::MmdModel -> 返回 long 句柄"
  - name: "render_tick"
    chain: "ClientTick -> BaseModelInstance.update -> NativeFunc.updateModel -> MMDPhysics.step_simulation -> Rayon/ComputeSkinning -> ToonShader/Outline -> OpenGL 呈现"
  - name: "first_person_render"
    chain: "Camera.setup -> FirstPersonManager.updateCameraPosition -> 动态近视锥裁剪 -> EBO 动态切分 -> 隐藏躯干并提交手部渲染"
  - name: "player_model_sync"
    chain: "ModelSelectorScreen -> PlayerModelSelectionSyncService -> ClientNetworkBindings.sendPacket -> 服务端广播 -> 远端客户端更新映射"
  - name: "iris_shadow_pass"
    chain: "IrisCompat.isRenderingShadows -> BaseModelInstance.composeModelViewMatrix -> 跳过 Toon 与描边 -> 提交实体深度与阴影几何"
