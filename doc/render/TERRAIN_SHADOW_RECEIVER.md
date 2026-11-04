# 地形阴影接收（2026-11-05）

沿用 `toonWholeModelLighting` 保存键，设置界面显示为“接收地形阴影”，默认关闭。
开启后主体和描边按各自片元的真实位置采样地形阴影，不再共用模型包围球上的采样点。
关闭、GUI 场景、原包照明同步失败或没有有效地形快照时回到经典逐片元阴影。

## 接入与范围

Fabric / NeoForge 的 `IrisShadowEvaluationMixin` 在每次 `renderShadows` 开始时作废旧快照，
在第三次 `invokeRenderSectionLayer`（solid、cutout、cutoutMipped）结束后复制深度。
位置匹配同时包含 Mojang 与 Fabric intermediary 描述符；不支持的 Iris 入口不启用替代采样。
捕获点位于实体提交之前，包含不透明建筑、地面和裁切树叶，不包含实体、方块实体及半透明地形。

`IrisShadowSource` 从 renderer 的 `targets` 读取阴影源 FBO、深度纹理和分辨率，
`IrisTerrainShadow` 校验纹理身份与尺寸后复制，维护独立深度纹理和 FBO，保留宿主读写 FBO、纹理绑定、
scissor 和 unpack PBO 状态。分辨率/格式变化时重建；renderer 销毁或替换时释放。
原阴影图继续接收全部后续人物几何，所以人物向世界投影的路径保持原有行为。
这一阶段排除所有实体的阴影，不提供“仅忽略当前人物、保留其他实体”的身份过滤。

BSL / Complementary 适配继续计算原包阴影坐标和过滤，仅在有效地形接收模式下将深度比较
改读专用纹理，并屏蔽宿主阴影颜色，防止过滤边缘混入后续人物或半透明投影颜色。
经典模式保留原深度和颜色采样。地形接收减少模型内部自遮挡，
材质法线、Toon 色阶和原有阳光平坦化继续参与表面照明。

`TerrainShadowBinding` 按程序活动 sampler 分配空闲单元，不修改 Iris 的阴影采样器绑定；
主体和描边切换时恢复前一绑定，模型绘制结束及异常退出时释放临时绑定。

## 验证入口

- `:common:test :fabric:compileJava :neoforge:compileJava`
- `tools/iris-toon-probe/run-sunlight-probe.ps1`
  - 生产深度复制：半幅遮挡、后续投影写入互不污染、宿主 GL 状态/PBO 恢复、
    分辨率变化、缺失捕获、关闭和 renderer 替换。
  - 实际 BSL / Complementary 的主体与描边编译，以及同次绘制中明暗两区、
    自阴影排除、相机坐标变换、描边一致性、缺失快照回退及纹理状态恢复。

2026-11-05 验证结果：

- `:common:test`：384 项，383 通过、1 跳过，失败与错误均为 0。
- Fabric / NeoForge 编译及 `remapJar` / `shadowJar` 成功，复用既有 Windows 原生库。
  两个 JAR 已核对包含地形接收类、新 shader 和 `natives/windows-x64/mmd_engine.dll`。
- BSL 8.4.02.2 / 10.1.8、Complementary 4.7.1 / Reimagined 5.9.3 / Unbound 5.9.3：
  30 组主体与描边程序编译成功；五个主世界实体程序均通过像素验证。
  三个启用彩色阴影的程序保留经典透色差异，地形模式红/蓝宿主阴影颜色切换的像素差均为 0。
- `ShadowHookProbe` 对实例 Iris 1.8.14-beta.1 的实际字节码验证了两平台捕获描述符、
  第三次地形调用先于实体，以及 renderer 销毁方法存在。
- 日志：`build/terrain-shadow-validation.log`、`build/terrain-shadow-sunlight-probe.log`、
  `build/terrain-shadow-depth-probe.log`、`build/terrain-shadow-hook-probe.log`。

独立 OpenGL 探针不包含 Minecraft 的完整 Mixin 装配和最终后处理。
游戏验收需要固定时间与人物动作，站在建筑阴影边缘，分别让阴影穿过头、胸、腿，
环绕镜头并跨越边界；同时检查地面人物投影、开关回退和光影重载。

## Iris 深度格式回归与柔化滑块

用户实测发现模型内部遮挡重新出现。安装包哈希与上一版构建一致，配置开启了地形接收，
运行日志没有地形快照成功消息。检查实际 Iris `ShadowRenderTargets` / `DepthTexture`
字节码后发现，Iris 使用未指定精度的 `GL_DEPTH_COMPONENT` 创建阴影图。
本机 NVIDIA 返回该格式枚举 6402、实际深度 24 位；旧实现只接受 sized 格式，拒绝复制后
回到包含 PMX 自阴影的宿主深度图。原先只使用 `DEPTH_COMPONENT32F` 的探针没有覆盖它。

修复通过实际深度位数和类型选择匹配的快照存储格式；本机对应 `DEPTH_COMPONENT24`。
新增相同分配方式的探针在修复前稳定失败，修复后通过深度回读。复制失败或入口未命中时，
运行日志现在会限频报告 `classic receiver fallback` 及具体原因。

新增 `toonWorldShadowSoftness`，范围 0–4，默认 1.5，Fabric / NeoForge 设置界面均可调整。
0 保留原包过滤；正值在原包完成坐标变形和深度偏移后执行一次九点加权深度比较，
使用独立的线性比较 sampler，避免在原 PCF 的每个 tap 内再次展开过滤。
主体与描边同步设置，经典自阴影模式不受影响。此滑块只控制模型接收的地形阴影，
人物投到地面上的阴影仍由光影包控制。

本轮验证：386 项公共测试，385 通过、1 跳过，零失败；两平台编译和打包成功。
30 组真实光影主体/描边程序通过，五套主世界实体程序均用 Iris 的实际深度分配方式验证。
受控 16 像素采样行中，柔化 0 的过渡像素数为 0，1.5 时为 7–13，4 时为 16；
归零恢复原始像素，描边同步，颜色隔离和相机变换检查通过。
日志：`build/terrain-shadow-unsized-before.log`、
`build/terrain-shadow-softness-probe.log`、`build/terrain-shadow-softness-validation.log`。
本轮未替换实例或执行游戏实景验收。

## 屏幕深度误采与网格边界回归

后续游戏日志记录了 `captured terrain before entities: 2560x1494`，
与窗口尺寸一致。Iris 的阴影图按单个 shadow resolution 创建，宽高应相同。
原实现假设捕获点当前绑定的 FBO 仍属于阴影通道，但此时 shader clear 已可能切回主目标，
导致屏幕深度按太阳阴影坐标采样，出现室内外边界破碎和错误的世界阴影接收。

现在由 `IrisShadowSource` 读取 renderer 私有 `targets`，沿公开 API
`getDepthSourceFb()`、`getDepthTexture()`、`getResolution()` 获得明确来源。
复制前校验 FBO 深度附件等于指定纹理，纹理宽高等于 shadow resolution。
拒绝身份和尺寸不匹配的资源，复制后恢复捕获前的读写目标，不依赖当前绑定目标推断来源。

地形模式也独立于 PMX 网格法线的 acne 偏移：BSL 的采样 NdotL 固定为 1，
不施加模型法线方向偏移；Complementary 4.x 固定采样偏移用的 NdotLm；
5.x 使用原始 playerPos 投影，绕过网格法线偏移与室内网格中心吸附。
表面 Toon 受光仍使用真实法线，经典模式保留原计算。

新增不同尺寸屏幕/阴影 FBO 的回归用例，旧版稳定复制屏幕深度而失败；
修复后即使屏幕 FBO 当前绑定，也只复制 renderer 所属阴影纹理。
真实三角形在复制后继续写入原阴影图，快照保持不变；身份和尺寸不匹配时拒绝捕获。
诊断 shader 仅输出世界遮蔽，固定坐标、改变正面/掠射/背面法线，
在柔化 0、1.5、4 下的可见度差均为 0，覆盖五套光影。
另验证 BSL 8 / 10 的 `SHADOW_BIAS=1`，共 42 组主体/描边程序通过。
公共测试 386 项，385 通过、1 跳过，零失败，两平台编译与打包成功。

实例 Iris 字节码已独立核对 `targets` 字段及上述 API、纹理/FBO ID 方法和捕获时序。
日志：`build/terrain-shadow-wrong-target-before.log`、
`build/terrain-shadow-source-validation.log`、
`build/terrain-shadow-normal-bias-validation.log`、
`build/terrain-shadow-source-api.log`、`build/terrain-shadow-source-build.log`。
这些验证覆盖受控 GPU 场景；本轮未替换游戏实例，用户两处实景尚待换包重启后验收。
