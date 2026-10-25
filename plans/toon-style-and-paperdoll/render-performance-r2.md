# 渲染性能 r2 验证

日期：2026-10-25。保留现有工作区改动；本记录不代表新包已安装或游戏 FPS 已提升。

## 实机日志

2026-10-25 17:07 的杏辞窗口，配置为 GPU 蒙皮、物理 60 Hz / solver 10、`maxFps=120`。17:07:07 与 17:07:12 的 `native.physics.SHADOW` 分别为 1.730 ms、2.057 ms；GPU compute 查询中位数约 0.014 ms。世界透明 120 帧采样中 `WATER_READBACK` 与 `OVERFLOW_SYNC_READBACK` 均为 0。WORLD 与 PAPERDOLL 复用 SHADOW 姿态，纸娃娃物理求值为 0。

运行中的客户端仍加载 `mmdskin-neoforge-physics1201-type2-as-type1.jar`；当时配置无新二维化滑杆值，按兼容默认值 0 处理，纸娃娃 cache hit 也是 0。该窗口不是新 r2 包的运行结果。

## MetadataClear 测量与集成

NVIDIA RTX 5070 Ti Laptop / OpenGL 4.3 的 GPU 微基准：

| 缓冲区 | `glClearBufferData` | `MetadataClearStage` | 中位数差值 |
|---|---:|---:|---:|
| 1920×1080 | 0.2174 ms | 0.1367 ms | 37.14% |
| 2560×1600 | 0.4268 ms | 0.2768 ms | 35.15% |

这是清除微基准，不是游戏整帧时间或 FPS 提升。helper 默认只在 NVIDIA 启用，`mmdskin.metadataClearCompute=false` 回退原清除路径。完整零填充、边界字、程序与 buffer state 恢复、fallback 开关探针通过；`BlockEntityTransparencyGlProbe` compute 开 / 关两组及 `WorldTransparencyTimingGlProbe` 均通过。逐层透明 batch 虽将 draw 数从 169 降到 9，但 CPU submit 从 1.1 ms 增到 1.7 ms，因此该实验未纳入实现。

## 构建与包校验

定向 Java 测试 45 项通过（配置、纸娃娃复用、透明阶段策略 / 回退及 Iris 目标）；Fabric `remapJar`、NeoForge `shadowJar` 成功。两个 r2 包均含 `MetadataClearStage`、CPU 并行模型入口、Toon shader、纸娃娃 / Iris 兼容类及 Windows 原生 DLL；包内 DLL 与 `rust_engine/target/release/mmd_engine.dll` SHA-256 一致：`d46d77b581d49e882fedb76fd125687bfb7fb7991e2d20b59105c4f78752f46f`。

- Fabric：`build/toon-style-validation/mmdskin-fabric-1.10alpha-1.21.1-toon2d-r2.jar`，SHA-256 `fd8d9dbe4dc95e1cbc61aa898441e01f4cccab1606d85578ef61365398568e19`。
- NeoForge：`build/toon-style-validation/mmdskin-neoforge-1.10alpha-1.21.1-toon2d-r2.jar`，SHA-256 `50f5839302acc8ddd3fd7c52923abcc0aa3ef90f93f12f500d13c5f20c3b7761`。
- 文件校验明细：`build/toon-style-validation/artifacts-r2.json`；Gradle 输出：`build/toon-style-validation/build-r2.log`。

游戏内二维化、纸娃娃缓存与帧时间 A/B 仍待新包运行验证。
