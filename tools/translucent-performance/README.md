# 透明排序性能诊断辅助工具

这里仅包含复验源码，不包含第三方模型、生成图像或采样结果。性能与资产口径见根目录 `TRANSLUCENT_PERFORMANCE_HANDOFF.md`。原版渲染约 110 FPS、当前 MMD 约 50 FPS、本轮改善约 20 FPS 三者不能混为同一渲染路径的更新前后数据。

`audit_assets.py <模型目录> <输出JSON>` 使用 NumPy/Pillow 只读解析杏辞、兰序和旗袍系列 PMX、原贴图 alpha、UV 候选及静态分组。输出属于绑定姿态模拟，不代表游戏 FPS。`export_sort_keys.py <模型目录>` 将像素包围盒候选的静态深度键写到脚本所在目录。

`SortBenchmark.java` 是独立 JDK 21 基准；先用 javac 编译，再把导出的 `sort-keys-*.bin` 文件作为 main 参数。它检查串并行结果逐元素相同，交替测量并排除数组复制成本，不模拟 Minecraft 的线程池竞争。

`validation.gradle` 为临时 Gradle init script，可通过 `:common:writeTranslucentProbeClasspath -I tools/translucent-performance/validation.gradle` 生成 `Logs/translucent-perf-20261014/classpath.txt`。使用该 runtimeClasspath 编译和运行 `CacheGlProbe.java`，还需对应平台 LWJGL natives 和可用 GLFW/OpenGL。它创建隐藏窗口，验证 CPU/GPU 输入、当前位置排序、候选复用、显隐恢复和动态 EBO 重建，不绘制完整原模型或 BSL 世界画面。

生成文件写入本地诊断目录，保持模型目录只读。模型参数缺失、贴图格式无法解码、上下文不可用时保留实际错误，不用伪造输出填补结果。
