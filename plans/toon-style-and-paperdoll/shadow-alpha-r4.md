# 阴影 alpha 修复与 r4 验证

2026-10-25。接续“优化三渲二与描边性能”中的 r3 工作；保留当前工作区已有配置、描边、纸娃娃与透明缓冲优化。

## 行为

- 阴影不再按 `hasTranslucentPixels` 或材质 alpha 小于 1 整块排除。可见子网格提交给宿主阴影 shader，纹理孔洞由宿主逐像素 alpha test 决定。材质有效 alpha 小于 0.001、隐藏子网格仍跳过。
- Fabric / NeoForge 的“三渲二”设置增加“阴影忽略透明度”，对应 `shadowOpaque`，默认关闭，不要求开启三渲二。开启时，仅阴影绘制临时将 diffuse 纹理采样 alpha 设为 1，并将提交的材质 alpha 设为 1；半透明和全透明纹理像素均投射实心阴影，透明孔洞会填满。RGB、纹理源数据、普通颜色渲染透明度不变。
- 使用 texture alpha swizzle 作用域，不替换宿主阴影程序，不复制纹理或回读 GPU 顶点。正常或异常退出后恢复所有触及纹理的原 alpha swizzle。隐藏材质不因该开关重新出现。
- CPU 和 GPU 后端共用 `SubMeshDrawHelper`。GPU 后端原有 compute 蒙皮位置和 UV morph 输出直接用作阴影 attribute，未改为 CPU 蒙皮。

## 探针修复

旧探针曾向已有输出目录写入失败，因此失败结果不能代表当前源代码。脚本现在每次使用独立 UUID 目录，编译失败立即停止，classpath 不包含旧探针目录。

清除旧 class 干扰后仍有差异，定位到参考绘制直接调用 `glDisable(GL_CULL_FACE)`，使 RenderSystem 的剔除缓存失真，后续第一次单面生产绘制错误地保留了双面；探针参考改用相同 RenderSystem 状态接口。另修正了动态透明度循环被逐行覆盖，原来的两档其实重复测了同一条件。这两项属于探针缺陷，不据此改变生产剔除规则。

## 当前验证

JDK 21 / Gradle 8.11.1，保留共享 Gradle 缓存。沙箱访问 wrapper 缓存锁失败后，经工具权限流程使用原缓存完成构建，没有清理缓存。

- `:common:test :fabric:compileJava :neoforge:compileJava :fabric:remapJar :neoforge:shadowJar -x buildRustNative --offline` 成功；Java 286 项测试，失败 / 错误 / 跳过均为 0。包含旧配置默认值、选项持久化及实时切换。
- `run-shadow-alpha-probe.ps1` 的 96 组实际 GL 颜色与深度对照全部一致：MaterialColor 与 Iris 风格颜色 attribute 两种合成 shader，另有真实 BSL v10.1.8 默认配置的阴影片段；两种骨骼 / UV morph 姿态；两档动态 alpha；0.1 / 0.98 裁切阈值；默认 / 实心投影；CPU / 生产 compute 输出几何。
- compute 测试直接执行 `SkinningComputeShader.dispatch`，使用输出位置和 UV 缓冲绘制，与独立 CPU 变换几何对照。未将 GPU 输出读回后作为 CPU 输入。
- 对参考图额外断言有实心覆盖、全透明孔洞和隐藏材质，避免空参考图误判通过；正常 / 回调异常退出的 swizzle、program、深度写入、EBO 与剔除恢复均通过；启用实心投影时普通绘制的纹理 / 材质 alpha 保持原值。
- BSL 检查展开本机原始 `shadow.fsh` 和 includes，使用 Iris 实际 `AlphaTest.toExpression` 在输出后执行 alpha test；使用受控顶点 shader、varyings、纹理及阈值。没有运行完整 Iris shader patcher 或游戏阴影场景。BSL 原始阴影片段按纹理 alpha 判定，不乘 `color.a`，探针忠实保留此规则；本修复不强制光影包改变其材质透明度策略。
- 两包包含新开关、三语文本、阴影作用域、GPU renderer 与 compute 资源；r3 条目均保留，内嵌 Windows DLL 与当前 release 文件 SHA-256 相同。仅含本机 Windows native，属于 Windows 测试包。

原始记录位于 `build/toon-style-validation/shadow-r4-probe.log`、`shadow-r4-build.log`、`artifacts-r4.json`。产物为该目录中的 Fabric / NeoForge `toon2d-r4.jar`。

## 验收边界

未替换或重启运行中的 Minecraft。完整游戏内须固定 GPU 蒙皮、模型 / 姿态 / 相机、BSL 及 Iris 版本，对照开关关闭 / 开启的地面与墙面投影，观察发片和衣物透明孔洞。此次离屏探针不能证明 Embeddium / NeoOculus 缺影已经修复。前序报告中的 `AlphaOrderGlProbe` 基线深度差异也不在此次修复范围，未将整个透明套件声明为通过。
