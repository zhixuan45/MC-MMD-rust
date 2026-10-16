# 光影兼容路径 CPU 提交优化报告

日期：2026-10-16，Asia/Shanghai。本次承接多模型优化后的光影性能分析，完成材质求值缓存、重复 GL 状态设置去重、验证及双平台打包；未提交或推送 Git。此前完成报告中的 JAR 已被本轮构建覆盖，以本文校验值识别当前产物。

## 日志依据与判断

用户说明本轮日志全程开启光影，GPU 使用率约 80–90%，帧率未达到设置上限。输入为 `D:/minecraft/.minecraft/versions/1.21.1-NeoForge_21.1.252/logs/latest.log`，本地分析快照为 `build/translucent-performance/shader-session-2026-10-16.log`。

- 环境为 Iris 1.8.14-beta.1+mc1.21.1、BSL_v10.1.8.zip HIGH、RTX 5070 Ti Laptop、Ryzen 9 7945HX，viewport 为 2560×1494。
- 50 条 WORLD 记录中，GPU_PREPARE 和 BATCH_SUBMISSIONS 均为 0，兼容回退持续命中。当前 Iris 外部程序不能消费 Toon 的多材质表，透明绘制仍逐 run 提交。
- 方舟洛天依／杏辞约 5000 次透明 draw／调用，SUBMIT 约 5.4–5.6 ms，回读约 1.3–1.6 ms；旗袍芒种约 9000–10000 次，SUBMIT 约 8.5–9.8 ms，回读约 1.3–1.7 ms。这些是日志区间统计，不是独立游戏帧的分位数。
- 纸娃娃已合批为一次透明 draw，回读为 0，主体绘制约 0.25–0.36 ms；仍有部分姿态复用未命中。阴影先执行原生求值，WORLD 正确复用。
- 本轮只有一个可见模型，也没有同条件关闭光影的日志，无法从中证明多人扩展表现或光影开关差异。

优先降低逐段提交的重复 CPU 工作有明确代码依据。SUBMIT 是 CPU 侧经过时间，可能包含驱动开销或等待；GPU 使用率本身不足以确认唯一瓶颈，实际收益仍需同场景游戏对照。

## 实现范围

1. `DrawMaterialCache` 在一次模型绘制内按材质 ID 懒求值并复用结果，避免同一材质被透明排序切成大量 runs 后重复计算 morph、颜色和 alpha。base alpha 改变时重算；下一次绘制重新创建缓存，读取最新 morph 和全局透明度。缓存内存随材质数增长，不随 run 数增长。
2. `MaterialShaderBinding` 在当前绘制作用域内比较实际颜色、高光、幂次和 alpha pass，仅设置变化字段。Iris 风格颜色 attribute 存在时，固定的 modulator 只设置一次；只有 modulator 的程序仍随颜色变化更新。退出恢复原有宿主状态。
3. `SphereTextureBinding` 对球面模式、乘法／加法因子去重，保留纹理切换和作用域退出时的状态恢复。

4. 透明排序、合批准备和 Iris 兼容逐 run 提交现在共享同一个绘制作用域缓存：材质 morph／alpha 与纹理解析按材质懒求值，避免排序后的重复 run 再次触发 CPU 解析。缓存仍在每次模型绘制结束时丢弃，因此不会跨帧复用动态材质或透明度。

这轮没有减少 Iris 的透明 draw 数、移除 GPU 数据回读或实现外部 shader 的跨材质合批，也没有改变透明排序、绘制顺序和现有兼容门槛。新增缓存每次绘制分配数组和实际使用材质的状态对象，不能把 CPU 成本描述为完全消除。

## 验证

| 检查 | 本轮结果 |
| --- | --- |
| 材质单元测试 | 29 项，失败／错误／跳过均为 0；新增三项覆盖交错材质缓存、base alpha 变化和下一次绘制读取新 morph／全局 alpha |
| 双平台编译 | Fabric／NeoForge compileJava 通过，JDK 21，离线模式；VS Code 关闭后共享 Loom 缓存构建恢复 |
| 新真实 GL 探针 | 重复／变化材质、Toon 高光、Iris 风格颜色输入、modulator 回退、alpha pass、球面模式／因子／纹理和宿主恢复均通过 |
| 原有 GL 探针 | CPU／GPU 提交颜色和深度、透明缓存、25 组 GPU 准备 oracle、计时查询、共享缓冲开启／关闭、纸娃娃合成和状态边界均通过 |
| release JNI 探针 | 当前重建 DLL 的协议、非法 buffer 不求值、未对齐边界、CPU／GPU 阶段及四个独立模型通过 |
| 发布构建 | Fabric remapJar、NeoForge shadowJar 成功，执行了原生资源准备和 release DLL 构建 |
| 包核验 | 两包均含新材质缓存及修改类的字段／引用；内嵌 DLL 与当前 release DLL 的 SHA-256 一致 |

探针使用合成 GL 场景，不启动 Minecraft，不代表已经通过 BSL 实际画面或 FPS 验收。探针编译仍出现三条缺少 Fabric EnvType 注解类的警告，执行全部通过。

本地证据：`build/translucent-performance/cpu-submit-gradle.log`、`cpu-submit-gl.log`、`cpu-submit-native.log`、`cpu-submit-package.log` 和 `cpu-submit-artifact-verification.json`。本轮没有重复执行未改动的 Rust 全库测试，既有全库并行测试隔离限制仍见多模型完成报告。

## 当前产物

| 产物 | 路径 | SHA-256 |
| --- | --- | --- |
| NeoForge | `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar` | `eeba7baf4c672188bd6b3d365664707e1098d480e439397f111cdb0b66d87960` |
| Fabric | `fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar` | `24fedd7c169c81ddda4825da6c92c20aad25cbf13b773a243a356d501493c875` |
| Windows DLL | `rust_engine/target/release/mmd_engine.dll`，4,638,720 字节 | `1f1100723dfe7c24edcaecf20aa77c1c8877e65eb51563675922ddadf3882862` |

DLL 在本轮打包时重新构建，其哈希与此前报告不同；本轮 CPU 提交改动位于 Java 材质路径。上述 JNI 探针使用当前 DLL 重新通过。JAR 继续包含工作树已有的多模型、纸娃娃及物理改动，不应把与更早版本的全部差异归给本轮材质优化。

## 游戏验收与后续方向

重启客户端加载新包，先保持原来的 BSL、模型、分辨率、相机、动作和纸娃娃设置，预热后采集同等时长日志。比较 WORLD SUBMIT、游戏帧间隔及波动，并检查头发／睫毛／服装透明边缘、球面、高光和全局 alpha。draw 数与回读量基本不变属于本轮预期，不能以两项下降作为验收前提。

当前没有这轮新包的游戏 FPS 结果。后续根据实测剩余成本选择 Iris 兼容的有序批量提交、减少透明准备回读，或修复纸娃娃姿态复用遗漏。外部 shader 合批必须先建立可验证的逐材质输入契约，不能直接放开 Toon 合批资格判断。
