# 多外亲的骨架依赖传播修复

日期：2026-11-09。

## 已撤回

用户实际游戏反馈：第 0 帧静止时尚可，后续动作出现严重位移和扇子乱飞。本方案已撤回，以下内容仅保留为失败尝试的历史记录，不代表当前实现或有效修复。合成探针通过未能验证原折扇动作的正确性。

已恢复此前的外部父参考系、局部静止原点、192 字节姿态接口和 `outsideParentVersion:2`。对应测试、探针和使用文档也恢复为回退前规则。用户资产和时间轴文件未由本轮回退改写；若曾用被撤回版本保存 `outsideParentVersion:3` 的补偿，需要在回退版编辑器中重新计算并保存。

回退验证：Java 458 项，457 通过、1 跳过；Rust model/animation/physics/vr 共 144 项通过；真实 JNI CPU/GPU 和 0～3600 帧舞台探针通过。日志位于 `build/prop-hierarchy-rollback/`。已重新生成 NeoForge JAR，未安装到游戏实例。

回退 JAR SHA-256：`7fa1213fcb47985a0109ad948f9336b93b8546741437bb8bf5eabf2752dab59a`；包内 DLL 与验证使用的 Release DLL 一致，SHA-256：`28676a339df6597d5e0bef9cd867d513c7fdc8d8270021e3f9dc480329c5d525`。下文旧产物与验证结论均已作废。

## 现场与定位

用户反馈：MMD 按教程完整登记时正常；只登记第 0 帧时的偏差与游戏一致；游戏增加之后的外亲仍无改善。

当前 MOD 的确定问题是：`BoneSet`、IK 中间刷新和物理回写遇到自身有外亲的骨骼时，都直接使用外部父矩阵，跳过原 PMX 父级。这样，上级在后续帧新增或切换外亲，不能传到已经有自身外亲的后代。原有测试明确将这种独立求值作为预期，未覆盖用户提出的依赖场景。

先添加 `later_ancestor_binding_reaches_an_already_bound_child_without_accumulation`，确认旧实现失败，证据见 `build/prop-hierarchy-fix/before-rust.log`。

参考实现：

- [nanoem Bone::applyOutsideParentTransform](https://github.com/hkrn/nanoem/blob/main/emapp/src/model/Bone.cc#L323-L340)
- [bx float4x4_mul](https://github.com/bkaradzic/bx/blob/master/include/bx/inline/float4x4_t.inl)

核对 bx 存储和乘法后，nanoem 的世界矩阵组合对应列向量约定下的 `原世界矩阵 × 静止原点逆 × 目标世界矩阵`，并非直接替换 PMX 父级。该参考支持本轮修复方向；它不等于取得了 MMD 本体闭源实现或原折扇逐帧矩阵。

## 修复后的规则

设 `P` 为原 PMX 父级当前全局，`L` 为自身完整局部变换，`O` 为骨骼模型空间静止原点，`T` 为换算到道具空间的人物目标骨骼，`C` 为登记补偿，`E` 为额外偏移：

```text
H = P × L
G = H × inverse(O) × T × C × E
C = inverse(T) × O × inverse(H) × 期望登记姿态
```

根骨骼的 `P` 为单位矩阵。`snap` 的 `C` 为单位矩阵。自动补偿和注册前摆放共用这一公式。

- 常规更新、IK 刷新、子树更新和物理回写均保留 PMX 父级，再应用一次自身外亲。
- 全局变换回写为局部变换时，同时去除普通父级和自身外亲，避免下一次更新重复组合。
- 骨骼静止原点改用模型空间初始位置，不能继续使用相对原父级的 `body_shift`。
- JNI 的 256 字节读取增加 `H`，保留上级影响但排除自身已有外亲，用于可靠重注册。128 字节的全局/局部读取保持兼容；192 字节的第三矩阵现在表示模型空间静止原点。
- 同帧登记仍按用户操作顺序计算补偿；播放时依 PMX 层级传播。新增的上级登记影响已经绑定的后代，未来帧的键不会提前作用到过去帧。
- 独立模型坐标仍为舞台默认；显式旧整模型挂载参与目标空间换算。保留完整动作意味着不再使用旧版“每根已绑定骨骼均忽略上级”的独立绑定结果作为判据。

本轮没有改写用户 PMX、VMD 或已有时间轴，未修改游戏实例。工作区原有改动保留。

## 兼容与使用

JSON 轨道格式仍为 `version:2`；校准标记升级为 `outsideParentVersion:3`。旧补偿按当前原点和组合方式重算后才能用于舞台骨骼外亲。

更新模组后，打开原时间轴，点击“按时间顺序重算全部补偿”，检查预览并保存。保存路径也会自动重算过期补偿。仅含旧整模型轨道的 `version:1` 文件不需要这项迁移。

## 验证

- Java 全量：460 项，459 通过、1 跳过，0 失败、0 错误。
- Rust model/core/animation/physics/vr：151 项全部通过，包括上级后登记、非零模型原点、IK 刷新、CPU/GPU 和动态物理。
- 真实 Release JNI：CPU/GPU 通过同帧多骨登记、后续增加旋转上级、仅第 0 帧绑定与完整绑定结果不同、上级解绑、0/30/45/59/60 帧、直接/回退/重复求值、摆放、动态穗子和普通实例隔离。
- 真实舞台 JNI：CPU/GPU × snap/keepWorld/摆放 × 有无显式整模型挂载，逐帧覆盖 0～3600；子骨骼先登记，后续增加及解除上级外亲；连续/直接/回退/延迟加载一致。最大矩阵元素误差 `0.000002861`。
- 编辑器交互探针通过；控件和 3D 预览为替身，不作为真实 Minecraft 画面验收。
- Fabric 编译、NeoForge shadowJar 和 `git diff --check` 通过。Gradle 使用 JDK 21、默认共享缓存及现有守护进程。

日志位于 `build/prop-hierarchy-fix/`：`after-rust.log`、`rust-related.log`、`gradle.log`、`jni.log`、`stage.log`、`editor.log`。

## 产物与剩余验收

产物：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。

JAR SHA-256：`484a0fad18058addf5f1a39cb9f8e59337636e899e87e4cd2b5069e541428047`。

包内 `natives/windows-x64/mmd_engine.dll` 与实际验证的 Release DLL 一致，SHA-256：`bad3363b240f34b275af8b04328d957e1de5d7cc8591ed5dbb5dfef902d9fccb`。

未安装到游戏实例。需要用原教程折扇、人物及双方 VMD，在游戏中核验 0、3098、3099、3167、3168、3370、3375 及相邻帧，与 MMD 完整绑定画面对照；合成资产的矩阵验证不能代替该项。
