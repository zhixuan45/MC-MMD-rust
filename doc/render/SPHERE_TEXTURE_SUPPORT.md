# PMX 球面贴图修复与验证

2026-10-15。用户报告真珠模型的珍珠在 PMXView 中呈蓝粉色，Minecraft 中呈黑色；并确认游戏使用 Toon。截图中的材质使用主贴图 `衣.png`、球面贴图 `珍珠.png` 和加法模式。

代码缺口已经确认：PMX reader 读出环境混合模式，但运行时材质没有保留；旧 JNI 模式函数恒返回 0；Java 材质加载器只读取主贴图；Toon 主体没有球面采样或混合。仅检查贴图文件存在无法修复这条缺失链路。

现在 PMX 模式保留到 Rust 材质，通过独立 `material_sphere.rs` / `SphereMaterialNative` 查询，原 Java 方法名委托新查询。没有向超过千行的 `native_func.rs` 添加代码。VRM 默认关闭球面层，PMD 原生加载仍未实现。模式以 PMX 字段为准，PNG 文件也可用作 SPA；纹理解码按内容识别，支持实际 PNG/BMP 内容的 `.spa`、`.sph` 文件。

Java 异步预加载与材质加载补齐球面资源，沿用 `TextureRepository` 的引用计数、显存统计与实例退出释放。`SphereTextureBinding` 在 CPU/GPU 共用的子网格绘制作用域内选择未被活动着色器采样器占用的纹理单元，恢复纹理、sampler 和 active unit；无球面纹理模型跳过额外查询。透明批次在支持球面层时按球面材质区分，避免同一主贴图的不同球面层被错误合并。

Toon 主体按视空间法线映射球面 UV。原生解码已经翻转图像 Y，因此正法线 Y 映射到上传纹理的上侧。模式 1 对表面颜色相乘，模式 2 相加；球面贴图 alpha 不改变表面覆盖或透明度。材质 morph 的球面乘加因子独立读取 56-float 缓冲偏移 20–23、48–51，RGB 算法参考 [Saba 的 MMD 片段着色器](https://raw.githubusercontent.com/benikabocha/saba/master/viewer/Saba/Viewer/resource/shader/mmd.frag)。描边、阴影贴图与眼部遮罩继续使用各自原有输入。

这轮覆盖 Toon 的 CPU/GPU 共用主体，以及已适配的 BSL/Complementary Toon 输出变体。关闭 Toon 后的原版/Iris/外部自定义材质程序没有新增球面公式；PMX 模式 3 的扩展 UV 子纹理也没有实现，保留原模式，不把它误画成普通球面层。

验证通过 Rust `cargo check --lib`、球面 JNI 2 项测试、纹理解码 4 项测试；Gradle `:common:test :fabric:remapJar :neoforge:shadowJar` 成功，153 项 Java 测试，0 失败/错误。旧光影探针缺少当前主体要求的 `MaterialColor` / `MaterialSpecular` 输入，已修正探针输入；随后 Iris 126 组程序/选项组合及现代 Complementary 12 组真实程序的主体/描边编译与回读通过，日志在 `build/sphere-texture-probe/iris-toon.log`。

`tools/sphere-texture-probe/Probe.java` 在隐藏 NVIDIA OpenGL 3.3 窗口中使用生产 Toon、材质加载器和子网格绘制验证：SPA 黑底叠色、SPH 逐通道乘色、关闭恢复、表面 alpha、morph、纹理单元避让、正常/异常状态恢复，以及两个不同球面材质的透明排序批次。真实只读输入为 `D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\3d-skin\EntityPlayer\真珠_眼部修复\星穹铁道—真珠2.pmx`。JNI 和材质加载器确认“珍珠”模式 2、主贴图 `衣.png`、球面贴图 `珍珠.png`，两个文件均加载成功。

离线对照在合成球面网格上使用该真实球面 PNG，并用不透明黑色主贴图隔离 SPA 贡献；不是原模型完整网格或游戏截图。关闭回读 `[0,0,0,1]`，启用回读约 `[0.424,0.659,0.859,1]`。A/B 图在 `build/sphere-texture-probe/sphere-ab.png`，完整输出在 `build/sphere-texture-probe/sphere.log`。原始 PMX 与贴图均未修改。Minecraft/Iris 实际场景、原模型遮挡及观感仍须游戏复测。

复验命令：先使用 JDK 21 编译 common，再执行 `gradlew.bat :common:writeSphereProbeClasspath -I tools/sphere-texture-probe/classpath.gradle`，之后执行 `tools/sphere-texture-probe/run.ps1 -PmxPath '<实际 PMX 路径>'`。

测试包为 `fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar` 和 `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`，包含两个新桥接/绑定类、修改后的 GLSL 和 Windows x64 原生库。两包内 DLL 与本轮 Release DLL、实际探针加载 DLL 的 SHA-256 一致：`FB60CA2F86E8DF73E552BE4698D7F1492D5AD87EE7634423B33FB431E75E43B9`。本轮没有提交、推送或安装到游戏；工作区既有物理改动保留，测试包以当前工作区构建，不能作为纯球面修复与其他物理版本的隔离 A/B。
