# 全局物理显示修复验证（2026-10-18）

用户确认前发、蝴蝶结、袖子的延迟和抖动也出现在普通模式。本轮保留上一轮舞台层单一时间源修复，继续修复全局求值时钟与接触显示预测。没有改变 Bullet 解算频率、ERP、刚体质量、原始关节或阻尼。

`BaseModelInstance` 的串行更新与并行准备原先使用毫秒墙钟。两条路径现共用 `ModelUpdateClock`，以 `System.nanoTime` 计算步长，保留亚毫秒更新、首帧零步和 0.25 秒限幅。长间隔限幅后从当前时刻继续，不积累时间欠账。

`BW_KinematicStepWorld::syncRenderStates` 原先分别裁剪接触两侧的世界位移，导致共同移动时也可能裁掉一侧的法向预测。现在比较双方接触点的相对位移，按动态体逆质量分摊平移修正，考虑零子步期间跟骨 MotionState 中的新目标。普通接触保留旋转预测；相向夹持无法通过平移满足时恢复参与动态体的完整求解姿态，并重新投影接触。修正仅写 MotionState。

新增 Bullet 回归覆盖共同移动、运动学目标共移、相向移动的间距与质心、单面旋转、静态内侵、墙角和夹墙回退；断言求解姿态和速度不被预测修改。接触夹具使用球心距 0.99、半径 0.5 的轻微重叠，直接建立真实流形后进行零子步预测，避免恰好相切时没有有效接触、或先行求解使夹具分离。

验证环境为本机 Windows x64、JDK `C:\Program Files\Zulu\zulu-21`、仓库 Gradle 8.11.1 wrapper 和已有依赖缓存。沙箱内访问构建缓存受限，受审查的沙箱外构建成功，没有删除缓存或更改全局环境。

| 验证 | 结果 |
| --- | --- |
| `gradlew.bat :common:test --tests '*ModelUpdateClockTest' --tests '*StageAnimSyncHelperLifecycleTest' --offline --console=plain` | 6 项通过，Java 主代码与测试编译成功 |
| `cargo test --offline --lib physics::bullet_ffi::tests -- --test-threads=1` | 19 项通过，含新增 7 项预测回归 |
| `cargo test --offline --lib model::runtime::physics::physics_tests -- --test-threads=1` | 2 项通过，覆盖零步/跳帧骨骼连续性及重同步请求 |
| `gradlew.bat :fabric:remapJar :neoforge:shadowJar --offline --console=plain` | 双平台 Java 编译、Rust release 原生库和 Jar 构建成功 |
| `git diff --check` | 通过 |

原生单元测试首次编译暴露已有 `evaluation_parallelism.rs` 测试中的名称笔误：解构得到 `sample_parallel_ns`，汇总却引用分支内的 `parallel_ns`。仅修正汇总变量引用，不修改运行时代码。编译仍有已有的 unused/dead-code 和可选 Mixin 提示，不影响上述结果。

Bullet 和模型回写测试日志分别保存在 `build/physics-stability-bullet-tests.log`、`build/physics-stability-runtime-tests.log`；Java XML 报告在 `common/build/test-results/test/`。

可测试产物为 `fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar` 和 `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。两个 Jar 都包含新时钟类，内嵌 `natives/windows-x64/mmd_engine.dll` 的 SHA-256 均与本次 release DLL 一致：`4DCA2C49A0E20188F36F94B22F9A46176132670870293CC13F8792F52B39B271`。

这些测试证明时钟精度、共同运动预测、局部接触纠偏及回写边界，不证明真实模型观感已经完全解决。游戏验收需要在相同模型、动作、帧率与镜头下，对普通待机、移动后停止、快速转头和舞台分别比较前发、蝴蝶结与袖子。真实碰撞求解本身的高频振荡仍可能存在；原计划中的用户预测比例、接触稳定性预设和自适应阻尼尚未实施。慢速 FollowBone 目标的死区过滤也保留当前行为，需要独立运行证据后再评估。

## 游戏复验后追加（求解迭代对照包）

用户反馈上一轮包抖动程度基本一样，关闭 GPU 蒙皮也基本一样。实际游戏日志确认使用了上述旧 SHA，因此不能把旧回归测试通过当成画面修复成立。本轮录屏、原模型和七组求解器对照详见 `solver-investigation.md`；增加默认10、范围10～80的可保存/热切换迭代设置，提供40次对照，尚未通过游戏观感验收。

本轮 Windows x64 验证：Bullet FFI 23项、模型物理3项、迭代范围1项通过；Java定向测试24项通过（配置14、模型配置4、时钟4、舞台生命周期2）。双平台包再次构建成功，`git diff --check` 通过。真实 JVM/JNI 探针验证新建40、热切10/40、非法9不变和下一物理步仍40，并确认现有多核求解实际同时执行任务；记录在 `logs/physics-stability-20261018/solver-jni-mt-probe.log`。未将单模型耗时当作游戏帧率保证。

最终两个 Jar 均包含 `PhysicsSolverNative.class`，内嵌 DLL 与 release 文件 SHA-256 一致：`ED3C4212C57DEFD688DAB5F7F06E89D133BF7930220195DC37130A8F28FAC592`。核对记录为 `logs/physics-stability-20261018/package-verification.json`。为避免后续其他构建覆盖本次测试包，副本位于 `build/physics-stability-20261018/mmdskin-neoforge-1.10alpha-1.21.1-solver-test.jar` 和对应 Fabric 包。

上述旧包复验曾要求从10改为40。用户最新反馈40反而变差，30除极端强抖动外没有问题，当前复验入口见下文。idle候选部件角步幅下降不代表停止后高频残余消失，不能标记为抖动已彻底解决。

## 下限扩展复验（2026-10-18）

按用户请求将生产范围统一扩展为1～80，默认仍为10；配置加载、Java桥接、Fabric/NeoForge滑块与Rust全局校验一致。工具提示取消10/40的固定建议，说明低迭代可能增加穿透或约束误差。仅开放调节范围，不改变作者关节、碰撞、ERP、阻尼和时间步。

`cargo test --offline --lib physics::solver_quality -- --test-threads=1`通过1项，覆盖0非法、1/9/10/30/40/80有效、81非法。`gradlew.bat :common:test --tests '*ConfigDataTest' :fabric:remapJar :neoforge:shadowJar --offline --console=plain`构建成功；配置14项测试全部通过，覆盖低值保存、加载、快照与默认10保留。真实杏辞JVM/JNI探针读回`new=1 hot=9/30/10/40 invalid0=rejected after_step=1/9/40`，说明新建、热切换和物理步进均没有退回旧下限。日志为`logs/physics-stability-20261018/lower-bound-{rust,gradle,jni}.log`。

双平台包均含桥接类，内嵌DLL与本轮release一致，SHA-256为`0BC7DBFC9577D5A4135D11EC8CF8A7A1BF287233C62733D8C6B78C56D6ECE0D3`，核对记录为`logs/physics-stability-20261018/package-verification-lower-bound.json`。新副本为`build/physics-stability-20261018/mmdskin-neoforge-1.10alpha-1.21.1-solver-1-80-test.jar`及对应Fabric包，旧对照包保留。

用户可从已观察的30开始逐步降低，在同一极端动作下比较抖动、穿透和关节支撑。下限1是允许范围，尚未证明1是最合适的工作点；本轮未完成新范围的游戏画面验收。
