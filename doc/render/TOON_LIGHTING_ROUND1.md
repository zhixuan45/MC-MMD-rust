# 三渲二第一轮修正（2026-10-11）

本轮针对用户的 Complementary 4.7.1 对照截图，实现环境光适配和描边透明遮罩。修改同时覆盖 CPU/GPU 蒙皮路径，保留现有输出缓冲约定、GUI 宽度换算与材质过滤。

主体直接复用 Iris 当前设置预处理后的 Complementary GetLighting 与太阳方向，传入分开的天空光和方块光。在 Iris apply 后，将实际程序的环境色、天气、阴影纹理与矩阵等 uniform 同步到 Toon 程序。材质色阶保留，移除这条路径额外叠加的白色高光；无光影的基础路径不再强制使用 0.2 的亮度下限，高光随照度衰减。光照源码加入变体缓存键，uniform 位置按 shader 实例缓存，防止重载后 GL 程序编号复用。源码契约不完整时沿用标准渲染回退；uniform 同步失败会限次记录并使用基础亮度路径。

描边使用主体的动态 UV，仅采样材质 alpha，小于 0.1 的透明片元会裁掉。剩余纹理 alpha、模型有效 alpha 与外缘淡出各应用一次，描边 RGB 保持 OutlineColor。无纹理材质关闭遮罩，pass 在正常及异常路径恢复纹理绑定、活动单元和属性。

验证：JDK 21 下 `:common:test :fabric:compileJava :neoforge:compileJava :neoforge:remapJar --offline` 通过，公共模块 101 项测试全部通过。`tools/outline-probe/run.ps1` 的像素、逐材质绑定、状态恢复、纸娃娃和透视回归通过。`tools/iris-toon-probe/run.ps1` 使用本机真实 Complementary/BSL 源码验证 120 个程序/选项组合，其中 18 个 Complementary 组合另编译原顶点/片段程序核对 uniform 完整性和同步类型。

光照探针在生产 Toon shader 内回读 RGBA32F，以独立程序模拟 uniform 输入；白材质线性 RGB 的白天约为 `(0.395, 0.318, 0.265)`，夜晚约为 `(0.015, 0.024, 0.039)`，洞穴接近零，火把约为 `(0.249, 0.139, 0.096)`；雨天和阴影均降低直射亮度。该结果证明环境输入参与着色，不能替代运行中 Iris、相机曝光、实际模型与截图观感的游戏验收。

NeoForge 测试包位于 `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。未安装到实例，未进行游戏画面验收。第一轮透明遮罩用于减少透明发片等部位的碎黑线；外轮廓几何锯齿、细线闪烁与专用描边抗锯齿属于第二轮。
