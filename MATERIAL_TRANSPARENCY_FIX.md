# PMX 睫毛半透明修复

本实现移植到默认分支 `feat/1.21.1-neoforge`（Minecraft 1.21.1，Fabric/NeoForge）。
已验证的 1.20.1 原始实现保存在
[`codex/pmx-transparency-1.20-backport-reference`](https://github.com/zhixuan45/MC-MMD-rust/tree/codex/pmx-transparency-1.20-backport-reference)，
提交 `f649dba`，供后续向下移植参考；默认分支移植保留眼瞳发光和光影包照明流程。

代码检查发现三个直接问题：`SubMeshDrawHelper` 只用材质 Alpha 判断是否跳过，
没有传给着色器；Toon 输出只包含贴图 Alpha 和全局 Alpha；半透明几何沿用深度写入。
此外，有贴图的材质未使用 PMX 扩散色、环境色和反射色。

修复方案与实现：

- 材质加载缓存扩散色、环境色、反射色及反射强度，复用现有 JNI 接口。
- `MaterialRenderState` 求值材质及 56-float 材质 morph。基础色使用
  `clamp(diffuse.rgb * 0.5 + ambient.rgb, 0, 1)`，再乘贴图色调。
  这是当前 Toon/原版共用的基础光照近似；保留极端负值至相加之后裁剪，
  默认白色材质维持白色。反射色与强度进入 Toon 高光。
- 最终透明度为 `textureAlpha * (baseMaterialAlpha * morphMulAlpha + morphAddAlpha)
  * globalAlpha * textureTintAlpha`。材质色调与 Alpha 各应用一次；无贴图材质改用白色
  回退纹理，避免原来的颜色纹理与新材质参数重复相乘。
- CPU 与 GPU 蒙皮共用材质绑定和两阶段绘制：先绘制不透明部分并写深度，
  再绘制半透明部分，保留深度测试、关闭深度写入，完成后恢复状态。
- Toon 按最终像素 Alpha 分离两阶段，同一贴图的实心睫毛仍能遮挡眼球，
  半透明部分与已绘制的眼瞳混合。Alpha 丢弃阈值从 0.1 降为 0.001。
- 原版/Iris 使用每次绘制的顶点颜色/`ColorModulator` 传入颜色和透明度；
  区分贴图的二值裁剪与真正半透明像素，避免只因存在 Alpha 通道就禁用深度写入。
  Iris 阴影通道仍写深度。

验证包含材质颜色、负环境色、材质 morph、贴图色调/透明度、贴图 Alpha 分类的 JUnit 回归测试。
`python tools/material-render-probe/probe.py` 使用 Mesa/EGL 编译生产 Toon 着色器并读取颜色和深度，
覆盖 11 项检查，包括黑睫毛与紫色眼瞳混合、透明度相乘、低透明度及零透明度，
以及默认分支的眼瞳发光、PMX 反射色和共享描边着色器兼容性。

默认分支验证结果：Java 21 下 Fabric/NeoForge 完整构建成功，137 项 Java 测试通过，
206 项 Rust 测试通过（1 项原有测试忽略），Rust Release 库构建成功，11 项 OpenGL 检查通过。
两个平台的发布 JAR 均包含当前着色器、材质绑定类及与本次 Release 输出一致的 Linux 原生库。

边界：透明材质仍按 PMX 原始材质顺序混合，没有新增三角形深度排序或顺序无关透明算法。
未确认输出契约的 Iris 光影程序采用整材质分类；
其 Alpha 裁剪阈值和最终光照依赖所用程序。当前修复不是对 MMD 光照的完整复刻。
附件是截图，尚未用对应 PMX 模型在 Minecraft 中进行画面对照。

## 1.21.1 渲染状态修复迁移

在 `cf7db49` 的 1.21.1 实现上合入前一轮 1.20.1 修复，保留当前分支的
材质/morph 求值、眼瞳发光、光影照明及按贴图裁剪的描边。没有用旧版的
简单整材质绘制替换此分支的像素分离，也不再跳过整张透明贴图的描边。

- 结束普通实体绘制时也调用 `ShaderInstance.clear`，并在 apply 后显式绑定程序，
  防止 Toon/计算着色器直接操作 GL 后，原版程序缓存与实际绑定失配。
- 主纹理通过 RenderSystem 绑定，上传纹理时保存并恢复当前绑定。
- 原版与 Iris 实体的单元 1/2 分别使用游戏覆盖色、光照图，UV1 使用中性覆盖坐标；
  外部 MMD 着色器仍保留自己的 lightMap 绑定。
- 普通路径在 apply 后恢复独立的 RGB/alpha 混合因子，Toon 使用相同因子；
  Iris 的 MRT 混合覆盖仍由 Iris 管理。材质绑定支持 `iris_ColorModulator` 名称。
- 半透明颜色仍在关闭深度写入时混合；全部颜色完成后增加一次只写深度的绘制，
  保留透明洞，阻止之后绘制的远处云覆盖已有表面。该阶段保存并恢复所有 MRT
  颜色写掩码，阴影阶段不增加额外绘制，全不透明模型跳过补深度阶段。

已下载并解析用户提供的洛天依模型：40 个材质、40181 个顶点。头发 PMX alpha
均为 1，`tianyi.png` 有 295044 个连续 alpha 像素；`UV1.png` 全不透明，
`UV3.png` 是二值镂空。模型仅保存在本地验证目录，没有加入仓库。

新增 `tools/material-texture-probe/HeadlessProbe.java`，在 Java 21/Mesa EGL 中
调用生产绘制、材质绑定及回退加载器，验证普通/Toon/Iris uniform 接口切换、
alpha 组合、回退 morph、透明层混合、覆盖深度、透明洞和 MRT 掩码恢复。
探针还用真实 `ShaderInstance` 重现程序缓存失配，并验证生产清理方法修复它。
运行命令：

```sh
LWJGL_EGL_JAR=/path/to/lwjgl-egl-3.3.3.jar GRADLE_COMMAND=/path/to/gradle \
  bash tools/material-texture-probe/run-headless.sh
```

这些探针不等价于完整 Minecraft/Iris 客户端验收；光影包的深度副本、延迟合成和
原模型截图对照仍需游戏内验证。透明表面仍按 PMX 顺序混合，没有新增三角形排序。

本轮 1.21.1 验证：Java 21 下 137 项 Java 测试全部通过，206 项 Rust 测试通过、
1 项原有测试忽略，Fabric/NeoForge 完整构建成功；原有 11 项 Toon GL 检查及新增
生产子网格/缓存/深度/MRT 探针通过。两个发布 JAR 的 Linux native 与本轮
Rust release 输出一致。NeoForge 元数据首次下载受环境网络策略限制，验证构建
复用了与 Mojang manifest 中 SHA-1 一致的本地 Minecraft 1.21.1 缓存。

## 关闭 Toon 后的实心像素遮挡补修

上述所有用户截图均来自 1.21 分支。前一轮探针使用简化普通片段公式，未覆盖完整
Minecraft 实体 shader，也未检测同一子网格内混合 alpha 贴图的前后遮挡。

用 Minecraft 1.21.1 原始 `rendertype_entity_translucent` 的完整顶点/片段程序已复现：
前方三角形采样不透明红色，后方三角形采样同一贴图的半透明蓝色，PMX 材质 alpha=1。
原有普通路径把整张混合贴图放入关闭深度写入的颜色阶段，输出为
`[0.49803925, 0, 0.5019608, 1]`，实心表面被后方覆盖；最后补深度不能恢复颜色。

`EntityPixelAlphaPass` 为当前程序建立一个缓存变体，重用原顶点程序并保留属性位置，
在原片段着色结果之后按颜色输出的最终 alpha 进行 discard。主绘制循环因此也能对
原版实体按像素分离实心与半透明：实心先建立深度，半透明随后混合。原程序的矩阵、
光照、雾、sampler 和 uniform block 绑定复制到变体，退出时恢复原程序；不改变
资源包或光影包文件，不替换原照明公式。修正后同一测试输出 `[1, 0, 0, 1]`。

Iris 仅对现有元数据解析确认的颜色输出契约启用此变体；额外几何阶段、图像/SSBO
访问或不支持的 uniform 类型保留原程序。未知光影包仍需要提供包名和版本验证，
不能将原版 shader 的结果直接推断成任意光影包均通过。

`VanillaEntityProbe` 使用完整 Minecraft 1.21.1 shader 验证该回归和有意设置的材质
alpha=0.5。可选 `MMD_PROBE_MODEL` 还通过生产 JNI 加载原 PMX，使用 `顔`、`Skin`、
`hair00`、`hair01` 的实际扩散/环境色及原贴图进行相同的重叠遮挡检查；这四组均通过。
这些检查使用测试三角形与原模型材质，未声称已经完成整个模型的游戏内截图验收。
