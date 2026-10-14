# 三渲二两阶段实现与验证（2026-10-11）

基线：`feat/1.21.1-neoforge`，`8f58c0f82128e016a2444e8e137712365b044a6d`。
本次 PR 包含此前未提交的第一阶段重实现，以及第二阶段描边覆盖率和眼睛发光选项。

## 第一阶段：材质色块与设置

主体共用 `toonDiffuseRamp` 和 `shadeToonSurface`。2～5 档保留相应数量的平坦区，只在边界用有限屏幕导数过渡。
Complementary 原阴影偏移仍使用连续 NdotL，在阴影采样后对漫反射权重分档，固定额外上方向面明暗。
GetLighting 以单位材质求环境照明，保留昼夜、天气、天空/方块光、阴影及动态灯输入，再与 Toon 材质合成一次。
高光和边缘光通过受强度约束的白色色块混合，随照明衰减；阴影 RGB 等设置实际参与最终颜色。
输出继续遵守原线性/MRT 契约。BSL 保留原亮度与输出适配，没有新增其环境光函数适配。

uniform 同步解析逗号声明、核对活动类型和数组大小，逐位置同步矩阵、采样器和其它输入。
缓存关联 shader 实例与来源/目标 program；缺失或类型不匹配显式回退并记录原因。

## 第二阶段：轮廓覆盖率

`OutlineRenderPass` 共用于 CPU/GPU，后端只提供实际蒙皮缓冲、动态 UV、材质遮罩和有效 alpha。
原倒置外壳宽度、世界距离淡出、GUI 缩放补偿与固定视线、临时 LESS、面部过滤和隐藏材质规则保留。
Iris 阴影通道继续跳过 Toon/描边。

`OutlineCoverage` 查询当前 draw/read FBO、viewport、draw buffers、深度对象格式与尺寸及采样数。
对支持的单采样目标，把当前 viewport 的深度以 NEAREST 复制到同格式、两倍宽高的独立缓冲。
描边在 RGBA8 覆盖率缓冲中绘制，使用已经编码的主颜色和预乘 alpha；每个输出像素严格平均对应 2×2 样本，再以 ONE/ONE_MINUS_SRC_ALPHA 合成。
场景颜色不参与滤波；覆盖率为零处直接丢弃。只路由到 profile 所确认的 colortex0，法线/材质等辅助 MRT 不写。
透明纹理、有效材质 alpha 和 silhouette coverage 各计算一次，不对 resolve 后的 alpha 重复相乘。

独立 VAO 避免改写调用者的顶点属性/EBO。
`OutlineGlState` 对应恢复 draw/read FBO、draw buffers、viewport/scissor、program、VAO、array/renderbuffer 绑定、纹理单元/texture/sampler、depth/cull、槽 0 的混合因子/方程/开关与颜色掩码。
改动使用成对原始 GL 调用，保持 Blaze3D 缓存不变；其它槽的混合设置不修改。
前景墙与模型主体依靠复制的场景深度遮挡，不写回场景深度。

资源全局复用，viewport 或深度格式变化时重建；关闭选项、断开连接、游戏关闭、shader 清理时释放。
世界/GUI 使用本次绑定目标与矩阵，不缓存旧场景深度。独立合成 shader 不引用光影包，无需在包切换时重编译；下一次绘制重新读取目标格式与编码。
未知/默认 FBO、MSAA、stencil test、越界 viewport、不支持的驱动或超出资源上限时使用主颜色槽直接描边并限次提示，保持原遮挡。
纹理深度格式查询需要 OpenGL 4.5；独立逐槽混合需要 OpenGL 4.0。更旧驱动保留直接路径。

RGBA8 + 24/32 位深度约需 32 字节/最终像素：1080p 63.3 MiB，1440p 112.5 MiB，4K 253.1 MiB；浮点深度+stencil 最坏 48 字节/像素。
分配按最坏情况限制为 384 MiB，并检查 texture/renderbuffer 尺寸上限。
每个模型增加一次深度 blit 和一次合成 draw，外壳几何提交数量不变，覆盖率阶段约四倍片段采样。
没有时间历史；该实现降低几何轮廓的二值跳变，不能消除所有运动闪烁，主体和原场景 AA 仍由游戏/光影处理。

技术依据：

- [Khronos glBlitFramebuffer](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glBlitFramebuffer.xhtml)：深度复制使用 NEAREST，源/目标深度格式一致；有多采样时禁止尺寸缩放，因此不强行处理 MSAA 目标。
- [Iris 1.21.1 GlFramebuffer](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/gl/framebuffer/GlFramebuffer.java)：按内部深度格式区分 DEPTH 与 DEPTH_STENCIL 的二维纹理附件，并将 DRAWBUFFERS 映射到实际颜色附件。
- [Iris RenderTargets](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/targets/RenderTargets.java)：目标由当前 pipeline 管理，本实现读取当前绑定，不假设固定 Minecraft FBO。

## 眼睛发光

Fabric/NeoForge 三渲二设置新增“眼睛发光强度”滑块，0～300，默认 0；100 对应 shader 强度 1。
配置经统一 ConfigData/ConfigManager/ToonConfig 保存和读取。
CPU/GPU 主体共用眼骨权重顶点遮罩，按实例首次启用时生成，标准渲染不上传 Toon uniform。遮罩只绑定主体绘制，正常和异常路径均在描边前解除；属性 divisor 恢复原值，模型释放时回收缓冲，显存统计包含每顶点 4 字节。

`EyeBoneMask` 识别左右目、左右目返回骨以及常见英文/VRM 眼骨名称，排除头骨、眉毛、睫毛、眼睑与眼镜。使用现有 native 骨索引/权重接口，不新增 JNI。整个眼瞳按原贴图颜色增加独立于环境光的亮度，含灰色虹膜、黑瞳及白色高光；alpha 不变，不再以色度或亮度挑出彩色部分。
TDA 洛天依的 `目` 与 `eye_hi` 都混用 Face.png 多块纹理区域，材质名称和颜色不能划定完整眼瞳。真实网格中 446 个眼骨顶点仅位于眼球空间范围，眼白和其它面部区域绑定头骨，眼瞳遮罩能区分它们。
外围光晕取决于光影包泛光设置，没有独立全屏眼睛模糊，也没有宣称适配所有包的自发光 MRT。
这条自动路径要求眼瞳与眼白具有可区分的骨权重。缺少可识别眼骨/有效权重时限次记录并关闭该实例发光；若其他模型把眼白与眼瞳都绑定同一眼骨，仍需额外资产遮罩，不能把本次 TDA 验证外推到所有模型。不修改模型、贴图或骨骼。

## 验证与复验

```bash
./gradlew :common:test :fabric:compileJava :neoforge:compileJava :fabric:remapJar :neoforge:remapJar --offline
bash tools/iris-toon-probe/run.sh SHADERPACK_DIRECTORY IRIS_JAR LWJGL_EGL_3.3.3_JAR
```

Windows 可使用 `tools/iris-toon-probe/run.ps1` 和 `tools/outline-probe/run.ps1`；需先构建 common。
Linux 探针使用 JDK 21、Gradle 依赖缓存、Mesa EGL；只读 Iris/光影包，不启动游戏。

- 公共模块测试涵盖眼部材质识别、配置往返及 profile 主颜色槽重排。
- Mesa OpenGL 4.5 回读实际生产 OutlineRenderPass：四个亚像素位置、部分覆盖斜边、前景遮挡、alpha 只乘一次、辅助 MRT 不变、程序/VAO/逐槽混合恢复、resolver 异常恢复。
- texture 深度验证：DEPTH_COMPONENT32F、DEPTH24_STENCIL8、DEPTH32F_STENCIL8；切换 viewport 尺寸与非零 origin 后无 GL 错误。
- vanilla/Complementary 回读：2～5 档平坦区、阴影/高光/边缘参数、黑暗衰减、白描边、发光滑块强度递增；黑瞳、白色眼内高光、灰色虹膜参与发光，眼白遮罩不变。
- 真实 Complementary 4.7.1、BSL 8.4.02.2：120 个程序/选项组合，18 个 Complementary 来源程序的活动 uniform、昼夜/洞穴/火把/雨/阴影输入。

构建日志：`/workspace/cloud-setup/logs/toon-stage2-final-build.log`。
像素探针：`build/toon-validation/stage2-probe.log`。
云端完整包只含当前 Linux 原生库；Windows 应从该分支本地构建或使用含 Windows DLL 的 CI 产物。
此前的八文件第一阶段补丁不能安装本次新增功能，保留于历史交付目录而不作为本次源码工具。

未执行完整用户模型/Windows 游戏视觉验收，CPU/GPU 实景、纸娃娃缩放、快速相机移动、透明发片/袖片和光影最终曝光仍需游戏验证。
以上无窗口像素结果不是参考截图观感一致的证明。
