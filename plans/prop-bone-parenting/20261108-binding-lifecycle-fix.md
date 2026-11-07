# 旧挂载受骨骼外亲残留影响的回归修复

日期：2026-11-08。

## 已复现的缺陷

`StagePropAnimationController.bind` 在验证 VMD 轨道之前就公布时间轴状态。渲染调用方在 bind 返回 false 后仍读取该状态，继续提交骨骼外亲。若失败发生在 session.model 建立前，stop 时没有模型可供清除，已提交的原生外亲可以留在缓存的道具模型上。旧版整模型挂载重用同一句柄时会受到这份骨内参考系影响。

修复前新增用例 `failedMotionCannotInstallBoneParentsOrLeakThemIntoTheLegacyAttachment` 确认失败：VMD 轨道被拒绝后，外亲仍然被提交，stop 未清除。证据：`build/prop-parent-leak-before.log`。

## 修复

- 时间轴先在局部读取，待道具 VMD 成功安装后才公布为有效控制状态。
- 仅允许会话已绑定、仍有效且句柄匹配的道具实例接收外亲。
- 释放或失败回滚时同时清除时间轴状态和外亲管理器引用。
- 保留旧挂载、VMD、骨骼外亲计算与物理；未修改 PMX、原生接口或人物挂点算法。

## 验证

- 新增三项回归覆盖失败安装无残留、有效骨骼时间轴切回旧挂载、失败后跳帧与重复绘制，以及错误道具句柄隔离。
- 定向控制器与矩阵测试通过：`build/prop-parent-leak-after.log`。
- 全量 Java：437 通过，1 项原有用例跳过，0 失败，0 错误。
- Fabric/NeoForge 编译及 NeoForge shadowJar 成功：`build/prop-parent-leak-final.log`。
- 原生代码未变。本轮未重复运行完整 Rust 回归。

产物：`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`。

SHA-256：`9996712d82c43bc54f5afe8342b14bdd92290b58790e59e4c649d170e166117b`。

已核对包内 DLL 与当前 Release DLL 相同；未安装到用户游戏实例。

## 与用户现场现象的对照

用户报告初始姿态/模型相关，并怀疑旧绑物品接口与新增功能混用。上述用例确认了一条状态残留路径，尚需本次实际模型/动作与场景对照其触发条件。

已登记的 keepWorld 补偿依赖注册时的人物和道具姿态。当前运行时按道具 VMD 旁的 sidecar 读取补偿，referenceMotion 仅供编辑器恢复参考动作，不负责按人物模型/人物动作自动区分演出。因此换模型或换参考动作需要重新登记/重算；按演出作用域区分补偿是独立后续问题。

只读检查到的常用 NeoForge 实例仍是未包含 PropBoneParenting 的旧包，latest.log 更新于 2026-11-06，无法据此定位本次新功能的现场现象。
