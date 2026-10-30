# MMD FX 效果包

入口：配置轮盘 → **效果包**。输入本机 ZIP、FX 或资源目录路径，点击导入，再点击某个效果对应的“人物”“场景”或“世界”。再次点击可关闭该目标。

按材质组合：点击右上角 **材质组合**，选择“角色”或“场景”，在当前已加载模型的 PMX 材质列表中逐项选择 FX。可跨多个已导入效果包组合，例如皮肤用包 A 的材质、头发用包 B 的材质、衣服用暖色效果，眼睛关闭效果。每个材质当前选择一个 FX；同一材质叠加多个 FX 尚未提供。

绑定优先于整模默认，也可在整模效果关闭时单独启用。无绑定表示“继承整模默认”，明确“关闭效果”会阻止该材质继承。角色和场景分别保存到 `characterMaterials` / `sceneMaterials`，以模型目录名、材质序号和原材质名定位，名称或序号变化时不套用旧规则；“全部继承”可清除此模型的所有材质覆盖。

人物、放置场景和世界独立保存选择。模型范围填写模型目录名，`*` 表示全部；人物和场景范围分别填写。世界目标是包含人物与场景的整幅世界画面，HUD 不参与此 pass。同一颜色效果同时选到世界和人物/场景时，只在世界画面应用一次；不同效果可以叠加。

效果库：`<游戏目录>/3d-skin/effectpacks/`。配置：`<游戏目录>/config/mmdskin/effectpacks.json`。导入时复制资源，原 ZIP/目录保持不变。每个包生成 `import-report.json`、版本为 1 的 `effectpack.json` 和 `_converted/` 中的材质 JSON / GLSL 颜色函数。运行时以原始 FX 重新解析为准，修改原始参数后点击重载；`_converted` 为导入时的审阅产物，重新导入会重新生成。

## 当前协议

| FX 类型 | 人物 | 放置场景 | 世界 | 兼容范围 |
| --- | --- | --- | --- | --- |
| Ray-MMD 2.0 参数材质 | 支持 | 支持 | 无世界通道 | 基础色、主法线、光滑度、金属度、高光、AO、静态发光 |
| 单 pass MME 逐像素颜色 | 支持 | 支持 | 支持 | 原像素采样、灰度、色调、曝光、反色、gamma 和常见数值运算 |

Ray 公共实现按规范化源码哈希识别，当前支持本次 Tex_1 提供的 2.0 实现；同一实现下可导入多个参数变体。公共实现经过修改、其它版本或没有公共实现的文件会给出未适配诊断。不会执行包内的 MME Script。

Ray 贴图来源支持常量、外部静态贴图和 PMX 主贴图；支持通道选择、UV 翻转/重复、线性缩放/幂缩放、普通/双通道法线、roughness 转 smoothness、线性/伽马 AO。基础色要求 `ALBEDO_MAP_APPLY_DIFFUSE=1`；透明度保留 PMX 贴图与 morph，要求 `ALPHA_MAP_FROM=3`、A 通道、无额外 UV 变换、常量 `alpha=1`。整模预设作为默认，可被逐材质绑定覆盖；没有 PMX 子材质分配表自动推断。

材质转换采用宿主环境照明、连续漫反射和 GGX 高光，保留已有 CPU/GPU 蒙皮、PMX 透明排序、球面层及阴影深度程序。它不会复现缺失的 Ray IBL、灯光、反射或完整色调映射；Iris 的辅助材质缓冲仍沿用原适配协议的中性标记，不会向未知光影包写入猜测的 PBR 数据。开启材质效果会使用模组可编程主体路径，即使全局 Toon 开关关闭。

MME 颜色入口要求 `float4 PS(float2 uv : TEXCOORD0) : COLOR0`，由单个 `PixelShader = compile ps_2_0/ps_3_0 PS()` 引用，并声明 `ScriptClass="scene"`。源画面 sampler 引用 `RENDERCOLORTARGET`，仅允许原 UV 采样。支持顺序的局部标量/向量声明、赋值、swizzle、算术及 `dot/lerp/saturate/pow/clamp/min/max/abs/sqrt/exp/log/sin/cos/floor/ceil/frac/normalize/length/step/smoothstep`。全局常量使用 `const float/float2/float3/float4` 的有限数值。RGB 由效果决定，覆盖率 alpha 保留宿主值。

顶点阶段由宿主几何或全屏三角形提供；显式 `VertexShader=compile ...` 会拒绝导入。邻域采样、深度、多 pass、额外渲染目标、宏/include、动态 uniform、控制器、自定义函数、分支/循环，以及 Ray custom 模型、副法线、视差、动画纹理、材质来源 4/5/7/8/9 等暂未适配。导入诊断列出原因；支持列表也可能在首次 GPU 编译时发现类型错误，这会显示在运行诊断和客户端日志中。源码及 ZIP 路径当前使用 UTF-8。

## Tex_1

导入 `C:\Users\JuziD\Downloads\Tex_1.zip`，可识别 `material_2.0.fx`。活动外部资源为 `Nor2.tga`（强度 0.2、双通道法线）、`smoothness.tga`（R）、`ao.tga`（R）。`albedo.png`、`alpha.png` 来自 PMX 主贴图语义，外部同名文件无需存在；`normal.tga` 未启用。

这个包没有 PMX 和子材质映射，使用时需选择 UV 匹配的模型，并在“材质组合”中指定应使用这些贴图的部位。其“世界”按钮不可用，因为它只有模型材质通道。可用仓库 `examples/mmd-effects/` 下的灰度和暖色 FX 验证三个目标的颜色变换。

## 验证

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MMD_FX_TEST_ZIP = 'C:\Users\JuziD\Downloads\Tex_1.zip'
.\gradlew.bat :common:test
.\gradlew.bat :common:classes :common:writeSphereProbeClasspath -I tools/sphere-texture-probe/classpath.gradle
.\tools\effectpack-probe\run.ps1 -FxZip $env:MMD_FX_TEST_ZIP
```

隐藏 GLFW 探针验证真实包导入、人物/场景作用域、颜色去重、生产 GLSL 330/430 变体、真实 TGA 上传、像素变化、AO/光滑度、覆盖率、sampler/纹理恢复、世界 pass 状态和重载释放。探针使用自己的未压缩 TGA 解码器；生产贴图解码继续走现有 Rust 图片加载器。

组合验证额外使用两个资源包的 Ray 材质、灰度、暖色、关闭与继承，并比较逐 draw、多个贴图窗口和 GPU 排序/间接提交的输出。普通材质行保持 96 字节；组合变体使用 432 字节的材质行携带效果选择和七通道数据，贴图加入原有批量窗口，不按效果重新聚组透明三角形。

仍需在 UV 相符的真实人物/场景中检查视觉结果、CPU/GPU 模式、Iris 兼容、透明遮挡、HUD 和效果包界面。离屏像素检查不能替代这些实机验证。

参考：[Ray-MMD 材质说明](https://github.com/ray-cast/ray-mmd/blob/master/Materials/README.md)、[Ray-MMD 框架](https://github.com/ray-cast/ray-mmd/wiki/%E5%9F%BA%E7%A1%80)。第三方材质及贴图不随模组分发。
