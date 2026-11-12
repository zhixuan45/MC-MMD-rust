# Forge 1.20.1 迁移验证

日期：2026-11-13。实施分支：`codex/port-1.20.1`。

## 范围与保留基线

- 从 `feat/1.21.1-neoforge` 的 `2fc8a8aad271a617de0e87ea55e06589820ccd6b` 迁移当前实现；原分支引用保持不变。
- 当前 Gradle 构建集合为 `common/forge`。Fabric 与 NeoForge 源码保留，Fabric 1.20.1 为后续阶段。
- 新 Forge 平台从当前 NeoForge 装配逻辑适配；旧 `1.20-vr` 仅作为 API 参考，没有覆盖当前业务实现。
- Rust 引擎源码未修改。保留当前 PMX/VRM、动画/物理、CPU/GPU、舞台/道具、材质/透明/描边、女仆、TaCZ、VR、YSM 等功能链路。
- 五个 dsf（`auto/deepseek-v4.1-flash`）子代理按 Forge 装配、Mixin、渲染、公共 UI/API/测试、CI 分工；主代理整合并统一构建验证。

## 工具链与发布路径

- Minecraft `1.20.1`，Forge `47.3.0`，Cloth Config Forge `11.1.136`。
- Gradle Wrapper `8.11.1`，Legacy Forge ModDev `2.0.78`，Shadow `8.3.6`。
- 本机 Gradle daemon 使用 `C:\Program Files\Zulu\zulu-21`；Java 编译 toolchain 和开发游戏运行使用 Zulu 17，模组 class major 为 61，Mixin 为 `JAVA_17`。
- 发布包为 `forge/build/libs/mmdskin-forge-1.10alpha-1.20.1.jar`。包含 common、重定位 jlayer、Windows x64 原生 DLL、许可证及非空 refmap，经过 `reobfShadowJar` 的 SRG 重映射。
- 开发运行使用独立目录 `forge/run/1.20.1-client` 和 `forge/run/1.20.1-server`，避免覆盖旧运行目录。
- CI 改为本分支 Forge 构建，保留十个平台的原生构建矩阵。本机未执行跨平台矩阵。

## 已执行验证

### Java 和 Forge

PowerShell：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Zulu/zulu-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
.\gradlew.bat :common:test :forge:build --stacktrace
```

主体迁移构建成功，Java 全量 499 项：498 通过、1 跳过、0 失败、0 错误。Oculus API 复核与 Vivecraft SRG 候选修正后再次全量执行，最终 502 项：501 通过、1 跳过、0 失败、0 错误。

重点覆盖模型视图矩阵、TaCZ 相机根变换、舞台道具、透明材质、效果包和 GUI 公共逻辑。构建执行 Forge 编译、Mixin annotation processor、Shadow、SRG 重映射和发布包生成。

日志：`.codex-forge-build-2.log`、`.codex-forge-final-build.log`。跳过项为 `FxPackLibraryTest.importsRealTex1AndPreservesItsActiveSemantics()` 的外部资源条件测试。

### Rust

```powershell
python tools/testing/rust_batches.py --groups all --offline
```

171 项通过：animation 36、model 83、physics 15、vr 30、core 6、serial 1。日志：`.codex-forge-rust-tests.log`。

### 真实 DLL / JNI 道具探针

```powershell
python tools/prop-bone-probe/run.py --jdk 'C:/Program Files/Zulu/zulu-17'
python tools/stage-prop-probe/run.py --jdk 'C:/Program Files/Zulu/zulu-17' --frame-scale 60
```

- 骨骼道具探针：CPU/GPU 均通过，覆盖窗口切换、解绑、逆向 seek、frame 0、摆放等。
- 舞台道具探针：CPU/GPU 的 snap、keepWorld、placed 均通过，覆盖 frame 0 至 18000，最大矩阵误差 `0.000000954`。
- 舞台探针补齐 Minecraft SharedConstants/Bootstrap 初始化；工具默认 JDK 已统一为 17。
- 日志：`.codex-forge-jni-probe.log`、`.codex-forge-stage-probe-fixed.log`。

### 客户端启动

```powershell
.\gradlew.bat :forge:runClient --stacktrace
```

Forge 47.3.0 / Java 17.0.20 / NVIDIA OpenGL 4.6 开发客户端成功启动；MMD 初始化、默认资源释放、真实原生 DLL 加载、求解器配置、资源重载和纹理图集初始化成功。必需的原版目标 Mixin 没有注入失败。客户端正常退出，Gradle 返回 `BUILD SUCCESSFUL`。

日志：`.codex-forge-client-smoke.log`、`forge/run/1.20.1-client/logs/`。

另执行 `.\gradlew.bat :forge:runServer --stacktrace`，独立服务端完成 Forge/Java/Mixin 预启动并正常停在首次 EULA 提示，Gradle 返回 `BUILD SUCCESSFUL`。没有接受 EULA，没有启动世界；此项不作为服务端完整加载或联机验收。日志：`.codex-forge-server-smoke.log`。

### 工具与产物检查

```powershell
python -m unittest discover -s tools/testing -p 'test_*.py'
python -m py_compile tools/prop-bone-probe/run.py tools/stage-prop-probe/run.py tools/timeline-editor-probe/run.py
git -c core.fsmonitor=false diff --check
```

14 项 Python 工具测试通过，探针脚本语法检查通过，diff 无空白错误。

发布包使用 `tools/ci/native_ci.py` 的 `verify_jar` 进行指定 JAR 检查：元数据、非空 SRG refmap、Mixin manifest、Java 17、SRG 方法引用、jlayer relocation、许可证、用户私有数据排除、原生 DLL 架构与 staging 内容一致性。

## Oculus / TaCZ 接口复核

只读核对本机真实 `oculus-mc1.20.1-1.8.0.jar` 与 `tacz-1.20.1-1.1.6-hotfix.jar`，没有修改已安装游戏实例：

- Oculus LevelRendererAccessor 的地形层重放参数包括 PoseStack 和投影矩阵；使用当帧阴影视图填充 PoseStack。
- Oculus 1.8.0 无 `ProgramId.ShadowWater`；`ShadowRenderer` 直接使用 `ProgramSet.getShadow()`，重放的程序协议验证与之对齐。
- Oculus 1.8.0 无 `IrisRenderingPipeline.bindDefault()`；使用 `RenderTargets.createFramebufferWritingToMain/Alt`，按当前透明阶段和 colortex0 flip 集选靶。FBO 交给 Iris 的 RenderTargets 管理深度附件更新和销毁，并按 pipeline 缓存，维度切换及验证重置复用缓存。未以 Minecraft 主帧缓冲替代光影包颜色缓冲。
- 可选兼容 Mixin 以实际类与方法签名为准；Oculus 水材质绑定针对 `ExtendedShader`。
- Vivecraft 玩家渲染钩子保留开发环境 `render` 并增加生产 SRG `m_7392_` 候选，签名经本机 1.20.1 `namedToIntermediate.tsrg` 核对；未找到可用的 Vivecraft 实际 JAR，此项仍需 VR 场景验收。`renderVRHands` 为 Vivecraft 自定义方法，保留原名。

## 后续场景验收

本轮完成源码迁移、编译/包结构、公共逻辑和开发启动验证。以下仍需游戏场景验收：

- 在干净的生产 Forge 环境安装最终 SRG JAR，验证生产加载与全部注入。
- 实际模型、物理、GPU/CPU 画面、第一人称、纸娃娃、轮盘、舞台道具的游戏操作。
- Oculus 光影包下透明水体、半透明阴影、描边、光照效果的画面对照。
- TaCZ 持枪/瞄准、Vivecraft、YSM 和女仆实际联动。
- 专用服务器及多人模型/舞台同步。
- Linux、macOS、Android 等 CI 原生包矩阵。

## 最终回归与产物

- 最终命令：`.\gradlew.bat :common:test :forge:build --stacktrace`，`BUILD SUCCESSFUL in 26s`。
- 全量 Java：502 项，501 通过、1 跳过、0 失败、0 错误。
- 新增回归覆盖 Oculus 阶段颜色绑定、维度切换/重置时复用 Iris 托管 FBO，以及与实际 ShadowRenderer 一致的程序选择。
- 最终指定 JAR 的 `native_ci.verify_jar(..., 'forge', ..., 'windows-x64')` 通过。
- refmap：13 个类、32 个映射；没有 Fabric/NeoForge 平台 class。
- 发布包大小：`4,241,886` bytes。
- SHA256：`26f8b4dde1b64d402fac2d7a87146fe100ff45a6904dceb7beaee75d23deeb26`。
- 原 `feat/1.21.1-neoforge` 分支仍指向 `2fc8a8aad271a617de0e87ea55e06589820ccd6b`。
- 改动保留在当前工作区，没有自动提交或推送。
