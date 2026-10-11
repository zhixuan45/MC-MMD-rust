# 日志复核与复现

原始日志保持只读。运行 `./tools/latest-log-audit/analyze-log.ps1 -LogPath '<latest.log 的绝对路径>' -OutputDirectory '<输出目录>'` 可生成带原行号的 anchor/contact/world/texture CSV 和计数摘要。对新日志使用另一个输出目录，避免覆盖本次证据。摘要中的 peak 是已有采样最大值，不能当作连续模拟峰值或视觉误差。

自动化验证在仓库根目录运行：

```powershell
cargo test --offline --manifest-path rust_engine/Cargo.toml --lib -- --test-threads=1
cargo test --offline --manifest-path rust_engine/Cargo.toml --test gpu_sdef_payload
cargo test --offline --manifest-path rust_engine/Cargo.toml --lib twenty_native_model_handle_cycles_return_bullet_objects_to_baseline -- --ignored --test-threads=1 --nocapture
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :common:test :fabric:compileJava :neoforge:compileJava
```

生命周期用例需单独运行，因为 Bullet 计数与 native handle 注册表是进程级共享状态。该测试使用合成模型直接调用现有 native DeleteModel 入口，不创建 JVM，不验证 Java 到 JNI 参数转换，也不证明整个 Minecraft 进程内存归零。

取得合法模型资源后，以同一 PMX/VMD、相机和模组组合做 A/B：默认合成裙环与在启动游戏的进程环境设置 `MMD_SKIRT_CROSS_SYNTHESIS=off`。该环境变量仅跳过自动合成裙环，原 PMX 关节、物理碰撞、重力和动画保留。恢复默认需删除变量并重启。每轮记录 Jar/native DLL SHA-256、模型版本，在加载后 2/10/30 秒和动作首帧取样并录像。开启旧物理诊断可获取 `SKIRT_CROSS_GROUP`、`SKIRT_CROSS_CREATED` 与初始化后的 `INIT_ANCHOR_PRE_STEP`，区分建约束绑定姿态、初始化运行姿态与后续求解。

问题诊断默认关闭。开启后每 2 秒保留摘要、当前接触、最差锚点和速度摘要，首帧/每 30 秒保留完整明细。关闭再开启相应诊断可立即取新的完整快照；调试代码可调用 `ModelIssueDiagnostics.requestSnapshot()`。需要原有每 2 秒完整明细时使用 JVM 参数 `-Dmmdskin.diagnostics.full=true`。这些读取不修改物理状态，摘要的 omitted/truncated 字段明确记录省略范围。

渲染仍需三个固定场景：光影关/Toon开、光影开/Toon关、光影开/Toon开，覆盖透明/不透明、描边与阴影，保存截图及 FBO/draw-buffer/GL error 信息。舞台仍需双客户端确认开始、结束、切换会话和断线的远端状态。本次资源不可得，因此这些步骤尚未执行。
