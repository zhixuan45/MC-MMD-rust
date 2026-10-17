# 2026-10-17 Gradle 启动错误修复记录

用户报告 Gradle 编译/打包出现 `Cannot read the array length because "<local6>" is null`。IDEA 日志显示，错误位于 `DefaultModuleRegistry.findJar:324` → `DefaultDaemonStarter.startDaemon:152` → Tooling API 启动阶段；当次请求为 `:neoforge:assemble`，尚未进入 Java 源码编译。

本机同版本 Gradle 8.11.1 有两份 wrapper 安装。旧腾讯镜像 URL 对应的目录只剩 `bin` 下两个脚本，整个 `lib` 缺失；官方 URL 对应目录完整。独立 registry 探针对旧目录精确复现同一异常文本和行号，完整目录正常。此证据确认残缺安装能够产生本次错误；IDEA 日志未直接输出当次 installation path，不能声称已从日志观察到 IDEA 选择了哪个目录。

完整源目录：
`C:\Users\JuziD\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1`

修复目录：
`C:\Users\JuziD\.gradle\wrapper\dists\gradle-8.11.1-bin\eac4u065zwes5phgltp5f9b9e\gradle-8.11.1`

从完整同版本安装补齐 312 个缺失文件；两个已有脚本哈希一致，未覆盖。不删除缓存、不更换 Gradle 版本、不修改构建源码。文件清单计数保存在 `Logs/multi-model-performance-20261017/gradle-install-repair.json`。

修复后 registry 探针正常。使用修复目录的 Tooling API、IDEA 当次的三个初始化脚本与 Zulu JDK 21 执行 `:neoforge:assemble`，结果 `BUILD SUCCESSFUL in 3s`，13 项任务，5 项实际执行。

另一次强制重编译执行 `:common:compileJava :fabric:compileJava :neoforge:compileJava :neoforge:shadowJar --rerun-tasks --offline --stacktrace --console=plain`，结果 `BUILD SUCCESSFUL in 12s`，12 项任务全部实际执行。因此已有源码重编译与 NeoForge 打包成功证据，不只依赖 UP-TO-DATE。仍有现存的 Mixin 编译提示、Rust 链接提示和 Gradle 弃用提示，没有将这些提示当作本次阻断错误。

证据均位于 `Logs/multi-model-performance-20261017/`：`compile-before.log`、`compile-rerun.log`、`registry-before.log`、`registry-after.log`、`assemble-tooling-after.log`、`gradle-install-repair.json`。该目录是本地忽略的诊断记录，不自动进入版本提交。

尚未在 IDEA 界面点击重跑，也没有据此进行游戏性能验收。修复解决的是构建启动问题；后续性能方案见 [BSL/阴影实施计划](shadow-bsl/implementation_plan.md)。
