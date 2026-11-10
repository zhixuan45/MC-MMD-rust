# 架构简化执行记录

依据 `implementation_plan.md` 执行。保留原 Git 历史，没有提交或部署到游戏实例。

## 范围与执行方式

使用六个子代理，分别承担时间轴领域模型、编辑器应用服务、骨骼适配及动作句柄、Rust 绑定状态、JNI 边界和 Iris 渲染边界。主线程承担 Rust 死代码清理、JNI 新协议、探针整合和交叉审查。

实施前工作区仅有未跟踪的本计划目录；本次不覆盖其他任务的生产代码改动。

## 已实施的代码方向

- 清除两条动画 tick 路径的不可达旧实现；保留唯一有效的内部 tick。
- 删除没有消费者的物理混合字段和辅助函数，保留原有采样姿态与 `enable_physics`。
- 移动资产扫描类和时间轴布局类，新增近期提交内容索引与 JNI 一致性检查脚本。
- 抽取时间轴应用服务、注册服务、纯矩阵运算、native 绑定适配器和统一 VMD 句柄生命周期。
- 将时间轴骨骼求值改为按轨二分，并以原线性求值作顺序行为对照。
- 新增 `SetPropBindings` 原子提交父矩阵和窗口，固定三矩阵姿态包与显式物理附件长度。
- 将切换窗口移到动画模块；骨骼容器负责最终父矩阵及动态骨骼禁绑规则。
- 统一 JNI panic 隔离和锁中毒失败处理。
- 统一 Iris 帧状态、GL 状态、渲染适配接口与独立配置，迁移旧配置。

## 兼容性决定

旧 `SetPropBoneParents`、`SetPropBoneWindows`、128/192 字节 `GetPropBonePose` 保留作兼容入口；生产 Java 适配器改用新协议。三参数 `SetPropPhysicsAttachment` Java 包装传入固定 64 字节长度。

旧 ABI 的布尔、句柄及字符串返回类型保持；panic 统一转为 Java `RuntimeException` 并返回对应失败哨兵。新绑定/姿态协议使用负错误码，避免将失败误认作成功。

## 线程证据

`DefaultStagePlayerSyncPort` 和 `StageAnimSyncHelper` 拥有舞台 begin/seek/stop 调用；渲染路径原先还调用 stop，现移除该生命周期写入。

`NativeEvaluationBatch.run` 会将 `Work.execute` 发到后台执行，并在返回前 join；`BaseModelInstance.PreparedEvaluation.execute` 调用模型更新 JNI。因此 native 更新确实存在工作线程执行路径。没有据此推定所有绑定调用都会与更新并发；原子绑定通过 Rust 同一模型锁保证完整状态提交，不依赖 Java 线程归属假设。

## 验证记录

最终自动化验证已通过。日志在本目录，Java XML 在 `common/build/test-results/test/`。

- JNI 源码一致性：178 个声明、178 个导出，名称/重复/参数/返回 ABI 以及全部导出 panic 边界检查通过。
- `cargo test --manifest-path rust_engine/Cargo.toml`：176 通过、0 失败、1 忽略，包含库、集成测试与 doc-test 目标。详见 `cargo-final.log`。
- `cargo check --manifest-path rust_engine/Cargo.toml --features legacy-physics --lib --target-dir rust_engine/target/legacy-check`：通过。legacy 后端的独立检查在提升相应文件权限后完成，详见 `legacy-check.log`；构建缓存位于已忽略的 `target` 目录。
- `:common:test`：499 项，498 通过、0 失败、1 跳过。
- `:fabric:compileJava` 与 `:neoforge:shadowJar`：通过。与全量 Java 测试在同一最终 Gradle 调用中成功；详见 `gradle-final.log`。
- `tools/prop-bone-probe/run.py`：真实 DLL 的 CPU/GPU 骨骼挂接、切换窗口、新旧姿态 ABI、非法事务拒绝通过。
- `tools/stage-prop-probe/run.py`：调用生产注册服务和播放控制器，12 个 CPU/GPU × snap/keepWorld/placed × 显式外层挂接组合通过；覆盖 0–300 帧连续采样、直接 seek、倒放、重复绘制、解绑与校准隔离。最大矩阵元素误差 `0.000000954`。
- `tools/iris-toon-probe/run-render-boundary-probe.ps1 -Probe all`：使用最终 Gradle 类文件的真实 GL 验证通过。包含地形深度复制与状态恢复、RGBA/RGBA8/RGBA16F/RGBA32F 透射回放，以及 BSL 8/10、Complementary 4.7.1、Reimagined/Unbound 5.9.3 的 30 个主体/描边变体。
- `git diff --check`：通过。

构建输出为 `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。已确认包含新应用服务、绑定适配器和 Iris 帧状态类，内嵌 Windows DLL 与本次 release DLL 逐字节一致。DLL SHA-256：`76df9abf84656f7554e1fbe36a4d81d8226235900b1407be51a7a862a84429bd`。

验证过程中处理的环境和测试设施问题：

- 初次 Gradle wrapper 启动受共享缓存锁文件访问权限限制；通过相应权限使用现有共享缓存。
- 构建进程使用 Zulu Java 21，未修改全局 Java、Gradle 或 IDE 配置。
- 最早 Rust 测试在链接阶段因无法覆盖已有 DLL 导入库失败，错误为 `LNK1114 / 错误代码 5`；尚未进入该次测试执行。
- 早期 Java 集成构建与子代理编辑有交叠，不作为干净 HEAD 基线。其结果用于发现并修正新增用例和接口整合问题。
- `probe_static_physics` 使用 legacy 专有的嵌入接触过滤及刚体 API；在 Cargo 中声明 `legacy-physics` 依赖，避免默认后端编译不适用的诊断程序。
- `tests/upstream1201.rs` 的三个测试共享全局配置和分配计数；增加同一互斥锁，消除并行测试的相互干扰，没有修改生产物理行为。
- 舞台探针原先直接写入 native 窗口并手工计算注册补偿；已改为调用新的生产 `PropRegistrationService`，由它提交完整绑定快照，避免探针继续复制旧编排。

复现核心验证：

```powershell
cargo test --manifest-path rust_engine/Cargo.toml
.\gradlew.bat :common:test :fabric:compileJava :neoforge:shadowJar :common:writeStagePropProbeClasspath --init-script tools/stage-prop-probe/classpath.gradle
python doc/check_jni_consistency.py
python tools/prop-bone-probe/run.py
python tools/stage-prop-probe/run.py
.\tools\iris-toon-probe\run-render-boundary-probe.ps1 -Probe all
```

GL 探针读取本地实例的 Iris 和 shaderpack，默认实例路径可用 `-Instance` 覆盖，不修改实例。

## 待场景验收

- 游戏内编辑器：打开、登记、重算、保存与退出确认。
- 游戏内 Iris：地形阴影与玻璃透射、相机移动、主体及描边表现。
- 带非单位 rest rotation 的 PMX 骨骼原点语义，继续保留为独立资产验证项。

编译、单元测试和合成探针分别记录；不以它们代替游戏场景验收。
