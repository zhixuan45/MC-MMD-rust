# 1.21.1 Toon 光影兼容

基于 `feat/1.21.1-neoforge`，适用于 Fabric 与 NeoForge 共用的 Iris Toon 路径。

## 已核查的最新正式版

2026-10-11 从 Modrinth 版本 API 获取各项目最新 `release`，下载并与 API 的 SHA-512 校验值比较。用户上传的 Unbound 和 BSL 与下载文件逐字节一致。

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

## 适配依据

原实现根据 Complementary/BSL 包名和固定版本号决定是否适配。现在包名仅供上层显示，不参与适配器选择；根据 Iris 预处理后的活动 GLSL 光照函数参数契约、颜色编码和各缓冲的写入语义识别协议。同样的源码即使改名、缺少名称或使用误导性名称，也得到相同结果。未知输出、缺失源码证据、布局错位或未确认的光照函数仍会回退。

- 保留 Complementary 4.x 和 BSL 8.x 的已验证协议。
- Complementary 5.x 使用 `DoLighting`，其颜色没有 4.x 的 `pow(albedo, 2.2)` 编码。复用当前程序中的光照函数、设置和太阳方向。接入 PR #7 的统一 Toon 色阶、高光和边缘光函数，保持眼部发光及描边抗锯齿功能。
- 5.x 的 colortex3 是 `1 - translucentMult`，colortex6 是材质数据，colortex4 是世界空间法线。非 PBR 的 Toon 模型输出原包中性的实体材质标记 254/255、零光滑度与零反射天空因子。
- 半透明实体的 colortex13 为 `sqrt(length(viewPos) / far)`。解析同时支持 `DRAWBUFFERS` 和带多位目标编号的 `RENDERTARGETS`；主体同步视图逆矩阵和远平面；沿用 PR #7 的独立颜色描边 pass，避免修改材质缓冲。描边颜色采用与主体相同的编码，并支持覆盖率合成的预乘 alpha。
- BSL 10.x 的 `entityMask` 随 alpha 变化，发光实体使用独立标记；保留混合模式的线性/平方根编码和 colortex1 体积光颜色。
- BSL 的 `GetLighting` 复用原包的阴影坐标变形、日夜/天气/维度颜色、动态手持灯与彩色方块光，直射光在阴影采样后应用 Toon 色阶。下界不要求原程序已优化掉的太阳 uniform。
- 输出布局已确认时，照明源码提取、编译或 uniform 同步失败会回退到通用环境光，保留 Toon 色阶、高光、边缘光与描边。通用层读取可选的标准 Iris 太阳方向、雾色与天气，结合游戏天空/方块光等级；缺失或类型不匹配的输入每帧恢复默认值。它不猜测其它光影的阴影纹理变形，也不猜测未确认的材质缓冲格式。
- 瞳孔发光在线性颜色空间与受光颜色相加，眼白和黑瞳遮罩保持不变，避免发光强度随 gamma 编码被指数放大。自动曝光仍会响应画面中的 HDR 发光区域。
- 相同布局下不同透明度输出也参与程序缓存键，防止重载设置后复用错误变体。

## 验证与复现

Java 回归测试覆盖新旧协议、任意/空/误导包名、未知写入、多位目标的描边定位、透明度缓存、实体标记和未确认光照的拒绝路径。

可选探针读取用户自行下载的 ZIP，不在仓库内分发光影。使用 Iris 的 Jcpp 预处理器，在主世界、下界、末地验证 entities、hand、entities_translucent 和 entities_glowing：

- 每个 Complementary 版本 4 组选项，共 48 个程序：默认、关闭反射和细节、自定义 PBR、IPBR 与高反射/细节。
- BSL 17 组选项，共 204 个程序：默认以及 ALPHA_BLEND、ADVANCED_MATERIALS、MCBL_SS、TAA_SELECTIVE 的 16 个组合。
- 合计 300 个程序。Mesa OpenGL 4.5 下，主着色器及描边均编译、链接成功；独立输出程序回读验证颜色、alpha、法线极点与旋转、材质标记、体积光颜色、半透明深度；活动光照 uniform 与原包一致。同一程序还以四种改名/空名重复解析；描边的直通/预乘颜色也通过回读验证。

探针用 Iris 1.8.14-beta.1 的预处理器。原包来源程序在链接比较前将 GLSL 版本提升为 330 compatibility，以支持旧声明版本下的新选项语法；这不是完整的 Iris 游戏运行测试。

Linux 复现需要 JDK 21、Python 3、libEGL/libGL 与 Mesa surfaceless 支持。`PROBE_CP` 包含 Iris JAR、Iris 内嵌的 jcpp JAR，以及 log4j-api、slf4j-api、Guava；这些可以从现有 Minecraft 安装或 Gradle 缓存取得。执行：

```bash
PROBE_CP='/absolute/path/iris.jar:/absolute/path/jcpp.jar:/absolute/path/log4j-api.jar:/absolute/path/slf4j-api.jar:/absolute/path/guava.jar' \
  bash tools/shaderpack-probe/run.sh \
  /absolute/path/ComplementaryUnbound_r5.9.3.zip \
  /absolute/path/ComplementaryReimagined_r5.9.3.zip \
  /absolute/path/BSL_v10.1.8.zip
```

生成的预处理源码和验证文件仅写入忽略的 `build/shaderpack-probe/`。这些检查不覆盖真实 Minecraft 场景、所有光影设置、GPU 驱动及第三方模组组合。MMD 模型继续使用中性的 PBR 输出；未为其添加光影材质贴图解析。

通用照明回退的实际 Java/GL 路由可单独复现：

```bash
bash tools/iris-toon-probe/run.sh --environment /absolute/path/iris.jar /absolute/path/lwjgl-egl-3.3.3.jar
```

该探针验证可选输入和错误类型、缺失参数、照明编译失败、变体缓存、GUI 与发光控制。设置 `PROBE_BSL_SOURCES` 为上述 BSL 源码探针生成的 `sources` 目录，还会验证生产 BSL 照明的白天、夜晚、洞穴、火把、雨天及不透明遮挡；遮挡时同步设置两张阴影深度图，避免把不透明方块误模拟成透光材质。`PROBE_NATIVE_DIR` 可指定未放入 Gradle 缓存的 LWJGL Linux 原生 JAR 目录。
