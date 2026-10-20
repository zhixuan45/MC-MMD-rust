# 2026-10-20：修复 CI 并行测试配置干扰

失败运行：[Build & Release #37](https://github.com/zhixuan45/MC-MMD-rust/actions/runs/39343125626)，提交 `153c650af819d6ac395607a0623550687dd0714d`。

## 失败阶段与原因

九个平台原生库构建成功；`build-rust-linux-x64` 在 `Test Rust library` 失败，Java 打包因依赖该作业而跳过，并未发生 Java 编译报错。CI 为 252 passed、1 failed、3 ignored，失败断言：

```text
physics::mmd_physics::issue_diagnostics::tests::snapshot_reports_applied_filter_and_real_bullet_contact
src/physics/mmd_physics/issue_diagnostics.rs:294
assertion failed: output.contains("active_stability=Stable")
```

`geometry_proxy_keeps_authored_masks_in_every_stability_mode` 会暂时修改进程全局物理配置，在 Strict/Stable/Relaxed 之间切换。虽然退出时恢复，但其它并行测试可能在切换期间创建或构建世界，读到错误模式。本地默认并行运行曾通过；随后用 16 个测试线程立即复现同一诊断失败，并连带使 `skirt_compatibility_preserves_other_originally_blocked_pairs` 失败（251 passed、2 failed）。这是测试间竞争，不能通过放宽 Stable 断言或仅重跑 CI 解决。

## 修复

- 将现有构建主体提取为接收显式 `PhysicsConfig` 快照的私有方法。生产入口仍在同一位置读取一次全局配置，再调用相同主体，碰撞和物理算法保持原行为。
- 模式测试传入独立配置，保留三个模式和三种作者碰撞掩码的真实 Bullet 断言，并验证应用模式及全局配置未被修改。不再更改全局状态，无须关闭测试并行。
- 另修正 Java 的 `failureOnCallerStillJoinsWorkerAndCompletesSuccessfulJobs`：为该并行异常测试显式传入四个逻辑处理器。它需要后台 worker，而生产预算在主机只有两个逻辑处理器时为零；测试不应依赖宿主核数。没有修改生产线程预算，也不再需要通过全局 JVM CPU 覆盖才能运行这套 Java 测试。

## 本地验证

```sh
cargo test --offline --lib --manifest-path rust_engine/Cargo.toml
cargo test --offline --lib --manifest-path rust_engine/Cargo.toml -- --test-threads=4
cargo test --offline --lib --manifest-path rust_engine/Cargo.toml -- --test-threads=16
bash gradlew --offline --no-daemon :common:test -x :prepareNativeResources
bash gradlew build --offline --no-daemon --stacktrace \
  -I /tmp/transparency-neoforge.gradle -PnativeResourcesPath=build/transparency-native
```

Rust 默认命令通过（253 passed、0 failed、3 个原有 ignored）；4 和 16 线程各连续运行 12 次全量测试，24 轮均通过。Java 在云端默认两核环境通过全部 235 项测试，未设置 `ActiveProcessorCount`。

全量 Gradle build 使用前轮已有的 Linux 原生库 staging；临时 init script 仅为云端选择已放行的官方 Mojang 清单入口，详情见前轮透明修复报告。线上十平台构建与通用 Jar 打包以新提交的 GitHub Actions 结果为准。本轮不改 CI 跳过条件，不将测试失败标成允许失败，也不取消任何断言。
