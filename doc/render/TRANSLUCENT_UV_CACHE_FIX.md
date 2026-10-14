# 静态 UV 透明候选缓存修复

基线：`feat/1.21.1-neoforge` 的 `5bee123117b9dbc7e7336fa98468482ecf31bdcf`。保留交接报告中的候选缓存、像素覆盖细化和 CPU 并行排序。

## 已确认的代码路径

GPU 后端对没有 UV morph 的模型也创建 `skinnedUvBuffer`。此前绘制输入按该缓冲是否分配选择 UV 来源和版本，因此普通骨骼动画每次更新都会触发 GPU UV 回读，并使透明候选缓存失效。CPU 后端即使不更新 UV，也始终传入动画版本，产生同类缓存失效。

生产 compute shader 在 `UvMorphCount == 0` 时直接把原始 UV 写入输出。因此无 UV morph 时，排序筛选可以直接读取初始化时保留的 CPU UV，无需等待 GPU UV 输出。

## 修复范围

CPU/GPU 的排序输入通过 `TranslucentDrawOrder.Inputs.cpu/gpu` 显式传入 UV morph 能力。无 UV morph 使用稳定的原始 UV 和版本 0；有 UV morph 继续使用当帧 CPU/GPU UV 和动画版本。

当帧蒙皮位置、深度计算、全局透明排序、动态第一人称索引、材质显隐和 alpha 资格失效规则保持原行为。未改变贴图、透明度、BSL/Toon shader 或物理。

对交接报告中的杏辞（40181 顶点），GPU 路径每次相应更新不再需要回读 321448 字节 UV。该数值是由顶点数计算的传输量，不是测得的等待时间或 FPS 收益。当前位置的同步回读与大量材质 runs 仍然存在。

## 验证边界

新增回归通过生产输入工厂检查：已分配 compute UV 缓冲但没有 UV morph 时，120 次动画版本变化仍可复用候选；CPU 静态 UV 同样复用；CPU/GPU UV morph 更新仍使缓存失效，GPU 动态 UV 仍来自 compute 输出。

提交时由现有 GitHub Actions 执行 Java 测试和发布构建，结果以对应工作流为准。当前聊天的云执行环境尚未就绪，未运行本地或离屏 GL 测试，也未获得新的游戏 FPS。

Windows 游戏验收使用相同模型、动作、镜头、BSL、Toon 和 GPU 蒙皮设置，对比此修复的父版本与修复版本。记录帧时间中位/P95，并检查发丝、睫毛、白丝、裙摆、长袖和 UV morph 的透明遮挡。原版约 110 FPS 是独立场景基准；本修复不声称已经恢复该帧率。
