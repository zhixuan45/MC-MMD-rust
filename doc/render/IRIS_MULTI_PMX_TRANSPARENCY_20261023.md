# Iris / BSL multi-PMX transparency

## Root-cause candidates addressed

- Iris may bind different FBO handles which nevertheless reference the same primary colortex0 and depth attachment. Former code rejected the second PMX based on FBO ID alone, mixing immediate alpha with the shared OIT queue.
- The first model's fallback depth snapshot can be too early to contain opaque triangles from subsequent PMX entities if the later block-entity depth stage is unavailable.

## Implementation

1. Share the OIT queue across Iris FBO wrappers only when the underlying color and depth attachment identity, format, sample count and viewport all pass validation. The old strict FBO equality check remains for non-Iris.
2. At the loader's after-entities stage, copy verified actual post-entity depth. The subsequent block-entity stage can replace it with more complete depth. Never read the late water-stage depth as an opaque cutoff.
3. Add **Debug Settings → Per-model transparency diagnostics** in both Fabric and NeoForge. This logs sampled model names, CREATE_SHARED_QUEUE / JOIN_SHARED_QUEUE / IMMEDIATE_FALLBACK decisions and per-frame counts. Enable **Transparency stage diagnostics** alongside it for precise depth/FBO stage messages. Both are off by default.
4. Override via `-Dmmdskin.debugTransparencyModels=true` and `-Dmmdskin.debugTransparencyStages=true`.

## Validation checklist

- Use BSL + Iris with two overlapping PMX models and rotate the camera; compare with shaders disabled.
- Repeat using a held diamond sword and a chest behind translucent PMX hair or clothing.
- Check logs for `JOIN_SHARED_QUEUE`, `after-entities depth captured`, `opaque-depth captured`, and `IMMEDIATE_FALLBACK`.
- Verify mismatched attachments do not join, and same backing textures/depth can join despite different FBO wrappers.
- Check `-Dmmdskin.worldTransparencyQueue=false` for comparison.
- Run `./gradlew :common:test --tests '*WorldTransparencyStagePolicyTest' :fabric:compileJava :neoforge:compileJava`.

## Remaining limits

The shared list contains PMX fragments, not every vanilla translucent item or water fragment. Exact cross-renderer transparency requires a shared OIT submission/depth API. This patch does not claim an in-game BSL visual verification; the GPU state needs a game test.

## 2026-10-23 本地续查：批次提交晚于旧深度快照

基线为 PR #21 的 `84cdb643e99fa996ebb028d46b21ffd422ef3ea0`，本地分支为
`fix/1.21-iris-cross-pmx-transparency-debug`。本轮修改未推送，截图中的完整游戏场景仍待复测。

### 当前证据与确认范围

- 本机 `latest.log`（21:10–21:22）显示 Iris `1.8.14-beta.1+mc1.21.1`、BSL `10.1.8`，
  `after-entities depth captured=true`、`opaque-depth captured=true`、resolve 均已执行。
  这些日志不能证明快照已包含尚未提交的实体批次。
- 检查本机 Iris JAR：`FullyBufferedMultiBufferSource` 的 `readyUp/endBatchWithType/endBatch`
  在 `MixinLevelRenderer` 的 `"translucent"` profiler 注入点执行。
  检查本机 NeoForge 1.21.1 源码：两个旧 loader 回调都早于该位置。
- 已在真实 OpenGL 上确认一个缺陷：先收集两 PMX 的透明片元，执行两个旧回调，再提交前景不透明物品，
  旧 resolve 仍会把 PMX 混到物品前。此结论适用于该提交顺序，不能直接等同于用户截图的全部根因。

### 补丁与诊断

- 两平台在 `renderLevel` 调用 `RenderType.translucent()` 前补一个只读阶段观察点。
  该位置晚于 Iris 的 profiler 批次提交，早于水的 terrain layer，不重定向区块绘制，也不强制 flush。
  继续校验真实深度附件、格式、采样数和 viewport，再刷新私有深度快照。
- 保留原有早期快照与颜色目标规则。半透明 PMX 不向世界主深度补写。
- 新增独立可选像素回读：每 120 帧记录同一像素在 model-end、实体后、方块实体后、
  水前、resolve 前后的 FBO/附件、RGBA、实际深度、快照深度、全帧分配/溢出数和前 8 个链表节点。
  `allocated/overflow` 是全队列累计值；`nodes(first8)` 只属于选中像素，顺序是链表顺序。
  `visible` 表示该节点是否通过当前快照深度；快照未初始化时为 NaN，不能据此判断最终可见性。

同场景复测时将异常交叠区域置于画面中心，添加 JVM 参数：

```text
-Dmmdskin.debugTransparencyModels=true
-Dmmdskin.debugTransparencyStages=true
-Dmmdskin.debugTransparencyPixel=0.5,0.5
```

坐标按 viewport 归一化，原点在左下，取值 `[0,1)`。像素回读会造成诊断性 GPU 等待，默认关闭，
采完日志后移除 `debugTransparencyPixel`；常规性能测量不应启用它。

### 验证结果

- `:common:test --tests '*WorldTransparency*Test'`：21 项通过。
- `:fabric:compileJava :neoforge:compileJava :neoforge:shadowJar :fabric:remapJar`：通过；
  原生代码未改，构建复用了已有 native resources，跳过 Rust 重编译。
- `BlockEntityTransparencyGlProbe`：真实 GL 上 8 个多 PMX 组合通过，覆盖两种提交顺序、
  shader/no-shader 队列、后提交 PMX 不透明面/延后 flush 的不透明物品。
  原有箱子、透明孔洞、水后深度、MRT 恢复、Iris colortex0 翻转及生产描边用例通过。
  诊断开启时同时校验了 pixel pack 布局恢复和最终 GL 错误状态。
- 修正探针之前仅选 `gbuffers_entities` 的盲区，当前选择与运行日志相同的
  `gbuffers_entities_translucent`，真实 BSL 输出布局为 `01`。
  这只使用包内预处理的输出 profile，未运行完整 BSL 后处理和游戏场景。

旧基线失败日志：`build/translucent-performance/pr21-late-opaque-before.log`：

```text
Baseline has no post-flush/pre-water depth callback
late-flushed opaque item must occlude both PMX layers ... channel=0 actual=0.30004883 expected=0.2
```

新探针日志：`build/translucent-performance/pr21-late-opaque-after.log`，像素 `(40,32)`：

```text
after-block-entities: snapshotZ=1.0
before-world-translucency: currentZ=0.2 snapshotZ=0.2
before/after-resolve: currentZ=0.050000016 snapshotZ=0.2
nodes: z=0.3, z=0.6, both visible=false
finalRight=[0.19995117, 0.099975586, 0.049987793, 1.0]
```

该用例证明水的后续深度没有替换不透明快照，PMX 片元被前景不透明物品正确裁掉。

### 本地复测包与未决项

NeoForge：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1-pr21-prewater-r5.jar`。
SHA-256：`4543BD7ACEEAAA6440BA34688FC5347D067847CC36D2A2B6688A4A6E88425AB1`。

Fabric：`fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar`。
SHA-256：`3D094012D42897AC28B213906B61487D3A65E070A2EBFF1B51C816BD503D713A`。

本轮没有替换游戏实例 JAR、启动 Minecraft 或进行同模型/动作/相机的 BSL 验收。
用户截图中的 PMX 相互交叠、剑、箱子/玻璃/水仍需逐项复测；三模型和其他 shader-pack 组合未覆盖。
如仍异常，优先用新增日志判断是否所有模型入队、选中像素在水前的深度是否齐全、
resolve 后颜色是否已正确；后续完整 Iris/BSL 合成是否覆盖或扭曲它仍需游戏抓帧。

## 水面续报：r5 尚未解决跨水面混合

用户已明确：水后的 PMX 透明部分盖到水面前。新增真实 GL 复现表明当前队列会将该模型按水前顺序合成。
此前“水后深度用例通过”只证明保存的 opaque 快照没被水覆盖，不能当作水与模型混合正确的证据。
受控水节点进入同一链表的参考模式可通过前/后两种顺序；生产 Iris 水程序尚未接入。
方案后续已获用户批准，并实现 r6 生产水面适配；实施记录与未决游戏验收见
`doc/render/IRIS_WATER_QUEUE_20261023.md`，r5 的上述结论保留作历史基线。
