# 当前代码库回迁 Minecraft 1.20.1：扫描与修改计划

计划日期：2026-11-11。实施更新：2026-11-13，已在 `codex/port-1.20.1` 完成 Forge 优先迁移，构建集合为 `common/forge`；Fabric 后续处理。下文保留原始扫描基线及双平台计划，实际完成范围与验证结果见 [verification.md](verification.md)。

## 1. 基线与目标

- 扫描基线：`feat/1.21.1-neoforge`，HEAD `2fc8a8aad271a617de0e87ea55e06589820ccd6b`。
- 扫描前工作区干净；本次只新增计划文档，不修改源码、构建配置或分支。
- 当前目标：MC `1.21.1`、NeoForge `21.1.240`、Fabric API `0.107.0+1.21.1`、Java 21。
- 当前参与构建的模块是 `common`、`fabric`、`neoforge`；当前树中没有 `forge` 模块。
- 当前主 Java 源文件共 553 个：common 475、Fabric 40、NeoForge 38；common 测试 Java 文件 116 个。文件数量不等于测试用例数量。
- 源码/API 扫描由 dsf（`auto/deepseek-v4.1-flash`）执行；主代理补充构建、CI、历史分支和官方资料核查，并整合计划。

建议保留当前功能，从当前 HEAD 建立独立的 1.20.1 维护分支，按 Forge 优先、Fabric 后续完成双加载器支持。Rust 引擎保持当前实现，重点适配 Java、加载器和渲染宿主。

### 历史参考

`1.20-vr` 指向 `986d01d9e75d69aeec4a2e4990eaad25ca878e49`，最近提交为 2026-10-08 的 1.20.1 女仆轮盘和缩放更新。它包含 Forge/Fabric、Java 17、Forge `47.3.0`、Fabric API `0.92.2+1.20.1` 的实现。

当前 HEAD 与该分支在 common/fabric/neoforge/forge/rust_engine 范围内有 636 个文件差异，约 56,414 行新增、6,738 行删除。该统计包含源码、测试和资源，体现差异规模，不用作工作量换算。

旧分支只用于查询旧 API、事件注册和签名，不整树覆盖。它的 Architectury 配置、旧版本号、MIT 元数据等也不能直接替换当前构建和许可证。

## 2. 平台与工具链方案

### 推荐平台

1. 主交付：Minecraft 1.20.1 + Forge 47.x。
2. 第二交付：Minecraft 1.20.1 + Fabric。
3. NeoForge 1.20.1 不列为默认验收目标。NeoForge 官方明确建议该版本使用 Forge；如实际有该加载器需求，后续增加独立兼容验收。
4. 当前 1.21.1 分支继续保留；实施时建议新分支名 `codex/port-1.20.1`，本轮不创建。

### 初始依赖候选

- Minecraft：`1.20.1`；元数据应精确限制到 1.20.1，避免把 1.20.2—1.20.6 宣告为已兼容。
- 游戏运行、Java 字节码和 Mixin：Java 17 / class major 61 / `JAVA_17`。
- Forge：先以旧分支的 `47.3.0` 作可对照基线，最终版本在实施阶段结合目标整合包和兼容模组锁定；本计划没有把它宣称为最新或最终发布版本。
- Fabric Loader：先保留 `0.17.2`，验证目标 MC 和选定 Fabric API 的运行组合。
- Fabric API：旧分支 `0.92.2+1.20.1` 作起始候选。
- Cloth Config：Forge/Fabric 改为 1.20.1 对应的 11.x；旧分支 Forge `11.1.136` 作候选，实施时解析并验证双方坐标。
- ModMenu：从当前 `11.0.1` 调整为支持 1.20.1 的版本，实施阶段查发布元数据后锁定。
- Gradle Wrapper `8.11.1`、Fabric Loom `1.9.2`、Shadow `8.3.6` 先保留；出现实际插件兼容问题再调整。
- Forge 构建优先采用 `net.neoforged.moddev.legacyforge:2.0.78`，版本与当前 `moddev_version` 一致。该版本插件存在且声明支持 Forge 至 1.20.1；完整多模块配置和下述 API 仍须实际配置验证。

### 构建与发布必须处理的差异

- `build.gradle:173,178`：`options.release`、Java toolchain 从 21 调整到 17。
- `settings.gradle:40-43`：迁移分支以 `common/fabric/forge` 为构建集合，避免未适配的 1.21.1 NeoForge 依赖进入全局配置。
- `common/build.gradle`：继续使用官方 Mojang mappings 编译 1.20.1 的公共代码，先验证 Fabric Loom 与 Legacy Forge 的输出/类路径衔接；不先引入整套 Architectury。
- 由当前 NeoForge 装配逻辑构建 Forge 模块；按 1.20.1 Forge 事件/API 重接入口。旧 Forge 文件只作为参考。
- Forge 使用 `META-INF/mods.toml`、`forge` 依赖和 `mandatory` 字段；处理 JavaFML 47.x 和客户端 Cloth Config 依赖。保留当前许可证、NOTICE 和第三方许可。
- Fabric 的 MC、Java、API、ModMenu 元数据一起修改；两个平台的 Mixin compatibilityLevel 改为 `JAVA_17`。
- Forge 发布要求 SRG 重映射。最终包含 common 和重定位 jlayer 的 Shadow JAR 必须参与 reobfuscation；不能直接发布当前 NeoForge 风格 Mojmap Shadow JAR。
- 根据锁定的 Legacy Forge 插件配置 Mixin annotation processor、非空 refmap、`MixinConfigs` manifest；在生产环境验证注入。官方当前文档示例使用 `obfuscation.reobfuscate(tasks.named('shadowJar'), sourceSets.main)`，实施时以 2.0.78 的实际 DSL 为准。
- 开发运行加载已发布的女仆、TaCZ 等模组时，要做 SRG → Mojmap 依赖重映射，并检查嵌套依赖；保留当前原生库 staging 和 jlayer relocation 机制。

### 当前本机工具链事实

已运行 `gradle-ide-parity/scripts/gradle_env_probe.ps1`，未执行 Gradle 构建。发现：

- `JAVA_HOME` 指向 `C:\Program Files\Zulu\zulu-21`，PATH 实际 `java` 是 Zulu 17。
- 本机已有 `C:\Program Files\Zulu\zulu-17` 和 `C:\Program Files\Zulu\zulu-21`。
- 未发现可解析的 IDEA Gradle JVM 配置。

实施时显式区分 Gradle daemon JVM、Java 17 编译 toolchain 和游戏 Java 17 launcher。若所选插件需要由 Java 21 启动 Gradle，可保留 daemon Java 21，同时以 Java 17 编译和运行游戏；不依赖当前 PATH 偶然取到的版本。

## 3. 已确认的 Java 17 兼容修改

以下文件当前直接使用较新的 Java API：

- `common/src/main/java/com/shiroha/mmdskin/stage/domain/timeline/PropTimeline.java:262,266`：List `getLast()`。
- `common/src/main/java/com/shiroha/mmdskin/render/effectpack/FxGraphGlBackend.java:56`：List `getFirst()`。
- `common/src/main/java/com/shiroha/mmdskin/render/effectpack/FxConstantExpression.java:110,138`：List `getFirst()`。
- `common/src/main/java/com/shiroha/mmdskin/render/effectpack/RayMaterialImporter.java:17`：List `getFirst()`。
- `common/src/main/java/com/shiroha/mmdskin/render/material/ShadowFxAlphaBinding.java:77,89`：List `getFirst()`。
- `common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java:21`：`Math.clamp`。
- `common/src/main/java/com/shiroha/mmdskin/render/material/ToonTransparencySettings.java:14`：`Math.clamp`。

List 改为保留空列表语义的下标访问；整数 clamp 改为等价 min/max 或既有工具方法。`Deque.removeFirst()`、`Comparator.reversed()` 在 Java 17 可用，不机械替换。测试和 tools 内的 Java 源也要在目标编译器下检查。

## 4. CI、测试选择和探针迁移

- `.github/workflows/build-mod.yml:5`：push 分支过滤固定为 `feat/1.21.1-neoforge`，需加入迁移维护分支或使用明确矩阵。
- `.github/workflows/package-platform.yml:47,57,74-75`：JDK、构建任务、产物名和路径固定到 Java 21 / NeoForge。更新为目标平台任务与 Java 17 编译/运行配置。
- `.github/workflows/tests.yml:66`：测试环境固定 Java 21，需验证公共测试真正以 Java 17 运行。
- `tools/ci/native_ci.py:144-168,234-267`：现有分支只有 Fabric/NeoForge；非 Fabric 分支检查 `neoforge.mods.toml` 和 Mojmap 内容。新增明确 Forge 分支，验证 `mods.toml`、Mixin config/refmap、生产映射、class major、重定位依赖及原生库内容。
- `tools/testing/plan.py:43`：平台路径目前只认识 fabric/neoforge；加入 forge 的路径路由并同步必要的测试。当前未知路径会保守扩大测试，迁移时保留这个安全后备。
- `tools/iris-toon-probe`、`tools/timeline-editor-probe`、`tools/translucent-performance` 等有硬编码的 Zulu 21、NeoForge 缓存结构、类文件路径和 1.21.1 游戏实例；迁移关键探针到目标 JDK/类路径/实例，避免拿旧实例结果充当 1.20.1 验收。
- 原生多平台构建任务默认保留。先验证 Windows x64 完整链路，再复用现有平台矩阵完成最终产物验证。

## 5. 官方资料与证据边界

已核实的官方资料：

1. NeoForge 用户指南对 1.20.1 推荐 Forge：
   https://docs.neoforged.net/user/docs/
2. Forge 1.20.1 的 Java 17 基线：
   https://docs.minecraftforge.net/en/1.20.1/gettingstarted/
3. ModDevGradle Legacy Forge 支持范围、SRG reobfuscation、Mixin refmap 和依赖重映射：
   https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md
4. Legacy Forge 插件 2.0.78 的发布页：
   https://plugins.gradle.org/plugin/net.neoforged.moddev.legacyforge/2.0.78
5. Forge 1.20.1 SimpleChannel 和可选对端规则：
   https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/
6. Forge 1.20.1 mods.toml：
   https://docs.minecraftforge.net/en/1.20.1/gettingstarted/modfiles/

官方主分支文档可能包含新于 2.0.78 的功能；不把文档当前示例等同于该固定版本已验证的构建脚本。依赖候选、候选 API 替代和工作量均须由实施结果校准。

本轮未编译、未运行单元测试、未生成迁移 JAR、未启动游戏。下列后续扫描结论与验收步骤属于实施依据，不代表已在 1.20.1 验证通过。

## 6. 分阶段实施与完成门槛

### P0：冻结范围与建立可比较基线

- 从上述当前 HEAD 建迁移分支，记录源码提交、原生库哈希和功能清单。
- 将历史分支固定到上述 SHA，仅提取所需 API 和签名参考。
- 锁定 Forge、Fabric API、Cloth Config、ModMenu，以及光影/VR/枪械/女仆目标测试版本。
- 验证 Gradle 插件配置、双工具链、common Mojmap 输出与 Forge 类路径；记录问题发生在依赖解析、配置还是源码编译阶段。
- 保存相同模型、动作、光影和场景的 1.21.1 参考画面与帧数据，用于回迁后的差异判断。

完成门槛：明确依赖锁定记录、目标功能清单、可复现的 wrapper 配置结果；不以空壳模组能启动代表当前功能已迁移。

### P1：公共代码和 Java 17 编译

- 完成第 3 节的 Java API 修改及实际编译暴露的其他 Java 17 问题。
- 在 MC 1.20.1 的官方 mappings 下修正公共 Minecraft API，包括资源标识、渲染、GUI、计时和实体接口。
- 业务域、序列化、VMD/PMX/VRM、场景/道具数据格式和 JNI 方法签名保持当前语义。
- 先跑 `:common:compileJava`，再跑 `:common:testQuick`，通过后完成公共测试全量；修正测试夹具的版本假设，不删除现有行为断言。

完成门槛：公共代码和测试以目标 Java 17 编译/运行，已有业务行为通过；所有失败有分类和处理记录。

### P2：Forge 生命周期与网络

- 建立 Forge 模块、入口、事件监听、客户端配置入口、资源重载和专用服务端初始化。
- 以 1.20.1 网络 API 接入当前消息内容、权限和会话生命周期；保留客户端必需/服务端可选行为。
- 检查消息长度上限、sender 校验、线程切换、无模组对端、入服初始同步和退出清理。
- 可与 P3 的渲染适配并行，前提是两者修改文件集合分离，共享 common API 变更由主代理整合。

完成门槛：Forge 客户端/专用服务端启动，安装和未安装服务端模组的连接矩阵通过；双客户端模型、动作、舞台消息实际互通。

### P3：Mixin、渲染和 GUI

- 按目标 1.20.1 类/方法描述符重建注入点，包括 camera、player renderer、world render、手持物、输入和界面。
- 适配旧顶点提交、BufferBuilder/BufferUploader、ShaderInstance 和矩阵栈接口；验证 CPU/GPU 后端与 GL 状态恢复。
- 保留当前透明排序、玻璃透射、阴影、描边、场景/道具和材质能力，逐项核对宿主接口。
- 验证所有 GUI，包括选择器、轮盘、配置、时间线、纸娃娃预览，以及窗口缩放和鼠标/键盘输入。
- 在无第三方兼容模组的基线环境先完成核心渲染，再进入兼容矩阵；最终验收仍覆盖既有兼容功能。

完成门槛：生产 JAR 无 Mixin apply/injection 错误；CPU/GPU、第一/三人称、预览/场景均完成游戏内验证，有画面/帧数据证据。

### P4：第三方兼容

- Forge：锁定 Oculus + Embeddium 的匹配组合，核对反射成员、Mixin target、Shader/FBO/透明阶段；Fabric：锁定 Iris + Sodium 组合。
- 对选定发行包分别核实新旧包名，避免按 Minecraft 版本统一替换前缀。官方 Oculus `1.20.1-new` 和 Iris `1.20.1` 分支当前都出现 `net.irisshaders.iris`；这不保证任意历史发行包相同。
- 当前 `IrisCompat.java:67` 的主 API 检测使用 `net.irisshaders.iris.api.v0.IrisApi`，阴影状态另外带旧包名候选；应按选定发布包验证各反射调用，不能只验证“检测到光影已开启”。
- 当前 `neoforge/src/main/resources/mmdskin-neoforge-refmap.json` 为手工空映射，Forge 必须生成实际 refmap；不可原样复制。
- Vivecraft：锁定版本后检查玩家姿态、双眼时序、手臂/手持物、第一人称裁切。
- TaCZ：锁定 Forge 1.20.1 生产包，检查瞄准、换弹、左右手和动作接口；可复用旧分支的已发布包重映射经验。
- 女仆：Forge 和 Fabric 分别确认目标模组和移植版是否存在、依赖及实体渲染签名，不把不同实现当作同一个兼容目标。

完成门槛：每个承诺支持的组合有明确版本、加载日志和实际场景结果；未验证组合在报告中单列，不冒充已完成。

### P5：Fabric 与双平台一致性

- 更新 Fabric 网络、入口、事件回调、Mixin 和元数据到选定 1.20.1 API。
- 复用已验证的 common 行为；对比两平台持久化数据、网络消息内容和客户端功能。
- 若 P2/P3 中存在可独立实施的 Fabric 文件任务，可提前并行；发布前仍需完成独立生产 JAR 验证。

完成门槛：Fabric 的 remapJar、refmap、实际加载和核心功能通过，两个平台不出现数据格式或行为分叉。

### P6：打包、CI 和回归交付

- 完成第 4 节的 CI 和探针适配。
- Forge 对最终 Shadow 内容执行 SRG reobfuscation；Fabric 执行 remapJar。
- 检查最终 JAR 中 common 类、平台入口、mod 元数据、非空 refmap、语言/默认动作资源、原生库、许可证和私有化 jlayer。
- Forge 资源 pack.mcmeta 按 1.20.1 要求检查；当前 NeoForge 文件已经是 `pack_format: 15`，不按分支名称盲目修改。
- 在干净安装目录验证最终发布 JAR，并确认包内不包含用户确认记录 `usage_notice.json`。
- 先 Windows x64 端到端验收，再恢复现有其他原生平台的打包与静态验证；逐平台记录实际启动覆盖程度。

完成门槛：全部必要测试和目标场景通过；产物哈希、依赖矩阵、已知问题和游戏内证据齐全。

## 7. 回归矩阵

以下为实施时的验收要求，本轮未执行：

- Java：先 `testQuick`；最终选择 `:common:test` 或 `:common:testAll` 其中一种完整执行，避免相同测试重复跑。
- Rust：保持现有引擎；最终用既有 animation/model/physics/vr/core 分组覆盖一次。只有代码或 ABI 发生变化时再增加相应针对性验证。
- JNI：使用 Java 17 真实加载并操作发布包中的原生库，覆盖模型/动作句柄创建销毁、DirectByteBuffer、骨骼和相机数据、重复加载释放。Mock 测试不替代此步骤。
- 渲染：CPU/GPU、Toon/默认、光影关闭/开启、透明材质/玻璃/水、描边、阴影、材质效果包、F3+T/窗口缩放/世界切换。
- 坐标：原点/非零坐标，玩家和相机旋转、缩放、第一/三人称、背包/纸娃娃预览，确保相机变换只应用一次，阴影与碰撞箱位置一致。
- 动作/物理：静止、步行、游泳转站立、跳跃、骑乘、长链/尾巴/裙摆、动作切换、倒放/seek、物理重置。
- 场景/道具：加载/保存舞台，外部亲绑定/解绑/重绑，父子层级，帧 0 与切换帧边界，相同帧多骨骼绑定，场景与 GUI 预览隔离。
- 沿用 `tools/prop-bone-probe`、`tools/stage-prop-probe`、`tools/timeline-editor-probe` 的实际 JNI/渲染探针；检查样本中 3098/3099、3167/3168、3370/3375 及相邻帧的连续性。
- 网络：两客户端舞台同步、掉线/重连、成员退出、停止/seek；客户端已装模组 + 服务端未装模组仍可正常加入。
- 界面：模型选择、轮盘、Cloth Config、时间线编辑、资源树、预览、输入法/键盘和 GUI scale。
- 第三方：各目标组合分别验证“未安装”“安装未开启”“实际使用”，避免可选依赖缺失导致启动失败。

历史渲染迁移文档仅作问题线索。矩阵组合、ShaderInstance 行为和全局状态恢复以目标源码及 1.20.1 运行结果为准，不机械反转旧修复。

## 8. dsf 源码扫描结果及主代理复核

以下路径均相对仓库根目录。行号对应扫描基线；候选方案仍需编译和运行验收。

### 8.1 网络：替换平台传输，保留业务协议

确定修改：

- `fabric/src/main/java/com/shiroha/mmdskin/fabric/network/MmdSkinNetworkPack.java:31-40`：
  `CustomPacketPayload`、`Type`、`StreamCodec`；同文件 92、115、132 行涉及解码、编码和载荷类型。
- `fabric/src/main/java/com/shiroha/mmdskin/fabric/register/MmdSkinRegisterCommon.java:20-24`：
  `PayloadTypeRegistry` 和 payload receiver。
- `fabric/src/main/java/com/shiroha/mmdskin/fabric/register/MmdSkinRegisterClient.java:101-103`：
  客户端 payload receiver。
- `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/network/MmdSkinNetworkPack.java:31-40`：
  相同载荷定义。
- `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/register/MmdSkinRegisterCommon.java:30-36`：
  `RegisterPayloadHandlersEvent`、`PayloadRegistrar`。

1.20.1 候选方案：Forge 使用 `SimpleChannel` + `FriendlyByteBuf`；Fabric 使用 ResourceLocation 通道与 buffer 收发。旧分支中 Forge `MmdSkinRegisterCommon.java:22-29`、`MmdSkinNetworkPack.java:122-130` 和 Fabric 网络包 `:40-44` 有直接参考。

`common/src/main/java/com/shiroha/mmdskin/player/sync/ClientNetworkBindings.java:14-31` 已隔离发送接口，继续复用消息业务、opcode、UUID 校验、舞台会话和缓存清理。旧分支没有当前完整功能协议，不直接复制旧包处理器覆盖新逻辑。不要求 1.20.1 与 1.21.1 跨游戏版本联机。

### 8.2 ResourceLocation 与测试数据组件

`ResourceLocation.fromNamespaceAndPath` 需改为 1.20.1 构造形式，已定位：

- 两个平台的 `MmdSkinNetworkPack.java:35`。
- `common/src/main/java/com/shiroha/mmdskin/render/entity/MmdSkinRenderer.java:30`。
- `common/src/main/java/com/shiroha/mmdskin/compat/maid/ui/VerifiedMaidDirectory.java:24`。
- `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/maid/MaidContainerGuiHandler.java:27`。

主代理用本机 1.20.1 Mojmap 缓存 JAR 的 `javap` 确认存在公开的 `ResourceLocation(String,String)` 和 `ResourceLocation(String)` 构造器。

`common/src/test/java/com/shiroha/mmdskin/compat/tacz/TaczFirstPersonFrameSnapshotTest.java:4,8,121-135` 使用 `CustomData` / `DataComponents.CUSTOM_DATA`。目标测试应改用 NBT 表达相同的“同物品动态标签变化”行为；`ItemStack.getOrCreateTag()`、`setTag()` 已在本机目标 JAR 中确认存在。生产快照匹配逻辑仍可保持。

### 8.3 DeltaTracker 与世界/相机渲染

确定受影响的入口与公共链路：

- `neoforge/src/main/java/com/shiroha/mmdskin/mixin/neoforge/GameRendererMixin.java:32-65`：
  render/renderLevel 的 `DeltaTracker` 参数和调用目标描述符；Fabric 对应 Mixin 同步适配。
- 同文件 `:82-83`：
  `player.blockInteractionRange()`、`entityInteractionRange()`；目标可参考旧分支 `gameMode.getPickRange()` / `hasFarPickRange()`，并核对 Forge reach 扩展以保留交互距离修正。
- `neoforge/src/main/java/com/shiroha/mmdskin/mixin/neoforge/LevelRendererMixin.java:35-38,58-61`：
  新 renderLevel 参数、透明渲染起止和独立模型预求值注入；Fabric 对应实现也需适配。
- `common/src/main/java/com/shiroha/mmdskin/player/render/PlayerWorldEvaluationPrepass.java:5,16,21` 和
  `common/src/main/java/com/shiroha/mmdskin/render/pipeline/WorldEntityEvaluationPrepass.java:18,54,61`：
  公共预求值接口也接收 DeltaTracker，必须沿调用链一起替换。
- `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/register/NeoForgeClientRuntimeHooks.java:122`：
  GUI 事件 partial tick 从对象访问改为 Forge 目标浮点时序；Fabric HUD 回调同步核对。

主代理已通过本机 1.20.1 Mojmap 缓存 JAR 确认以下签名，必须区分两个类：

```text
GameRenderer.render(float, long, boolean)
GameRenderer.renderLevel(float, long, PoseStack)
LevelRenderer.renderLevel(PoseStack, float, long, boolean,
                          Camera, GameRenderer, LightTexture, Matrix4f)
Minecraft.getFrameTime() -> float
Minecraft.getDeltaFrameTime() -> float
```

缓存 JAR 包含历史开发依赖补丁，只用于确认上述目标接口，不代表干净 Forge 生产环境已验证。`getTranslucentTarget()`、透明阶段 profiler/Mixin 落点、Iris 介入时机仍需核对锁定 Forge 和光影包，不能仅改入参后宣称完成。

### 8.4 矩阵和相机 roll：最高视觉风险

- `common/src/main/java/com/shiroha/mmdskin/render/backend/BaseModelInstance.java:608-618`：
  当前显式组合相机视图和实体 PoseStack，且阴影 pass 单独处理。旧 1.20.1 实现直接使用 PoseStack，说明这里必须重新确认矩阵来源。
- 正式修改前分别检查玩家、独立实体、场景、GUI 预览和阴影 pass 的调用上下文。候选是 1.20.1 使用已有完整视图矩阵的 PoseStack；不得把所有路径统一套用同一个猜测或简单恢复旧代码。
- 旧分支设置过 `INVERSE_VIEW_ROTATION_MATRIX`；是否需要补回以目标 ShaderInstance uniform 和光照/雾运行结果为准。
- `neoforge/src/main/java/com/shiroha/mmdskin/mixin/neoforge/CameraMixin.java:31-33,63`：
  当前使用 NeoForge 的三参数 `Camera.setRotation(yaw,pitch,roll)`；本机 1.20.1 目标类只确认双参数版本。
- 候选实现：使用现有 `StageCameraOrientation.applyRoll`，参考旧 Forge CameraMixin 同步修改 quaternion 和 forwards/up/left 等方向数据，保留当前眼骨骼锚点、正面第三人称 pivot 和 VR 相机处理。

验收必须覆盖矩阵只应用一次、转动相机不发生绕原点漂移、身体朝向/缩放一致、阴影 pass 不混入主相机矩阵，以及 stage camera roll 的实际画面。

### 8.5 GUI、顶点和背包预览

- `neoforge/src/main/java/com/shiroha/mmdskin/mixin/neoforge/InventoryScreenMixin.java:14,19` 和
  `common/src/main/java/com/shiroha/mmdskin/ui/paperdoll/PaperDollRenderer.java:351`：
  当前背包实体渲染参数包含新的浮点区域/Vector3f；1.20.1 描述符需同步更改。
- 本机目标签名已确认：
  `InventoryScreen.renderEntityInInventory(GuiGraphics,int,int,int,Quaternionf,Quaternionf,LivingEntity)`。
- `common/src/main/java/com/shiroha/mmdskin/ui/wheel/AbstractWheelScreen.java:63`：
  `renderBackground` 改为目标 `Screen.renderBackground(GuiGraphics)`；检查其余 Screen 子类的 override。
- `fabric/src/main/java/com/shiroha/mmdskin/fabric/config/PlayerReplacementModelPickerScreen.java:112`、
  `MobReplacementModelPickerScreen.java:113`，以及 NeoForge config 目录中同名文件：
  也有四参数 `renderBackground`；迁移后的平台配置屏需要一起处理。
- 同文件 `:512,520,534,539`：
  `Tesselator.begin`、`buildOrThrow`、`addVertex/setColor` 改为旧顶点链路。
- 目标已确认：`Tesselator.getBuilder()` 后 `BufferBuilder.begin(Mode,Format)`、
  `vertex/color/uv/uv2/normal/endVertex`，以及 `BufferBuilder.end()` 返回 `RenderedBuffer`，交由 `BufferUploader.drawWithShader`。

自定义 Toon GL program 由 `render/shader/ShaderCompiler.java:43-57` 直接创建，不需要为迁移重写全部着色器。ShaderInstance 的 uniform、采样器和宿主状态仍需检查，重点是矩阵和 GL 状态恢复。

### 8.6 低风险可复用部分及第三方范围

dsf 对比历史分支发现，Entity 的眼位置/视向、MouseHandler 的 onPress/turnPlayer、KeyboardInput.tick、PlayerRenderer.render、主要手持物方法和 ItemRenderer.renderStatic 有同源 1.20.1 参考。它们应优先保留业务逻辑，但最终描述符和重映射仍需生产包验证。

扫描未发现需要迁移的自定义 EntityType/Item DeferredRegister 链路；现有实体替换主要注册现有 EntityType 的渲染器，降低实体/物品注册工作量。

Iris 深度耦合集中在以下 Mixin 和公共反射代码：

- `mixin/*/compat/IrisShadowEvaluationMixin.java`：ShadowRenderer。
- `mixin/*/compat/IrisWaterShaderMixin.java`：TransformPatcher / patchSodium。
- `mixin/*/compat/IrisWaterStateMixin.java`：SodiumShader。
- `compat/iris/IrisWorldTransparencyTarget.java`、`IrisToonMetadata.java`、
  `IrisTerrainShadowReplay.java`：管线目标、内部元数据、阴影重放等反射调用。

当前没有 `isModLoaded("iris")` 式门控，主入口使用 API 类检测，因此不能直接归因为 Oculus 的 mod-id 不同。Sodium/Embeddium 的风险主要通过上述 Iris 内部接口传递；所有承诺功能最终都需完成相应适配，不能通过永久关闭透明/阴影兼容来验收。

Vivecraft 和 TaCZ 优先参考旧分支 1.20.1 注入点，合并当前双眼时序、双臂 native target 和动作行为。YSM 还需针对选定 1.20.1 发行版检查混淆类名；女仆继续保留当前模型所有权、轮盘、网络和缩放行为。

### 8.7 Rust / JNI 复用边界

- `common/src/main/java/com/shiroha/mmdskin/NativeFunc.java:1-4` 使用标准 ByteBuffer 边界。
- `common/src/main/java/com/shiroha/mmdskin/NativeLibraryLoader.java:71-89` 提供原生库加载。
- `rust_engine/src/jni_bridge/native_func.rs:40-41` 以 `Java_com_shiroha_mmdskin_NativeFunc_*` 导出。
- `rust_engine/Cargo.toml` 未依赖 Minecraft 或其加载器。

据此，MC 回迁没有要求重写 Rust 引擎的直接依赖。继续使用当前 JNI 导出、矩阵/顶点内存布局、PMX/VMD/VRM、物理和外部亲采样实现；不要取回旧分支的 NativeFunc 清单。若发现 Java 17 启动、native 生命周期或实际缓冲布局问题，再以实际证据决定是否修改引擎。

## 9. 估算与实施选择

以下为 dsf 根据静态扫描给出的初步人日估算，未经过目标编译校准，不代表代理运行时间或交付承诺：

- Forge 可运行基线：约 5—8 人日。
- 主要功能、第一人称、舞台和 GUI 回归：累计约 8—13 人日。
- 加上深入的光影、TaCZ/VR 兼容和视觉回归：Forge 累计约 12—21 人日。
- 正式 Fabric 交付：在 Forge/common 基础上额外约 3—5 人日。

这些区间是逐步扩大的累计范围，前三项不能相加。第三方包版本和渲染差异是主要波动来源，P0/P1 之后重新估算。

本计划默认采用 Forge 优先、Fabric 第二阶段；保留当前功能，双版本使用独立维护分支，不在 common 中铺设大量运行时版本判断。如果只交付 Forge，可缩减 P5，但需明确记录为范围变更。若要求 NeoForge 1.20.1，增加独立依赖与运行验收，不能用 Forge 构建成功代替。

源码复用程度按职责评估；工作量在目标编译后校准。主代理已复核关键源码，并以本机目标 JAR 确认 GameRenderer、LevelRenderer、GUI、Camera 和顶点接口签名；后续按实际失败和场景验收推进。
