# 道具注册前摆放与保持姿态登记

日期：2026-11-08。基于同目录的 MMD 外亲语义修复继续实现，保留已有工作区改动。

## 操作与行为

1. 在外亲轨道选择道具骨骼和人物目标骨骼。
2. 点击「对齐所选骨骼并摆放」。对齐使用该帧求值后的骨骼原点与朝向，包含当前道具 VMD 的影响，不以模型原点代替选中骨骼。
3. 在「摆放位置」和「摆放旋转」中微调。位移沿人物目标骨骼局部轴，单位沿用道具 PMX；角度单位为度。微调实时更新预览。
4. 点击「确认摆放并保持姿态注册」，将预览中的同一份绑定登记到当前帧，再保存时间轴。

摆放暂停帧播放，仍可旋转视角、缩放预览。隐藏的道具会临时显示，确认与取消均不改写显隐轨道。只有确认才写入骨骼关键帧；取消、切换帧、切换轨道或切换参考动作会结束临时摆放。关闭时仍有未确认摆放会提供放弃或继续编辑。窗口缩放和从关闭确认页返回会保留数值输入，包括尚未输入完整的负数；输入非法时保留最后一个有效姿态，并禁止确认。

摆放锁定骨骼选择和绑定模式，保存按钮及 Ctrl+S 不会把临时预览偷偷登记。重新点击对齐会归零微调参数。

## 求值和持久化

人物目标骨骼换算到道具参考系的矩阵为 `T`，静止原点为 `O`，道具动画局部变换为 `A = inverse(O) × L`，微调为 `E`。

摆放期望姿态为 `G = T × E`；注册补偿为 `C = inverse(T) × G × inverse(A₀)`。因此登记帧得到 `T × E`，之后为 `T(t) × E × inverse(A₀) × A(t)`。这保持摆放姿态，并继续播放道具 VMD 的后续位移和旋转。

- `PropBoneParenting.registration` 是预览和登记共用的计算入口。
- `PropTimelineEditor.preview` 将临时绑定覆盖到预览状态，编辑轨道不变。
- 确认直接写入已预览的 `BoneBinding`，不重新采样；随后恢复正常时间轴预览。
- `BoneBinding` 增加可选的 `placement` 六分量参数；原六参数构造器继续使用 `null` 默认值。
- JSON 仍为版本 2，新增可选 `placement: {offset:[x,y,z], rotation:[x,y,z]}`。只允许与 `keepWorld` 一起使用。
- 运行时仍只应用已烘焙的补偿；`placement` 供编辑器重算使用，不重复叠加。保存、重新打开、来源过期重算和编辑已有摆放关键帧均保留其参数。

未修改用户 PMX、VMD，也没有向游戏实例写入模组或时间轴。

## 验证结果

- Java 全量 453 项：452 通过、1 跳过、0 失败、0 错误。
- Fabric 编译、NeoForge 编译和 shadowJar 成功。使用现有 JDK 21、共享 Gradle 缓存和已有 daemon。
- 真实 Release JNI，CPU 与 GPU 骨骼求值均通过：选中动画骨骼对齐、非零位置/旋转微调只应用一次、登记连续性、重复重算、30→45→60→30 帧 VMD 播放及回退。
- 强制清除原生外亲并重新提交后，登记前后位置误差与矩阵最大元素误差在 CPU/GPU 探针中均为 `0.000000000`。
- 原有真实 JNI 检查继续通过：同帧多骨登记、非零 VMD 位置绑定、骨骼静止原点、外层坐标抵消、物理/关节、解绑恢复及普通道具实例隔离。
- 生产编辑器事件探针通过：实时微调、非法输入、未确认时无关键帧修改、直接确认不重新采样、窗口缩放/关闭确认返回、保存/重算、取消恢复、隐藏道具临时显示，以及 320×240 小窗口摆放与登记。
- 已目视检查 854×480 和 320×240 布局截图。此探针的 Minecraft 控件与 3D 预览为替身；骨骼数值另由真实 JNI 验证。
- `git diff --check` 通过。

命令：

```powershell
.\gradlew.bat :common:test :fabric:compileJava :neoforge:shadowJar --offline --console=plain
python tools/timeline-editor-probe/run.py
python tools/prop-bone-probe/run.py
```

日志：`build/prop-placement/{gradle,editor,jni}.log`。

布局截图：`build/timeline-editor-probe/placement-854.png`、`placement-640.png`、`placement-320.png`。

## 产物与边界

最终产物：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。

JAR SHA-256：`a2840ea47ba00ce0ef1c0ca642054aae9eff5389d5a6360e04e543cbd13bf96a`。

包内 DLL 与本轮真实 JNI 验证的 Release DLL 相同，SHA-256：
`4f181fdd4e07516f2ea27aff1116bb00fcc68cd6a290659172d8a582937031f2`。

本轮已完成数值、原生运行时及界面事件验证。尚未安装到游戏实例，也没有对原截图人物/道具/动作进行游戏内目视验收。
