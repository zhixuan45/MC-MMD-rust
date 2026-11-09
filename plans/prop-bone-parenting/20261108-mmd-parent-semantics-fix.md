# MMD 外亲位置、旋转与注册坐标修复

日期：2026-11-08。

## 原因与最终行为

旧 `snap` 补偿取注册帧完整局部矩阵的逆，把 PMX 静止平移和当帧 VMD 位移、旋转一起抵消，因此强制重合并抹掉动作姿态。原测试也将完整矩阵等于目标作为正确行为。新增回归在修改前已失败，日志为 `build/prop-parenting-analysis/regression-before.log`。

按 MMD 官方 v9.32 x64 附带 readme 的外亲说明，骨骼位移为零时位置重合，沿用旧 VMD 时保留其载入姿态相对位移。官方说明副本在 `build/prop-parenting-analysis/official-readme.txt` 第 274–308 行；实际实现依据该语义，不移植 nanoem 未经本体对照的矩阵次序。

现在区分静止平移 `O` 与完整局部矩阵 `L`，动画变换为 `A = inverse(O) * L`：

- 位置绑定：补偿 `C = I`，骨骼全局为 `T * 附加偏移 * A`。零位移时位置重合，有动作位移时保留距离，旋转组合为目标参考系乘道具动作旋转。
- 自动补偿：注册时 `C = inverse(T) * G * inverse(A)`；之后求值为 `T(t) * C * 附加偏移 * A(t)`。无附加偏移时注册不跳变，之后继续使用道具动作变化。
- 原生父矩阵提交为 `T * C * 附加偏移 * inverse(O)`，Rust 骨骼、IK 与物理回写继续使用原有父矩阵乘完整局部矩阵的通路。

`GetPropBonePose` 在 192 字节缓冲区中返回第三个静止平移矩阵；原 128 字节双矩阵读取保持兼容。静止平移取骨骼相对原父级的 `body_shift`，不能用当前动画位置或骨骼绝对初始位置替代。

## 旧整模型挂载的影响

之前预览和舞台均默认把道具先挂在配置手骨，附加握持变换并乘旧 10 倍系数。这会让首次自动注册记录默认手部参考系。

含骨骼关键帧的时间轴现在默认从独立模型坐标求值，不继承隐式手骨和握持位移、旋转；人物与道具模型缩放分别只应用一次，道具大小设置保留。编辑器选择道具骨骼后即可预览这一坐标，首个注册不需要已有骨骼关键帧。首条关键帧之前、解绑之后仍使用独立坐标。

显式整模型时间轴挂载仍作为可选外层参考系参与坐标换算。普通简单道具挂载和仅有旧轨道的时间轴保持原行为。渲染与物理使用相同的模型参考矩阵。

## 兼容与迁移

JSON 仍用版本 2。校验增加 `outsideParentVersion:2`，旧版父矩阵补偿不能按新定义直接解释。旧文件在编辑器中打开并保存会按时间顺序重算并登记新校验；保存前的旧补偿不会用于舞台演出。仅含版本 1 整模型轨道的文件无需迁移。未修改任何用户 PMX、VMD 或已保存的时间轴。

## 验证

- Java 全量：449 项，其中 448 通过、1 跳过、0 失败、0 错误。
- Fabric Java 编译、NeoForge Java 编译及 shadowJar 成功，使用现有 JDK 21 和共享 Gradle 缓存。
- Rust model 分组：69 项全部通过，包括原父级非零时相对静止原点的新增用例。
- 真实 Release JNI，CPU 与 GPU 骨骼求值均通过：非零注册帧的绝对 VMD 位移/旋转保留、零位移重合、同帧父子多骨注册连续、之后动作变化、0→60→0 回退、外层位移旋转抵消、旧缓冲兼容、动态物理/关节、解绑恢复、普通实例隔离。
- 生产编辑器交互探针通过，覆盖同帧注册、前置状态、保存与过期重算、选择页返回及预览生命周期。该探针使用替身控件与 3D 预览，不等同于游戏画面。
- `git diff --check` 通过。

命令：

```powershell
.\gradlew.bat :common:test :fabric:compileJava :neoforge:shadowJar --offline --console=plain
python tools/testing/rust_batches.py --groups model --offline
python tools/prop-bone-probe/run.py
python tools/timeline-editor-probe/run.py
```

日志：`build/prop-parenting-analysis/verification-{gradle,rust,jni,editor}.log`。

## 产物与现场边界

产物：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。

JAR SHA-256：`8142d6b1712ad79ef3d032105aa1cd6a2a8935230ca7a6e3f5c40a76bdbb4b17`。

包内 `natives/windows-x64/mmd_engine.dll` 与完成 JNI 验证的 Release DLL SHA-256 相同：
`569752db05144ad11f7404562af7c9004b94013367abe031f6bc6e121cb71dc7`。

本轮没有替换游戏实例中的模组，也没有完成原截图人物、道具和动作的游戏内视觉对照。数值及原生运行时验证已完成，原截图场景的握持画面仍需在更新模组并重算旧时间轴后验收。
