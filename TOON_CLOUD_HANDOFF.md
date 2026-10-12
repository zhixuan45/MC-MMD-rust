# 三渲二修复：云端精简交接

## 目标与起点

请检查仓库现状后完成两轮修复，不重复覆盖已存在的有效实现。用户在 Minecraft 1.21.1、Complementary 4.7.1 下反馈：关闭三渲二时人物明暗尚正常，开启后人物明显发白、头发和衣服层次丢失，发束与长袖出现碎黑线，描边锯齿明显。两张截图机位和人物占屏大小不同，不能直接按像素作曝光或锯齿定量对比。第一轮处理环境光与透明遮罩，第二轮处理轮廓采样锯齿和运动闪烁。

本地仓库为 `C:\tmp\mc-mmd-rust`，分支 `feat/1.21.1-neoforge`，交接时 HEAD 为 `8f58c0f82128e016a2444e8e137712365b044a6d`。第一轮已在本地实现，但尚未提交；云端分支未必包含这些修改。先完整读根目录 `index.md`，检查 `git status`/`git diff`，核对下述实现是否存在，再决定补做范围。仓库还有物理、纹理、诊断等无关改动，不得 reset、clean 或覆盖。文件路径下文均相对仓库根目录。

## 第一轮：核对并补齐

环境光入口在 `common/src/main/java/com/shiroha/mmdskin/render/shader/`：

- `ComplementaryToonLighting.java`：从当前包经 Iris 预处理的实体/手部顶点和片段源码，复用原 `GetLighting`、太阳与上方向计算。非恒定全局初值移到片段函数内按依赖顺序初始化；保留色阶阴影染色，天空光与方块光分别输入。太阳方向使用原 `gbufferModelView`，不能依赖某些手部程序会优化掉的逆矩阵。避免额外白色高光与亮度重复相乘，保持现有线性颜色/MRT 写出约定。
- `ToonUniformBridge.java`：Iris `apply()` 后按名称、独立 location 同步活动光照 uniform、矩阵和 sampler 单元；采样器使用 Iris 已绑定的纹理。缓存关联实际 shader 实例，不能只按可复用的 GL program 编号缓存；不支持或缺失输入必须显式回退并记录，不能拿未初始化数据照亮模型。
- `ToonOutputProfile.java` / `ToonShaderBase.java`：光照源码参与变体缓存键，布局和光照契约校验失败走既有标准渲染回退；GUI/无 Iris 不启用包内光照。基础 Toon 路径移除固定 0.2 亮度下限，高光随照度衰减。BSL 仅保持原输出适配，不冒称已完成同等环境光适配。

`common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisToonMetadata.java` 读取实际顶点/片段源码。CPU/GPU 两条 `renderToon` 链均在 Iris apply 后、主体绘制前同步环境光，传入 shader 实例和 `LightingHelper.LightData` 的 block/sky 两项。

描边集中在 `common/src/main/java/com/shiroha/mmdskin/render/outline/OutlineRenderPass.java`。输入包含位置、法线、主体使用的动态 UV 和逐材质纹理回调；CPU/GPU 均接入。`toon_outline_body.vert.glsl` 传 UV，`toon_outline_body.frag.glsl` 只采贴图 alpha：小于 0.1 裁掉，剩余覆盖率乘 `OutlineAlpha` 和外缘淡出各一次。RGB 只由 `OutlineColor` 决定，无纹理材质禁用遮罩，避免采上一材质残留绑定。正常/异常后恢复纹理单元、绑定与属性。

两条调用入口：`common/src/main/java/com/shiroha/mmdskin/render/backend/opengl/OpenGlModelRenderer.java` 与 `common/src/main/java/com/shiroha/mmdskin/render/backend/gpu/GpuSkinningModelRenderer.java`。GLSL 资源在 `common/src/main/resources/assets/mmdskin/shader/`。

## 第二轮：继续实现与验证

第一轮透明裁切不等于几何抗锯齿；现有 facing/smoothstep 也不能证明轮廓获得像素覆盖抗锯齿。先分别定位透明孔洞碎线、三角形外缘阶梯和运动中的亚像素闪烁。不能通过全屏模糊、加粗描边、删除透明片、修改模型或压暗整个场景掩盖问题。

候选方向是独立描边覆盖缓冲，经适当采样/resolve 或局部抗锯齿后合成；这是待设计方案，不是已验证的固定实现。先追踪实际 framebuffer、viewport、draw buffers、深度附件格式、Iris MRT/每附件混合与后处理顺序，确认抗锯齿发生在哪一阶段。核实能否利用现有光影抗锯齿，以及独立缓冲与目标深度的兼容性，再选择最小可维护方案。引用技术结论时查验官方 OpenGL/Iris 文档或源代码，不能假定所有目标都可直接挂载、复制或 resolve。

两后端只适配输入，资源、合成与状态管理留在独立描边模块。覆盖 resize、GUI 与世界切换、光影重载、关闭描边、退出和异常释放；避免逐模型逐帧创建 FBO/纹理，估算显存与额外绘制成本。合成不能让角色穿墙或污染主体/法线/材质 MRT，必须保持透明度只合成一次和颜色编码一致。恢复 framebuffer、draw/read buffers、viewport、program、VAO、纹理、混合、深度及剔除状态，并考虑 RenderSystem 的状态缓存。

保护既有 GUI：正交宽度为 `OutlineWidth * (modelView 第一列长度 / abs(modelRootScale))`，根缩放为 `0.09 * modelScale`；不换成固定 GUI 宽度。GUI 使用固定视线和临时 `GL_LESS` 防同深度薄片覆黑，结束恢复原深度函数。世界既有宽度/距离淡出、人脸过滤、材质可见性与有效 alpha 保留。Iris 阴影 pass 继续跳过 Toon/描边。不要扩展到物理、JNI、PMX 解析或蒙皮算法。

遵循仓库 AGENTS：中文沟通、简短注释、单个代码文件超过 1000 行须拆分；跨文件修改先梳理接口，重大架构改变先提交实施计划并按现有规则审批。原 `implementation_plan.md` 包含其它任务，不得覆盖。

## 验证与交付

本地第一轮已通过：公共模块 101 项测试，Fabric/NeoForge 编译，NeoForge remapJar；真实 Complementary/BSL 源码共 120 个程序/选项组合，其中 18 个 Complementary 组合编译原包程序并核对活动 uniform 与同步类型。光照像素回读覆盖昼夜、洞穴、火把、雨天和阴影。描边回读覆盖透明裁切、半透明、RGB 独立、逐材质绑定、无纹理和异常恢复，以及纸娃娃与透视回归。环境亮度由独立来源程序模拟，以上均不是完整 Minecraft/Iris 游戏画面验收。

可复用 `tools/iris-toon-probe/`（`Probe.java`、`LightingProbe.java`、`run.ps1`）、`tools/outline-probe/`（`Probe.java`、`PaperDollProbe.java`、`PassStateProbe.java`、`run.ps1`）及 `ToonOutputProfileTest`。本机使用 Zulu JDK 21：

```powershell
./gradlew.bat :common:test :fabric:compileJava :neoforge:compileJava :neoforge:remapJar --offline
./tools/outline-probe/run.ps1
./tools/iris-toon-probe/run.ps1
```

云端按实际系统/JDK 路径调整，不清理共享缓存或锁。真实光影探针默认只读本地 `D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252`，云端没有这个实例时不能声称已运行；查明可取得的资源，缺失则报告限制并提供本地复验步骤。

第二轮新增实际 shader/pass 的细斜线、透明发片、长袖薄片、不同分辨率/距离/GUI 缩放、相机亚像素移动与前景遮挡对照；检查抗锯齿减少阶梯且没有糊边、黑身、重复 alpha 或资源泄漏。最终需同模型、同机位、同曝光/光影设置，在 CPU/GPU 下对照白天、阴影、夜晚、洞穴和火把，并测试纸娃娃/背包。拿不到真实模型或游戏环境就保留验收未完成，不能用合成测试冒充用户观感改善。

交付代码、构建产物路径和简短验证记录，分清本轮实测、沿用本地记录及未验收项。已有 `TOON_LIGHTING_ROUND1.md` 和 `OUTLINE_RENDER_ISOLATION.md` 可作补充。只收到此 Markdown 不等于获得第一轮未提交代码、光影包或截图附件；务必先核对实际输入。
