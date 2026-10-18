# 全局前发与饰品刚体延时、抖动优化实施计划

## 目标

降低普通动画和舞台模式中前发、蝴蝶结、袖子等动态刚体的视觉滞后和高频抖动，同时保留正常自由摆动、S 形头发和身体支撑碰撞。用户已明确问题是全局现象，舞台时间源修复只覆盖其中一个特例。

## 当前证据

1. 舞台相机在 `Camera.setup` 中调用 `MMDCameraController.updateCamera`。舞台播放推进后，每次渲染都会通过 `StageAnimSyncHelper.seekLayer` 写入第 0 层舞台帧。
2. 模型随后在 `BaseModelInstance.update` 中按渲染间隔调用 native 更新；Rust `tick_animation_internal` 仍会执行 `animation_layer_manager.update(elapsed)`，因此第 0 层存在 seek 与 elapsed 双重时间驱动。
3. 物理回写前 `MMDPhysics::get_dynamic_bone_transforms` 会调用 `sync_render_states`，从最终解算姿态按剩余固定步时间预测 MotionState；预测姿态再经过接触面投影或退回解算姿态。
4. Bullet 接触求解参数目前没有独立配置入口，无法单独降低接触纠偏强度。

以上是静态调用链结论，仍需通过固定模型、固定动作和固定镜头的 A/B 画面确认视觉收益。

## 全局路径补充修复（2026-10-18）

串行更新与并行准备都从 `BaseModelInstance` 计算 native elapsed。两条路径原先使用 `System.currentTimeMillis`，时间被量化到毫秒，同一毫秒内的新姿态可能被零步分支跳过。统一使用实例共享的 `ModelUpdateClock` 和 `System.nanoTime`，保留首帧零步、强制求值及 0.25 秒限幅，不改变求值缓存规则。确定性测试覆盖亚毫秒推进、重复采样、负纳秒原点和限幅后恢复。

全局 `syncRenderStates` 原先对接触两侧分别按世界空间位移裁剪预测，将对侧当作静止。两刚体同向共速时，相对运动为零，仍可能裁掉其中一侧的共同位移。改为比较双方接触点从求解姿态到显示姿态的相对位移，按动态体逆质量分摊向内预测修正，并纳入零子步时运动学 MotionState 的新目标。修正只写显示状态，不修改 Bullet 求解姿态、速度、关节和全局物理频率。

本轮先验证这两项具体缺陷。原第二阶段的预测比例、第三阶段接触参数和第四阶段阻尼仍保留为后续可选项，不把新增参数当作已实现，也不将自动测试通过视为真实模型的观感验收。

## 游戏复验失败后的调整（2026-10-18）

用户复验认为抖动严重程度与此前接近，本计划不再将时钟和预测修复当作主要症状的有效改善。实际游戏日志确认加载的 DLL SHA 与上一轮产物一致；用户提供了杏辞模型与本地录屏，关闭 GPU 蒙皮后现象基本相同。纸娃娃在对应日志中只读复用 WORLD 姿态，不再次推进同一实例的物理。

保持当前资产和配置的独立进程探针已加入显式 solver 单因素对照。接触 ERP、split impulse 和关节限位 ERP 分开测试，不混为一个参数。实际静止尾窗中，长发蝴蝶结父子相对角步幅受到求解迭代次数明显影响；40 次比 10 次的 idle 步幅低约 62～67%，但停止尾窗的高频差分仍存在，80 次也并非更好，不能认定增加迭代彻底解决根因。

这次在现有配置链路中增加 `physicsSolverIterations`，范围 1～80、旧配置缺省与默认均为 10。Fabric/NeoForge 物理设置提供同名整数滑块，可保存并热应用到已有世界；新建和重建实例读取当前值。通过独立的 JNI 类转发，不改旧 `SetPhysicsConfig` 参数布局；不更改碰撞组、作者关节限位、ERP、split impulse、物理 FPS 或阻尼。用户游戏反馈 40 次反而变差，30 次除极端情况外没有问题，因此按请求将原下限 10 扩展至 1，供继续对照。新增配置的实现沿用现有配置/运行时接口，不进行目录或核心模块重构。

验证包括旧配置缺省、持久化/启动/保存快照、范围归一化、原生热应用不重置姿态与速度，以及新建世界使用当前全局值。下限扩展后，额外验证 1 和 9 不会被旧下限截断，0 被原生入口拒绝。实际游戏以用户已观察的 30 次作为对照，逐步测试更低值和极端强抖动场景；不能把范围开放等同于根因已修复。实验原始数据与限制记录到 `solver-investigation.md`。

## 第一阶段：修复舞台层双重推进

### 方案

将舞台第 0 层设为外部帧驱动：舞台同步调用 `seekLayer`，Rust 更新时不再按 `elapsed * 30` 推进该层。优先采用现有 `setLayerSpeed` 接口将舞台层速度设为 `0`，并在退出舞台时恢复默认速度；如果过渡状态需要独立处理，再补充显式的外部驱动标志。

### 预计修改文件

- `common/src/main/java/com/shiroha/mmdskin/model/runtime/ModelInstance.java`
- `common/src/main/java/com/shiroha/mmdskin/render/backend/BaseModelInstance.java`
- `common/src/main/java/com/shiroha/mmdskin/player/runtime/MmdSkinRendererPlayerHelper.java`
- `common/src/main/java/com/shiroha/mmdskin/stage/client/sync/StageAnimSyncHelper.java`

如现有 `setLayerSpeed` 只在底层已有而模型实例接口没有暴露，则只补齐该接口和调用，不改变 JNI ABI。

### 验证

- 增加 Java 单元测试，确认舞台开始、远端舞台开始、舞台结束分别设置和恢复第 0 层速度。
- 在固定舞台动作下记录舞台目标帧、Rust 层当前帧和每次 native 更新的 elapsed，确认同一渲染帧不再出现 seek 后额外推进。
- 画面对比确认前发目标姿态不再随渲染帧率发生小跳，普通待机和非舞台动画不受影响。

## 第二阶段：增加渲染预测控制

### 方案

增加 `renderPredictionStrength`，控制 `sync_render_states` 中剩余固定步预测的比例。默认值保持 `1.0` 以兼容当前低延迟行为；舞台稳定档可使用 `0.5`，接触状态下可自动降到更低值。该参数只影响渲染 MotionState，不改变 Bullet 解算状态。

### 预计修改文件

- `rust_engine/bullet_wrapper/bw_kinematic_world.h/.cpp` 或对应 C ABI 文件
- `rust_engine/src/physics/bullet_ffi.rs`
- `rust_engine/src/physics/mmd_physics.rs`
- `rust_engine/src/physics/config.rs`
- `common/src/main/java/com/shiroha/mmdskin/config/ConfigData.java`
- JNI 配置转发相关文件

### 验证

固定条件下比较预测比例 `1.0 / 0.5 / 0.0`，记录 MotionState 与 simulation transform 的差值、接触时的姿态跳变和主观延时。

## 第三阶段：增加接触稳定性控制

### 方案

暴露 Bullet `btContactSolverInfo` 中的接触纠偏参数，优先调低 ERP 或等价的穿透修正强度，并保留 split impulse 作为稳定性选项。不要用降低全局物理频率替代接触参数调节。

用户侧先提供三个预设：低延迟、平衡、稳定。底层参数保留默认值兼容开关，避免一次性暴露过多 Bullet 细节。

### 验证

在刚体重叠、快速转头、舞台动作和普通待机四种场景中比较接触深度、角速度峰值、帧间姿态变化和画面结果，确认不会破坏肩部支撑与 S 形头发。

## 第四阶段：动态链自适应阻尼

仅对前发、蝴蝶结、袖子等动态链应用接触时的临时阻尼倍率；自由摆动时恢复原始 PMX 阻尼，避免裙摆和全身物理被统一变硬。该阶段放在前两阶段确认后实施。

## 退出条件与回滚

- 任一阶段出现舞台动作帧不同步、动态链明显变僵或肩部支撑退化，立即保留诊断日志并回退该阶段开关。
- 所有新参数默认采用当前行为等价值，关闭参数即可回到现有路径。
- 构建和单元测试通过不能替代固定条件下的舞台画面 A/B 验收。

## 实施顺序

先实施第一阶段并完成 A/B；确认舞台时间源稳定后，再实施第二阶段；只有接触重叠仍有高频振荡时才进入第三阶段和第四阶段。

## 执行记录（2026-10-18）

第一阶段已实现：舞台第 0 层进入外部逐帧 seek 模式时设置播放速度为 `0`，退出舞台或断开连接时恢复为 `1`；本地、远端和待机动画接口均保持兼容。新增生命周期测试覆盖普通结束和断开连接的速度恢复。

前轮验证曾受隔离 Gradle 缓存与 Windows 权限阻塞。本轮恢复使用已有共享缓存、JDK 21 和沙箱外受审查的构建后，Java 编译、4 项时钟测试和 2 项舞台生命周期测试均通过。Rust 的 19 项 Bullet 接口/接触预测回归及 2 项模型物理回写测试通过；另修正已有并行性能测试中一个耗时变量名称笔误，以解除 native 单元测试编译阻塞。

Fabric `remapJar` 与 NeoForge `shadowJar` 均成功，已校验两个发布 Jar 内的 `ModelUpdateClock.class` 和 Windows x64 原生库哈希。本轮未调整 ERP、阻尼或物理频率，也没有新增稳定性设置。详细命令、产物和验收边界见 `validation.md`；固定模型、动作、镜头下的游戏画面 A/B 仍未完成。
