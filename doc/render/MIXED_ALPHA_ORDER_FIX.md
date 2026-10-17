# 混合透明度与排序提交修复记录

日期：2026-10-17。对象为杏辞洛天依模型头发、脸部及白色过膝袜异常。本次已修改生产代码并在沙盒外构建；尚未进行本次产物的 Minecraft 同条件画面对照，也未验证多模型帧率。

## 真实资产

只读审计见 `Logs/alpha-assets-20261017.json`。模型包含 40181 个顶点、57009 个三角形、40 个材质。`Face.png`、`Skin.png`、`UV2.png` 均同时含不透明、半透明和完全透明像素。头发 `tianyi.png` 有 753532 个实心像素、295044 个半透明像素，没有零 alpha 像素。

白丝袜 `UV3.png` 包含 1448211 个实心像素和 2746093 个零 alpha 像素，贴图本身没有半透明像素，但对应材质 alpha 约为 0.9。因此必须按最终贴图与材质相乘后的 alpha 绘制，不能把贴图实心区域直接当成完全不透明，也不能排除这些区域的透明排序。

## 已确认的代码缺陷与修改

`SubMeshDrawHelper` 的批量透明提交曾绑定原始 EBO，但材质表对应排序后的三角形，导致几何和材质错配。现改为提交前绑定排序 EBO。

批路径不可用时曾丢失远近排序。现复用已有 CPU 排序；若 GPU 准备后才回退，则在原始 EBO 上重建同帧 CPU 排序，再逐材质提交。

恢复已知原版实体 shader 的逐像素 alpha 拆分，保留实心像素写入深度、透明像素由远到近混合的顺序。只替换已知原版 0.1 裁切表达式为 0.001，保留有意的低 alpha；不强制材质 alpha 为 1。

批路径原先把一次 uniform 数组读取误当作全部八个 sampler 的值，恢复时会使用未初始化数据，真实 GL 探针报告纹理单元越界。现逐元素保存并恢复 `MmdBatchTextures[i]`，回归以八个不同的合法值检查恢复。

动态纹理单元分配继续避开程序已有 sampler 占用。额外对照发现，主贴图绑定自定义 sampler 后，批表默认纹理采样与原路径最大相差 68/255（`Logs/sampler-filter-before-20261017.log`）。现对此保留有序兼容路径，避免丢失主贴图过滤设置；完整生产入口已验证颜色、深度及 sampler 绑定一致。

## 验证

`AlphaOrderGlProbe` 用不同几何的三层表面、alpha 为 255/128/0 的同一贴图和材质 alpha 0.9，独立指定远近顺序，不以另一条生产排序路径作为唯一标准。修复前四条路径全部失败，颜色最大误差分别为 101、144、144、101；修复后 CPU 兼容、CPU 批量、GPU 间接提交、GPU 晚期回退四条路径的最大颜色差、超差通道数及深度差均为 0。

原版真实 Minecraft 1.21.1 实体 shader 探针中，前方实心红色曾被后方蓝色混合为 `[0.498,0,0.502,1]`，修复后为预期的 `[1,0,0,1]`。前方半透明、低贴图/材质 alpha、六种跨材质/索引/动态几何排序场景也通过。

完整 GL 回归通过：混合 alpha、材质绑定、批量提交、主贴图自定义过滤、CPU/GPU 排序缓存、显式索引区域与版本、GPU 计时、25 个 GPU 准备对照、静态缓冲开关、纸娃娃状态边界。回归保留 GL 错误检查。

BSL 10.1.8、Complementary Reimagined 5.9.3、Unbound 5.9.3 等实际包的离线光影回归通过，共 126 个实际程序/选项组合，含主体、描边、颜色回读和变体切换。这不代表游戏中 Iris 的完整运行验收。

相关日志：

- `Logs/alpha-order-before-20261017.log`、`Logs/alpha-order-after-20261017.log`
- `Logs/vanilla-alpha-before-20261017.log`、`Logs/vanilla-alpha-after-20261017.log`
- `Logs/translucent-regression-20261017.log`
- `Logs/shaderpack-regression-20261017.log`
- `Logs/alpha-package-20261017.log`

## 构建与待验收

沙盒外使用 `C:\Program Files\Zulu\zulu-21`、既有共享缓存和 daemon 执行 `gradlew.bat :common:test :neoforge:shadowJar --offline --stacktrace`，结果为 `BUILD SUCCESSFUL in 8s`。211 项 Java 测试通过，失败、错误和跳过均为 0。

产物为 `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`，大小 3744486 字节，修改时间 2026-10-17 17:33:33，SHA-256 为 `D9D256956CA0FC1E88DA534B4FD9DFD70C2A1920920223AA85C11014013D5F84`。包内已检查本次修复类、`ProgramSamplerLayout`、NeoForge refmap 和 Windows 原生 DLL。

尚需使用该产物，在相同模型、动作、相机及配置下检查头发遮挡、脸部和白色过膝袜，分别覆盖无光影及三个指定光影包。没有本次游戏画面前，不能认定此前截图问题已全部解决。原来两模型约 30 FPS、四模型约 15 FPS 的性能问题仍未闭环。
