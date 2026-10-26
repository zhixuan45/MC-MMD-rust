# 长帧与零步长导致物理显示姿态闪回

## 已确认的缺陷

默认 upstream1201 路径的 `MmdModel::update_physics` 在零/负/非有限步长、以及长帧恢复分支提前返回，遗漏 `writeback_physics_bones`。动画阶段此前已重新计算全局骨骼矩阵，因此该次求值生成的 CPU 顶点或 GPU 蒙皮矩阵可能退回纯动画姿态。Bullet 动态体和关节锚点仍然保持原姿态，物理开关也没有关闭；下一次正常求值又恢复物理显示，形成偶发闪回。

上一版的延迟重置修复处理了显式 reset 请求，没有覆盖这两个提前返回分支。legacy-physics 路径已有相应回写，但默认 upstream1201 路径没有；本次回归测试直接覆盖默认路径。

日志记录 `physics_fps=120`，现有长帧门槛为 `5 / physics_fps`，即约 41.667 ms。平均 30 FPS 也可能因个别帧超过门槛而触发。光影负载、相机变化造成的帧耗时变化与这个触发条件相容，但日志没有逐帧步长，不能单凭它证明这些因素的因果关系。零步长分支则可由姿态预求值触发，无须掉帧。

## 修复

这两个分支在归还物理世界前调用已有的 `writeback_physics_bones`，发布当前 Bullet 姿态、设置物理骨骼保护并更新非物理子骨骼。保留原来的跳步、速度恢复和显式 reset 时序。

## 模型复现

从用户指定的 Google Drive `using models` 读取 `TDA 方舟指令[洛天依][杏辞] Ver 1.00`。模型资产仅用于本地探针，未加入仓库。PMX SHA-256：

`3415d73fca12fc97f18f8198f15b906c997f9d03570585919e19c778eb9e4e20`

运行 `probe_physics_gap`，不加载 VMD，以 60 FPS 连续求值 180 帧后插入指定步长。探针使用默认物理配置，并设置 `static_collider_scale=1`、`inertia_strength=0`，分别比较显示骨骼与 Bullet 关节锚点。这是引擎级确定性复现，没有启动 Minecraft 或执行 GPU 绘制。

| 版本与输入 | 动态骨骼数 | 退回动画位置的骨骼数 | 最大显示跳变（模型单位） | 最大求解器锚点变化 |
| --- | ---: | ---: | ---: | ---: |
| 修复前，60 Hz / 100 ms / GPU 输入路径 | 429 | 388 | 8.430601 | 0 |
| 修复后，60 Hz / 100 ms / GPU 输入路径 | 429 | 0 | 0 | 0 |
| 修复后，120 Hz / 50 ms / GPU 输入路径 | 429 | 0 | 0 | 0 |
| 修复后，120 Hz / 0 ms / GPU 输入路径 | 429 | 0 | 0 | 0 |
| 修复后，120 Hz / 50 ms / CPU 蒙皮 | 429 | 0 | 0 | 0 |

上述每种情况的 `physics_enabled` 都为 true。退回计数定义为前一帧相对绑定位置偏离超过 0.01，而插入帧与绑定位置距离小于 0.0001；没有位移的动态骨骼不计入退回数。

复现命令（PMX 路径按本地位置替换）：

```sh
cargo run --offline --manifest-path rust_engine/Cargo.toml --example probe_physics_gap -- '<model.pmx>' 0.05 120 gpu
cargo run --offline --manifest-path rust_engine/Cargo.toml --example probe_physics_gap -- '<model.pmx>' 0 120 gpu
cargo run --offline --manifest-path rust_engine/Cargo.toml --example probe_physics_gap -- '<model.pmx>' 0.05 120 cpu
```

## 自动验证

新增 `upstream_render_pose` 集成测试在修复前因显示姿态丢失而失败，修复后通过。测试覆盖 60/120 Hz、CPU/GPU 输入路径、零/负步长、50/100 ms 长帧、NaN/正负无穷步长、非物理子骨骼、CPU 顶点，以及异常步长不消费待处理 reset 请求。

```sh
cargo test --offline --manifest-path rust_engine/Cargo.toml --lib --tests -- --test-threads=1
```

结果：138 项通过，0 项失败，2 项忽略。单线程参数用于隔离进程级物理配置及对象计数。

## 实机验证边界

本次未取得视频附件，也未运行 Windows Minecraft + NeOculus 的实际绘制。不能据此声称所有光影设置下的闪回、相机距离效应或重启物理后约 30→80 FPS 的变化均已消除。

下一轮应使用包含本次 Rust 原生库的 Windows x64 包，按用户顺序检查：开启光影且关闭三渲二 → 关闭光影 → 手动关闭再开启物理 → 再开启光影；每一步分别固定近/远相机，记录帧耗时和闪回。再覆盖三渲二开启状态。必须重建并打包原生 DLL；只重编 Java 或使用旧 DLL 不会包含此修复。
