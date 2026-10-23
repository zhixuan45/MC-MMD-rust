# Iris / BSL 水面与 PMX 共享透明合成方案

2026-10-23；状态：用户已授权，已反馈 r6 原水面排序现象修复；合并暂停，r7 局部性能优化与游戏复测继续。
关联：`implementation_plan.md` 的 OpenGL 透明工作；PR #21 本地分支与 r5 改动保留。

## 已确认的缺陷

用户明确现象为“水后的模型透明部分盖到水面前”。本机新日志显示 r5 的
`before-world-translucency` 深度复制确实已执行。本问题不能用继续提前/延后不透明快照解决：
PMX 队列没有水的颜色/透明度节点，水先混合，之后 PMX resolve 无条件覆盖到它前面。

已有文档 `doc/render/EXTERNAL_TRANSLUCENT_STAGE_1_21.md` 的 Known limitation 已明确记录此限制，
且禁止恢复 `renderSectionLayer/VertexBuffer.draw` 内部重定向，因为之前导致 Embeddium 启动失败。
此次方案不恢复这些重定向。

真实 GL 最小复现使用白色 PMX alpha=0.5、蓝色水 alpha=0.5、背景 `(0.2,0.1,0.05)`：

| 情况 | 正确结果 RGB | 当前 r5 回读 RGB |
|---|---|---|
| PMX 在水前 | `(0.55,0.525,0.7625)` | `(0.5498,0.5249,0.7627)` |
| PMX 在水后 | `(0.30,0.275,0.7625)` | `(0.5498,0.5249,0.7627)`，失败 |
| PMX 覆盖范围外 | `(0.10,0.05,0.525)` | 通过 |

该探针执行生产 PMX 队列与 resolve；水面使用受控 RGBA/depth 绘制，**不等于完整 BSL 游戏复现**。
真实 BSL 水程序输出包含反射/Fresnel、雾、透射、MRT 和可选折射，生产实现须保留这些语义。

## 建议范围

先支持当前实测的 Iris 1.8.14 / BSL 10.1.8 / Minecraft 1.21.1，Fabric 与 NeoForge 共用适配逻辑。
让经过验证的水程序把最终 RGBA 和深度提交到现有片元链表，与 PMX 按像素深度统一 resolve。
复用现有排序内核；新增的是水程序的提交适配与阶段状态，不重写物理、材质或整个透明后端。

不采用下面的临时替代：

- 用水后的 depthtex0 硬裁切 PMX：会把水变成不透明遮挡，丢失水下模型。
- 把全部 PMX 提前到水前合成：会使位于水前的透明部分也被水覆盖。
- 从已混合的颜色猜水的透明度：当前 BSL 的 alpha/MRT/折射契约不足以保证可逆。

## 生产实现步骤

1. **先验证 shader 接入位置。** 本机 JAR 存在 `TransformPatcher.patchSodium` 等程序变换入口；
   此事实不等于稳定的跨版本插件 API。先验证可限定程序名、profile、目标附件和链接状态的适配点，
   区分 Sodium 地形与 ExtendedShader 实体路径。未知布局、冲突 SSBO、geometry/tessellation 或
   `gl_FragDepth` 等未验证变体不启用水适配，并记录具体原因。
2. **增加窄范围 Iris 水适配器。** 在可验证的 `gbuffers_water` 片元程序中收集完成光照后的 RGBA/depth；
   保留原 alpha discard、材质/折射等辅助 MRT 和水的原生 depthtex0 写入。
   原生主颜色不能先混合一次、resolve 再混合一次。需要在明确的程序作用域抑制原主 RGB 混合，
   同时保持原 alpha 与其他 draw-buffer 契约，退出后恢复 indexed blend 等实际 GL 状态。
   可评估程序作用域的 indexed blend 控制；不能仅把输出 alpha 置零后假定所有状态仍等价。
3. **定义帧契约。** 有 PMX 队列且适配验证成功的帧才开启水捕获。
   正常首帧、无 PMX、无水、关闭光影、阴影、GUI、资源重载仍执行对应原路径。
   校验当前 framebuffer/viewport、reserved SSBO 范围、容量、内存屏障和 native 水状态的恢复。
   该窄适配不能改变用户 Toon/透明配置，也不能因失败关闭整个渲染系统。
4. **保留不透明快照。** opaque cutoff 仍来自水前；水节点参与排序，而非充当 opaque cutoff。
   水程序不能照搬带 `early_fragment_tests` 的 PMX capture 后继续写主深度：
   必须验证 discard 不会提前留下水深度、depth-fail 片元的 SSBO 副作用如何处理。
   如采用手动 opaque 截断，必须采私有快照，避免对当前附加的深度纹理产生反馈。
5. **处理密集覆盖与合成时机。** 先检验 PMX、水共同占用节点后的容量与 overflow；
   对不同深度的混合 overflow 不得宣称精确。BSL composite 折射会重采颜色，
   因而主颜色的逐像素排序通过后还需验证真实波纹/折射边界，不能据此提前结案。

预计主要文件：`compat/iris` 新适配器/契约、`pipeline/transparency` 的提交状态与资源范围、
独立水捕获 GLSL、两平台可选 Iris Mixin/注册、逻辑测试及现有 GPU 探针。
若无法在不恢复地形内部 draw 重定向的前提下获得可靠接入，先报告该阻碍并修订方案。

## 已完成的参考验证

`BlockEntityTransparencyGlProbe --water-order` 已复现当前错误。
`--shared-water-reference` 把受控水节点加入现有链表后，前/后两种顺序均通过：
后方 PMX 回读 `(0.30005,0.27490,0.76270)`。
参考模式使用额外的受控深度绘制模拟 native 水，**不是拟提交的生产水面重绘方案**；
尚未接入 Iris 的实际水程序，也不验证其辅助 MRT/折射。

复现命令（当前 r5 预期失败）：

```powershell
./tools/translucent-performance/run-block-entity-probe.ps1 -BslPack '<BSL_v10.1.8.zip>' -WaterOrder
```

共享队列参考（当前通过）：

```powershell
./tools/translucent-performance/run-block-entity-probe.ps1 -BslPack '<BSL_v10.1.8.zip>' -SharedWaterReference
```

本地日志：`build/translucent-performance/pr21-water-order-before.log`、
`build/translucent-performance/pr21-water-shared-reference.log`。

## 验收与交付边界

- 将水顺序失败用例改为生产路径通过，覆盖前/后、alpha 0/低值/1、交叉深度、多 PMX/多水层与孔洞。
- 用真实预处理 BSL 水程序检查 colortex0、辅助 MRT、depthtex0/1、blend 与 SSBO 恢复；
  覆盖原有 late-item、箱子、colortex0 翻转和描边探针。
- 执行透明相关单测、两平台编译与打包，保留运行包哈希。
- 实际游戏在同模型/动作/相机/配置下验收水前、水后、穿过水面、波纹/反射/折射、
  箱子/玻璃、多个 PMX、切换光影和资源重载。构建和参考探针不能替代此项。
- 已完成生产 shader/state 适配与 r6 两平台复测包；结果与哈希见
  `doc/render/IRIS_WATER_QUEUE_20261023.md`。用户确认原报告水面顺序已修复，完整场景矩阵未逐项验收。

## 授权记录与实施补充

用户已明确回复“按方案继续实施”，授权扩展 PMX-only 队列，接入 Iris/BSL 水片元。
无需为该范围再次确认。实现通过源适配与 native SodiumShader setup/reset 作用域接入。
共享合成在水程序结束时执行，原生水颜色先于后续实体/粒子恢复；空水批次仍沿用原晚期回调。
opaque 截断在 resolve 使用私有水前深度，不需要在水 capture 中采当前附加的深度纹理。
