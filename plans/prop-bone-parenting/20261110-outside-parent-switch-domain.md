# 外亲切换窗口纯计算

日期：2026-11-10。

## 范围

仅修改 `PropTimeline` 的纯逻辑，并新增独立测试；未编辑 Rust、`PropBoneParenting.java`、`StagePropAnimationController.java`、`NativeFunc.java` 或其他文件。工作区既有改动保留。

## API

`PropTimeline.BoneWindow(int previousFrame,int nextFrame)`：

- `DEFAULT = (0,0)` 表示无切换窗口，保持普通 VMD 采样。
- 校验非负帧；`nextFrame != 0` 时必须 `previousFrame < nextFrame`，与 Rust `OutsideParentWindow` 的校验一致。
- `active()` 在任一字段非零时为真。

查询方法：

- `outsideParentWindow(String childBone,float frame)`：单骨骼窗口，无绑定历史返回 `DEFAULT`。
- `outsideParentWindows(float frame)`：返回所有历史或未来有外亲的骨骼；已解绑骨骼仍保留，以便旧局部动画按窗口归零。
- `outsideParentWindows(float frame,State state)`：在时间轴窗口基础上并入当前 `State` 的绑定；仅存在于 `State` 的草稿绑定返回 `DEFAULT`。

`State` 与 `evaluate` 未改动，现有三参数构造和当前绑定结果保持兼容。

## 语义

窗口按每个 `childBone` 的 target 变化分段，只比较目标骨骼名：

- 连续登记相同 target 不产生切换；补偿、`offset`、`snap`、`placement` 变化不影响窗口。
- 绑定到不同 target、解绑、重新绑定都产生切换。
- `previousFrame` 为当前绑定段起点；采样帧早于首次绑定时，取首次绑定帧，使未来绑定影响更早帧。
- `nextFrame` 为下一次 target 变化帧；没有后续变化时为 `0`。
- 解绑段沿用解绑帧作为 `previousFrame`，因此解绑后的骨骼仍有窗口并可归零旧局部动画。
- 从未绑定过的骨骼不进入窗口表。

`PropTimeline` 本身按 owner 实例化，`BoneBinding` 不增加 owner UUID；child 骨骼名加 target 骨骼名已足够区分本时间轴内的状态。

## 验证

新增 `PropTimelineSwitchWindowTest`，覆盖仅 0 帧、完整 3098/3099/3168/3370/3375、未来解绑、连续同目标、重绑不同目标、小数帧、无绑定历史、初始 `State` 绑定及草稿绑定。结果：10 项，10 通过，0 失败，0 错误。

命令：

```powershell
.\gradlew.bat :common:test --tests "com.shiroha.mmdskin.stage.domain.timeline.PropTimelineSwitchWindowTest" --offline --console=plain
```

构建成功；测试报告为 `common/build/test-results/test/TEST-com.shiroha.mmdskin.stage.domain.timeline.PropTimelineSwitchWindowTest.xml`。
