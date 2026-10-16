# 纸娃娃姿态复用优化报告（2026-10-16）

## 结论

纸娃娃不再为 GUI 单独构造一份“预期姿态”并逐项比较头部、眼睛和位置输入。世界模型在当前渲染帧完成绘制后，记录实体、帧号、native 输入版本和是否为第一人称；纸娃娃命中时直接使用这份已求值的骨骼、动画、物理与 GPU 蒙皮结果。GUI 仍独立设置光照、排序和显示矩阵。

固定朝向模式只旋转纸娃娃的整体根显示矩阵，保留本帧局部骨骼、头眼和物理结果；跟随模式继续根据玩家身体朝向与视角俯仰设置整体显示朝向。复用开关、VR、舞台播放、外部姿态输入、模型未就绪、帧或输入版本失效，以及第一人称缺少完整身体索引时仍回退独立纸娃娃实例。

## 代码变化

- `BaseModelInstance` 删除 `ScenePoseSnapshot` 热路径和 GUI 预期姿态同步。
- 新增 `PaperDollPoseState`，只保存安全复用所需的实体、帧、输入版本和第一人称标志。
- `LivingEntityModelStateHelper` 的常规同步改为直接写入 native port，不再分配姿态快照。
- 新增 `PaperDollDisplayTransform`，集中测试固定／跟随两种整体朝向。
- 纸娃娃共享绘制入口只调用 `doRenderModel`，不会再次推进动画或物理。

## 验证

- common JUnit：210 tests，0 failures，0 errors，0 skipped。
- Fabric、NeoForge `compileJava`：通过。
- Fabric `remapJar`、NeoForge `shadowJar`：通过；构建重新执行 Rust release native。
- 真实 GL 探针：材质绑定、透明提交、GPU 准备、缓存、静态缓冲和纸娃娃合成／深度／状态恢复全部通过。
- `git diff --check`：通过。

## 游戏复测关注点

启动后保持相同模型、光影、分辨率和相机，分别记录纸娃娃关闭／开启时 `scene.PAPERDOLL.evaluations`、`scene.PAPERDOLL.reuses`、`nativeUpdate.PAPERDOLL`、`physics.PAPERDOLL` 与帧间隔。预期共享模型的纸娃娃 evaluations 和 physics 次数下降，reuses 上升；固定模式下眼睛、头部、动作和物理应与世界模型保持当帧状态，只改变整体显示朝向。实际 FPS 和光影兼容性仍需游戏内日志确认。

本轮未修改游戏配置，也未提交 Git。
