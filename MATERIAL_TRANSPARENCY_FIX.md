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
原版和任意 Iris 光影程序不能使用 Toon 的像素分离 uniform，采用整材质分类；
其 Alpha 裁剪阈值和最终光照依赖所用程序。当前修复不是对 MMD 光照的完整复刻。
附件是截图，尚未用对应 PMX 模型在 Minecraft 中进行画面对照。
