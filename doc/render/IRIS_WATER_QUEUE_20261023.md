# Iris / BSL 水面与 PMX 共享透明合成：r6 本地交付

2026-10-23；用户已批准实施方案，已反馈 r6 原报告的水面排序现象修复。
随后要求暂停合并并优化水面合成；r7 优化说明见 `IRIS_WATER_PERFORMANCE_20261023.md`，待游戏复测。
分支：`fix/1.21-iris-cross-pmx-transparency-debug`，基线 PR #21 `84cdb64`。
本地同时保留 r5 的批次提交后、水绘制前深度快照修复。

## 缺陷与改动

用户确认“水后的模型透明部分盖到水面前”。r5 只收集 PMX，水先混合、PMX 后合成，
所以不透明快照正确时仍会给出错误的水前后混合顺序。受控真实 GL 复现中，
后方白色 PMX/蓝色水的正确 RGB 是 `(0.30,0.275,0.7625)`，r5 回读却约为 `(0.55,0.525,0.7627)`。

r6 在验证 BSL 水输出契约后，将水的最终 RGBA/depth 加入原有链表，和 PMX 统一排序。

- `IrisWaterShaderPatcher`：仅适配经过校验的 `gbuffers_water`，复制 Iris 的源缓存结果，
  保留 native discard、输出与 late depth 语义，不注入 `early_fragment_tests`。
  目前接入 `TransformPatcher.patchSodium`；未验证 geometry/tessellation、显式 `gl_FragDepth` 等变体。
- `WorldWaterCapture`：在 native SodiumShader 状态作用域内绑定并恢复 SSBO 范围，
  仅延后主 RGB 混合，保留原 alpha、辅助 MRT 与水的原生深度。
- `WorldTransparencyPipeline`：核对实际颜色/深度附件与 viewport 后准入水程序；
  opaque cutoff 继续使用水前私有快照，水不被当成不透明裁切面。
- 水程序结束后、后续实体/粒子绘制前完成共享合成，避免把原生水颜色一直延后到粒子之后。
  没有水片元时保留原有晚期 PMX 路径；晚期 loader 回调不会重复合成。
- Fabric/NeoForge 均注册可选 Iris shader/state Mixin。不恢复 `renderSectionLayer/VertexBuffer.draw` 重定向。
  未验证的 shader、无 PMX 队列、无光影、阴影与不匹配目标不会开启水捕获。

## 验证

使用 Zulu JDK 21、现有共享 Gradle 缓存，命令：

```powershell
./gradlew.bat :common:test --tests '*WorldTransparency*Test' --tests '*IrisWaterShaderPatcherTest' :neoforge:shadowJar :fabric:remapJar -x buildRustNative --offline
```

25 项相关单测通过，两平台构建通过；JNI/原生代码未改，复用既有 native resources。

GPU 验证：

| 检查 | 结果 |
|---|---|
| 水 alpha 0/.002/.5/1，PMX 位于水前/水后 | 8 个组合通过 |
| PMX 与斜水面交叉，另有一层水，随后绘制透明粒子 | 两侧逐像素结果正确；晚期回调没有重复合成 |
| native 辅助 MRT、alpha discard、不透明/水深度、blend 与 SSBO range 恢复 | 通过 |
| 水节点超出容量时的同色覆盖 | 通过；weighted tail 仍是近似路径 |
| BSL world0/world-1/world1 的真实 Iris 转换、适配、编译链接 | 通过 |
| 三个维度真实 BSL 水片段程序，受控输入下与 PMX 前/后混合 | 6 个组合通过 |
| 原有箱子、物品、孔洞、MRT/颜色翻转、描边、多 PMX/no-shader 回归 | 通过 |

真实 BSL 测试使用本机 Iris `1.8.14-beta.1`、BSL `10.1.8` 的源码与转换器；
探针用独立的 **host-config shim** 避免启动 NeoForge，不替换 shader 转换/排序内核。
原生 vertex/fragment 链接已检查；像素测试对真实 fragment 提供受控 varyings、纹理和宿主 uniform。
它不等于完整 Minecraft 渲染、Mixin 实际命中或 BSL 后处理视觉验收。

日志：

- `build/translucent-performance/pr21-r6-build.log`
- `build/translucent-performance/pr21-water-production.log`
- `build/translucent-performance/pr21-native-water.log`
- `build/translucent-performance/pr21-regression-r6.log`

探针可用 `run-block-entity-probe.ps1 -BslPack '<pack.zip>' -WaterOrder`、`-NativeWater` 重跑；
不加额外开关执行原有回归。`-SharedWaterReference` 保留为早期受控参考，交付验证使用生产 scope。

## 复测包

NeoForge：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1-pr21-water-r6.jar`

SHA-256：`DF27C0CBD405C44428A02ABBDC3F1759FC1CB46441954FABD149831A9C3F32FB`

Fabric：`fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1-pr21-water-r6.jar`

SHA-256：`7366D874562D2E9E1B7C52FBC048F0DF70552BC2192916999053A1B561C0BF1D`

本轮未替换实例 JAR、未启动游戏、未提交或推送远端。

## 游戏验收与运行标记

沿用同模型/动作/相机/BSL 配置，测试模型在水前、水后、穿过水面、多个 PMX、箱子/玻璃、
波纹/反射/折射、透明粒子、切换光影和资源重载。

启动/运行时确认日志有：

```text
[MMD transparency] prepared BSL water shader capture: gbuffers_water
[MMD transparency] Iris water joins shared queue: ...
```

打开阶段诊断后，水帧应有 `water-stage resolve: fragments=...`；只见 prepared 不代表运行时准入成功。
沿用 `debugTransparencyPixel=x,y` 可检查同像素节点、opaque cutoff 与合成前后颜色。
仅作 A/B 对照可用 `-Dmmdskin.irisWaterCapture=false`；默认对验证通过的水程序开启。

尚未覆盖完整 BSL composite 的空间折射与时域行为，以及未知 shader-pack、NeOculus/Embeddium 版本组合。
任意未进入队列的透明实体仍不具有全渲染器精确排序保证。节点溢出的混合深度尾部仍近似；
若实际日志出现 pool exhausted，可结合同场景采样评估 `mmdskin.worldTransparencyMiB`，不将溢出结果称为精确排序。
