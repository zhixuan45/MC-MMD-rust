# 1.21.1 独立 Toon：不透明原型与准入边界

基线：`feat/1.21.1-neoforge` 的 `d32551b`。仅修改 1.21.1 Fabric / NeoForge。

**这是可执行的不透明原型，不是任意光影包、透明 PMX 的完整兼容实现。**
半透明、多 PMX 透明交错、水/玻璃和实际游戏视觉/性能验收尚未完成。未知协议不能通过此 PR 无条件保留 Toon；不满足下述条件时保留集成或原生渲染，且记录原因。

## 调查结果

| 系统 | 当前实际调用链与边界 |
| --- | --- |
| CPU / GPU | 两后端分别更新 CPU 顶点缓冲或 compute 蒙皮结果，随后共同使用 `ToonShaderCpu`、`SubMeshDrawHelper`、`MaterialRenderState`、`OutlineRenderPass`。GPU 的 Toon draw 直接读取 compute 输出 VBO，并非另一套材质着色器。 |
| Iris 输出 | `IrisToonCompat.prepare` → `IrisWorldTransparencyTarget.entityShader` → `IrisToonMetadata.read` → `ToonOutputProfile` → `ToonShaderBase.selectOutputProfile`。按实际源码、DRAWBUFFERS、编码和写入语义验证，不按包名。未知输出拒绝自定义 MRT writer。 |
| 照明回退 | 已确认输出协议后，原包照明提取/编译失败可保留 writer，降为通用环境光；`ToonUniformBridge` 校验 uniform 类型、数组和 sampler。输出协议本身不支持或变体均编译失败时，当前 pass 标准渲染。 |
| 主体顺序 | 实心颜色/深度在实体阶段提交；透明片元进入世界共享 SSBO 队列，或保留现有局部分层/排序回退；主体之后执行原生外壳描边。世界回退不提前补写半透明深度。 |
| 描边 | `OutlineRenderPass` 共用材质 alpha、姿态、描边颜色与 GUI 宽度计算。`OutlineCoverage` 私有 2×2 覆盖率缓冲，使用同格式深度副本；共享世界队列时保留几何片元深度。 |
| FBO / 深度 | Iris 实体和公共 loader 回调的 FBO ID 可以不同；现有队列核对真实颜色/深度 backing storage、viewport 和格式，并通过 `bindDefault()` 选择 pre-composite 的活动颜色侧。颜色 flip 不能用共享深度冒充颜色相同。 |
| 箱子 / 物品 | 实体后、方块实体后及 `RenderType.translucent()` 调用前更新经过验证的深度快照。最后一个位置晚于 Iris 实体 batch flush，早于水。不能以水后 depthtex0 覆盖 opaque cutoff。 |
| 水 / 多 PMX | 多实例共用世界片元链表，兼容同 backing storage 的 FBO 包装。`IrisWaterShaderPatcher` / `WorldWaterCapture` 只对已验证的水协议捕获，保留原生 alpha、辅助 MRT、深度及 GPU 水面判定；未改这些算法。 |

复核了任务所列兼容、shader、后端、描边、透明文件与六份历史文档，并补读 `IRIS_WATER_QUEUE_20261023.md`。

### final 入口证据与运行时门槛

检查 Iris `1.21.1` 分支提交 `12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3`：

- [`MixinLevelRenderer.iris$endLevelRender`](https://github.com/IrisShaders/Iris/blob/12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3/common/src/main/java/net/irisshaders/iris/mixin/MixinLevelRenderer.java) 在世界返回时调用 `pipeline.finalizeLevelRendering()`。
- [`IrisRenderingPipeline.finalizeLevelRendering`](https://github.com/IrisShaders/Iris/blob/12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3/common/src/main/java/net/irisshaders/iris/pipeline/IrisRenderingPipeline.java) 先 composite，再 `finalPassRenderer.renderFinalPass()`。
- [`FinalPassRenderer`](https://github.com/IrisShaders/Iris/blob/12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3/common/src/main/java/net/irisshaders/iris/pipeline/FinalPassRenderer.java) 输出到 Minecraft 主颜色纹理；世界使用主目标的深度。后续 `finalizeGameRendering` 仍可做显示颜色转换。

因此直接观察 Iris 此方法的 HEAD / RETURN，不依赖两个模组在 `LevelRenderer.RETURN` 的注入顺序，也不把 loader 的 AFTER_TRANSLUCENT 当作 final 后。

新增三个可选 Mixin（两平台一致）：pipeline 生命周期、Extended/Fallback shader apply、Sodium shader setup。所有注入 `require=0`；`ToonIrisMixinPlugin.postApply` 在**实际变换后的方法体**检查 handler 调用。应用 Mixin 前先检查入口的 void/参数签名，变更或歧义入口直接跳过；应用后仍须检查真实调用。Fabric 正式版兼容 `ShaderInstance.apply` 的 intermediary 名 `method_34586`（已核对 1.21.1 Loom mappings，发布 JAR 保留该 selector）。仅有 handler 声明、Iris 已安装、反射符号存在，都不能获得能力。

首次创建 pipeline 的帧禁止独立提交。必须实际执行一次 final 前/后观察，确认同一主颜色、深度对象、格式、尺寸和有效单采样 FBO，下一帧才准入。漏掉回调、切换 pipeline 或无效目标会撤销门槛。开启原有“透明阶段诊断”后，每 120 帧可记录 `before-final` / `after-final`、绑定 FBO、目标颜色、native depth、队列数和拒绝原因；没有默认像素读回。

**本轮没有启动 Minecraft，尚无实际 Iris 的事件/GL 抓帧记录。** 上述源码调查、字节码门槛和运行时自校验不能代替游戏内确认 Mixin 命中、目标存活及最终画面。

## 实现选择

`ToonOffscreenRenderer` 管理一个按世界帧共享的 RGBA16F 颜色 + 原格式深度目标。
`ToonSceneCompositor` 只处理明确验证为 Minecraft RGBA8 / GL_LINEAR 存储的 presentation 目标。这里颜色沿用原 vanilla Toon 的显示编码，不把任意 HDR gbuffer 当作 RGBA。

1. 在实体阶段做一次原生实体绘制，保留 shader-pack 材质/深度作为底图。只有整模型 alpha、顶点变换等条件通过才走此分支。
2. 记录小型矩阵、光照方向、submesh 描述及 revision；不复制动画顶点，不重算物理或蒙皮。
3. Iris composite/final 开始前，将已验证的主世界深度复制一次到私有目标。此深度是较晚场景深度，**不称为不透明深度**。只有已排除世界透明混合的原型帧可使用它。
4. Iris final 返回后，核对同一 presentation、clip/depth convention 和全部模型缓冲有效性，在私有 FBO 中重放主体及原有描边。使用原始世界投影，硬件深度测试；native 底图已有同深度模型，故私有 pass 明确使用 LEQUAL。
5. 私有颜色清为透明；原有描边 2×2 覆盖率合成保留。所有模型完成后仅一次预乘 alpha 全屏合成。零覆盖处 discard，不对场景其余位置写颜色或深度。
6. 任一绘制失败，在发布私有颜色前中止，保留 native final 底图。draw/read FBO、MRT mapping、viewport/scissor、program、VAO/VBO/EBO、逐槽 blend/mask、depth、cull、纹理/sampler、sRGB 与 Blaze3D 缓存均恢复。

保留 native 底图会增加一次模型几何绘制。这是原型在晚期目标、透明或资源失败时仍能保留完整模型的代价；尚未证明满足最终 GPU 开销目标。没有重绘世界，也没有每模型新增整屏 Toon 合成。

### 必须通过的能力条件

- 活动 shader pack、普通世界视图、Toon 开启；GUI、纸娃娃、物品、第一人称、VR 和特殊遮罩/效果包保持原有路径。
- 实际可选 Iris hooks 完整，并有同一 pipeline 前一帧的 final 观察证据。
- 整个模型所有可见材质的最终 alpha（含 diffuse / texture alpha morph / global alpha）均为实心，贴图 alpha coverage 完全不透明。二值 cutout 也因过滤边缘产生半透明而拒绝；不通过关闭透明获得资格。
- native shader 没有额外 geometry / tessellation / image write / 显式 fragment depth；只接受明确的 Position → ModelViewMat → ProjMat 矩阵表达式或等价的简单 ftransform helper。Iris fallback 的 `Position + ChunkOffset` 还要求实际 offset 为零。原生投影与 model-view uniform 的类型和值必须匹配 MMD 本次绘制。
- 不猜测局部位置形变、TAA jitter、clip planes 或特殊投影。复杂但可能正确的程序也可能被保守拒绝。这一检查不依赖 shader-pack 名称或 MRT 语义。
- 非反转透视深度、默认 clip origin/range；单采样、有实际尺寸/格式的 depth backing、level/layer 0、完整 viewport；native 与主目标深度必须是同一实际存储。
- 观察到天空背景以外任何需要混合的 Iris world submission（包括辅助通道的未知混合），就取消**整帧**独立合成。透明实体、粒子、云、水/玻璃因而可能使原型退回 native/integrated，即使它们不与模型相交。这是有意的保守边界，不是假装实现了透明交错。
- 私有存储上限 128 MiB，最多 64 个请求；缩放/深度格式变化才重建。关闭光影/Toon、改用集成策略、资源重载和 renderer 释放会清理资源及观察状态。

未经过这些 Iris shader hooks 的第三方直接 GL 渲染、最终屏幕空间扭曲及任意 shader compute 修改不在保证范围内，不能据此宣称通用兼容。

## 三种设置

统一 `ConfigData` / `IToonConfig` / `AbstractMmdSkinConfig` / `ConfigManager`，两平台同一枚举与说明：

| 模式 | 行为 |
| --- | --- |
| AUTO（默认） | 已验证协议保留现有集成；未知协议尝试上述原型；能力不足保留标准模型。集成编译失败时下一帧开始尝试观察独立入口。 |
| TOON_PRIORITY | 优先尝试原型；准入失败仍保留可用的集成或标准路径。之后发现世界混合或晚期失败时保留已绘制的 native 底图。 |
| SHADER_INTEGRATED | 仅允许现有已验证集成，未知输出标准回退，绝不猜写未知 MRT。 |

旧配置缺字段、未知枚举或 null 都归一为 AUTO。Toon 总开关不变。独立模式复用原色阶、高光、Rim、阴影颜色、眼瞳发光和描边；仅使用游戏环境亮度，不冒充完整 shader-pack 阴影、彩色动态光或体积光。它不参与已完成的雾、曝光、泛光、DOF、TAA；后续显示颜色转换仍可能执行。

## 性能与诊断

沿用 `WorldTransparencyProfiler` 的 CPU 窗口与非阻塞 GPU timestamp 查询池，增加：

| 阶段 | 范围 |
| --- | --- |
| TOON_NATIVE_UNDERPAINT | 原型所需的原生模型底图绘制 |
| TOON_DEPTH_CAPTURE | 每世界帧一次深度复制及目标准备 |
| TOON_RENDER | 每模型晚期重放（包含描边） |
| TOON_OUTLINE | 原有描边 pass，是 TOON_RENDER 的子集，不能与其相加 |
| TOON_COMPOSITE | 每世界帧一次颜色合成 |

开启“GPU 日志”或 `-Dmmdskin.profileWorldTransparency=true` 查看。查询池满则跳过采样，不 glFinish、不等待 query。独立原型明确跳过已证明实心提交的三角形透明排序，防止 hasAlpha 格式标记导致无意义的 GPU 顶点读回；原有透明路径的排序规则未改。

默认诊断原因每资源生命周期去重。常见原因：`WAITING_FOR_IRIS_FINAL_OBSERVATION`、`IRIS_HOOK_CONTRACT_UNVERIFIED`、`NATIVE_VERTEX_TRANSFORM_UNVERIFIED`、`TEXTURE_ALPHA_REQUIRES_SHARED_TRANSPARENCY`、`WORLD_TRANSPARENCY_REQUIRES_INTERLEAVING`、`NATIVE_DEPTH_OR_PROJECTION_UNVERIFIED`、`PRESENTATION_COLOR_OR_WORLD_DEPTH_CHANGED`、`MODEL_BUFFERS_CHANGED_BEFORE_FINAL`。成功为 `OPAQUE_INDEPENDENT_COMPOSITE_ACTIVE`。

## 验证与尚未完成的验收

本轮测试结果见下方交付记录。复现 Java 与 EGL 探针：

```sh
./gradlew -I tools/translucent-performance/validation.gradle \
  :common:test :fabric:compileJava :neoforge:compileJava \
  :common:writeTranslucentProbeClasspath -x :prepareNativeResources

LWJGL_EGL_JAR=/absolute/path/lwjgl-egl-3.3.3.jar \
LWJGL_NATIVE_CLASSPATH='/absolute/path/lwjgl-natives.jar:/absolute/path/opengl-natives.jar:/absolute/path/glfw-natives.jar' \
  bash tools/translucent-performance/run-independent-toon-probe.sh
```

没有修改 JNI/Rust；此处跳过 native 重编译，使用工作区既有 native resources。NeoForge 在本环境通过临时 Gradle init script 给 NeoForm CLI 的 `--launcher-meta-uri` 指向获准的官方 `piston-meta.mojang.com` 镜像，并使用工作区 JDK 21；没有修改仓库构建逻辑或关闭 TLS。

单元测试覆盖策略、旧配置迁移/持久化、实际变换方法的 hook 验证、全模型 alpha/morph 准入和规范顶点表达式。新 OpenGL 探针使用生产 Toon/描边/离屏/合成代码和受控替换 final，检查覆盖区域而非仅单像素，包括前景遮挡、未知辅助 MRT 不变、GL 缓存与实际状态不一致、首帧观察门槛、异常、stale buffers、尺寸变化和资源销毁。

这不是完整 Iris 运行。实际 CPU/GPU PMX、BSL、Complementary、未知光影、白天/夜晚/火把、箱子、F5、GUI、Sodium/Embeddium、切换光影，以及透明头发/裙摆、两/三 PMX、水和玻璃都仍需固定相机/姿态游戏截图与 GPU 抓帧验收。特别要验证原型是否真的准入，不能仅凭“模型仍可见”当作 Toon 成功。

尚无实际游戏的单/双/三 PMX CPU/GPU 性能对比，也没有 FPS/毫秒收益结论。复杂材质通常不会进入本原型；只有继续扩展可靠的跨渲染器透明接口、减少底图重放并完成游戏验证，才能达到任务的完整完成标准。


### 本轮交付记录（2026-11-03）

- `:common:test`：392 项，0 failure、0 error。
- `:fabric:compileJava :neoforge:compileJava :fabric:remapJar :neoforge:shadowJar`：通过。检查发布 JAR 含新增共用 renderer、plugin、两平台 hooks，并确认 Fabric intermediary selector 未丢失。
- 新 `IndependentToonGlProbe`：通过。生产 Toon/OutlineRenderPass、替换式 final、主体/描边区域、前景深度、未知辅助 MRT、原始 GL 与 Blaze3D 缓存不一致、EyeMask 常量、首帧门槛、注入异常、缓冲变化、尺寸与释放重建。
- 原 `DepthAttachmentGlProbe`：通过，包含实际深度格式/复制/描边及不匹配复制拒绝。
- 原 `ToonTransparencyLayersGlProbe`：通过，CPU/GPU 输入、交叉面、覆盖率/共面 oracle、透明尾部、异常清理和状态恢复。
- 原 `ToonTransparencyGlProbe`：普通及 batch 均通过，CPU/GPU 输入 alpha/裁切/透明开关/孔洞及状态一致。
- 原 `BlockEntityTransparencyGlProbe`：通过，箱子前后、低 alpha、共享存储的不同 FBO、stable/flipped 颜色、生产描边、两组模型片元及后提交 opaque 遮挡。
- 原水面 `--water-order`：8 个 alpha/前后组合、空水 pass、两水层交叉和 overflow 覆盖通过。此处为原生产适配器配合合成 fixture，**没有加载实际 BSL / Complementary ZIP**。

GL 环境为 Mesa 25.0.7 llvmpipe / OpenGL 4.5 compatibility，EGL pbuffer 请求 D24。为了复用无 X server 环境，只把旧探针的 GLFW 入口替换为 EGL 上下文；原断言保留。`BlockEntityTransparencyGlProbe.runInCurrentContext` 由原 main 同样调用。日志中的 intentional probe exception 为故障注入，通过条件包含其后的状态及颜色检查。

本地证据日志：`/tmp/mmd-toon-final-build.log`、`/tmp/mmd-independent-gl-final.log`、`/tmp/mmd-toon-gl-regressions.log`、`/tmp/mmd-toon-world-regressions.log`（不随源码分发）。上述不是实际游戏视觉或性能结果；旧分层探针打印的 llvmpipe 数字也不是本实现的单/双/三 PMX 对比。

构建产物仅复用已有 Linux 原生库，不是包含 Windows DLL 的完整测试包：

```text
fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar
SHA-256 067069a88a16d753b83aee5b8f6f86c0174a1f833b674caca2af8504fe1ac20a
neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar
SHA-256 24a8562c5ff698bf9b54e277766887d8465c31e6b042077dad980970a111146b
```
