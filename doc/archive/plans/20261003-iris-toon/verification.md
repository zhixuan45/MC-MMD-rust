# Iris / Toon 修复验证

2026-10-03。按用户选择保留 Toon 与光影并用。已知输出语义允许缓冲重排；未知版本、缺失颜色槽、重复槽、越界输出、错误法线标记和额外实体阶段会显示原因，并保留聊天记录，由用户手动关闭 Toon。兼容程序编译失败也提示，配置不变。

本次首先适配实例中的 ComplementaryShaders_v4.7.1、BSL_v8.4.02.2。人物明暗使用现有 Toon 计算，兼容层转换光影输出编码、提供中性材质与正确法线；它不实现各光影包的全部实体照明与反射。上一轮 Iris 阴影视图与第一人称提交修复保留在这次 Jar 中。

使用 JDK `C:\Program Files\Zulu\zulu-21`、Gradle wrapper 与已有共享缓存。15 项定向测试通过，覆盖 Toon 协议、阴影矩阵、第一人称姿态与防走光；Fabric、NeoForge 编译及 remapJar 均成功。最终 Jar 已检查新增兼容类、主体/描边模板和 Windows 原生 DLL。

本机可选探针读取 D 盘实例的真实光影 ZIP 和选项文件，在内存展开 include 并调用实例 Iris 的 JcppProcessor 预处理。主世界、下界、末地的实体与手部程序共 120 个选项组合通过。覆盖 Complementary 的 0、037、0361、03761，以及 BSL 的 0、08、03、083、0367、08367；BSL 两种混合颜色编码均验证。探针在 NVIDIA OpenGL 3.3 隐藏上下文编译实际主体和描边变体，回读颜色、Alpha、原色和正负法线极点，验证切回无光影时复用原程序及资源释放。

探针入口为 `tools/iris-toon-probe/run.ps1`，日志为 `build/iris-toon-probe/probe.log`。它验证 GLSL 输出契约和变体生命周期，不能代替游戏中的完整 Iris 帧缓冲、透明混合、屏幕提示、切包、纸娃娃、人物投影与最终画面验收；这些项目仍需在游戏中试用。

NeoForge 交付文件：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`，SHA256 为 `8E18958028D7AF581904373CD06C6890DE3C59D1A779E4C790C376BFABC7C8A2`。

Fabric 交付文件：`fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar`，SHA256 为 `9A3F5B8484C226E4A2ED7E43B56FD1432FCA6EC66B3AF4D9ADCCA84492CAE81B`。

远程 issue #1 的[相关评论](https://github.com/zhixuan45/MC-MMD-rust/issues/1#issuecomment-5966130843)确实提到部分光影干扰三渲二并需要回退。本次依据本地源码和光影输出做了适配，未在远程 issue 发送消息。

2026-10-04，普通路径输入修复。用户在同一实例关闭 Toon 后人物几乎全黑，轮廓和地面投影仍存在、环境正常，属于实体着色严重异常；具体频率未知。这个对照不能作为正常光影亮度基线。检查实例 Iris 1.8.14-beta.1 字节码确认纹理单元 1 是覆盖色、2 是游戏光照图，原代码却将两者覆盖为模型 lightMap，CPU/GPU 也缺少 `iris_UV1`。这是已确认的接口错误，但尚未在完整游戏中证明它是全黑的唯一原因；默认全白不透明 lightMap 本身不能解释所有全黑情况。

修复在 Iris 程序下绑定游戏覆盖色和光照图，补齐整数中性覆盖坐标 `(0,10)`；普通/自定义 MMD 程序保留原有 lightMap 契约和每顶点 UV1 数据。中性坐标存于既有 UV1 缓冲尾部的额外 8 字节，Iris 属性用 divisor=1 读取第 0 实例，普通非实例绘制的所有顶点共用该坐标，清理时重置 divisor；不改变上下文级常量属性，也不增加 GL 资源。普通 CPU/GPU 通用混合状态提前到 Iris apply 前，结束后清理 Iris 覆盖。Iris clear 会绑定主目标，因此阴影路径保存并恢复 DRAW/READ 帧缓冲。每个 Iris 程序仅记录一次 `Iris实体诊断`，含光照、实际颜色乘数、UV1/UV2/切线属性位置和 RenderSystem 纹理单元 1/2，跳过阴影；重置兼容状态时清空记录。

最终代码在 Zulu JDK 21、共享 Gradle 缓存中通过 15 项定向测试和 Fabric/NeoForge remapJar。再次执行 120 组实际光影程序/选项探针通过。新增 OverlayProbe 用隐藏 OpenGL 上下文验证 EntityPatcher 的 texelFetch、反 alpha 与 Complementary 的 albedo 混合公式；仅第 10 行为中性的纹理同时验证每顶点整数 VBO 和缓冲尾部 `(0,10)`，使用非实例 glDrawElements，验证全局常量属性未改变；零 alpha 覆盖纹理确实输出黑色。它是隔离公式验证，不是游戏问题复现，也未验证完整 Iris 帧缓冲生命周期。

本轮 NeoForge Jar SHA256：`C61B5DDCEAF1CCF0B08D85ECF0719C6C03F45CEE6D16C38B0BC82AA1EAA89E3F`；Fabric：`F27632B2603FE7EB7616CAF2F467195F2F03A70931D0481BEE7767FAAB20B340`。Jar 已核查包含兼容类和 Windows 原生库，未覆盖 D 盘实例。下一步保持位置、时间、相机和光影选项一致，换包后先关闭 Toon 验证人物颜色与投影，再开启 Toon 对照整体亮度。若仍黑，读取一次性诊断，继续核对颜色乘数、光照与材质/切线分支；本轮没有按“25%”硬调 Toon 亮度，环境光偏差仍待实测定位。
