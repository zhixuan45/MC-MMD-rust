# 三渲二透明叠层与深度补写对照

2026-10-19；1.21 基线 `3bfae6cfedfec220d678c895436ff13d17d3e26b`。本轮修复了可复现的层选择漏画及共享池错误回退，原截图仍**待游戏验收**。完整命令、证据和交付边界见 [本轮报告](../../plans/toon-transparency/2026-10-19_report.md)。

## 设置与生效规则

Fabric/NeoForge 的“三渲二”分类提供以下设置，保存后在后续绘制中生效：

| 设置 | 配置字段 | 默认与行为 |
| --- | --- | --- |
| 透明叠层数量 | `toonTransparencyLayerCount` | 默认 4；0 关闭分层，1～16 请求最多相应数量的逐像素深度层。预算可能降低实际层数。 |
| 关闭深度补写（对照） | `toonDisableDepthSeal` | 默认 false；开启后仅跳过主体半透明混合后的深度补写，保留原透明颜色和 Alpha、实心深度、阴影。可能影响后续描边、云和后处理的遮挡。 |
| 禁用透明材质 | `toonDisableTransparency` | 默认 false；开启时可见半透明像素实心化并写深度，孔洞仍裁切，同时跳过透明排序与分层。与关闭深度补写独立。 |

数量为 0 时仍混合半透明颜色，并默认保留深度补写；不会变成不透明。旧配置缺省为 4 层和正常深度补写，磁盘配置中的数量规范到 0～16。没有透明材质时保持快速路径。

保留 JVM 诊断覆盖，优先于保存的配置：

```text
-Dmmdskin.toonTransparencyLayers=false
-Dmmdskin.toonTransparencyLayerCount=8
-Dmmdskin.toonDisableDepthSeal=true
-Dmmdskin.toonTransparencyDiagnostics=true
```

数量属性合法范围为 0～16；非法字符串或越界值明确报告 `INVALID_COUNT` 并回退普通透明排序。`toonTransparencyLayers=false` 强制关闭分层；设为 true 不覆盖配置中的 0，固定层数对照请同时设置数量。JVM 覆盖会掩盖游戏设置，正常使用应移除这些启动参数。

全局/发片裁切仍使用 `toonAlphaCutoff`、`toonHairAlphaCutoff`（0.001～1，默认均为 0.001）。最终 Alpha 先裁切，再分类实心/半透明；本轮没有改变阈值、双面、材质 morph 或模型资产。

## 层选择与合成

实心像素先绘制并写深度。开始透明阶段后，将宿主不透明深度按**相同深度格式**复制到一张共享纹理，继续用硬件深度测试处理不透明遮挡，保留 LESS/LEQUAL 的区别。每层使用 R32F 颜色附件及 MIN 混合选择最近的原始 `gl_FragCoord.z`，不写这张共享不透明深度。空层清为 2，合法窗口深度 0～1（包括远平面 1）仍可区分。

下一层只保留严格更深的原始深度；颜色重放只保留与选中 R32F 深度位值相等的片元。固定 `1e-7` 容差已移除，避免 D24 存储/采样量化与容差加法舍入产生漏层或合层。原始深度完全相等的片元在同一组内按源 submesh/三角形提交顺序混合；CPU/GPU 排序结果不用于重排这些已选层。该规则对于不变模型稳定，但不声称能从数值相同的深度恢复几何上不可区分的前后关系。

超过已选层数的更深片元仍按既有三角形中心排序混合，再从远到近重放所选层。**尾部保留，仍为近似排序**；16 层不保证所有模型、所有姿态精确。分层按一次模型绘制执行，多个透明模型之间也没有全局 OIT 保证。颜色阶段沿用原光影 profile 的照明、MRT writer 和混合规则，不将未知 MRT 通道当作普通 RGBA 合成。

## 预算与诊断

共享池上限 128 MiB。逻辑分配字节为：

```text
池宽 × 池高 ×（原格式不透明深度字节数 + 4 × 池层数）
```

D24/D32/D32F/D24S8 的不透明深度按 4 字节计，D32FS8 按 8 字节计；驱动对象、对齐等开销另计。1920×1080、D24、4 层约 39.55 MiB，8 层约 71.19 MiB，16 层约 134.47 MiB，故此分辨率在预算内最多请求生效 15 层。没有修改全屏纹理为局部缓冲。

池可复用历史较大尺寸/层数；降低数量会减少重放次数，但不保证立即缩小现有池。若历史高水位组合超预算，而当前请求可以容纳，则重建为当前需求，不能错误关闭分层。小型且格式不同的 GUI 目标仍保守回退，避免反复挤掉世界目标。

开启“GPU 日志”或 `toonTransparencyDiagnostics=true` 后：

- `[MMD透明分层]` 输出 JSON：启用状态、原因码、请求/实际层数、目标尺寸/深度格式/样本数、预算、池尺寸/层数/逻辑分配字节及深度补写开关。状态变化日志最多每 5 秒一条。
- `[MMD透明余量]` 查询**真正绘制的尾部片元**，沿用当前颜色 pass 的 Alpha、裁剪、双面、几何与硬件不透明遮挡条件。末层非空不等于余量。
- 查询环固定 4 项，仅 ready 后读取，满时跳过采样，不等待 GPU。结果标注源 `frame` 和 `submission`；不是读取结果时的当前帧，也不用于省略本帧任何几何。中途异常的尾部查询作废。

MSAA、默认 FBO、活动宿主遮挡查询、stencil 测试、缺少 uniform/纹理单元、不支持的目标/深度函数及预算耗尽等均返回有原因码的普通透明回退。纹理深度附件仍要求 core 4.5 或 ARB DSA；D16 暂不启用。正常退出、异常退出均恢复 FBO、viewport/scissor、深度、纹理/sampler、颜色掩码和索引 0 的独立混合方程/开关。

## 离线验证与本地验收

当前已验证：独立相交面参考、2/4/6/8/16/17 层、D24/D32F、近深度/共面/远平面、Alpha 0/11/128/254/255、CPU/GPU 排序输入、普通/批量尾部、不同像素的层数与不透明遮挡、异常状态清理、预算降级，以及深度补写开关保留半透明颜色和实心深度。

Linux 先生成 classpath，再提供已安装的 Linux LWJGL natives 与 DISPLAY（或由 `xvfb-run` 启动脚本）。脚本不下载依赖：

```sh
bash gradlew --offline -I tools/translucent-performance/validation.gradle \
  :common:classes :common:writeTranslucentProbeClasspath -x :prepareNativeResources
LWJGL_NATIVE_CLASSPATH='/path/lwjgl-natives.jar:/path/glfw-natives.jar:/path/opengl-natives.jar' \
  bash tools/translucent-performance/run-toon-transparency-probe.sh
```

Windows 继续使用 `run-toon-transparency-probe.ps1`。它也运行新的容量和叠层参考探针；真实 BSL profile 对照仍可使用 `-BslPack`。本轮云端没有实际光影包，不能把历史 BSL/Complementary 结果当作本轮验收。

本地游戏固定原 PMX/贴图、动作时刻、镜头和分辨率，依次比较数量 0/4/8/16；记录实际层数和余量。固定同一数量再单独切换“关闭深度补写（对照）”。检查发尾三角块、渐变、脸/衣料/袜子及描边，分别覆盖无光影与实际光影包、静止和转身、多模型。记录 Jar/原生库哈希、配置、GPU/CPU 时间及显存；离线通过不能替代原截图验收。
