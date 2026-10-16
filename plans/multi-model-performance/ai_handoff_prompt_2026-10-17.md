# 可交给其他 AI 的任务提示词

你正在接手 Windows 项目 `C:\tmp\mc-mmd-rust` 的 Minecraft 1.21.1 NeoForge MMD 渲染性能问题。

请先读取：

1. `plans/multi-model-performance/handoff_2026-10-17.md`
2. `common/src/main/java/com/shiroha/mmdskin/player/render/PlayerWorldEvaluationPrepass.java`
3. `common/src/main/java/com/shiroha/mmdskin/render/pipeline/NativeEvaluationBatch.java`
4. `common/src/main/java/com/shiroha/mmdskin/render/backend/BaseModelInstance.java`
5. `common/src/main/java/com/shiroha/mmdskin/model/runtime/ModelRepository.java`
6. `common/src/main/java/com/shiroha/mmdskin/render/material/TranslucentDrawOrder.java`
7. `common/src/main/java/com/shiroha/mmdskin/render/material/TranslucentBatchSubmission.java`

## 用户现象

四个相同 MMD 模型同时显示时，游戏稳定下降到约 15 FPS。截图环境为 Iris + BSL 高画质，GPU 约 58%，因此优先排查 CPU 渲染线程、native 求值、每实例物理/CPU 蒙皮、透明排序提交和同步等待。

## 已有实现

- 已有远端玩家 native 并行求值，最多 3 个工作线程。
- 已有模型句柄去重和同帧求值复用。
- 已有透明材质/纹理解析缓存与部分 GPU 透明准备。
- 已有纸娃娃姿态复用。
- 已新增 `ModelDiagnosticsPort.loadedWorldModelCount()`，统计 active 世界模型并排除纸娃娃。

## 关键限制

- `PlayerWorldEvaluationPrepass` 目前仍然只从 `mc.level.players()` 收集实际批处理候选。
- 舞台模型、生物替换模型、女仆扩展模型只会影响模型数量门槛，尚未进入统一并行队列。
- `loadedWorldModelCount()` 是已加载模型数量，不是本帧可见模型数量，只能作为临时快速路径。
- 并行 native 不等于并行完整渲染；主体绘制、材质 morph、submesh、透明排序和提交可能仍然串行。
- 不要用玩家数量作为全局 MMD 模型负载代理。
- 不要假设相同模型文件可以共享动画/物理结果；每个实例的姿态、物理和实体输入仍然独立。

## 你的任务

请先做代码和日志分析，再决定修改方案。目标是找出四模型 15 FPS 的主要 CPU 来源，并实现最小、可验证的修复。

优先顺序：

1. 找到当前实际渲染的所有 MMD 世界实例来源：玩家、MOB 替换、舞台、女仆/扩展。
2. 建立本帧可见实例登记或等效机制，避免用玩家数量或已加载数量推断本帧负载。
3. 对可证明独立的实例统一收集 native 输入快照，再批量执行 JNI。
4. 为同步回退保留明确条件：阴影、VR、IK、外部姿态、舞台播放、手持物品和 shader 特殊状态。
5. 分别测量 native、主体绘制和透明提交，不能只看总 FPS。
6. 检查四个相同模型是否仍然重复进行不必要的模型资源读取、材质解析、CPU 蒙皮或透明排序。

## 必须遵守

- 不要回滚或覆盖工作区中其他未提交改动。
- 不要使用 `git reset --hard` 或 `git checkout --`。
- 修改前先说明将修改哪些文件。
- 优先遵循项目现有接口和渲染场景抽象，不要引入孤立的全局缓存。
- 不要为了提升 FPS 删除正确性边界或跳过透明深度语义。
- Iris/外部 shader 下不能假设存在 MMD 材质表契约。
- 阴影 pass 必须继续安全跳过或使用独立验证路径。

## 验证要求

至少完成：

- `git diff --check`
- 能执行时运行相关 common 测试或最小编译任务
- 静态确认玩家、MOB、舞台和扩展路径不会重复登记同一模型句柄
- 对照 `mmdskin.parallelEvaluation`、`mmdskin.translucentBatch` 和 `mmdskin.gpuTranslucent`
- 输出修改前后对 `native.batchPrepare`、`native.batchExecuteJoin`、`draw.body.WORLD`、`materialMorphFetch`、`subMeshFetch`、透明提交计数的影响

如果无法完成真实游戏测试，请明确说明阻塞原因，不要把静态检查当作游戏性能验证。

## 期望输出

请按以下顺序回答：

1. 已确认事实
2. 最可能的瓶颈及证据
3. 代码修改
4. 测试和验证结果
5. 仍需用户在游戏内采集的日志字段

