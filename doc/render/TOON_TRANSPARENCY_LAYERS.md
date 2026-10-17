# 三渲二透明发片分层与不透明对照

2026-10-17：用户授权实现杏辞长发透明错序候选的修复，同时要求控制性能。原截图仍需同模型、同姿态、同相机的游戏对照，不能用下列合成结果代替。

## 行为

Toon 主体的透明 pass 默认剥离最近 4 层深度，再逐像素按远到近重放这些层。每层另有 R8 有效像素标记，避免把复制的不透明背景深度当作透明表面再次混合。更深的片元仍按原中心深度排序混合；超过上限的深层相交面仍可能错序。实心像素、alpha 孔洞、双面属性、材质 morph、包照明、MRT 输出协议和最后的深度补写保留。

Fabric/NeoForge 的“三渲二”分页新增“禁用透明材质”，默认关闭，配置字段 `toonDisableTransparency`。开启后保留材质，将可见半透明像素按不透明绘制并写入深度，透明孔洞仍裁切；不推进透明排序和分层。关闭后恢复透明行为。描边也取消材质透明度，仍保留轮廓覆盖率。此对照开关与分层修复独立。

同页提供“透明裁切阈值（全局）”与“透明裁切阈值（发片）”，对应 `toonAlphaCutoff`、`toonHairAlphaCutoff`，范围 0.001～1.000、步长 0.001、默认均为 0.001，保存后生效。发片独立覆盖全局值；最终 alpha（贴图与材质有效透明度的乘积）低于对应阈值时裁掉，然后再处理不透明对照和透明分层。描边同步采用对应阈值，同时保留原贴图遮罩 0.1 下限。

`HairMaterialClassifier` 仅按材质名称识别头发并排除发饰，结果随材质缓存；不使用贴图名，以免杏辞的头发与衣料共用图集而误判。杏辞的 `後髪`、`后長髪` 可识别，无语义材质名仍使用全局值。新增发片标记复用合批材质表的空闲字段，不增加 draw、批次或显存缓冲；没有测得游戏帧率成本。

测试时先保持全局 0.001，仅把发片提高到 0.050、0.100，固定相机、姿态和光影设置对照浅色三角块及发丝边缘。阈值过高会裁掉细发丝和淡出中的材质，恢复 0.001 可回到原裁切。滑块是诊断控制，不表示已经确认或修复截图根因。

## 性能边界

深度采集在光照计算前返回；只准备一次排序，已选层按原材质提交，避免逐层重放大量排序 runs。共享纹理/FBO 池只在尺寸增大、主目标格式变化或层数增加时重新分配；小目标复用大目标，较小且格式不同的 GUI 目标保留旧路径，避免纸娃娃/世界切换造成分配抖动。共享池硬预算为 128 MiB，没有每模型一份全屏纹理。四层 D24 加 R8 的 1920×1080 缓冲逻辑占用约 39.55 MiB，实际驱动开销另计。

使用 GPU 条件绘制跳过已知空层，不在生产路径读取查询结果、像素或位置。仍会提交额外 draw 和深度复制，不能声称无性能成本。没有可见半透明材质、开启不透明对照、阴影通道、已有宿主遮挡查询、MSAA、默认帧缓冲或不支持的深度目标时保留原路径；支持单采样 D24/D32/D32F 及对应深度模板目标，纹理附件需要 core 4.5 或 ARB DSA。D16 因深度量化容差不同暂不启用。

诊断属性 `-Dmmdskin.toonTransparencyLayers=false` 保留原中心排序；`-Dmmdskin.toonTransparencyLayerCount=1` 至 `8` 调节层数。它们用于固定场景的质量/成本对照。

320×320、两个相交三角形、静态热缓存、24 次绘制的本机探针中，中心排序约 CPU 0.423 ms / GPU timer 0.257 ms，四层约 CPU 1.115 ms / GPU timer 1.130 ms。GPU timer 包含提交间隙，数值不能解释为纯 GPU 内核耗时，也不能外推为游戏 FPS；未把初始化和像素回读纳入这组热缓存计时。实际多人、杏辞网格和全分辨率成本尚未测量。

## 验证

`ConfigDataTest` 覆盖旧配置缺省、持久化与配置转发；Fabric/NeoForge 编译通过。

`ToonTransparencyGlProbe` 回读 CPU/GPU 输入下的贴图 alpha 0/128/255、材质 alpha 0/.4、不透明遮挡、孔洞和开关恢复，并检查主体/描边选项一致。普通与合批 shader 均验证全局/发片独立裁切、先裁切后实心化及阈值恢复。`ModelMaterialHairTest` 覆盖杏辞材质名、共用图集和发饰排除；`ConfigDataTest` 覆盖独立持久化、配置转发、范围与非有限值。`tools/outline-probe/run.ps1` 核对描边的发片选择、有效 alpha 换算和异常状态恢复。本轮阈值回读记录为 `build/translucent-performance/alpha-cutoff-validation.log`。

`ToonTransparencyLayersGlProbe` 使用屏幕左右前后关系交叉的独立三角形，按每像素真实深度计算 source-over 参考。原排序出现错序；分层在两种提交顺序、CPU/GPU 输入下均匹配 23380 个内部像素，RGB/alpha 误差为 0。另验证两层上限下第三片蓝色远层保留，以及回调异常后的遮挡查询、FBO、纹理/sampler、深度和 uniform 清理，异常后再次绘制仍匹配参考。

`BslTransparencyGlProbe` 将上述参考替换为真实 BSL 10.1.8 三维度 profile，验证输出协议与深度分层相容。它不模拟完整 Iris 帧调度和游戏后处理；原包照明由既有 LightingProbe 独立回读覆盖。

复验先运行 Gradle 的 common 编译与资源处理，再运行 `tools/iris-toon-probe/run.ps1`。透明探针入口：

```powershell
./tools/translucent-performance/run-toon-transparency-probe.ps1 -BslPack 'D:/minecraft/.minecraft/versions/1.21.1-NeoForge_21.1.252/shaderpacks/BSL_v10.1.8.zip'
```

运行记录保存在 `build/translucent-performance/toon-transparency-validation.log` 和 `build/iris-toon-probe/` 下。游戏截图与真实性能验收仍待完成；本轮没有替换实例 Jar。
