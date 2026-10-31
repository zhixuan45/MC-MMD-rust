# FX 统一验证结果（2026-10-31）

## 构建与自动验证

- 使用 Zulu JDK 21、Gradle wrapper 8.11.1、共享缓存及离线依赖。
- `:common:test :fabric:remapJar :neoforge:shadowJar -x buildRustNative --offline` 通过。353 项 Java 测试，0 失败，1 跳过。Rust 代码未修改，本次使用既有 native 资源，未重新编译 Rust。
- 扩展效果探针在 NVIDIA OpenGL 4.3 上通过 8,394 项检查，包含原版 material_2.0.fx 导入、来源 9 公式和低指数、外置 alpha 采样/UV 翻转/重复、副基础色、副法线、两阶段图像素/alpha/状态恢复，以及跨包 CPU 窗口和 GPU 排序批量一致性。
- 阴影探针通过既有 96 组颜色/深度对照（CPU/GPU、材质 alpha、UV morph、普通/实心阴影、真实 BSL v10.1.8 + Iris alpha-test）。另加入真实 BSL 外置 alpha 补丁编译/链接检查。该 96 组是原路径回归，不能当作外置 alpha 在完整 Iris 游戏管线中的验收。
- 扩展效果探针对生产阴影变换器执行了合成片元程序像素检查：保留 RGB、替换 alpha、保留原后续 alpha 运算，以及关闭效果后还原原采样。完整 Iris ShaderInstance 克隆选择和材质切换仍属于实机验证。
- `git diff --check` 通过。

## 检查中修复

- 批量通道扩展后，旧测试仍断言 336 字节；现检查十通道 480 字节及原通道偏移。
- 主体/阴影交替使用同一个程序缓存导致反复克隆编译；分离为有界缓存，并清理删除程序的 sampler 元数据。
- Iris 包装原入口后，实际采样可能位于辅助函数；按唯一主 sampler 包装两参数采样，并将 helper 放在首次函数定义之前。
- 老 GLSL/预处理后无显式版本的阴影片段不能调用 `texture`；按版本选择 `texture2D`。
- 图节点编译失败时中止图，不再将未执行的节点当作成功输出。
- 图格式对未知选项明确拒绝，避免静默忽略深度等未实现能力。
- 探针使用独立输出，阴影脚本显式编译本次新增变换器，避免误用旧 class。

## 尚需实机验证

1. 安装本次 JAR 并重启对应 Minecraft 实例，固定模型、动作、相机及光影包。比较无光影/目标 Iris 包，CPU/GPU 路径的主体、透明、描边、阴影与水面合成。
2. 外置 alpha：同时包含透明孔洞和半透明区域，检查主体/阴影轮廓、UV morph、材质 alpha morph、逐材质效果开关、实心阴影对照。查看运行时不支持诊断；复杂采样、LOD/bias、额外着色器阶段仍可能回退宿主程序。
3. 世界图：导入目录/ZIP 并选择世界效果，检查 HUD 不受影响、窗口尺寸变化、重载/切换/关闭后资源释放、其他世界后处理的执行顺序，以及帧时间和显存变化。
4. 光照：相同模型与贴图比较 Ray 参考和当前宿主下的法线、金属、高光、AO、自发光及副基础色。此处是宿主光照近似，未复刻 Ray IBL、多光源和整套后处理。

当前复杂效果入口为显式 `.fxgraph.json`，每个节点引用已支持的逐像素颜色 FX。任意多 pass MME Script、深度/历史帧输入、控制器或自定义顶点阶段仍未提供通用转换支持。

构建日志：`build/fx-validation-gradle.log`。扩展 GL 日志：`build/fx-validation-gl.log`。Java 测试报告：`common/build/reports/tests/test/index.html`。
