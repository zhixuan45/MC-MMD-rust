# 多模型与纸娃娃优化续作完成报告

日期：2026-10-16，Asia/Shanghai。承接 `pause_report_2026-10-16.md`，剩余代码已完成修正、验证及双平台打包，游戏内画面和多人帧率等待实测。工作基于提交 `731e532`，没有提交或推送 Git。

## 交付内容

- 透明准备消费已知 CPU 原始／动态索引及版本，世界、第一人称、GUI 各持有独立有界缓存；GPU 纹理窗口按实际透明候选构造，补 `GPU_WINDOW_FALLBACK` 汇总。既有 GPU 排序、间接合批、静态缓冲共享和同帧复用保留。
- 独立远端玩家模型支持批量 JNI 求值。输入准备和结果发布在渲染线程，最多三个后台线程加调用线程执行，全部任务汇合后才继续 GL 工作。相同句柄去重，异常任务不提前结束其它任务的生命周期。Iris 主世界实体程序也保留这条 CPU 预求值路径，阴影 pass 仍受平台注入边界和运行时保护。不同模型句柄可并行执行，不依赖资产相同。新增六项批处理测试覆盖真实重叠、回调线程、汇合、失败、拒绝提交和同步回退。
- 修复预处理边界：关闭同帧复用时同步禁用并行；至少两个合格远端 GPU 模型才准备输入；匹配原版逐实体冻结状态和 partial tick，检查视锥、区块、外部模型及特殊姿态。不加载额外模型，不改变显示数量和现有动画频率。
- 纸娃娃可选每帧／60 Hz／30 Hz，默认每帧。离屏模式用独立深度、RGBA8 和预乘 alpha 合成，预算 128 MiB；刷新成功才计数。两只手均为空且无光影包时才使用，特殊 GL 目标或状态保守回退。补齐三种语言的回退说明。
- 原生采样记录互不重叠的动画、IK、物理、CPU 蒙皮时间与真实 Bullet 调用／子步计数；GPU 求值不再把空分支耗时归给 CPU 蒙皮。JNI 输出先校验 direct buffer，旧 DLL 无入口时安全回退。常规更新先释放全局模型注册表锁，再执行独立模型求值。
- 屏幕面积优先级、稳定距离滞回、主体／描边分场景计时等暂停前代码已纳入这次构建。

当前 DLL 包含基线提交中的裙摆／头发物理代码；本次没有修改那些物理策略。不要把与旧 DLL 的物理差异全部归因于性能优化。

## 验证结果

| 检查 | 结果与边界 |
| --- | --- |
| common 单元测试 | XML 汇总 202 项，失败／错误／跳过均为 0 |
| Python 日志汇总器 | 5 项通过，覆盖旧日志缺省字段及新增窗口计数 |
| Rust 统计定向测试 | 4 项通过，包含 TLS、真实 Bullet 步进、采样／并行结果一致性 |
| Rust 全库串行 | 237 项通过、2 项忽略，0 失败；默认并行运行的限制见下文 |
| JNI release 探针 | 协议、非法 buffer 不求值、未对齐边界、CPU／GPU 字段、四独立模型并行对照均通过；每模型 48 次求值，最终骨骼与常规串行逐字节一致 |
| 旧 DLL 探针 | 在打包覆盖前使用旧生成 DLL，连续两次接口检测回退、不求值、后续常规更新通过 |
| 真实 GL 提交对照 | 原 CPU 提交、CPU 合批、GPU 间接合批颜色／深度通过；新增 10 张原始纹理筛选为一个候选窗口的生产入口验证通过 |
| 真实 GL 透明准备 | 25 组 CPU／GPU oracle 通过；显式 CPU 索引范围／版本／长度与三视图交替缓存通过 |
| 共享资源及计时查询 | 池开启／关闭、引用释放、预算回退、查询所有权／生命周期通过 |
| 纸娃娃真实 GL | 合成像素、深度隔离、刷新／命中／失效／重建／异常重试、状态恢复及特殊目标回退通过 |
| 双平台构建 | Fabric remapJar、NeoForge shadowJar 成功，包含新 release DLL；本次没有跳过 prepareNativeResources |
| 打包核验 | 两包 DLL 与 release 输出逐字节一致；新增类、compute shader、三种语言和 Mixin 配置存在，Fabric refmap 有新注入点的映射 |

Rust 默认并行测试首次结果为 235 通过、2 失败、2 忽略。失败项为 `snapshot_reports_applied_filter_and_real_bullet_contact`（期望 Stable）和 `skirt_compatibility_preserves_other_originally_blocked_pairs`（期望裙腰接触）；两项独立运行均通过，随后全库 `--test-threads=1` 通过。现象符合物理全局配置在测试之间的干扰，尚未定位并修复隔离根因，不能称默认并行全库测试通过。本次新增独立世界并行 Rust／JNI 对照则均通过。

探针不启动 Minecraft。原版 `LevelRenderer` 实体循环、方法参数和冻结 partial tick 字节码已核对，但未在客户端验证本次 Mixin 的实际应用、兼容模组组合或多人画面。由于注入使用 `require=0`，游戏日志还需要确认 `native.parallelBatches` 真正增长。

## 产物

| 产物 | 路径 | SHA-256 |
| --- | --- | --- |
| Fabric | `fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar` | `ca93efa377ca672210d95e944b73b191c1ee33082fb76017abe69c75a474cfd3` |
| NeoForge | `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar` | `0eacbac97659917861c1014da9b2393a381af3ce72fd365ab7c23bac1b0c727a` |
| Windows DLL | `rust_engine/target/release/mmd_engine.dll`，4,638,720 字节 | `336f9bcf146e7a9f750abc0a6f43485f03875249e807c9b70a81989081af521a` |

校验明细：`build/translucent-performance/multi-model-artifact-verification.json`。串行 Rust 日志：`build/translucent-performance/rust-lib-tests-serial.log`。build 目录为本地生成结果，不作为版本化源码。

JDK 采用 IDEA 对应的 `C:\Program Files\Zulu\zulu-21`，沿用共享 Gradle 缓存和 daemon，没有 clean。最终打包命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:CARGO_NET_OFFLINE='true'
.\gradlew.bat --offline -I tools/translucent-performance/validation.gradle :common:test :fabric:remapJar :neoforge:shadowJar :common:writeTranslucentProbeClasspath
.\tools\translucent-performance\run-batch-probe.ps1
.\tools\translucent-performance\run-native-probe.ps1
python -m unittest discover -s tools/translucent-performance -p test_summarize.py
cargo test --offline --manifest-path rust_engine/Cargo.toml --lib evaluation_profile
cargo test --offline --manifest-path rust_engine/Cargo.toml --lib -- --test-threads=1
```

## 下一轮游戏验收

用户此前反馈波动减小、提高约 10–20 FPS，是上一版的实测反馈。本次没有新的游戏 FPS 数据。已分析的 00:10:43–00:12:55 日志中 `avgVisible` 全程为 1，不能代表 2／4／8 独立世界模型表现，也不能证明本次候选窗口修正确实覆盖该模型。

1. 重启开发客户端确保加载新 DLL；先关闭纸娃娃，固定分辨率／相机／动作／场景，采集 1／2／4／8 个世界模型，相同与不同资产分别测试，预热 30 秒、采样 60 秒。并行路径至少需要两个合格远端玩家模型；本地玩家加一个远端不会触发。
2. 同条件对照 `-Dmmdskin.parallelEvaluation=false`，检查 `native.parallelBatches`、`native.parallelModels`、`native.batchExecuteJoin`、各场景求值／复用、真实子步和游戏帧间隔 median／P95。累计 native 工作量大于帧时间在并行时可能正常。
3. 固定 1 和 4 个世界模型，对比纸娃娃关闭／每帧／60／30 Hz。先双手空，再持物，检查透明头发／眼睫／衣物边缘、描边及深度；持物或光影包下全速回退属于预期。姿态复用与离屏帧复用分别看计数。
4. 检查 `GPU_PREPARE`、`PREPARED_REUSE`、`GPU_WINDOW_FALLBACK`、`READBACK_CALLS/BYTES`，确认候选窗口和索引来源改动命中。超出单窗口、未知索引来源或外部着色器仍允许兼容回退。
5. 覆盖第一／第三人称、手持物品、暂停／冻结恢复、切换模型、资源重载、离开世界、VR／TaCZ／Iris。任何新画面或物理异常需保留原始日志和对应模型／配置。

这些游戏检查是剩余验收项，代码和探针完成不等于真实多人收益已经确认。
