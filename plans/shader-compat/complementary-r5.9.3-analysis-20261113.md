# Complementary r5.9.3：1.20.1 / 1.21.1 兼容分析

日期：2026-11-13。范围：分析用户指定的 Unbound / Reimagined ZIP、当前工作区实现，以及 BSL 的可复用处理方式；本轮不修改生产代码、不修改光影包、不安装游戏 JAR。

当前分支为 `codex/port-1.20.1`，存在大量已有改动。以下结论以当前工作区源码为准，不能直接等同于历史 1.21 构建或已安装版本。

## 核心判断

这项工作应在既有 Complementary 5.x 适配上补齐功能。项目已经有现代 Complementary 实体/手部的 MRT 输出、原包光照提取和真实包编译探针；水面共享透明、独立地形玻璃透射与材质合批仍有 BSL 专用限制。

推荐让两个版本复用光影语义适配，把 Iris/Oculus 的调用签名和生命周期差异留在平台适配层。不能通过把包名加入白名单，就认为水、玻璃阴影与后处理已经兼容。

## 0. 两个实际 ZIP 的结构

输入目录：`D:/minecraft/.minecraft/versions/1.21.1-NeoForge_21.1.252/shaderpacks/`。

两个包各有 **436 个非目录文件**，文件路径集合完全一致。逐文件内容比较后，仅 `shaders/lib/common.glsl:14` 不同：

```glsl
// ComplementaryUnbound_r5.9.3.zip
#define SHADER_STYLE 4
// ComplementaryReimagined_r5.9.3.zip
#define SHADER_STYLE 1
```

这是同一套源码的编译期风格选择，适合共用一个现代 Complementary 适配器；风格会改变活动宏分支，因此仍须分别验证。

主代理重新读取完整 ZIP 字节得到的 **ZIP 文件 SHA-256**：

- Unbound：`98ac741dee294d2a3c82d153349148c3bb7fb688c30eb3b49c3b9ac37e968c5a`
- Reimagined：`fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38`

复核清单保存于 `build/diagnostics/complementary-compat-20261113/zip-audit.json`。

### 0.1 入口和共享代码

- `shaders/dimension.properties` 将 `world0`、`world-1`、`world1` 路由到主世界、下界、末地。
- 各维度目录提供 wrapper，定义维度与阶段后进入 `shaders/program/*.glsl`；公共选项在 `shaders/lib/common.glsl`。
- 实体、半透明实体、发光实体共用 `program/gbuffers_entities.glsl`，水与地形分别进入 `program/gbuffers_water.glsl` / `program/gbuffers_terrain.glsl`。
- 阴影入口包括 `shadow`、`shadowcomp` 及 Colorwheel 相关程序。
- 后处理具有 `deferred1`、`composite`、`composite1`、`composite3` 至 `composite7` 和 `final`；实际启用组合由宏与 properties 决定。`composite2` 为保留程序。
- 包内同时使用 `DRAWBUFFERS` 和 `RENDERTARGETS`，不能把所有后处理/扩展程序都按一位数字附件编号解析。

### 0.2 附件语义与 BSL 的差别

`shaders/lib/pipelineSettings.glsl:2` 起的注释以及实际读写位置共同表明：

- colortex0：场景主颜色。
- colortex3：随阶段复用为云/水映射、透射乘数、bloom / final 数据。
- colortex4：法线及反射强度。
- colortex6：`smoothnessD / materialMask / skyLightFactor`。
- colortex8：世界空间反射结果及顶层半透明信息。
- colortex13：供体积光混合使用的实体深度。

水程序 `shaders/program/gbuffers_water.glsl:288` 默认输出布局为 `03`，分别写 `color` 与 `vec4(1.0 - translucentMult.rgb, translucentMult.a)`。`:293` 起根据细节与世界空间反射选项，布局还可扩展为 `036`、`0348`、`03648`。

`shaders/program/composite1.glsl:134` 通过 `1.0 - texelFetch(colortex3, ...).rgb` 恢复透射乘数；`:138` 起进入可选折射。因此水适配必须保留 colortex3 及当前活动反射附件。

对照 BSL 10.1.8：`shaders/program/gbuffers_water.glsl:726` 的基础布局为 `01`，主颜色为 `albedo`，第二槽为 `vlAlbedo`；折射和多色光选项还可增加其他附件。两包的“第二输出槽”和 colortex3 语义均不相同。

### 0.3 阴影颜色、双深度与高级选项

Complementary 的 `shaders/lib/lighting/shadowSampling.glsl:1` 使用原包阴影坐标扭曲，并将 shadow z 缩放为 `0.2` 后映射到纹理空间。`:11` 起分别采样 `shadowtex0`（完整遮挡）和 `shadowtex1`（不透明遮挡），再用 `shadowcolor0` 计算透射；`:117` 起的颜色放大倍数与指数还随光照参数变化，不能复制 BSL 的固定解码公式。

`shaders/program/shadow.glsl:35` 起的自然阴影颜色公式包含 `1-alpha^64`，并将颜色缩小到 `0.1`。`:215` 起最低布局为 `0`，`SHADOW_QUALITY >= 1` 时扩为 `01`，第二个 shadow color 附件用于光轴。

因此，支持其透明地形重放时至少要处理 shadowcolor0 / shadowcolor1 以及宿主深度的备份、捕获和恢复；当前只接受 `{0}` 的 BSL 重放逻辑不能直接启用。

包内还有条件启用的 image、voxelization、SSBO 和世界空间反射路径。应根据**活动预处理程序及宿主报告**决定是否可重放，不能仅凭 ZIP 存在相关文件拒绝全部配置。BSL 10.1.8 在开启多色光时也有 voxel/image 路径，现有 BSL 适配同样不是覆盖其所有选项。

## 1. 已有公共实现

### 1.1 当前光影程序与输出契约

调用链：

`IrisToonCompat.prepare` → `IrisToonMetadata.read` → Iris 当前维度 `ProgramSet` / `ProgramFallbackResolver` → `ToonOutputProfile.resolve` → `ToonShaderBase.selectOutputProfile`。

- `common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisToonMetadata.java:60` 起：只接受当前实际 ExtendedShader，读取 Iris 自身的程序回退关系与预处理后源码，不凭光影 ZIP 名称决定协议。
- `common/src/main/java/com/shiroha/mmdskin/render/shader/ToonOutputProfile.java:127` 起：按函数和输出赋值识别 BSL / Complementary 4 / Complementary 5。
- 同文件 `:175` 起校验现代 Complementary 的 colortex3、6、4、13 输出；`:278` 起生成对应 writer。
- 现代 Complementary 主颜色使用现有代码称为 `gamma` 的直接颜色输出。BSL 可使用 `sqrt-linear`，旧 Complementary 使用另一套线性化规则，不能直接套用 BSL writer。
- 当前现代实体 writer 使用中性材质标记 `254/255`、世界空间法线及可选实体深度；它不代表完整接入 Complementary 自定义 PBR 材质。
- `common/src/test/java/com/shiroha/mmdskin/render/shader/ModernToonOutputProfileTest.java` 已覆盖两个版本名、颜色编码、输出布局、法线、实体深度及未知契约拒绝。

### 1.2 原包光照、阴影与 uniform

`ComplementaryToonLighting.createModern`（`common/src/main/java/com/shiroha/mmdskin/render/shader/ComplementaryToonLighting.java:21`）提取当前预处理源码中的 `DoLighting`，沿用原包太阳方向、维度、天气与阴影函数；同时转换 core GLSL 不支持的旧采样/雾用法。

`ToonUniformBridge`、`ToonShaderBase` 及描边光照路径负责向派生程序同步实际程序的 uniform。光照源码参与变体键，设置或维度变化不能沿用错误的旧照明变体。原包光照编译失败时可能保留输出协议、回退通用照明；验收必须检查实际选择的 profile，不能仅检查编译函数返回成功。

现有 ModernPackProbe 会检查实际选择的 profile，已有此项防误判逻辑。

### 1.3 世界透明合成

`IrisWorldTransparencyTarget` 获取 Iris 当前阶段的 colortex0 目标。当前工作区已经补充 Oculus 缺少 `bindDefault()` 时的目标构建逻辑，按 `isBeforeTranslucent` 与 flip 集合选择 main/alt FBO。

`WorldTransparencyPipeline.prepareModel`（`common/src/main/java/com/shiroha/mmdskin/render/pipeline/transparency/WorldTransparencyPipeline.java:340`）检查实际颜色附件、阶段目标与深度；水前深度保持私有快照。`COLOR_BUFFER_SWITCHED` 本身不等于必须拒绝，阶段 binder 验证成功后仍可接入。

这是两个包应复用的基础，后续必须核对其 deferred / composite 是否消费正确的一侧颜色纹理，以及辅助 MRT 是否保留。

## 2. 参考 BSL 时需要补齐的具体差异

### 2.1 水面准入只识别 BSL

`IrisWaterShaderPatcher.supports`（`common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.java:18`）要求同时出现：

- `vlAlbedo = mix(... sqrt(albedo.a)) * (1.0 - pow(albedo.a, 64.0))`；
- `gl_FragData[0] = albedo`；
- `gl_FragData[1] = vec4(vlAlbedo, 1.0)`；
- `GetWaterFog` 与 `cloudBlendOpacity`。

这是一份 BSL 水程序语义契约。后续应增加经过验证的现代 Complementary 水契约，校验主颜色位置、颜色编码、透射辅助输出、深度及活动选项分支。

可复用机制：

1. 在 Iris 地形 GLSL 变换之后复制并包装片段源码，不原地修改 Iris 缓存。
2. 在原 `main` 完成后记录最终颜色和片段深度，保留原 discard 与 late depth 行为。
3. `WorldWaterCapture` 只延后主 RGB 的混合，保留原 alpha、其他 MRT 和原生水深度；绑定并恢复 SSBO / indexed blend。
4. 在原生水程序结束时共享排序、合成并消费相应像素，再交回后续实体/粒子；空水批次保留晚期 PMX 路径。
5. 运行准入继续校验 viewport、颜色/深度附件身份与 SSBO 冲突。

不能只扩大 `supports` 条件：还要验证 Complementary 对辅助透射缓冲、折射、反射与后处理重采样的使用。

### 2.2 独立玻璃透射重放只接受 BSL 单输出协议

`IrisTerrainShadowReplay.supports`（`common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainShadowReplay.java:20`）要求 BSL 的 alpha→透射色公式；`currentPackSupported` 同时要求 shadow draw buffers 恰为 `{0}`，并拒绝 image/SSBO/compute/额外几何阶段等尚未验证的副作用。

这与通用的不透明地形深度快照是两项能力。`IrisTerrainShadow` 的不透明快照可继续复用，不能把“有地形阴影”理解成“现代 Complementary 已有独立彩色玻璃接收”。

BSL 的可复用流程：在实体绘制前备份宿主阴影附件 → 重放透明地形 → 保存独立深度/颜色 → 恢复宿主附件 → 正常绘制实体与透明地形。需要按 Complementary 自身的阴影输出、透射编码及所有被改写的附件建立新契约。

采样侧也需要独立检查：`ComplementaryToonLighting.createModern` 目前使用 `shadowSamplerWrapper(false)`；`adaptShadowSampler` 会把比较采样统一转到独立不透明深度。BSL 则额外区分 opaque/full 深度，并使用自己的透射色解码和联合滤波。即使开放重放准入，也不能直接认为现代 Complementary 接收端已经正确。

结合实际包的双深度读取，可以确认：启用当前独立地形接收时，modern 路径会合并 full / opaque 深度的区别；自定义柔化还会提前返回单一不透明深度过滤结果。后续需要保留双深度和彩色透射公式，并验证零柔化与正柔化两种情况。

### 2.3 材质合批排除了 Complementary

`ToonShaderBase.supportsBatchProfile`（`common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java:190`）只允许原版或 `bsl:` profile。`complementary_modern:` 当前不会编译、登记合批变体。

可复用 BSL 的合批 shader、材质表、sampler 分配与普通提交/合批像素对照方式，但需覆盖现代 Complementary 的 MRT、纹理单元占用和 SSBO 冲突。此处确认的是路径差异，本轮没有测量帧率损失。

## 3. 当前跨版本实现的已确认差异

当前 `settings.gradle` 只启用 `common` / `forge`，`gradle.properties` 为 Minecraft 1.20.1 / Forge 47.3.0。保留的 Fabric / NeoForge 源码不能视为当前默认构建已经覆盖的 1.21 平台。

共用 `IrisTerrainShadowReplay.java:48` 当前固定查找：

`invokeRenderChunkLayer(RenderType, PoseStack, double, double, double, Matrix4f)`。

本轮使用 `javap` 检查用户 1.21 实例中的 `iris-neoforge-1.8.14-beta.1+mc1.21.1.jar`，其 `LevelRendererAccessor` 实际提供：

`invokeRenderSectionLayer(RenderType, double, double, double, Matrix4f, Matrix4f)`。

因此，同一份当前 replay 代码直接用于此 1.21 Iris 时，若进入重放调用，会在寻找方法时失败。后续应按精确参数签名选择调用方式，分别传入 PoseStack 或 view/projection，不能只靠方法参数数量匹配。

### 3.1 本机 1.20 Oculus 与水面 hook

子代理定位了本机另一实例的 `D:/minecraft/2/.minecraft/versions/1.20.1-Forge_47.4.6/mods/oculus-mc1.20.1-1.8.0.jar`；主代理随后用 `javap` 复核了 accessor 与转换器签名。该 JAR 使用 `net.irisshaders.iris.*`。

- 1.20 Oculus 的 `TransformPatcher.patchSodium` 比当前 1.21 hook 多一个 `ShaderAttributeInputs` 参数。Forge 水源码 Mixin 已使用对应完整描述符，不能与 1.21 的描述符合并。
- Forge 水状态 Mixin 钩在 `ExtendedShader.apply/clear`，额外检查 water shader 名称；Fabric / NeoForge 的保留实现钩在 `SodiumShader.setupState/resetState`。实际水捕获是否命中，需要分别检查运行日志。
- Forge / NeoForge 的世界透明回调使用 `AFTER_ENTITIES`、`AFTER_BLOCK_ENTITIES`、`AFTER_PARTICLES`；Fabric 使用 `AFTER_ENTITIES`、`BEFORE_DEBUG_RENDER`、`AFTER_TRANSLUCENT`。最终仍由公共流水线核对实际阶段附件。
- 这些可选 Mixin 使用 `@Pseudo` / `require=0`。启动成功不能代替 hook 命中验收，需要补充实际版本的签名检查和运行诊断。
- 运行实例 Forge 47.4.6 与当前构建配置 Forge 47.3.0 有差别，后续记录兼容矩阵时应分别写明。

## 4. 后续实施和验收顺序

1. 固定 1.20.1 / 1.21.1 各自的实际加载器、Iris/Oculus JAR 和入口签名；先恢复可分别验证的构建/探针环境。
2. 回归已有两个包的现代实体/手部输出和原包光照，确认维度、重载与选项改变后实际使用对应 profile。
3. 补充现代 Complementary 材质合批，先做普通/合批颜色、alpha、深度和辅助 MRT 对照。
4. 增加现代 Complementary 水契约并复用现有捕获/共享排序流程；验证原包透射、折射和反射合成。
5. 增加独立的 Complementary shadow 重放/接收契约，检查全部附件与副作用恢复，再接玻璃着色、柔化及 Toon 阳光补光。
6. 在两种 Minecraft 版本中分别游戏验收：模型在水前/水后/穿水、多模型/多水层、玻璃前后、墙后、粒子、阴影、三个维度、切换光影、资源重载。

现有验证入口：

- `tools/iris-toon-probe/ModernPackProbe.java`：两个真实包 × 三维度 × 四类程序，主体/描边编译及实际 profile 检查。当前固定 `MC_VERSION=12101` / `IRIS_VERSION=10814`，不能替代 1.20 验证。
- `tools/iris-toon-probe/BslBatchGlProbe.java`：真实 BSL 普通/合批对照，适合扩展成现代 Complementary 对照。
- `tools/iris-toon-probe/WaterShaderProbe.java`：真实 BSL 地形变换、源码注入、链接及受控像素测试。
- `tools/iris-toon-probe/SunlightGlProbe.java`：已有两个 r5.9.3 包的照明覆盖入口。
- `tools/translucent-performance/run-block-entity-probe.ps1`：共享透明、原生水、MRT、深度及状态恢复验证基础。
- `IrisWaterShaderPatcherTest` / `IrisTerrainShadowReplayTest` / `ModernToonOutputProfileTest`：协议准入与拒绝回归。

`WaterShaderProbe` 当前固定 1.21 的转换签名与 SodiumShader；`ShadowHookProbe` 也固定检查 1.21 的 section-layer 入口和 Fabric / NeoForge 类。后续需增加 Oculus / Forge 分支和跨版本 replay 分派检查，不能把现有探针的历史通过结果外推到 1.20。

Windows `tools/iris-toon-probe/run.ps1` 当前在处理 `MinecraftJar` 覆盖参数之前就读取 `neoforge_version` 并调用 `.Line.Split(...)`，而当前 1.20 配置没有该属性。因此单独传 `MinecraftJar` 也不能绕过前面的初始化问题；需先调整版本相关启动层，再提供匹配版本的依赖，不能用旧构建目录的 class 混合验证。

## 5. 本轮验证边界

重复性的包清点/对比、平台入口清点分别由两个 `auto/deepseek-v4.1-flash` 子代理完成；主代理复核了两个 ZIP 的全部文件差异与完整 ZIP 哈希、关键水/阴影 GLSL，以及本机 Iris/Oculus 的 accessor/转换器签名。只新增本分析文档及 build 目录内的复核清单。

本轮未执行 Gradle 编译、GPU 场景探针或游戏视觉验收。历史文档中的通过结果仅用于理解 BSL 设计，不作为当前工作区和两个目标版本已经通过的证据。
