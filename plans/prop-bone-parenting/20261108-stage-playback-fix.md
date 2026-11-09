# 舞台道具加载与绝对帧一致性修复

日期：2026-11-08。沿用当前工作区的外亲语义修复与注册前摆放功能，保留其他已有修改。

本记录更新 `20261108-binding-isolation-plan.md`、`20261108-binding-isolation-verification.md` 中“校验失败时跳过舞台道具 VMD、回到简单挂载”的旧策略。

## 已复现的问题

1. `StagePropAnimationController.bind` 在加载 VMD 之前执行补偿校验，原比较包含独立骨骼时间轴不使用的默认手骨与握持位移、旋转。变更这些配置即可让 bind 返回 false，连 VMD 都不加载。两个新增 Java 回归在修复前均失败，见 `build/stage-prop-fix/before-java.log`。
2. 渲染调用方忽略 bind 结果，失败后读取默认时间轴状态，再按普通主手挂载绘制静态道具；这条源码路径同时解释了“外层绑定覆盖时间轴”和“VMD 不播放”两种表现。
3. 人物舞台动画 speed=0，通过外部 `SeekLayer` 定位，但仍保留普通循环设置。原生 seek 不钳末帧，后续 update 会将超出短动作末帧的舞台帧取模。预览却将同样的帧钳到人物动作末帧。已用 60 帧人物动作、90 帧舞台时钟复现原始回卷，再以原生模型回归验证修复。

上述为源码和回归复现，不代表已经取得用户当前游戏现场日志或原模型画面对照。已知本机 NeoForge 实例缺少截图中这份道具 VMD/时间轴，其旧日志不能确认本次具体触发条件。

## 实现

- 普通手持继续使用外层默认骨骼和握持变换。界面将此设置命名为「普通手持绑定」。
- 舞台道具默认使用独立模型坐标，与是否有骨骼关键帧无关；只有时间轴显式指定整模型挂载时使用该外层参考系。
- 预览、注册、舞台绘制共用 `usesIndependentPropSpace` 策略，首个关键帧前、解绑后、无配套时间轴的 VMD 也遵循同一坐标规则。
- 来源校验仅检查实际参与坐标计算的配置。独立舞台不因默认手骨或握持位移、旋转变化而拒绝补偿；显式整模型挂载仍检查握持变换，资产与缩放继续校验。
- 原始自动补偿仍校验人物参考动作。位置绑定 `C=I` 与摆放后补偿 `C=E×inverse(A₀)` 不依赖该人物动作，允许使用不同/合并的人物 VMD。
- 真正的补偿失效只移除不安全的骨骼控制，保留可用的道具 VMD、显隐和显式整模型轨道。提示一次原因与重算入口，不再静默回落到默认主手。
- VMD 本身不可用时隐藏舞台道具，保持失败加载的所有权与清理约束；不会提交无会话负责清除的原生外亲。
- 舞台校验使用演出实际选择的道具 PMX，不依赖随后可能变更的普通手持 PMX 选择。
- 原生绝对 seek 钳到该动画末帧；普通按 elapsed 推进的循环语义保持，新增回归覆盖从 seek 末帧恢复普通循环。

## 验证

- Java 全量：458 项，457 通过、1 跳过，0 失败/错误。
- Rust 全分组（含单独运行的资源生命周期测试）：151 项全部通过。
- Fabric 编译、NeoForge shadowJar 通过，使用现有 Zulu JDK 21、共享 Gradle 缓存和 daemon；未执行 clean。
- 既有真实 JNI 外亲/摆放探针通过：CPU/GPU 注册前后位置误差和矩阵最大元素误差均为 `0.000000000`，VMD 位移/旋转、外层坐标抵消、物理/关节及普通实例隔离继续通过。
- 生产编辑器交互探针通过；Minecraft 控件和 3D 预览在此探针中为替身。
- 新增真实 JNI 舞台集成探针，运行生产 `StagePropAnimationController`、`PreviewMotionBinding`、挂载变换和 `PropBoneParenting`。合成 PMX 含网格、父子控制骨、动态刚体和关节；此探针对姿态一致性关闭物理，物理连续性由既有探针另测。
- `snap`、`keepWorld`、摆放登记 × 有/无显式整模型挂载 × CPU/GPU，共 12 组。先对照 0～300 帧，再将动作与登记时刻扩大 12 倍，对 0～3600 帧逐帧检查。
- 检查短人物动作末帧保持、道具 VMD 持续变化、登记连续性、外层换骨、显隐、解绑、同帧重复求值、连续播放、重新加载后直接跳帧、回退、延迟绑定，以及过期补偿不阻止 VMD。两轮最大矩阵元素误差均为 `0.000000954`，未发现播放历史累积的额外偏差。

命令：

```powershell
.\gradlew.bat :common:test :fabric:compileJava :neoforge:shadowJar --offline --console=plain
python tools/testing/rust_batches.py --groups all --offline
python tools/prop-bone-probe/run.py
python tools/timeline-editor-probe/run.py
.\gradlew.bat :common:classes :common:writeStagePropProbeClasspath -I tools/stage-prop-probe/classpath.gradle --offline --console=plain
python tools/stage-prop-probe/run.py
python tools/stage-prop-probe/run.py --frame-scale 12
```

日志均位于 `build/stage-prop-fix/`：`final-build.log`、`rust-batches.log`、`parenting.log`、`editor.log`、`stage-jni.log`、`stage-jni-long.log`。`git diff --check` 通过。

## 产物与实际验收范围

NeoForge 产物：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。

JAR SHA-256：`5663decc27e8ddd6d246722f5c569eb64e5a874c33ddf035623ad2a4ebfdb444`。

包内 `natives/windows-x64/mmd_engine.dll` 与真实 JNI 探针使用的 Release DLL 逐字节相同，SHA-256：`5c6fff81ef8afd90287e40994f15ec0f127a4d17d5b1067158ea21e62ec8d5c9`。已核验包内中英文「普通手持绑定」及舞台道具提示文案。

没有替换游戏实例的模组或改写用户 PMX、VMD、时间轴。尚未使用原截图中的人物、道具和动作在实际 Minecraft 画面中验证。合成探针通过不能替代这项验收，也没有据此断言原模型全部偏差都由短动作回卷造成。

旧 v2 时间轴若缺少 `outsideParentVersion:2` 来源信息，需要在编辑器重新打开、重算并保存；再重新开播。有效且来源匹配的现有时间轴可直接运行。
