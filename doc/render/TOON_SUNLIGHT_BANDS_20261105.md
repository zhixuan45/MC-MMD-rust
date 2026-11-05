# 1.21.1 Toon 太阳补光与色阶隔离

## 已定位的问题及修复边界

工作区基线 `d840004`，Minecraft `1.21.1`。只修改当前 1.21.1 工作区，没有操作 1.20 分支。

BSL、Complementary 4 和 5 的已识别路径原本将太阳补偿应用了两次：

1. 在包内直射光权重中，`shadow * toonSunDiffuseRamp(NdotL, shadow)`，保留环境、天气、昼夜和遮挡。
2. 在 `shadeToonSurface` 的表面色阶参数中，再次调用 `toonSunlitRamp(rawBand)`。

当平坦度、太阳高度、天空光和无遮挡可见度满足满补光条件时，第二步会使每个表面档位都变为 1。
色阶对比度随补偿系数 `k` 缩小为原来的 `1-k`；`k=1` 时全部消失。原有 `LightingProbe` 甚至断言此时亮暗法线必须输出同色。

修复保留第一步照度补偿，把 helper 改名为 `toonSunDiffuseFill` 明确用途；第二步始终传入原始表面档位。
即使直射光照度被补足，`albedo * mix(ShadowColor, 1, rawBand)` 仍保留分档。真实遮挡仍在包内乘上直射光，未把材质改成自发光。
没有禁用光影包、恢复旧版阴影采样、修改输出协议或透明 Pass。`ToonLevels`、`TransitionSoftness`、`ShaderFlatness` 保留；平坦度 100% 的新语义是补足照度并抑制细节，同时保留 Toon 表面色块。

另一个独立问题通过实际顶点着色器回读确认：主体和描边使用普通矩阵变换法线，在非均匀缩放时会得到错误 NdotL。
两者改用逆转置矩阵；光方向继续使用方向矩阵。普通角色 yaw 已由两个 Java renderer 在上传 LightDir 前逆向配对，不能再把 LightDir 当成未经处理的世界太阳方向。
BSL/Complementary 的太阳向量由包内视图矩阵得到，与片段视空间法线比较，修复没有改动这条数据流。

这些结果确认了可以消除 Toon 的机制，**尚不能证明它是用户原始 PMX 在特定朝向退化的唯一原因**。
没有原始模型、固定游戏存档、配置或真实光影包资产，未在 Minecraft 中重现原始画面；也没有独立分支的本地 ref 可作视觉对照。

## 自动验证

JDK 21，先编译 common 并导出 classpath；纯着色器验证不需要构建 JNI：

```sh
bash gradlew -I tools/translucent-performance/validation.gradle \
  :common:classes :common:writeTranslucentProbeClasspath \
  :common:testRender :common:testCompat -x :prepareNativeResources
bash tools/iris-toon-probe/run-toon-rotation-probe.sh
```

Linux 探针需要系统 `libEGL.so.1`、`libGL.so.1` 和 Python 3 标准库。它不下载依赖，GL 初始化或断言失败即失败。
每轮使用独立输出目录，不能运行上轮残留的 class；输出 JSON 标明驱动、测量值和编译数量。
三个合成光照/阴影函数经过生产 BSL/Complementary 适配器，照明环境与 shaderpack 其它代码不在合成测试范围内。

覆盖内容：

- 原版表面路径及三个适配器，2～5 档、平坦度 0/0.7/1、每 15° 旋转一圈。
- 每张图有 512 个不同法线的 4×4 区域；在 RGBA32F 目标上比较亮度跨度、颜色分布、各档平坦区覆盖率和 0°/360° 接缝。
  允许窄的抗锯齿过渡产生额外颜色，但必须有全部配置档位，且超过 95% 区域位于平坦档位，不能靠单像素判定。
- 原始档位、补偿后直射权重、补光强度和阴影可见度均直接从生产 GLSL 读取。
- 低天空光、夜间无太阳补偿、合成世界遮挡、合成天气照度衰减、alpha 不变，以及柔和度 0.5/1/2、NdotL 为 0/±1e-7 时的有限颜色。
- 两个真实顶点程序，在所有 yaw 下执行普通缩放和非均匀缩放，对照独立解析法线和方向，验证光方向不会随模型锁定。
- 通过生产 source builder 组装完整主体/描边，再替换为上述合成照明协议；编译正常和全部调试模式共 44 个程序。
  这里使用原版输出 writer，**不是实际 BSL/Complementary 全包、MRT 合成或 Iris 回退验证**。

本次环境：Mesa 25.0.7 / llvmpipe，OpenGL 4.5；是实际 OpenGL 执行和像素回读，但使用 CPU 软件驱动，不是硬件 GPU 或游戏画面验收。

测量结果：旧实现在满补光时，三种适配器都合并为 1 个颜色，红通道亮暗差 0；修复后所有旋转的最小红通道亮暗差为 0.330000，所有档位保留。
满补光直射权重仍为 1，说明没有简单禁用补光。非均匀缩放的最大法线/NdotL 分量误差由约 1.0115 降到 `1.31e-7` 以下。
此处 RGB 数值对应探针固定材质，并非人眼亮度或实际模型统计。

Java 渲染测试 209 项（208 通过，1 项因缺少外部效果资产跳过），兼容测试 26 项全部通过；common 编译通过。
NeoForge 编译在 `createMinecraftArtifacts` 阶段访问 `launchermeta.mojang.com` 被网络代理返回 403，未完成平台编译。

## 默认关闭的游戏诊断

JVM 参数 `-Dmmdskin.toonDebug=N`，每次改变需重启客户端。默认 0，不定义调试宏，额外变量和输出逻辑不进入正式程序。
启用时日志记录实际选中的 `ToonOutputProfile`，包括编译后回退到通用照明的变体。

| N | 显示 |
|---|---|
| 0 | 正常颜色 |
| 1 | 视空间法线，`normal * 0.5 + 0.5` |
| 2 | 带符号 NdotL，`NdotL * 0.5 + 0.5` |
| 3 | 实际用于表面 tint 的原始 Toon 色阶 |
| 4 | `toonSunlightStrength` |
| 5 | `toonSunVisibility` |
| 6 | 补偿后直射权重；无可用包直射路径时显示表面档位 |
| 7 | 进入表面着色的 `surfaceIllumination` |
| 8 | 最终着色颜色，与正常输出相同，用于对照 |
| 9 | R = `UsePackLighting`，G = `ToonGenericEnvironment` |

诊断只替换主体 RGB，仍经过当前输出编码/后处理，截图灰度不能直接当作线性 float 读数。
alpha 裁切、透明深度采集和输出 writer 继续执行；诊断不是全包关闭后处理模式，也不替代正常材质验收。

## 待游戏环境验收

固定模型版本/hash、客户端、分辨率、相机和位置；设置 `doDaylightCycle=false`、`doWeatherCycle=false`，白天选有明显侧向太阳的时间（例如 `/time set 3000`）和晴天。
原版、BSL、Complementary 分别在平坦度 0/0.7/1 下每 15° 保存画面及诊断模式 2～7、9 的结果；记录实际 profile，检查色块边界随世界光移动、相邻角度变化和身体区域亮度分布。
再固定午夜、雨天、室内方块光和真实方块遮挡场景。特别检查 Flatness=0 时是否仍有原始退化，防止把已确认的补光缺陷误认成唯一根因。

用同一 PMX 分别启用 CPU/GPU 蒙皮，对照透明分层、描边、深度、眼睛发光和球面纹理，并验证真实包的主体/手部/各维度编译与回退日志。
现有真实包探针 `run-sunlight-probe.ps1` 中的过时“满补光同色”断言已更新；需提供实际光影包和实例才能运行。
本次没有完成这些视觉、真实世界阴影、真实包连续漫反射排查、CPU/GPU 蒙皮一致性或硬件性能验收，不应据此宣称全部验收标准通过。
