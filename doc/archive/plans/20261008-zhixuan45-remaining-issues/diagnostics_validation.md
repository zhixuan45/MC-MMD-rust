# 四项问题诊断日志验证记录

2026-10-08，基于 `feat/maid-wheel-and-scaling-fix` 的 `7312496` 增量实现。四项开关位于 Fabric/NeoForge 调试设置，默认关闭，独立于旧物理日志。没有修改模型资产、碰撞资格或骨骼/蒙皮算法。

Java 使用本机 Zulu 21 和项目 Gradle wrapper，保留共享缓存。沙箱最初拒绝 wrapper 锁文件写入，经批准按原缓存完成构建。`common`、`fabric`、`neoforge` 编译通过，`common:test` 最终 82 项通过。

`cargo test --manifest-path rust_engine/Cargo.toml --lib --offline --quiet` 最终 166 项通过；新增诊断定向 5 项覆盖独立标记、静态清单、无物理模型、模式 1/2 姿态以及真实 Bullet 接触和禁碰回读。

`cargo build --manifest-path rust_engine/Cargo.toml --release --lib --offline` 通过。用真实 Java NativeFunc 声明加载 release DLL 和受控空 PMX，验证无效句柄、三个独立类别、关闭时空输出及模型销毁，输出 `JNI_SMOKE_OK`。受控文件保存在 `.tmp/issue-diagnostics-20261008`，不是反馈模型。

两平台 `remapJar` 成功。已核对新包内三语言四项标签及 DLL 内容哈希，两包均包含 `natives/windows-x64/mmd_engine.dll`，与本次 release 产物一致，SHA-256 为 `EC86EF3CEFFDB583B3A6110FF8C9DFE2836D1D446C17B7BCCFA25D148F7FE32E`。

本次可分发产物为 `fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar` 和 `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。目录中还保留其他版本历史产物，它们不属于本次验证。

`git diff --check` 通过，所有修改或新增代码文件均低于 1000 行。未运行真实 Minecraft 界面验收，未取得反馈模型；诊断结果是限频快照与有界候选，不能替代原问题录像或宣称原问题已修复。用户取证步骤见根目录 `ISSUE_DIAGNOSTICS_GUIDE.md`。
