# Forge 1.20.1：镜头、GUI 模型与 Euphoria Patches 修复

日期：2026-11-13。分支：`codex/port-1.20.1`。

## 镜头旋转

本次核对了实际 Minecraft 1.20.1 `Camera.setRotation` 字节码，以及 Forge 47.3.0 的 `Camera`、`GameRenderer` 补丁。

- 1.20.1 相机四元数使用 `rotationYXZ(-yaw, pitch, 0)`，局部前向为 `+Z`，左向为 `+X`。原 `StageCameraOrientation` 沿用 1.21.1 的基向量与 Roll 符号，非零 Roll 会翻转前向、左向。
- 原 `GameRendererMixin` 对 `mulPose` 的 `ordinal = 1` 注入位于眩晕投影分支，普通画面不执行；同时把度直接交给 `Quaternionf.rotationZ`，单位错误。
- 现在由 Forge 的 `ViewportEvent.ComputeCameraAngles` 提交以度为单位的 yaw/pitch/roll。相机自身的四元数与方向基使用 1.20.1 的约定。
- Forge 的 `setAnglesInternal` 仅更新 yaw/pitch 字段，不重建四元数；相机基与世界视图因此分别在各自正确入口更新，避免重复 Roll。

回归测试使用真实 1.20.1 `Camera`，覆盖正负 Roll、零 Roll、竖直俯仰及逐帧切换。与双精度参考矩阵对照时，真实 float 四元数的最大方向误差为 `5.45245394316815E-5`，参考矩阵容差取 `1E-4`。

`tools/camera-probe/run.ps1` 直接执行生产 Forge 事件回调，再执行 Forge 实际视图旋转操作。覆盖 875 组活动状态/三轴姿态及非活动状态透传。相同探针对旧 `StageCameraOrientation` 立即报方向不匹配，新实现通过。

## GUI 模型消失

迁移提交 `7609810` 将模型视图组合统一改成仅使用实体 `PoseStack`。1.20.1 GUI 投影使用 `1000..21000` 深度范围，GameRenderer 的全局 ModelView 另有 `z=-11000` 位移。仅上传实体 GUI 栈会使原本位于 `z≈50` 的人物落在裁剪范围外。

现在 `BaseModelInstance.composeModelViewMatrix` 接受 `RenderScene`：

- 背包和纸娃娃组合 `RenderSystem.getModelViewMatrix() * entityPose`。
- 世界、第一人称和阴影继续使用已经包含视图的实体栈。
- CPU/OpenGL 与 GPU skinning 的普通、Toon、描边及相关矩阵上传调用均传递渲染场景。

补充真实 `InventoryScreen` 入口签名检查、GUI 深度与既有世界/阴影矩阵回归。`tools/gui-model-probe` 提供独立隐藏 GL 像素可见性检查，使用生产的公开矩阵入口。

## 实际 Euphoria 包

用户提供：

`C:\Users\JuziD\Downloads\ComplementaryUnbound_r5.1.1 + EuphoriaPatches_1.2.zip`

SHA-256：`88d60dbd6297e89c6483639988ed371de9901fd76da9a9b81d613d79941cb9d7`。

包内源码、缓冲消费端与展开证据位于：

`build/euphoria-compat/complementary-unbound-r5.1.1-euphoria-1.2-88d60dbd/`

确认 `colortex6` 存储 `(smoothnessD, materialMask, skyLightFactor, 1)`。该版直接计算天空光，而原适配仅识别较新版本的 `GetSkyLightFactor`。新增旧版直接计算协议，并保留 r5.9.3 原有严格检查。

同时适配旧包的 `colortex5` 世界法线、`colortex8` 彩色方块光输出，以及水面 `03/031/0318/038` 布局。水面捕获保留原生辅助 MRT、深度与透明逻辑。

`ComplementaryToonLighting` 根据活动 `DoLighting` 的参数名构造调用，分别适配旧版 15 参数与新版 18 参数，避免输出协议通过后因照明签名不同而回退。实际包与选项文件可通过 `tools/iris-toon-probe/run.ps1 -PackFile ... -OptionFile ...` 单独验证。

## 验证与交付

JDK：`C:\Program Files\Zulu\zulu-17`，使用现有共享 Gradle 缓存。

```powershell
.\gradlew.bat :common:test :forge:assemble --offline --console=plain
.\tools\camera-probe\run.ps1
.\tools\gui-model-probe\run-gui-model-probe.ps1
.\tools\iris-toon-probe\run.ps1 -Probe all -RequireWaterAdapter
```

全量 Java 测试：522 项，521 通过、1 跳过、0 失败/错误；Forge 发布构建通过。

构建、镜头探针和产物审计：

`build/diagnostics/1201-camera-gui-euphoria-20261113/`

隐藏 GL 结果：

- GUI：`INVENTORY` 与 `PAPERDOLL` 各 288 个可见像素，旧 entityPose-only 基线为 0，世界路径为 288 且保持输入世界栈不变。
- 用户提供的 Euphoria 包：默认设置与 `MCBL_MAIN_DEFINE=1` 各 12 组实体/透明实体/发光实体/手部程序，覆盖三维度，主体、描边与合批编译全部通过；原包照明源码非空，实际 profile 无回退。这两份选项文件用于覆盖 MRT 开关，并非完整 EUPHORIA 预设的全部设置。
- Euphoria 水面：上述两组设置各三维度，实际 Oculus 9 参数转换、GL 链接、辅助 MRT/深度保留、模型在水前/后的逐像素排序通过。
- 原有 BSL 8.4/10.1、Complementary 4.7.1 和 r5.9.3 的 `-Probe all` 套件通过，覆盖既有照明、阴影/玻璃、批次 MRT/深度和共享透明。

旧包在 `gaux1.r` 读取云深度，新包在 `gaux2` 读取相应云信息。水面探针的全屏“无云遮挡”纹理同时设置 R/A 为 1，并分别绑定这两个活动 sampler，使旧、新包使用同一物理含义的夹具。

Euphoria 原包和逐项日志：

`build/euphoria-compat/complementary-unbound-r5.1.1-euphoria-1.2-88d60dbd/gl-*.log`

发布包副本：

`build/distributions/mmdskin-forge-1.10alpha-1.20.1-camera-gui-euphoria.jar`

SHA-256：`6a50e0a5eb1dc9d5ed94f55adafd2548179f352a88300eb895934b664498de44`。包含 900 个 class，最高 class major 为 61（Java 17），Forge Mixin 配置和 refmap 齐全。

完整游戏内的真实 VMD、背包/纸娃娃与光影后处理视觉验收仍需执行；本次不替换游戏实例 JAR。代码尚未提交。

未操作另一个对话的 `build/worktrees/complementary-1.21.1-plan` worktree 或其构建输出。
