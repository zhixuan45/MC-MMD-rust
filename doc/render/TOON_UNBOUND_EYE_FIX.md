# Unbound 5.x 与完整眼瞳发光修复核验

2026-10-11，基于用户指定的 Minecraft 1.21.1 / NeoForge 21.1.252 实例、ComplementaryUnbound_r5.9.3.zip 和 TDA 方舟指令[洛天依][杏辞] Ver 1.00。生产代码已修改，模型、贴图、实例配置及安装 Jar 未修改。此前诊断见 [diagnosis.md](Logs/iris-toon-unbound-20261011/diagnosis.md)。

Complementary 5.x 提取后的光照函数原先遗漏 `shadow2D` 的 core GLSL 转换，导致实体、半透明实体、发光实体和手部的 Toon 片段编译失败。现代与旧版路径现在共用返回 vec4、内部使用 `texture()` 比较采样的包装函数。没有关闭阴影采样或 Toon，也没有降低 GLSL profile。新增现代源码回归和真实 r5.9.3 三维度四类程序主体/描边编译探针。

眼瞳发光按用户确认包含整个虹膜、中央暗色瞳孔和眼内高光，眼白排除。原实现仅按材质名启用，再用色度与亮度过滤像素：目标材质 `目` 未命中名称，`eye_hi` 命中，灰色/暗色/白色细节又被过滤。直接扩大材质范围同样不可靠，因为 `目` 共用 Face.png 的其它脸部纹理区。只读 PMX 网格与眼骨权重检查证实，目标全部 446 个眼骨顶点只位于眼球表面，眼白与其它脸部网格绑定头骨。

`EyeBoneMask` 从现有 native 骨名、四槽骨索引和权重生成每顶点遮罩。CPU/GPU 使用同序顶点，在 Toon 主 pass 绑定同一静态属性；完整贴图 RGB 参与发光，不再以饱和度或亮度剪掉眼瞳细节，透明度仍按原规则处理。黑瞳保持原图的深色，不额外填白。`EyeEmissionBuffer` 首次启用时创建，先初始化 CPU 所需 native 骨数组，复制不完整则拒绝使用。绘制正常/异常均清理属性、恢复 Iris 可能留下的 divisor，实例释放时回收缓冲。遮罩显存为每顶点 4 字节，目标完整模型 40,181 顶点共 160,724 字节，计入两端显存统计；没有逐帧重新上传。

自动识别仍依赖模型作者的眼骨命名，以及眼瞳和眼白能通过骨权重区分。缺少眼骨或有效权重时该实例不启用发光并限次记录。如果其它模型把眼白也绑定到同一眼骨，需要额外资产遮罩；本次验证不代表这类模型已经支持。外围光晕仍取决于光影泛光。

Zulu JDK 21、默认共享 Gradle 缓存下，`:common:test :fabric:compileJava :neoforge:compileJava :neoforge:shadowJar --offline` 通过，共 117 项测试、零失败。最终代码的测试包位于 `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`，SHA256 为 `138716CADCAAFE51C7E0DE7DC963C615C7863FDED11F28A8CFBFB47550ADE831`。已检查包含 EyeBoneMask/EyeEmissionBuffer、更新后的顶点 shader 和 Windows x64 native 库。构建记录见 [gradle-validation.log](Logs/iris-toon-unbound-20261011/gradle-validation.log)。

隐藏 OpenGL 探针通过旧 Complementary 4.7.1 / BSL 8.4.02.2 的 120 个程序/选项组合，以及 r5.9.3 三维度四类程序的 12 组主体/描边编译。vanilla 和旧 Complementary 的实际 fragment 回读确认黑瞳、白色眼内高光、灰色虹膜参与发光，眼白遮罩保持不变，alpha 不变。记录见 [shader-validation.log](Logs/iris-toon-unbound-20261011/shader-validation.log)。生产眼瞳缓冲另通过 20 轮创建/关闭、初始化顺序、属性 divisor 恢复、异常释放和显存计数；此 native 端口检查使用测试替身，不冒充 JVM 游戏生命周期验收，记录见 [eye-buffer-validation.log](Logs/iris-toon-unbound-20261011/eye-buffer-validation.log)。

真实资产探针只读原 PMX，导出脸部材质 0～3 的 3,032 个原始顶点及骨权重，调用生产 EyeBoneMask 构造 446 个遮罩值 1 和 2,586 个遮罩值 0。使用生产 ToonShaderCpu 在固定正交近景、光照强度 0.05 下比较发光 0 与实例当前强度 1.6：遮罩为零的网格全图颜色不变，眼部网格有 31,014 个像素颜色增加；暗色瞳心、灰色眼瞳、高光、眼白和脸部五类真实三角形样本检查通过，透明度不变，最终无 GL 错误。记录见 [eye-asset-validation.log](Logs/iris-toon-unbound-20261011/eye-asset-validation.log)，图像见 [发光关闭](Logs/iris-toon-unbound-20261011/eye-before.png)、[完整眼瞳发光](Logs/iris-toon-unbound-20261011/eye-after.png)、[变化区域](Logs/iris-toon-unbound-20261011/eye-delta.png)。这些使用原始绑定姿态网格，没有游戏动画、表情、Iris 曝光或泛光，不是游戏截图。首次资产探针曾因未激活程序就设 uniform、正交深度方向错误而失败；修正后的单独资产复跑通过，原记录保留在 `asset-initial-run.log`。

复验命令（Windows 项目根目录，已先完成 common 构建）：

```powershell
python tools/iris-toon-probe/export_eye_asset.py
./tools/iris-toon-probe/run.ps1 -EyeAssetData 'Logs/iris-toon-unbound-20261011/eye-asset.bin'
```

尚未更换运行实例的 Jar，未做 Minecraft/Iris 实际链路的新截图验收。后续应退出游戏后使用新测试包，同模型、同机位、同光影设置，核对主世界/末地不再出现编译失败提示，以及 CPU/GPU 下眼白不亮、完整眼瞳明暗与高光保留。现有游戏截图只能作为修复前证据。
