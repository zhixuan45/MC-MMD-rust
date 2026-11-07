# 多骨骼道具外亲验证记录

交付日期：2026-11-08。

## 自动验证

- Java 全量：434 项通过，1 项跳过，0 失败，0 错误。
- 跳过的现有测试：`com.shiroha.mmdskin.render.effectpack.FxPackLibraryTest.importsRealTex1AndPreservesItsActiveSemantics()`。
- Rust 默认 upstream1201：148 项通过。动画/骨骼 28、模型/JNI 68、物理 15、VR 30、核心 6、资源生命周期隔离 1。按分组进程运行，资源生命周期用例单独串行执行。
- Fabric、NeoForge Java 编译及 NeoForge shadowJar 构建通过。
- 真实 Release JNI + 生产 Java 外亲转换：CPU/GPU 骨骼求值路径均通过同帧 Root/Center 登记连续性、VMD 位移/旋转保留、后代传播、动态骨运动/关节、非法输入原子拒绝和清除绑定恢复。资产为探针自动生成的合成 PMX/VMD。
- 编辑器交互探针：通过拖动、显隐、骨骼选择返回、同帧独立注册及前置补偿顺序、v2 保存、小窗口删除、放弃修改和预览资源生命周期检查。Minecraft 控件及原生 3D 为明确标记的替身。
- 重复外亲输入去重测试通过，避免重复绘制使原生姿态缓存失效。

## 重跑命令

使用现有 JDK 21：

```powershell
.\gradlew.bat :common:test :neoforge:shadowJar :fabric:compileJava --console=plain
python tools/testing/rust_batches.py --groups all --offline
python tools/prop-bone-probe/run.py
python tools/timeline-editor-probe/run.py
```

日志：`build/prop-bone-final-gradle.log`、`build/prop-bone-final-rust.log`。
布局截图：`build/timeline-editor-probe/multi-bone-854.png`、`attachment-320-scrolled.png`。

## 构建产物

`neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar`

SHA-256：`3a1612648d93b5004f4a55bcaf10d35e9c218f3b2cb74f40cf7c31d446f49d27`

已逐字节核对 JAR 内 Windows x64 原生 DLL 与本次 Release DLL 一致，并检查包内外亲类、骨骼状态类和编辑器类包含本次改动。Shadow 会重新编码 Java class 常量池，因此未将 class 文件逐字节相等作为验收条件。未向用户游戏实例安装。

## 后续现场验收

需要文档对应折扇 PMX、人物和道具 VMD，在真实游戏中核验 0、3098、3099、3167、3168、3370、3375 帧及相邻帧，检查旋转中心、CPU/GPU 画面、光影阴影和穗子物理。以上自动验证不替代该项。legacy-physics 未纳入本轮验证。

当前目标为道具所属人物，人物跨模型碰撞代理与跨人物外亲未在本轮新增。现有独立道具物理、刚体和关节保留。
