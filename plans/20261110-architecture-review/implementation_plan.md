# 最新提交架构梳理与重构计划

日期：2026-11-10

状态：四批代码重构已实施并通过自动化验证；游戏内场景验收待进行。执行结果与兼容性决定见同目录 `execution_report.md`。

## 0. 提交范围核对

分支 `feat/1.21.1-neoforge`，已提交内容与 `origin/feat/1.21.1-neoforge` 同步（HEAD `eddb229`）。除本计划自身产生的未跟踪目录 `plans/20261110-architecture-review/` 外，无其他改动。本轮考察最近 6 个提交：

| 提交 | 主题 | 主要落点 |
| --- | --- | --- |
| `eddb229` | 外亲切换窗口 + 动作轨道采样 | Rust `motion_track.rs` / `bone_set.rs` / `native_func.rs`，Java `PropTimeline` / `PropBoneParenting` |
| `f0e228b` | 校准化舞台道具动画 + 模型生命周期 | Java `StagePropAnimationController` / `PropTimelineCalibration` |
| `03a6df6` | 动作加载失败后清理陈旧骨骼绑定 | Java `StagePropAnimationController` |
| `6369540` | 骨骼挂接手持道具 + 时间轴支持 | Rust `bone_set.rs` / `prop_physics.rs`，Java `PropBoneParenting` / `HeldItemPmxRenderer` |
| `63e482e` | 舞台道具时间轴 + 隐藏渲染场景 | Java `PropTimeline` / `PropTimelineScreen` / `RenderScene` |
| `7ebbd8e` | 地形透射捕获状态与可见性 | Java `compat/iris/*`、`toon_surface_lighting.glsl` |

**提交标题与内容不符（事实）**：`eddb229` 标题为 `Add effect pack import and material editing support`，但该提交内不含任何 `effectpack` / `EffectPack` 文件改动；`git log -- common/src/main/java/com/shiroha/mmdskin/render/effectpack/` 显示该目录最后一次改动是 `d32551b`。该提交的 46 个文件全部是道具外亲与时间轴相关。`f0e228b` / `03a6df6` 同样只改 Java 与探针，Rust 侧无改动。

结论：标题误导，后续检索效果包相关工作时不要以 `eddb229` 为锚点。

## 1. 总体判断

这一批提交实现的是同一条主线：**完整 PMX 道具的多骨骼外亲（outside-parent）绑定**，附带 VMD 侧车时间轴、图形编辑器、以及 Rust 侧 MMD 外亲切换窗口的采样语义。

架构现状是**"领域模型较纯、应用层缺失"**：

- `PropTimeline`（`common/src/main/java/com/shiroha/mmdskin/stage/domain/timeline/PropTimeline.java`）是干净的值模型，无 Minecraft / IO 依赖，是本批代码中质量最高的部分。
- 但"打开 → 校准 → 重算 → 保存"这条编排逻辑写在 UI Screen 里（`PropTimelineScreen.java:77-96`、`:397-410`、`:450-457`），"加载 → 校准 → 绑定 → 播放"又独立写在播放端 Controller 里（`StagePropAnimationController.java:145-210`）。两侧各自实现了一遍 VMD 句柄生命周期，并各自持有一个 `PropBoneParenting` 应用入口；注册补偿的计算只在 `PropTimelinePreview.register()` 内，播放端只应用已保存的补偿。
- 结果是：VMD 句柄生命周期与外亲应用路径各存在两份实现，UI 与播放端对"什么是合法状态"的判断也可能不一致（推断）。

这条主线的架构债比 Iris/渲染线严重，但 Iris 线的模块边界问题（兼容层反向渗入渲染层）影响面更广。

## 2. 已核实的结构性问题

以下每条均已用源码核对过，行号对应 `eddb229`（HEAD）。标注"推断"的条目没有运行或线程证据，实施前需先验证。

### 2.1 主线：道具外亲 / 时间轴

1. **UI 层承担了应用层职责**（事实）。`PropTimelineScreen.java:87` 直接调 `PropTimelineFiles.open`，`:95-96` 调 `PropTimelineCalibration.matches/capture`，`:457` 直接写文件落盘。同时 Screen 持有 `draftVisible`、`keepWorld`、`pendingBone`、`placement`、`placementValid` 等业务态字段（`:44-64`）。

2. **外亲应用路径有两个入口，且类位置错位**（事实）。补偿只在 `PropTimelinePreview.java:104` 的 `register()` 内计算（经 `PropBoneParenting.registration/compensation`），`StagePropAnimationController` 不计算补偿，只通过 `PropBoneParenting.apply` 应用已保存的补偿（`:121-124`）。`PropBoneParenting` 位于 `player.render` 包，却被 `stage.client.playback`（`StagePropAnimationController.java:40/121`）和 `ui.selector.timeline`（`PropTimelinePreview.java:47`）共同依赖 —— 包名与实际职责错位；同时它把纯矩阵运算（静态方法）与 native 读写（实例方法）混在一个类里。

3. **Editor 复制了领域聚合逻辑**（事实）。`PropTimelineEditor.java:18-21` 自建 `TreeMap` / `boneKeys` / `snapshot`，`:83` 手工排序替换，`:97` 自行实现 `beforeRegistration` 的注册顺序过滤；而 `PropTimeline.java:121-140`、`:244-258` 已经做了排序、去重、唯一性校验。两侧规则未来必然漂移。

4. **VMD 句柄生命周期重复**（事实）。`PreviewMotionBinding.java:23` 与 `StagePropAnimationController.java:180-200` 各自完成 load → 校验 bone/morph → 设置层 → seek → delete 的完整流程。

5. **JSON schema 分裂**（事实）。`PropTimelineFiles.java:79-95` 负责 timeline 顶层结构，`PropTimelineCalibration.java:68-90` 独立解析/序列化 `calibration` 字段；`PropTimelineFiles.java:93` 硬编码 `objects[0]`，单道具假设写死在 codec 里。

6. **渲染层反向依赖舞台客户端并改写播放状态**（事实）。`HeldItemPmxRenderer.java:32-33` 在渲染路径里取 `StageClientRuntime.peek()`，`:36` 在渲染路径里调用 `propAnimations.stop(player.getUUID())` —— 渲染线程直接改写播放会话生命周期。

7. **`synchronized` 块内做资产 IO 与哈希**（事实 + 推断）。事实：`HeldItemPmxRenderer.java:49-55` 经 `bind` 传入的 calibration 回调会对模型/VMD 做 SHA-256（`PropTimelineCalibration.java:59`），`StagePropAnimationController.java:145-210` 的 `synchronized bind` 内读 JSON 并 `loadAnimation`；`bind` 由渲染路径调用，且有 `attempted` 缓存（`:149`），仅首次或句柄/目录变化时执行，不是每帧开销。推断：`begin/stop` 来自 `StageAnimSyncHelper`/`DefaultStagePlayerSyncPort`，与渲染线程是否为不同线程、是否实际争锁，尚无线程证据，需确认。

8. **失败语义不统一**（事实）。`HeldItemPmxRenderer.java:48-60` 中"道具未就绪"、"挂接失败"、"缺骨骼"、"故意隐藏"统统返回 `true` 占位，调用方无法区分；`:92-94` 又把异常降级为 `false`。`NativeMatrixPort` 的默认实现返回 `false`（`NativeMatrixPort.java:20-28`），会静默退化为错误姿态而非明确失败。

9. **挂接缓存仅随 `clear()` 清空**（事实）。`PropBoneParenting.java:18` 的 `catalogs` 以 native handle 为键缓存骨骼表，仅在 `clear()`（`:176-186`）时整体清空，而 `clear()` 只在 `StagePropAnimationController.java:256` 被调用。Rust 侧句柄由单调自增的 `AtomicI64` 分配（`jni_bridge/mod.rs:47-51`），不会复用，因此"handle 复用命中旧骨骼表"不成立；`PropTimelinePreview.close()`（`:212`）不调用 `clear()`，但预览及其 `PropBoneParenting` 随编辑器关闭一并回收，缓存不会跨会话累积；仅长生命周期实例（播放端 `session.parenting`）在 `stop` 前会随绑定过的模型增多而增长，影响有限。

10. **派生状态双份维护**（事实）。`PropTimeline.java:113-114` 同时保存 `boneKeys` 与派生的 `targetChanges`，`evaluate()`（`:140-146`）每帧线性扫全部骨骼键。键数量多时 UI 与播放端成本线性上升。

### 2.2 JNI / Rust 侧

11. **Java 声明与 Rust 导出不对齐**（事实）。Java 侧 174 个 `public native`，Rust 侧 181 个唯一导出（`NativeFunc` 类），7 个 Rust 导出没有对应的 `public native` 声明。其中 6 个无任何 Java 调用：`GetLayerMaxFrame`、`PauseLayer`、`PlayLayer`、`ResumeLayer`、`SetLayerFadeTimes`、`StopLayer`。`GetMaterialSpTextureMode` 不是缺口：`NativeFunc.java:98` 有非 native 的 Java 包装，实际转调 `SphereMaterialNative.getMode`（对应 Rust 导出 `material_sphere.rs:11`），Rust 侧同名 `NativeFunc` 导出（`native_func.rs:618`）才是冗余。

12. **采样层字段的读取方缺失**（事实）。`mixed_translation` / `mixed_orientation`（`motion_track.rs:61/70`）仓库内无调用方；`local_transform_mix`、`disable_physics`（`:23-27`）只在 `motion_track.rs` 内被写入，并在 `seek_precisely`（`:385-398`）中参与插值，但结果无人读取。`enable_physics` 同样没有 `motion_track.rs` 以外的读取方。`:282-296` 的物理切换分支只在关键帧 `is_physics_simulation_enabled` 由 true 变 false 时触发；`vmd_loader.rs:232` 创建的关键帧默认为 true，VMD 解析是否会产生 false 未核实。这些字段来自早期提交 `5fe18f8`，不是 `eddb229` 引入。

13. **`seek_windowed` 绕过 `seek` 的物理过渡**（事实）。`motion_track.rs:163-219` 命中窗口时直接返回 `from_keyframe` 或 `default`，而 `from_keyframe`（`:44-58`）固定 `local_transform_mix: None`。

14. **概念放错层**（事实）。`OutsideParentWindow` 定义在 skeleton 层（`bone_set.rs:11-26`）并由 `BoneSet` 持有（`:64-67`），animation 层为使用它而 import skeleton（`motion_track.rs:11`、`motion.rs:11`）。依赖方向是 animation → skeleton，skeleton 并不依赖 animation，不存在循环或反向依赖；问题只是窗口属于 VMD 时间线语义，却由骨骼容器定义与存储。

15. **绑定更新非原子**（事实 + 推断）。事实：`external_parents` 与 `outside_parent_windows` 是两个独立 HashMap、两次独立加锁的 JNI 调用（`native_func.rs:1216-1237`、`:1242-1286`）；Java 侧 `PropBoneParenting.java:152-173` 先推 windows、再推 parents、最后 `evaluatePropPose`，任一步被拒会留下部分更新。推断：是否存在并发 tick 观察到中间态，取决于 `apply` 与 `UpdateModel` 是否在不同线程，尚无线程证据。

16. **不变量校验分散且可绕过**（事实）。动态刚体骨骼禁绑的判定在 `MmdModel`（`prop_physics.rs:13-21`），而 `BoneSet::replace_external_parents`（`bone_set.rs:447-472`）只校验索引与矩阵 —— 直接调用 BoneSet 可绕过该规则。

17. **死代码**（事实）。`animation.rs:371` 与 `:532` 的 `tick_animation` / `tick_animation_no_skinning` 在 early `return` 之后保留了整段不可达旧实现，用 `#[allow(unreachable_code)]` 压制警告。`animation.rs` 共 757 行，其中约 130 行不可达。

18. **物理后端双实现**（事实）。`prepare_prop_physics` 在 `physics.rs:168` 与 `physics_legacy.rs:254` 各接一次。

19. **`BoneSet` 职责过载**（事实）。`bone_set.rs` 816 行，兼管层级 / IK / append / 物理回写 / 蒙皮 / VRM / 外部父级 / 切换窗口 / 内存统计。

20. **错误处理不统一**（事实）。绝大多数 JNI 导出返回 `0` / `false` / `null`；仅 `UpdateModelParallel`（`evaluation_profile.rs:85`）抛 `RuntimeException`；`native_func.rs` 内 `lock()/read()/write().unwrap()` 共 282 处（如 `:1203/1205/1233/1235`）；`catch_unwind` 只出现在 `issue_diagnostics.rs`，其余导出没有 panic 隔离。

21. **缓冲区 ABI 隐式**（事实）。`GetPropBonePose` 容量 128 返回 2 个矩阵、≥192 返回 3 个（`native_func.rs:1295-1311`），Java 侧固定分配 192 字节（`PropBoneParenting.java:19`）；`SetPropPhysicsAttachment` 硬性要求 64 字节，无显式长度/版本参数。

22. **轨道抽象不完整**（事实）。`MotionTrack` trait 仅 Bone / Morph 实现，IK 与 Camera 各有私有 API（`motion_track.rs:646`、`:739`）。`Motion::duration` 不含 IK（`motion.rs:47-69`），`motion_end_frame` 含 IK（`vmd_loader.rs:422-427`）；`clear` 不清 IK，`merge` 不合并 camera/IK。

### 2.3 Iris / 渲染线

23. **反射直插 Iris 内部**（事实）。`IrisShadowSource.java:15-30` 读私有 `targets`；`IrisTerrainShadowReplay.java:38-55` 读 `shouldRenderTranslucent`、`packHasVoxelization`、`LevelRendererAccessor`；mixin 依赖 `ordinal=2` 注入点。

24. **兼容逻辑反向渗入渲染层**（事实）。`render/shader/TerrainShadowBinding.java:3-4` 直接 import `compat.iris`；`BaseModelInstance.java:78-82` 用 `IrisCompat` 决定接收。

25. **Shader 适配靠字符串重写且重复**（事实）。`BslToonLighting.java:22-68` 与 `ComplementaryToonLighting.java:24-114` 各自匹配精确表达式与参数签名，雾处理重复（Bsl `:53-57` / Comp `:36-40`），GLSL 由 Java 字符串拼接生成。

26. **GL 状态恢复有三套实现**（事实）。`ShadowReplayGlState.java:9-84`、`IrisTerrainShadowReplay.java:123-163` 的 `EngineCacheState`、`IrisTerrainShadow.java:47-50` 手写保存，三者不共享实现。

27. **配置语义过载**（事实）。`ConfigData.java:58` 的 `toonWholeModelLighting` 同时充当"地形阴影接收"（`BaseModelInstance.java:79-81`）与捕获开关（mixin `:30-31`）。透射无独立配置，仅由纹理可用性隐式决定。

28. **主体与描边取色路径不同**（事实）。`OutlineRenderPass.java:166-172` 用材质 diffuse 作自动描边色，主体走 `applyPackLighting`（`toon_main_body.frag.glsl:87-88`）。强光影下两者可能不一致。

## 3. 重构计划

按"收益 / 风险"排序，分 4 批。每批独立可交付、可回滚。

### 第 1 批：死代码清理与命名归位（低风险，立即可做）

**目标**：不改行为，只降低后续重构的噪音。

1. 删除 `animation.rs:371`、`:532` 两处 `#[allow(unreachable_code)]` 后的不可达实现；把真正生效的 `tick_animation_internal` 提为唯一实现。
2. 清理 `motion_track.rs` 内无消费者的 `local_transform_mix` / `disable_physics`（`enable_physics` 保留，物理开关确有意义），或为它们补上消费者 —— 二选一，取决于后续是否要做 VMD 物理开关。
3. 修正 `eddb229` 等误导性提交标题 —— 无法改历史，改为在 `doc/` 新增一份"近期提交索引"标注真实内容。
4. 包归位：`TimelineMotionChoices` 从 `ui.selector.timeline` 移到 `asset.catalog`（它做目录扫描，`TimelineMotionChoices.java:17`）；`PropTimelineLayout` 从 `ui.selector` 移到 `ui.selector.timeline`。
5. 统一 JNI 导出清单：删除或补齐 6 个无 Java 调用的 Rust 导出（`PlayLayer` / `StopLayer` / `PauseLayer` / `ResumeLayer` / `SetLayerFadeTimes` / `GetLayerMaxFrame`）。`GetMaterialSpTextureMode` 的 Rust 导出同名冗余，`NativeFunc.java:98` 的 Java 包装走 `SphereMaterialNative`，删除前先确认没有外部调用。并在 `doc/` 留一份 JNI 一致性检查脚本。

**验收**：`.\gradlew.bat :common:test` 通过；`cargo test --manifest-path rust_engine/Cargo.toml` 通过；`:neoforge:shadowJar` 通过。

### 第 2 批：Java 侧应用层抽取（主线核心，收益最高）

**目标**：消除 UI 与播放端的两份编排。

6. 新建 `stage/application/PropTimelineApplicationService`（放在已有的 `stage/application` 下，与 `StageSessionService` 对齐），职责：open / calibrate / recalculate / save / 冲突检测。`PropTimelineScreen` 只保留控件与事件绑定，不再直接触碰 `PropTimelineFiles` 与 `PropTimelineCalibration`。
7. 新建 `stage/application/PropRegistrationService`：承接 `PropTimelinePreview.register()` 的补偿计算，使 `PropTimelinePreview` 退化为纯播放与绘制。
8. 拆分 `PropBoneParenting` 为两部分：
   - `stage/domain/timeline/BoneParentMath`（纯矩阵运算，无 native 依赖，可单测）
   - `bridge/runtime/PropBoneBindingAdapter`（负责 native 读写）
    两者同时服务 UI 预览与舞台播放，消除第 2 节第 2 条的双入口与包错位。
9. 统一 VMD 句柄生命周期：抽出 `PropMotionHandle`，让 `PreviewMotionBinding` 与 `StagePropAnimationController` 共用 load → 校验 → 设层 → seek → release 流程。
10. `PropTimelineEditor` 改为委托 `PropTimeline` 做排序 / 去重 / 唯一性校验，消除派生规则双份维护；`PropTimeline` 预计算每轨 segment 索引，把 `evaluate()` 从线性扫描改为二分 + 索引。

**验收**：新增 `PropTimelineApplicationServiceTest`、`BoneParentMathTest`；现有 `PropTimelineFilesTest`、`PropBoneParentingTest`、`StagePropAnimationControllerTest`、`PropTimelineEditorTest` 全绿；编辑器功能手工走查（打开 / 登记 / 重算 / 保存 / 退出确认）。

### 第 3 批：JNI 边界收敛（跨语言，风险中）

11. **绑定事务化**：合并 `SetPropBoneParents` + `SetPropBoneWindows` 为单次 `SetPropBindings`，Rust 侧单锁、单次校验、统一错误码。Java 侧 `PropBoneParenting.java:152-173` 的三步改为一次调用，避免任一步失败留下部分更新。
12. **窗口语义归位**：在 animation 层定义 `PropBindingState`（含 parent matrix + 切换窗口），`BoneSet` 只存最终 parent matrix，窗口归 animation。让 `OutsideParentWindow` 不再定义于 skeleton（`bone_set.rs:11-26`），`motion_track.rs:11`、`motion.rs:11` 不必再 import skeleton 类型。
13. **统一失败模型**：Rust 对绑定失败返回错误码（handle 缺失 / 骨骼缺失 / 动态刚体 / 矩阵非法），Java 映射为 `Ready / Pending / Failed`；渲染器只在 `Ready` 时占位，`Failed` 按策略隐藏并只上报一次。同步清理 `NativeMatrixPort` 的默认 `false` 静默退化路径。
14. **不变量收口**：把动态刚体骨骼禁绑的判定从 `MmdModel`（`prop_physics.rs:13-21`）下沉到 `BoneSet::replace_external_parents`，使其不可绕过。
15. **缓冲区 ABI 显式化**：`GetPropBonePose` 改为固定 3 矩阵 + 显式返回值；`SetPropPhysicsAttachment` 增加显式长度参数。
16. 所有 JNI 导出统一 `catch_unwind` + 统一错误码，`lock().unwrap()` 改为可恢复的失败路径。

**验收**：`cargo test --manifest-path rust_engine/Cargo.toml`；新增 Rust 单测覆盖"绑定+窗口一次提交"与"动态刚体骨骼被拒"；`:common:test` 全绿；用 `tools/prop-bone-probe` 与 `tools/stage-prop-probe` 复跑第 2 批未改动的行为基线。

### 第 4 批：Iris / 渲染层边界治理（可与前 3 批并行）

17. 引入每帧 `TerrainShadowFrame` 状态对象，统一 `captured / valid / transmission / status / reason` 与资源生命周期，删除 `IrisTerrainShadow`、`IrisTerrainTransmission`、`IrisTerrainShadowReplay` 三处分散的静态字段。
18. 统一 GL 状态管理：合并 `ShadowReplayGlState` 与 `EngineCacheState`，删除 `IrisTerrainShadow.java:47-50` 的手写保存；同时缓存 uniform location。
19. 拆分配置语义：`toonWholeModelLighting` 拆为 `receiveTerrainShadow`、`terrainTransmission`、`outlineMaterialColor`、`baseBrightness`，旧键做迁移（`doc/render/TERRAIN_SHADOW_RECEIVER.md:3` 已记录沿用旧键的事实）。
20. 建立 `IrisBackend` 适配接口，把反射、字段名、注入点集中收敛到 `compat.iris`；渲染层只依赖接口，不再直接 import `compat.iris`。

**验收**：`tools/iris-toon-probe` 的 `LightingProbe` / `TransmissionGlProbe` 复跑；`:common:test` 全绿。

## 4. 执行顺序与依赖

```
第 1 批（清理/归位）
        ↓
第 2 批（Java 应用层）───→ 第 3 批（JNI 边界）
                                    ↓
第 4 批（Iris 边界，可并行）
```

第 2 批先于第 3 批：绑定事务化需要先有稳定的 Java 侧调用方（第 2 批会把 `PropBoneParenting` 拆开），否则 JNI 改造会同时动到两层。第 4 批与主线无耦合，可独立推进。

## 5. 本轮未做 / 需另行确认

- 跨人物外亲、多道具对象、事件轨道：文档明确留待后续（`doc/PROP_TIMELINE.md`），本次未纳入计划范围。
- Iris 实景视觉验收：`doc/render/TERRAIN_GLASS_TRANSMISSION_20261106.md:130` 记录探针验证已过但实景验收未闭环，第 4 批完成后的验收方式需要单独定场景。
- `PropBoneParenting.catalogs` 仅在 `clear()` 时清空（第 2 节第 9 条）已核实；句柄不复用，影响有限，不单独列为缺陷。第 2 批拆分该类时顺带让生命周期显式化即可。
- 第 2 节第 7、15 条涉及的线程归属（`begin/stop`、`bind/seek`、`apply` 与 `UpdateModel` 是否在不同线程）尚无证据，实施第 3 批前需先确认。
- `prop_bone_origin` 仅取 translation（`prop_physics.rs:34-37`），若道具骨骼存在非单位 rest rotation，Java 侧 `origin^-1 * local` 无法完全去除静止姿态。当前道具资产大概率不受影响，需用带旋转 rest 的模型验证。
- 本计划编写时未执行测试与构建；后续实施与验证结果已记录在 `execution_report.md`，早期整合运行不作为干净 HEAD 基线。
