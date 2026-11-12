# Complementary r5.9.3：Forge 1.20.1 实现与交付

日期：2026-11-13。实现分支：`codex/port-1.20.1`。

## 实现内容

1. 现代 Complementary 水程序按实际语义准入，支持已验证的 `03` / `036` / `0348` / `03648` 布局。按最后活动输出指令与逐槽赋值核对，保留原生 alpha、discard、深度及全部辅助 MRT，仅将主颜色加入共享透明队列。BSL 既有路径保留。
2. `complementary_modern:` Toon profile 进入材质合批变体；原包照明、输出编码和描边继续使用原有协议。
3. 地形阴影重放增加现代 Complementary 的单/双颜色协议。shadowcolor1 和 shadowcolor0、深度一并备份、恢复，正常/异常/重试均覆盖。
4. 现代接收端区分 full / opaque 深度，沿用原包随光照参数变化的透射解码。正柔化联合过滤深度和颜色，并按不透明几何覆盖率控制 Toon 补光。
5. 阴影准入允许共享头中已知且未使用的 sampler 声明，继续拒绝实际附件反馈读取、图像写入及未验证的额外阶段。低阴影质量不额外激活宿主已优化掉的颜色 sampler。
6. 探针按当前 1.20.1、Oculus 1.8.0、JDK 17 的真实转换器和生产 classpath 运行，提供 class 过期检查、独立输出目录和原包依赖哈希复用。

主要生产文件：

- `common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.java`
- `common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainShadowReplay.java`
- `common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainTransmission.java`
- `common/src/main/java/com/shiroha/mmdskin/compat/iris/TerrainShadowFrame.java`
- `common/src/main/java/com/shiroha/mmdskin/render/shader/ComplementaryToonLighting.java`
- `common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java`
- `forge/src/main/java/com/shiroha/mmdskin/mixin/forge/compat/IrisWaterShaderMixin.java`

## 分支清理与已有改动

专门的 deepseekflash 代理删除了当前 1.20 分支中 90 个受 Git 跟踪的 Fabric / NeoForge 1.21 平台文件，同步 README 和索引；旧 `fabric/build` / `neoforge/build` 产物保留。详细清单：`plans/minecraft-1.20.1-migration/legacy-cleanup-20261113.md`。

开始时的 66 个已修改跟踪文件已记录补丁和 SHA-256。任务涉及其中 5 个文件，其余 61 个保持逐字节不变。交叉涉及的文件采用局部修改，保留原来的 1.20 迁移、地形目标及 shader 改动。

记录：

- `build/diagnostics/complementary-1201-20261113/initial-worktree.patch`
- `build/diagnostics/complementary-1201-20261113/initial-dirty-hashes.json`
- `build/diagnostics/complementary-1201-20261113/preservation-audit.json`

## 测试与构建

构建环境：Zulu JDK 17，共享 Gradle 8.11.1 缓存，离线模式。实际渲染验证使用本机 Oculus 1.8.0 JAR、Minecraft 1.20.1 映射依赖和隐藏 OpenGL 上下文。

命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :common:test :forge:assemble --offline --console=plain
.\tools\iris-toon-probe\run.ps1 -Probe all -RequireWaterAdapter
```

Gradle：**512 项测试，511 通过、1 跳过、0 失败/错误**；Forge 编译、原生 release 构建及重混淆发布包生成通过。

GL 覆盖：

- 两个 r5.9.3 包 × 三维度 × 四类实体/手部程序，共 24 组主体、描边与合批登记。
- 两个包 × 三维度的真实水 shader，经过 Oculus 9 参数转换器、生产注入与实际链接；12 组 PMX 位于水前/水后的逐像素排序，辅助 MRT、原生深度和状态恢复。
- BSL 10.1.8 与两个现代 Complementary 包 × 三维度，共 9 组实际普通/合批提交；比较每个 MRT 的 RGBA 和深度，要求真实 batch window 数量大于零。
- 五套真实光影的 30 组主体/描边照明变体；现代两包各自验证低/中阴影质量的 4 个真实 shadow 输出契约。
- 单/双阴影颜色目标在 unsized RGBA、RGBA8、RGBA16F、RGBA32F 下的正常/异常/重试恢复。
- 现代玻璃的存储缩放、无色/彩色透射、墙后遮挡、柔化 0/1.5/4、低天空光、连续半影覆盖、补光、主体/描边与经典回退。
- 共享透明回归：箱子前后、低 alpha、孔洞、多 PMX 顺序、目标翻转、Iris final、无光影、空水批次、交叉水面、后续粒子与溢出覆盖。

真实包 GL 主要验证默认设置；高阶 image / voxelization 组合继续按活动程序副作用准入，不把未验证重放当作成功。高阶水布局另有协议单测和真实预处理检查。隐藏 GL 探针未覆盖完整游戏后处理、实际 Mixin 命中或真实模型视觉验收。

日志目录：`build/diagnostics/complementary-1201-20261113/`，包含 `build-test-final.log`、`gl-all-final.log` 及分项 GL 日志。

## 发布包

标准产物：`forge/build/libs/mmdskin-forge-1.10alpha-1.20.1.jar`

便于引用的副本：`build/distributions/mmdskin-forge-1.10alpha-1.20.1-complementary-r5.9.3.jar`

- SHA-256：`38d301073ce1df9677573a2c8f6f60bb4e39c3c9887433f9bb26af2d2f065e06`
- 大小：4,251,590 bytes。
- 900 个 class，最大 class major 为 61（Java 17）。
- Forge metadata、Mixin 配置和 refmap 均在包内；无 Fabric / NeoForge 平台类与 Fabric metadata。
- Windows x64 原生库与当前 release 构建一致，SHA-256：`0fa481902970407ee855d0a19ebc623dbd6ba68335f7e2a59dbfecab70101ee0`。

包内检查记录：`build/diagnostics/complementary-1201-20261113/artifact-audit.json`。

本轮没有替换游戏实例中的 JAR。实际游戏中还需核对捕获/合成日志和水、折射、反射、玻璃、阴影、粒子及热重载的视觉表现。

## 1.21.1 后续计划

已在独立 worktree 的 `feat/1.21.1-neoforge` 分支创建并提交计划，提交：`4238e86`。

仓库相对路径：

`plans/shader-compat/complementary-r5.9.3-1.21.1-plan.md`

该提交只包含计划文件。保留 1.21 的 Fabric / NeoForge 树、Java 21 和 Iris 专用签名；移植功能时不合并本轮的 1.20 平台删除和其他已有业务改动。
