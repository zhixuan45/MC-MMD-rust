# Ray-MMD 1.5.2 第一阶段实现与验证

日期：2026-11-01。

## 实现

- 新增 `FxConstantExpression`：标量/向量、嵌套构造器、括号、一元正负、四则运算、
  常量引用、对象宏、纯函数式宏、pow/exp/exp2/sqrt/abs/saturate/min/max/clamp/lerp。
  限制表达式长度、嵌套、运算次数、宏/常量数量和参数数目，拒绝循环、未知输入、
  非有限结果及向量宽度不匹配。
- 新增 `RayMaterialParameters`：正确消费 static const 及函数式宏声明；
  宏体、字符串和 include 不参与普通常量声明扫描。保留未知代码/条件宏拒绝。
  Editor 的 CONTROLOBJECT 依赖给出专用错误。
- 常量 alpha 来源 0 与默认原模型贴图 alpha 使用独立标识。
  主体普通/批量路径、透明候选、阴影采样包装和描边支持常量 alpha，
  保留材质透明度因子且不再被原贴图 alpha 挖空。
- ALBEDO 通道的 type 字段携带 diffuse 乘色开关（原字段未用于基础色类型）；
  普通 uniforms 和批量 480 字节通道布局保持一致。
- 基础色常量按 Ray 的线性值解释；颜色贴图/diffuse 解码后再应用缩放和副颜色。
  ColorSynthesis 使用线性基础色与亮度合成，返回宿主颜色接口时重新编码。
- 两个 renderer 的现有宿主程序清理改动完整保留；
  本轮仅在各自描边调用处追加原始材质名，以支持逐材质效果绑定。

## 回归与构建

使用已有共享 Gradle 缓存/daemon 和 Zulu JDK 21，没有修改全局 Gradle/JDK 配置。
第一次沙箱构建被共享 wrapper 锁文件权限阻止，随后通过批准的沙箱外执行完成。

```powershell
.\gradlew.bat :common:test :fabric:remapJar :neoforge:shadowJar -x buildRustNative --offline --console=plain
```

结果：368 项 Java 测试，0 失败、0 错误、1 项跳过。
测试包含表达式计算、错误/资源边界、参数结构、常量 alpha、
diffuse/ColorSynthesis 选项、批量序列化及描边遮罩。

Fabric 和 NeoForge JAR 均成功，检查包含新增解析类，打包 shader 与最终源码字节一致。
本轮使用既有 Rust native，没有改动或重编译 native。

日志：`build/ray-1.5.2-diagnosis-20261101/stage1-validation.log`。

## 真实包复扫

用当前生产导入器通过只读 ZIP FileSystem 扫描用户提供的原 ZIP。

- Ray 候选共 268 个，原通过 34，现通过 63。
- 新增 29 个通过，原通过文件零回退。
- 截图的 ClearCoat/material_black.fx 和 material_white.fx 通过。
- purple/white_blue 的颜色表达式错误消失，现到达闪烁未支持检查。
- TDA 的 SSS_SKIN_TRANSMITTANCE 宏可以计算，现到达皮肤着色模型未支持检查。

剩余 205 个文件的首个拒绝原因：

- 140：MMD Editor CONTROLOBJECT 控制器。
- 34：EMISSIVE_MAP_APPLY_BLINK。
- 25：CUSTOM_ENABLE。
- 2：EMISSIVE 来源 6（视频/宿主资源）。
- 各 1：ALBEDO 来源 2、ALBEDO 来源 6、EMISSIVE 来源 2、
  EMISSIVE_MAP_APPLY_MORPH_COLOR。

完整结果：`build/ray-1.5.2-diagnosis-20261101/stage1-import-results.tsv`。
导入成功不代表使用了原 Ray 场景灯光/IBL/后处理。

## OpenGL 像素验证

```powershell
.\tools\effectpack-probe\run.ps1 -FxZip C:\Users\JuziD\Downloads\Tex_1.zip
```

最终运行：NVIDIA OpenGL `4.3.0 NVIDIA 610.62`，16,630 次断言通过。
其中 16,384 次是四个 32×32 RGBA 输出的逐分量有限值检查，不代表同数量的独立场景。

- 原有真实 Tex_1 与新建静态材质分别比较普通提交、CPU 多纹理窗口和实际 GPU
  排序/间接提交；两轮最大像素差均为 `0.0`。
- 检查常量 alpha 替换原贴图 alpha 并保留材质透明度、无覆盖时还原原 alpha、
  diffuse 关闭/开启、线性颜色空间及 ColorSynthesis 的独立数值预期。
- 生产阴影补丁保留 RGB 和原后续 alpha 运算；新增常量 alpha 覆盖像素检查。
- 保留既有来源 9、副法线、外置 alpha、世界图、资源释放及 GL 状态恢复检查。

中间一次探针失败是新增无外部纹理材质仍沿用“至少三窗口”的断言；
已按纹理需求修正断言，静态材质允许单窗口，纹理预设仍要求拆窗。

## 交付及未决项

- `fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar`
  SHA-256：`073f571e4607edd515f715297d2558441a8bb7a4b4c867d5d56a270f5a0ca042`。
- `neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`
  SHA-256：`2893097797e6f601cfcca68bb9ee6bf2b25760339900fe70032ac86ed7a1f2ff`。

未安装到实例，未启动游戏。Iris 实际程序选择、完整材质切换及描边外观仍需实机检查。
闪烁、GIF 和专用着色模型按适配计划进入后续阶段，当前仍明确拒绝。
外置 alpha 的描边纹理/UV 适配是既有未覆盖范围；本轮描边仅补齐常量 alpha。
