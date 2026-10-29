# 测试分层与分批

日常修改先运行相关领域；提交前需要完整回归时再运行全部批次。Java 的标准 `:common:test` 仍保留全量语义，既有命令不会悄悄变成抽样通过。

## 日常入口

```sh
# 快速检查：音频样本、舞台协议、模型身份、求值缓存、模型配置，共 26 项
bash gradlew :common:testQuick -x :prepareNativeResources

# 只验证舞台；其他领域可用 testModel / testRender / testCompat / testConfig
bash gradlew :common:testStage -x :prepareNativeResources

# Rust：默认只运行 core；可组合 animation,model,physics,vr,core,serial
python tools/testing/rust_batches.py --groups animation,model

# 根据提交差异列出建议批次；历史缺失会扩大为全量
python tools/testing/plan.py --base HEAD~1 --head HEAD
```

`-x :prepareNativeResources` 只用于这些不调用真实 JNI 的 Java 单元测试，免去为假端口测试编译 Release Rust/Bullet。打包或真实 JNI 验证时不能使用该排除项。首次运行仍需要正常下载 Minecraft、映射及 Java 依赖；分批减少执行范围，不承诺消除首次配置与编译时间。

## 领域回归与隔离层

| 批次 | 范围 |
| --- | --- |
| Java `testStage` | 音频、镜头、播放状态、道具 VMD、同步、协议与服务端 |
| Java `testModel` | 模型资产、模型身份、Java native 端口、玩家与道具挂接 |
| Java `testRender` | 绘制、材质、透明、调度、纹理与纸娃娃 |
| Java `testCompat` | Iris、VR、TaCZ 兼容 |
| Java `testConfig` | 配置持久化、模型设置界面、选择器与诊断配置 |
| Rust `animation` | VMD/VPD、插值、平滑与骨架 |
| Rust `model` | 模型运行时、JNI 生命周期、求值预算与并行 |
| Rust `physics` | 当前后端物理与坐标/参数规则 |
| Rust `vr` | VR IK、VRM 跟踪与弹簧骨骼 |
| Rust `core` | 纹理解码与日志缓冲 |
| Rust `serial` | 独立进程、单线程运行进程级 Bullet 分配计数检查 |

Rust 脚本每次只执行一次 Cargo 测试编译，再复用测试可执行文件按领域启动独立进程。`serial` 不在并行模型批次执行，也不会被继续静默忽略。Rust 的 `--offline` 可显式启用离线模式；默认不强制离线。

完整回归命令：

```sh
# 二选一，避免完整批次和旧全量任务重复执行
bash gradlew :common:testAll -x :prepareNativeResources
# bash gradlew :common:test -x :prepareNativeResources
python tools/testing/rust_batches.py --groups all

# 仅在排查旧后端时显式启用，不放入日常默认批次
python tools/testing/rust_batches.py --legacy --groups all
```

`groups.json` 是 Java 领域任务、Rust 过滤器和 CI 计划的共同清单。Java 测试新增后必须恰好属于一个完整批次；Rust 脚本检查所有已发现测试的归属。遗漏、重复归属、失效的串行测试名和未知批次均报错。

## CI 运行策略

`Build & Release` 先调用 `Layered tests`。普通 push/PR 按整个提交差异选择相关领域，删除和重命名的旧路径也参与选择；共享配置、构建文件和测试基础设施改动扩大覆盖，未知改动或缺失基线按保守范围处理。文档改动不运行引擎测试。全部 Java 测试仍会被编译，领域选择控制执行量。

Java 报告按领域上传，同一批测试只运行一次，不再由十个平台各跑一次 Java 全套测试。公共测试与平台构建并行，平台打包只依赖自己对应的 native 构建成功。公共测试失败或其他平台失败，不影响成功平台继续编译、打包与上传产物。测试失败仍保留红色状态，不使用 `continue-on-error` 掩盖；有产物不代表测试通过。发布汇总继续允许部分平台失败并收集成功平台产物。

发布标签、手动完整构建，以及每周一 UTC 04:23 的测试工作流执行全部默认后端批次。也可在 `Layered tests` 手动选择旧物理后端验证。多平台原生构建、包内原生库架构和哈希核查保持原有流程。

## 清理依据与边界（2026-10-29）

- 删除 `ModelMaterialEyeTest` 两项：旧 `isEye()` 分类已没有生产调用；现行发光区域由 `EyeMaterialMaskTest`、`EyeBoneMaskTest` 覆盖。
- 删除 `cpuModelsCanJoinTheWorldEvaluationBatch`：只断言固定的能力返回值；真实批处理去重、并行异常和完成顺序由 `NativeEvaluationBatchTest` 等覆盖。
- 删除长期忽略的私有样本并行 A/B 测试及其专用计时辅助函数，保留不依赖样本的矩阵内核等价性检查。
- 删除旧物理后端中固定 348 个刚体、固定名称与索引的私有 PMX 探针，保留合成碰撞掩码/实际 Bullet 回归。历史资产证据保留在既有文档和 Git 历史。

本轮仅修改测试、测试入口、CI 和文档，不改产品行为。真实游戏、GL、原始音频和多人联机验收依然属于按需集成验证；单元测试通过不能代替它们。

## 本轮验证记录

2026-10-29 本地结果：

- 快速批次：26 项通过；完整 Java 领域批次共 317 项通过，0 失败/错误/忽略。各领域为舞台 62、模型 33、渲染 148、兼容 26、配置 48；完整回归不包含重复的快速批次。
- 默认后端 Rust：5 个普通批次 137 项通过，独立串行批次 1 项通过。旧物理后端显式完整运行 267 项通过，均无失败或忽略。
- 测试选择器 7 项检查通过，包含文档免测、共享改动扩大覆盖、JNI 消费者选择、未知路径回退、清单完整性与串行隔离；全零基线回退完整回归也已验证。
- 三个相关工作流通过 actionlint 检查；旧版检查器以临时配置识别工程已有的 `macos-15-intel` runner 标签，未改动平台选择。
- Fabric/NeoForge 打包任务图验证成功，打包任务不再包含 `:common:test`。本轮未重新构建或发布产品 JAR。

以完整 Java 回归计，平台打包从同一套测试运行十遍改为验证阶段运行一遍，重复执行量减少 90%。26 项快速批次约为旧 320 项全套的 8%；这是测试项数量比较，不是实测总耗时加速比。

## CI #100 修复：干净检出与平台独立性

[Build & Release #100](https://github.com/zhixuan45/MC-MMD-rust/actions/runs/40804209178) 中，十个平台 native 构建及五个 Java 批次均成功；Rust 验证在编译前因 `--locked` 拒绝创建未跟踪的 `Cargo.lock` 而退出 101。此前本地已有忽略的锁文件，未暴露这个干净检出问题。

Rust 分批脚本现在与既有 native 构建一致，允许在新检出中生成锁文件；未引入依赖版本调整。所有平台打包解除对公共 `validation` 作业的依赖，只等待自身 native 作业，避免公共验证错误连带跳过所有成功平台的模组包。其他平台仍各自运行，Java 批次保留 `fail-fast: false`。

本轮在独立干净 worktree 中先复现旧脚本的退出 101，再用修复后的脚本生成新锁文件，完成六批 Rust 验证，共 138 项通过。测试选择器 7 项和工作流 actionlint 检查通过。依赖图模拟覆盖公共测试失败和逐一 native 失败：前者十个平台打包均可继续，后者仅阻止对应平台，其余九个仍可继续。
