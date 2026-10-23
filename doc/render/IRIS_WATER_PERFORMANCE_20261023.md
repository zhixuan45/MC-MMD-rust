# Iris / BSL 水合成 r7 性能优化

2026-10-23。用户已反馈 r6 的水面顺序修复有效，后续明确暂停合并，要求在保留排序效果的前提下优化水面合成。
本轮限定水捕获与 resolve；游戏整体性能由另一 AI 并行排查，不能把这里的吞吐测量当成约 30 FPS 的归因。

## 修改与不变的契约

- 水开始时用一个 compute invocation 保存 GPU 节点分配计数；结束时用分配差值判断该批次有无水片元。
  移除原先每个水片元的额外全局 `atomicAdd(mmdReserved0, 1u)`，不改变每片元节点、颜色、深度与链表。
  shader-storage 屏障保证快照、捕获和回读顺序，compute 程序恢复 native 水 program，随管线 release 释放。
- 水计数与溢出计数合并到同一次 header 回读，水阶段 resolve 复用该溢出值。
  **仍保留一次同步回读**：CPU 必须区分空水批次，保证空批次仍由原晚期回调合成 PMX。
  不提前合成空批次、不降低精度、不减少透明层或跳过水。
- 空像素在采样深度前退出；无 overflow 的单节点像素直接执行同样的深度裁切和预乘混合，省去链表循环。
  多节点、同深度节点以及溢出尾部继续使用现有算法，无局部数组截断或固定层数上限。
- 增加 water-pending 标记，避免空水批次之后无关程序的 reset 消耗后续 PMX 队列。
  native 水深度、alpha、辅助 MRT、SSBO 与 blend 恢复契约保持原样。

## 正确性验证

真实 GL 检查包括水 alpha 0/.002/.5/1 的模型前后 8 个组合、空水批次后继续提交 PMX 并遇到下一批水、
两层水与 PMX 交叉、后续透明粒子、晚期回调不重复合成、overflow 覆盖、native 深度/discard/MRT 与状态恢复。
已有箱子、物品、孔洞、颜色翻转、描边、多模型和无光影回归通过。

本机实际 Iris/BSL 的 world0、world-1、world1 变换与 vertex/fragment 链接通过；
受控输入的真实 BSL fragment 与 PMX 前后混合共 6 个组合通过。探针仍使用独立 host-config shim，
没有替换 Iris 变换器，但也不等于 Minecraft 内的完整后处理与 Mixin 验收。

构建使用 Zulu JDK 21 / Gradle wrapper 8.11.1，共享缓存，跳过再次构建 Rust native：

```powershell
./gradlew.bat :common:test --tests '*WorldTransparency*Test' --tests '*IrisWaterShaderPatcherTest' :neoforge:shadowJar :fabric:remapJar -x buildRustNative --offline
```

## 耗时样本与边界

新增探针 `run-block-entity-probe.ps1 -BslPack '<pack.zip>' -WaterPerformance`。
对照保留本轮修改前 r6 的 common classes/resources 快照；使用相同探针与 RTX 5070 Ti Laptop GPU，
1024×1024、12 帧预热、48 帧样本，分别测单层和四层水。PMX 为 56 像素宽列，水覆盖除左侧 4 像素外的画面。
原生水输出用受控颜色，节点捕获和合成执行生产代码；不包含 BSL 光照、物理或后处理。

| GPU query 中位数，ms | r6 | r7 |
|---|---:|---:|
| 单层水 capture | 0.3828 | 0.2505 |
| 四层水 capture | 0.7361 | 0.5852 |
| 单层 resolve 独立重放 | 0.1365 | 0.1208 |
| 四层 resolve 独立重放 | 0.2108 | 0.2109 |

capture 计时包括 water shader draw，不含之前那一次 compute snapshot；整段 stage wall 另行记录，包含两者及 CPU/驱动工作。
resolve 独立重放使用同一生产 shader 和保留的节点，单独计 draw；不把它与整段 wall 简单相加。
这些样本显示 capture 的局部受益，不证明真实游戏 FPS 提升幅度。各轮 stage wall 波动明显，
不能把最近一次 r6 14 ms / r7 3.3 ms 宣称为 4 倍加速；需固定前台状态和并发负载重新采样。

日志在 `build/translucent-performance/`：

- `water-r7-build.log`、`water-r7-order.log`、`water-r7-native.log`、`water-r7-regression.log`
- `water-performance-r6-final-a.log`、`water-performance-r7-final-a.log`
- `r6-performance-baseline/` 是本地对照构建快照；不提交构建产物。

## 并行工作边界与交付

另一 AI 正在同工作区加入 `WorldTransparencyProfiler` 与 `AsyncOverflowReadback`，并编写整体性能报告。
本轮保留其改动；这些诊断与无水路径的异步溢出读回不是本轮水捕获优化的独立成果。
最终共同版本仍须重新编译和执行 GL 回归；benchmark 模式显式关闭额外世界透明 profiler，避免嵌套计量干扰。

PR #21 保持未合并；本轮不替换游戏实例内 JAR。r7 游戏内水排序与性能复测尚未完成。
