# 1.21.1 Toon 光影兼容

基于 `feat/1.21.1-neoforge`，适用于 Fabric 与 NeoForge 共用的 Iris Toon 路径。

## 已核查的光影包版本

下表是 2026-10-11 回归使用的三个原版光影包。下载文件与当时 Modrinth API 提供的 SHA-512 校验值一致。

| 光影 | 版本 | 发布日期 | 下载来源 |
| --- | --- | --- | --- |
| Complementary Unbound | r5.9.3 | 2026-09-15 | [Modrinth 下载](https://cdn.modrinth.com/data/R6NEzAwj/versions/B1kyfoUZ/ComplementaryUnbound_r5.9.3.zip) |
| Complementary Reimagined | r5.9.3 | 2026-09-15 | [Modrinth 下载](https://cdn.modrinth.com/data/HVnmMxH1/versions/Bqen1mJX/ComplementaryReimagined_r5.9.3.zip) |
| BSL | 10.1.8 | 2026-09-21 | [Modrinth 下载](https://cdn.modrinth.com/data/Q1vvjJYV/versions/Y68yiql9/BSL_v10.1.8.zip) |

下载 ZIP 的 SHA-256：

```text
ComplementaryUnbound_r5.9.3.zip     98ac741dee294d2a3c82d153349148c3bb7fb688c30eb3b49c3b9ac37e968c5a
ComplementaryReimagined_r5.9.3.zip  fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38
BSL_v10.1.8.zip                    36b0a50ff7918bf10e9422c401d93779f9d0088a26acea975b435243f96930b5
```

另有隐藏窗口 GL 回归记录的组合包括 Complementary Unbound r5.1.1 + Euphoria Patches 1.2，以及 Complementary Unbound 和 Reimagined r5.9.3 各自搭配 Euphoria Patches 1.10.5。具体覆盖范围见下文。

## 适配依据

原实现根据 Complementary/BSL 包名和固定版本号决定是否适配。现在包名仅供上层显示，不参与适配器选择；根据 Iris 预处理后的活动 GLSL 光照函数参数契约、颜色编码和各缓冲的写入语义识别协议。同样的源码即使改名、缺少名称或使用误导性名称，也得到相同结果。未知输出、缺失源码证据、布局错位或未确认的光照函数仍会回退。

- 保留 Complementary 4.x 和 BSL 8.x 的已验证协议。
- Complementary 5.x 使用 `DoLighting`，其颜色没有 4.x 的 `pow(albedo, 2.2)` 编码。复用当前程序中的光照函数、设置和太阳方向。接入 PR #7 的统一 Toon 色阶、高光和边缘光函数，保持眼部发光及描边抗锯齿功能。
- r5.9.3 基础协议的 colortex3 是 `1 - translucentMult`，colortex6 是材质数据，colortex4 是世界空间法线。Euphoria Patches 的材质写入会随包版本和选项变化，按活动源码识别。非 PBR 的 Toon 模型输出原包中性的实体材质标记 254/255、零光滑度与零反射天空因子。
- 半透明实体的 colortex13 为 `sqrt(length(viewPos) / far)`。解析同时支持 `DRAWBUFFERS` 和带多位目标编号的 `RENDERTARGETS`；主体同步视图逆矩阵和远平面；沿用 PR #7 的独立颜色描边 pass，避免修改材质缓冲。描边颜色采用与主体相同的编码，并支持覆盖率合成的预乘 alpha。
- BSL 10.x 的 `entityMask` 随 alpha 变化，发光实体使用独立标记；保留混合模式的线性/平方根编码和 colortex1 体积光颜色。
- BSL 的 `GetLighting` 复用原包的阴影坐标变形、日夜/天气/维度颜色、动态手持灯与彩色方块光，直射光在阴影采样后应用 Toon 色阶。下界不要求原程序已优化掉的太阳 uniform。
- 输出布局已确认时，照明源码提取、编译或 uniform 同步失败会回退到通用环境光，保留 Toon 色阶、高光、边缘光与描边。通用层读取可选的标准 Iris 太阳方向、雾色与天气，结合游戏天空/方块光等级；缺失或类型不匹配的输入每帧恢复默认值。它不猜测其它光影的阴影纹理变形，也不猜测未确认的材质缓冲格式。
- 瞳孔发光在线性颜色空间与受光颜色相加，眼白和黑瞳遮罩保持不变，避免发光强度随 gamma 编码被指数放大。自动曝光仍会响应画面中的 HDR 发光区域。
- 相同布局下不同透明度输出也参与程序缓存键，防止重载设置后复用错误变体。

## 验证与复现

### 已有验证记录

- **Java 回归**覆盖新旧协议、未知缓冲写入、多位渲染目标、透明度缓存、实体材质标记和光照回退。运行方式见[测试分层与分批](../TESTING.md)。
- **2026-10-11 源码与 Mesa GL 基线**使用 Iris 1.8.14-beta.1 预处理三个原版包。三维度内，每个 Complementary 包覆盖 4 组设置、48 个程序，BSL 覆盖 17 组设置、204 个程序。共 300 个程序通过主体与描边的编译链接，并检查了颜色、alpha、法线、材质标记和深度输出。此结果对应当时的代码和设置。
- **2026-11-13 至 14 日隐藏窗口 GL 回归**使用 Minecraft 1.21.1 / Iris 1.8.14，覆盖原版包和 Euphoria Patches 组合。Unbound r5.1.1 + EP 1.2、Unbound r5.9.3 + EP 1.10.5 各运行了 `all`。Reimagined r5.9.3 + EP 1.10.5 分别运行了 `modern`、`water`、`shadow`。这些模式分别检查主体与描边、水面顺序和 MRT、阴影与玻璃透射。原版包和 EP 1.2 的用例细节见 [1.21.1 适配记录](../../plans/shader-compat/complementary-r5.9.3-1.21.1-plan.md)。
- **2026-11-19 Windows 复跑**在 Iris 1.8.14-beta.1 和 NVIDIA OpenGL 环境中通过下文的 `-Probe all`。覆盖五个原版 ZIP 的对应探针，本次运行未传入 Euphoria Patches 组合。

### 在 Windows 上复现 1.21.1 探针

使用 JDK 21 和安装了 Iris 1.8.14 的 Minecraft 1.21.1 实例。将表中的三个原版 ZIP 和旧协议回归用的 [`ComplementaryShaders_v4.7.1.zip`](https://www.curseforge.com/minecraft/shaders/complementary-unbound/files/4367398)、[`BSL_v8.4.02.2.zip`](https://www.curseforge.com/minecraft/shaders/bsl-shaders/files/6251106) 放在实例的 `shaderpacks/` 下，保留原文件名。若需验证 Euphoria Patches，请将对应 ZIP 的实际路径传给 `-ExtraPack`，每次运行使用一个组合。

从仓库根目录执行。将路径替换为本机的 JDK 和 Minecraft 实例目录。

```powershell
$jdk = 'C:\Path\To\JDK-21'
$env:JAVA_HOME = $jdk
$instance = 'C:\Path\To\Minecraft-1.21.1'
$packs = Join-Path $instance 'shaderpacks'

.\gradlew.bat -I tools/translucent-performance/validation.gradle :common:classes :fabric:classes :neoforge:classes :common:writeTranslucentProbeClasspath
.\tools\iris-toon-probe\run.ps1 -Probe all -Instance $instance -Shaderpacks $packs -Jdk $jdk
```

探针默认从实例的 `mods/` 中查找 Iris，并从构建 classpath 中查找映射后的 Minecraft JAR。自动查找不到时，运行[探针脚本](../../tools/iris-toon-probe/run.ps1)时可传入 `-IrisJar` 和 `-MinecraftJar` 指定路径。如果探针报 `Production class is stale`，重新执行 `.\gradlew.bat :common:compileJava --rerun` 后再运行探针。默认输出在 `build/iris-toon-probe/` 下。

### 游戏内验收

上述记录来自 Java 测试和离屏或隐藏窗口的 GL 检查。真实游戏验收仍需在目标加载器和驱动上检查模型在水面、玻璃和阴影前后的表现，以及切换维度、切换光影和重载资源后的画面。反馈时请提供光影包版本（含补丁版本）、Iris 版本、触发场景、截图或录像，以及相关日志。
