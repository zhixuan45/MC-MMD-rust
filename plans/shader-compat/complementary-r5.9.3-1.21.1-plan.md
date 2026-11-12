# Complementary r5.9.3：1.21.1 实施计划

日期：2026-11-13。

目标分支：`feat/1.21.1-neoforge`，本计划创建前基线为 `2fc8a8aad271a617de0e87ea55e06589820ccd6b`。

先行实现分支：`codex/port-1.20.1`。两个版本属于不同分支，按功能逐项移植；禁止把 1.20 分支的整套平台清理、构建配置、Java 17 API 迁移或未提交的其他业务改动整体合并到本分支。

本提交只增加计划，尚未在 1.21.1 实施、构建、安装或游戏验收。

## 1. 目标与输入

支持 Complementary Unbound / Reimagined r5.9.3 的既有实体、手部和描边 Toon，并补齐材质合批、水面共享透明排序、独立地形彩色玻璃透射。

实际输入：

- `ComplementaryUnbound_r5.9.3.zip`，ZIP SHA-256：`98ac741dee294d2a3c82d153349148c3bb7fb688c30eb3b49c3b9ac37e968c5a`。
- `ComplementaryReimagined_r5.9.3.zip`，ZIP SHA-256：`fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38`。
- 本机来源目录：`D:/minecraft/.minecraft/versions/1.21.1-NeoForge_21.1.252/shaderpacks/`。
- 本机 1.21 Iris：`iris-neoforge-1.8.14-beta.1+mc1.21.1.jar`。
- 构建配置基线为 NeoForge 21.1.240，运行实例为 NeoForge 21.1.252；验收记录分别写明。

两个 ZIP 各含 436 个非目录文件，唯一区别是 `shaders/lib/common.glsl:14` 的 `SHADER_STYLE` 默认值：Unbound=4、Reimagined=1。共用现代 Complementary 协议适配器，分别验证两种风格及当前活动选项。

## 2. 分支边界与实施前检查

1. 在独立 worktree 中工作，先读本分支 `index.md` 路由和 `git status`，保留所有已有改动。
2. 保持本分支 `common` / `fabric` / `neoforge` 构建结构及 Java 21。不移植 1.20 Forge 模块或其依赖版本。
3. 不移植 1.20 专用 `PoseStack` 地形调用、`SystemToastIds` 改动和 Oculus 缺少 `bindDefault()` 的兼容补丁。
4. 以实际 Iris JAR 验证 shader transform、water state、shadow layer 与 program metadata 的方法描述符，不能仅因 `require=0` 下启动成功就认定 Mixin 命中。
5. 1.20 分支的 `plans/shader-compat/complementary-r5.9.3-analysis-20261113.md` 和完成报告可提供实现背景；本计划已包含移植所需关键约束。

## 3. 按功能移植

### 3.1 现代水面协议

主要文件：

- `common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.java`
- `common/src/test/java/com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcherTest.java`
- 两个平台的 `IrisWaterShaderMixin.java`。

移植要求：

- 保留已有 BSL 准入逻辑，新增现代 Complementary 的颜色、光照、雾和透射输出语义检查。
- 接受已验证布局 `03`、`036`、`0348`、`03648`；按 Iris 规则使用最后一个活动 DRAWBUFFERS/RENDERTARGETS，并与逐槽赋值、变换后输出严格核对。
- 包装最终片段 main 后捕获 location 0 的颜色/深度，保留原 alpha、discard、late depth 和所有辅助 MRT。
- colortex3 存储 `1-translucentMult`，后续 composite1 会恢复透射乘数；反射分支还使用 colortex6/4/8。不得按 BSL 的 colortex1/vlAlbedo 规则改写这些附件。
- 对未知输出、显式深度写入、image/SSBO 副作用、额外 shader stage 和重复注入维持明确的拒绝与诊断。
- 保留复制 transformed-source map 的行为，禁止原地污染 Iris 缓存。

1.21 专用接入：

- 保留 `TransformPatcher.patchSodium` 的 8 参数签名；不要复制 Oculus 额外的 `ShaderAttributeInputs` 参数。
- 保留 `SodiumShader.setupState/resetState` 水状态作用域；不要替换成 Oculus 的 `ExtendedShader.apply/clear`。
- 将日志中的 BSL 专用名称改成能区分实际协议的通用名称，运行时确认 capture 和 resolve 均实际命中。

### 3.2 现代 Toon 材质合批

主要文件：

- `common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java`
- `tools/iris-toon-probe/ModernPackProbe.java`

将已通过输出契约检查的 `complementary_modern:` profile 纳入合批变体。保留原版、BSL、未知协议和编译失败的现有行为。

逐维度、逐程序检查实际选择的 profile、主体/描边编译和合批登记；增加普通提交/合批的颜色、alpha、深度与辅助 MRT 像素对照。检查 sampler 数量、纹理单元和 SSBO 绑定冲突，不只检查 GL 链接成功。

### 3.3 双颜色阴影地形重放

主要文件：

- `common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainShadowReplay.java`
- `common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainTransmission.java`
- `common/src/main/java/com/shiroha/mmdskin/compat/iris/TerrainShadowFrame.java`
- 对应协议单测及 `tools/iris-toon-probe/TransmissionGlProbe.java`。

移植要求：

- 为现代自然阴影公式增加独立协议，允许已验证的 shadow color 输出 `{0}` 或 `{0,1}`；BSL 继续保留自己的单目标公式。
- 重放前备份全部会被改写的颜色附件和地形深度。现代 shadowcolor1 用于光轴，必须在正常及异常退出时恢复。
- 对接收侧只保存所需的独立 full-depth 和 shadowcolor0，额外颜色目标仅作宿主恢复。
- 按纹理格式、尺寸、翻转状态和内存预算验证附件；资源在重载、维度切换和销毁时正确失效或释放。
- 保留 image/voxelization、compute、geometry/tessellation 等未验证副作用的准入保护。以活动源码和宿主报告为准，不按包名或 ZIP 文件存在性判定。
- 现代包的共享头会声明未使用的 shadow samplers。只允许去除已知类型/名称的声明后检查副作用；保留对 fragment/vertex 中任何实际阴影附件读取的拒绝，避免将无害声明和反馈读取混为一谈。
- 核对原生 `ShadowRenderer` 实际使用的 base shadow program；1.20 先行实现改用 `ProgramSet.getShadow()`，本分支同样需要按实际 Iris 字节码确认，不能盲用 ShadowWater fallback。

1.21 地形方法必须保持：

```java
invokeRenderSectionLayer(
    RenderType layer, double x, double y, double z,
    Matrix4f view, Matrix4f projection
)
```

本机 Iris 已通过 `javap` 确认该签名。不得引入 1.20 的 `invokeRenderChunkLayer(RenderType, PoseStack, ...)`。

### 3.4 现代玻璃透射接收与 Toon 补光

主要文件：

- `common/src/main/java/com/shiroha/mmdskin/render/shader/ComplementaryToonLighting.java`
- `common/src/test/java/com/shiroha/mmdskin/render/shader/SunlightToonLightingTest.java`
- `tools/iris-toon-probe/LightingProbe.java`。

移植要求：

- `shadowtex0` 对应包含透明地形的完整深度，`shadowtex1` 对应不透明深度，分别重定向，不能全部压成同一张不透明快照。
- 使用原包 `shadowcolor0 * opaque * colorMult` 及 `colorPow` 解码。Complementary 将阴影颜色缩小 10 倍，放大和指数随光照参数变化，不能照搬 BSL 的固定平方解码。
- 正柔化在原包最终阴影坐标对 full/opaque/color 同时采样和过滤；零柔化保留原包采样路径。
- 用不透明几何可见度决定 Toon 太阳补光覆盖率，玻璃吸收和染色继续影响能量，避免将减光误当成太阳被墙挡住。
- 没有独立快照、快照失效或未进入有效采样分支时，保留经典接收行为。
- 主体和描边均验证。

## 4. 探针适配要求

保持独立的 1.21 依赖配置：JDK 21、`MC_VERSION=12101`、`IRIS_VERSION=10814`、实际 Iris 转换器及映射后的 1.21 Minecraft JAR。不能让 1.20 class、Oculus、映射 JAR 或历史 build 输出混入 classpath。

从 1.20 先行实现移植探针的功能改进，但以 1.21 为默认：

- 两个真实包 × 三维度 × 实体、半透明实体、发光实体、手部，检查实际 profile 和合批登记。
- 真实水程序经过 Iris transform 后注入、链接、逐像素比较前/后排序及辅助 MRT。
- 水夹具使用实际水材质编号 32000，提供屏幕尺寸的云/深度图；实体绘制后重新绑定原生水纹理。参考与生产路径使用相同深度、采样位置及 uniform。
- Shadow GL 包含单/双颜色目标、正常/异常/重试、四种已支持颜色格式，以及宿主深度和颜色精确恢复。
- 现代玻璃夹具遵守 0.1 的存储缩放，分别验证无色/彩色玻璃、墙后、柔化 0/1.5/4、天空光变化和阳光补光。
- 完整模式必须实际执行 TerrainShadowGlProbe / TransmissionGlProbe，不能以 lighting-only 模式跳过资源恢复验证。
- 保留 BSL 回归；探针生产源码与 class 必须匹配，不把 lighting fallback、原生水未接入或旧 class 当作通过。

## 5. 验收与交付

1. 协议单测、渲染相关测试、Fabric / NeoForge 编译与各自发布包构建通过。
2. 真实两个 r5.9.3 包按上述矩阵通过 GL 检查；记录命令、JAR/ZIP 哈希、驱动、实际参数与日志。
3. 实际游戏验证：水前/水后/穿水、多个 PMX/多水层、折射/反射边界、粒子、玻璃前后/墙后、人物向世界投影、三个维度、切换光影与资源重载。
4. 验证未知或带副作用的配置有具体诊断，宿主渲染继续按原路径工作。
5. 分别交付 Fabric / NeoForge 包、哈希与验证范围；原生库若未变化，记录复用来源及哈希。

不得用 1.20.1 的测试结果代替 1.21.1 验收，也不得把隐藏 GL 探针称为完整游戏视觉验收。
