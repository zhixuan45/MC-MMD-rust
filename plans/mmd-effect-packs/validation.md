# 验证结果（2026-10-30）

- `:common:test`：337 项，0 失败、0 错误、0 跳过；包括外部 `Tex_1.zip` 的真实导入检查。
- `:fabric:remapJar`、`:neoforge:shadowJar`：成功。两包均检查存在 FX runtime、选择界面和 `fx_material.glsl`。
- 隐藏 GLFW / NVIDIA OpenGL 4.3：137 项检查通过。覆盖真实包解析、TGA 上传、人物/放置场景选择和模型限制、世界颜色去重、GLSL 330/430 生产主体编译、实际材质表批量像素等价、AO、光滑度、高光、原 alpha、纹理/sampler 恢复、世界 FBO/viewport/program/indexed blend/mask/PBO 恢复、HDR 颜色和重载释放。
- 既有 `tools/sphere-texture-probe/run.ps1` 在 OpenGL 3.3 上通过：无效果路径的 SPA/SPH/alpha、暗处照明、morph、sampler 避让、异常恢复和透明批次。
- `git diff --check`：通过。

构建使用 JDK `C:\Program Files\Zulu\zulu-21` 和共享 Gradle 缓存。沙箱首次访问 wrapper 锁文件被拒绝，获自动批准后使用同一缓存完成构建；未修改 JDK、IDE、全局代理或缓存配置。

## 本地交付

`build/effectpack-release/`：

- `mmdskin-fabric-1.10alpha-1.21.1-fxpack-r1.jar`
- `mmdskin-neoforge-1.10alpha-1.21.1-fxpack-r1.jar`
- `basic-color-filters.zip`：本仓库自有的灰度、暖色示例。
- `Tex_1-fxpack-r1.zip`：仅用于用户本机的原输入资源、兼容报告与转换后材质 JSON；不提交第三方资源到仓库。

Fabric JAR SHA-256：`bf3ac31f890e7360eca905d6caf3a18f1d0e4d4b136312361bb9651ed6bdb4a9`。

NeoForge JAR SHA-256：`01d305a9133f3a6ed3f39f8e1236a9d0461a0be4c3126ee2aa7d10760c91e713`。

## 实机缺口

没有将包安装到用户游戏实例、重启游戏或在窗口中操作效果选择界面。Tex_1 没有提供匹配的 PMX 和子材质分配表，当前预设应用到所选模型的全部材质。UV 匹配、真实人物/场景效果、CPU/GPU 实际游戏切换、Iris/其它模组组合、透明遮挡、HUD 与原生界面布局仍需对应场景检查。

复杂 MME 多 pass、附加深度/阴影/反射以及 Ray 自定义光照模型不在首版适配范围；导入报告明确拒绝，而不会表示已兼容。世界能力当前为 RGB 后处理，Ray 材质本身没有世界通道。详细协议和用法见 `doc/render/MMD_EFFECT_PACKS.md`。
