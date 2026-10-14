# 单模型尾巴与裙摆碰撞开关实施计划

状态：2026-10-13 完成计划审查与文档修订，生产代码尚未实施。本文供后续执行 AI 接手；本轮只修改计划，不能将文档修订当作代码实现或游戏验收完成。

## 需求与范围

在单模型设置的“尾巴物理”卡片增加第三个开关“裙摆碰撞”，配置名为 `tailSkirtCollisionEnabled`。保留原计划默认开启（true）；旧配置缺少该字段时开启，明确保存的 false 必须经过复制、保存、加载、实例同步和物理重建后保留。

用户已确认：此开关仅覆盖裙子／裙摆，不包括披风、外套或泛称衣摆。闲置抬起与移动增强保持现有独立控制，不修改尾巴受力、物理关节参数、身体碰撞壳或资产原始数据。保存后同步同名已加载实例，并在后续加载和物理重建时恢复。每次切换不需要重建物理世界。

默认开启会改变旧 Stable 模式对初始重叠尾裙的保护行为。关闭屏蔽全部目标尾裙对，也不等同于旧的“仅初始重叠时屏蔽”。说明和验收应如实反映这两项行为变化。

## 当前源码基线

执行前先完整阅读根目录 `index.md`，再检查 Git 状态和相关文件。保留已有未提交改动；本文路径和行数以本次审查的工作树为基准，执行时重新核对。

`rust_engine/src/physics/collision_topology.rs` 的 Stable 分支目前同时过滤初始 AABB 重叠的动态尾巴—动态下装、动态长发—动态下装对。前面的同组动态图规则和共享 FollowBone 根规则也可能过滤尾裙对，不能只删除末尾 AABB 特例就宣称开启生效。拓扑计划目前只在 `collision_enabled && joints_enabled` 时构建。

`mmd_physics.rs` 还会安装 `garment_contact_plan.forced_ignore_pairs`：它保护裙摆—骨盆／大腿掩码补位时意外开放的其他对象对。身体初始深嵌过滤由 `mmd_physics/body_contacts.rs` 安装；Bullet 关节还有自身禁碰规则。尾裙开启路径不能一律清除这些规则。

当前 `is_skirt_or_lower_garment` 包含裙子、披风、外套等广义下装关键词；此函数继续服务现有兼容逻辑，不能为新开关直接整体收窄。当前拓扑尾巴分类只看刚体本地名与英文名，后续 `set_tail_bone_names` 才按关联骨名补充 `is_tail_dynamic`，需要消除新开关构建与热切换之间的分类差异。

现有初始化顺序为 `MmdModel::init_physics` → 构建刚体／拓扑 → 身体嵌入检测 → 骨名补充尾巴分类 → 应用尾巴受力选项 → initialize。配置变化引发的物理重建再次调用 `init_physics`，新选项必须在构建过滤时就可用。

## 碰撞控制契约与优先级

目标对集合定义为：两个不同、成功创建的动态刚体，其中一个属于尾巴，另一个属于严格裙子／裙摆分类。动态包括 `Physics` 与 `PhysicsWithBone`；排除 FollowBone、静态尾根、身体 blocker、头发和马尾。集合不以初始 AABB 重叠作为条件，也不依赖是否存在关节或是否同组。

实际碰撞需要全局碰撞启用、PMX 双向组位允许，并且没有仍应保留的禁碰原因。开启不补齐尾裙掩码，不保证所有 PMX 模型必然发生接触。双方任意一方原始掩码禁止时，仍然禁止。

| 条件 | 目标尾裙对行为 |
| --- | --- |
| 全局 `collision_enabled=false` | 实际无碰撞；保存单模型选项，重新开启及重建后恢复 |
| PMX 任一侧原始掩码禁止 | 无碰撞，不能通过撤销 ignore 或掩码补位意外开放 |
| 开关关闭 | 添加尾裙开关禁碰原因；Strict／Stable／Relaxed 均生效，`joints_enabled=false` 也生效 |
| 开关开启 | 移除尾裙开关禁碰原因；目标对跳过 Stable／Relaxed 通用拓扑、共享根和初始 AABB 稳定过滤 |
| 目标对仍有其他禁碰原因 | 保留，例如掩码补位保护和关节自身禁碰；不能承诺 `check_collide_with` 必然为 true |

Strict 仍不增加通用稳定过滤，但遵守显式的单模型关闭设置。开启只让目标尾裙对退出稳定过滤，不关闭整个稳定系统。长发与下装的旧 AABB 防御规则、披风／外套与尾巴的旧稳定规则继续保留。尾巴与身体、裙摆与身体及静态尾根与裙摆均不受此开关影响。

## 分类与原生过滤设计

新增尾裙接触专用模块，集中维护严格裙摆识别、目标对集合及切换状态。严格裙摆识别使用明确的裙子／裙摆语义，例如“裙”、`スカート`、`skirt`、`petticoat`，支持常见分段名称。不得仅凭“摆／裾／下装／下衣／衣摆／coat／cloak／cape／flap／燕尾”等宽泛词获得资格。沿用现有明确非裙部件的排除规则；本地名明确表示披风、外套、饰品或头发时，不应被英文别名中的裙摆词覆盖。冲突和无明确裙摆语义的名称保守排除，并用测试固定边界。

尾巴识别复用现有 `classify_tail_dynamic`，输入刚体本地名、英文名和直接关联骨骼名，保持马尾、头发、静态体及 blocker 排除。构建阶段和热切换使用同一分类结果。无骨名的底层构建入口仍按刚体名识别，不伪造骨名；模型运行时入口必须传真实骨名。

在 `build_physics_with_bone_names` 构建过滤计划前完成上述分类。将新目标对标记独立传入拓扑计划，不用严格裙摆标记替换原有广义 `is_skirt`，避免连带改变披风／外套稳定过滤。`set_tail_bone_names` 的波浪延迟职责保留；其尾巴缓存分类应与构建时一致，不能让分类变化留下旧禁碰对。

从 `collision_topology.rs` 拆开目标尾裙对与长发／广义下装规则。在同组拓扑、共享根及末尾 AABB 规则的所有入选点排除目标尾裙对，交给尾裙专用规则统一处理；非目标对按现行模式处理。尾裙关闭的对集合独立于 `joints_enabled` 和稳定模式安装，不能继续放在现有拓扑门控内部。

### 禁碰来源组合与热切换

Bullet 的 ignore 写入不按业务来源管理。为受控目标对保存规范化索引 `(min, max)`、应保留的基础禁碰原因和尾裙开关原因；最终忽略状态为这些原因的并集。基础原因至少覆盖掩码补位保护，关节自身禁碰保持原有安装和生命周期。身体过滤仅处理当前资格允许的身体对，尾裙接口不能撤销其结果。

不要求重构全库碰撞系统。只在目标对上建立明确的原因组合，并让拓扑安装过程遵守目标对排除规则即可；若必须扩大到全局过滤架构，先更新计划说明必要性和影响，不顺手进行重大重构。

`MMDPhysics::set_tail_skirt_collision` 更新实例选项并计算最终状态差异，只对发生变化的目标对调用双向 `set_ignore_collision_check`。不能对所有尾裙对无条件写 false，也不能重复写 true 依赖 Bullet 去重。反复保存相同值应无副作用。

对变化涉及的刚体去重后调用 `refresh_body_collision_filter`，释放旧接触算法／流形缓存。现有 Rust 封装已双向设置 ignore，C++ 刷新使用 `cleanProxyFromPairs`，先复用该路径；无证据表明缺陷时不新增 C ABI。恢复接触须在碰撞检测或下一步模拟后检查，不能只读取 ignore 标志。刷新共享刚体后，其他仍允许的接触应能重新建立。

目标对、分类和基础原因在构建时缓存，切换时读取缓存，不做逐帧名称扫描。构建／销毁清理状态；重复构建入口也要清理旧状态，防止索引或指针失效。诊断区分拓扑过滤与尾裙关闭数量，保持热切换后的统计真实，不能沿用已失效的构建快照冒充当前状态。

## 配置、UI、JNI 与实例生命周期

`ModelConfigData` 新增默认 true 字段，并同步 `copy()`。`normalizedCopy()` 依赖 copy，遗漏会使明确关闭值在配置管理器读取或保存时恢复默认。补充缺省、false、copy、normalizedCopy、JSON 往返及配置管理器路径回归。

尾巴卡片高度从 42 增至 56 像素，第三开关沿现有排列使用 `tailCard.y + 44`，矩形宽高沿用 26×9。内容总高度由公式计算，从 242 增至 256，不单独维护不一致的硬编码总值。新增布局矩形 `tailSkirtToggle`、标签绘制、点击、悬停和工具提示；命中必须经过 viewport 裁剪。说明文本表达“仅裙子／裙摆；开启仍遵守模型原始碰撞设置”。保存、重置、重新打开及滚动后状态一致，保留标题和底部操作固定布局。

语言键 `gui.mmdskin.model_settings.tail_skirt_collision` 对应中文“裙摆碰撞”和英文“Skirt Collision”；说明文本也通过语言键管理。旧的两个受力开关含义和默认值保持现状。

Java 方法统一扩展为：

```java
setTailPhysicsOptions(long modelHandle, boolean idleLift,
                      boolean movementBoost, boolean skirtCollision);
```

这是新增第三个 boolean，总计四个业务参数。`NativeFunc.SetTailPhysicsOptions` 同序；Rust JNI 在 JNIEnv 和 receiver 之后依次接收 `jlong, jboolean, jboolean, jboolean`，按 `!= 0` 转换。声明、桥、端口、具体实现、空实现、测试替身及调用点必须同步。JNI 名保持一致，但签名变化要求 Java 与原生库同批交付，不能混用旧 DLL。

首次加载链为 `ModelRepository` → `ModelRuntimeAccessPort` → `DefaultModelRuntimeAccessPort` → `NativeScenePort` → `NativeRuntimeBridge` → `NativeFunc`。保存后的同步链为 `DefaultModelSettingsRuntimeGateway.applyTailOptionsToMatchingModels` → `NativeScenePort` → 原生层，继续覆盖同名已加载实例；其他模型不变。

`MmdModel` 增加 `tail_skirt_collision` 字段并在构造时默认 true。setter 在物理尚未创建时也保存值，物理存在时转发给当前实例。`init_physics` 创建 `MMDPhysics` 后、构建过滤和首次接触检测前设置该选项；设置函数不能因尚无刚体而丢掉状态。配置重建重新应用该字段，再构建目标对与过滤。Java 初次加载向已创建的原生实例传配置时，也通过同一热切换路径正确撤销旧接触，不仅修改后续重建默认值。

## 文件范围与实施顺序

以下路径相对仓库根目录。接口联动前用 `rg` 核对所有调用／实现，不把清单当作不会漂移的完整搜索结果。

| 文件 | 修改职责 |
| --- | --- |
| `common/src/main/java/com/shiroha/mmdskin/config/ModelConfigData.java` | 字段、默认值、copy；复核配置管理器使用 |
| `common/src/main/java/com/shiroha/mmdskin/ui/selector/ModelSettingsLayout.java` | 第三开关矩形和高度公式 |
| `common/src/main/java/com/shiroha/mmdskin/ui/selector/ModelSettingsScreen.java` | 绘制、输入、悬停、说明和重置 |
| `common/src/main/resources/assets/mmdskin/lang/zh_cn.json`、`en_us.json` | 标签及说明 |
| `common/src/main/java/com/shiroha/mmdskin/NativeFunc.java` | 正确包路径下的 JNI 声明 |
| `common/src/main/java/com/shiroha/mmdskin/bridge/runtime/NativeScenePort.java`、`NativeRuntimeBridge.java` | 接口与 JNI 转发 |
| `common/src/main/java/com/shiroha/mmdskin/model/port/ModelRuntimeAccessPort.java` | 首次加载接口契约 |
| `common/src/main/java/com/shiroha/mmdskin/model/runtime/DefaultModelRuntimeAccessPort.java`、`ModelRepository.java` | 首次加载转发和配置应用 |
| `common/src/main/java/com/shiroha/mmdskin/scene/client/SceneModelManager.java` | 更新匿名空实现 |
| `common/src/main/java/com/shiroha/mmdskin/render/pipeline/LivingEntityModelStateHelper.java` | 更新匿名空实现 |
| `common/src/main/java/com/shiroha/mmdskin/ui/selector/adapter/DefaultModelSettingsRuntimeGateway.java` | 保存同步第三选项 |
| `common/src/test/java/com/shiroha/mmdskin/config/ModelConfigDataTest.java` | 默认、复制、持久化回归 |
| `common/src/test/java/com/shiroha/mmdskin/ui/selector/ModelSettingsLayoutTest.java`（新增） | 使用现有 JUnit 5 验证布局和裁剪；该文件当前不存在 |
| Java gateway／runtime access 定向测试（按现有结构补充） | 同名同步、首次加载参数、不同模型隔离 |
| `rust_engine/src/jni_bridge/tail_options.rs` | JNI 参数转换与转发 |
| `rust_engine/src/model/runtime/mod.rs`、`tail_options.rs`、`physics.rs` | 状态、setter、首次构建和重建应用 |
| `rust_engine/src/physics/collision_topology.rs` | 排除目标对；保持其他拓扑／长发规则 |
| `rust_engine/src/physics/mmd_physics/tail_contacts.rs`（新增） | 严格分类、缓存目标对、组合原因、差异切换与刷新 |
| `rust_engine/src/physics/mmd_physics.rs` | 挂载模块、构建阶段分类／安装及实例委托 |
| 对应 Rust 分类／拓扑／模型生命周期／Bullet 接触测试 | 覆盖下述行为矩阵，避免只镜像实现 |
| `index.md` | 同步接口、分类范围、优先级及验收边界 |

`garment_contacts.rs`、`mmd_physics/body_contacts.rs`、`mmd_rigid_body.rs` 和 Bullet 封装是必须核对的依赖。优先复用，仅在组合原因或共享分类确有需要时最小修改，不扩大碰撞资格。`mmd_physics/diagnostics.rs` 及相关快照输出按统计变化调整。

实施顺序：先建立严格分类、目标对排除与模式矩阵测试，再实现基础原因组合和真实 Bullet 切换；随后接入模型状态／重建、Java 配置与完整端口链，最后完成 UI／翻译、构建和游戏对照。跨层修改前确认签名和时序一致。代码注释简短说明关键边界。

本次审查 `mmd_physics.rs` 为 946 行，余量很小。实现放入子模块，主文件只保留必要状态与委托，测试可独立文件。执行前后统计所有改动代码文件行数，任何单文件超过 1000 行必须拆分，不能只检查这一处主文件。

## 验证与验收

### 自动化行为验证

分类／拓扑测试覆盖：严格裙子命中；披风、外套、普通衣摆、马尾、头发、静态尾根及 blocker 排除；仅关联骨名能识别尾巴；无效骨索引和缺失骨名；本地排除语义与英文别名冲突。对目标尾裙测试同组／跨组、有／无关节、共享根、初始重叠／分离、三种稳定模式及关节关闭。开启移除目标对稳定过滤，关闭不依赖 AABB；非目标长发和披风／外套保持原行为。

真实 Bullet 测试使用成功创建的重叠尾／裙动态刚体，先检测建立接触，再关闭开关并刷新，检测后确认目标对接触流形消失；重新开启并检测，确认原本 PMX 双向允许且无其他禁碰原因的接触恢复。还要测试重复 true／false、连续往返切换、一个刚体共享多个接触、掩码任一侧禁止、全局碰撞关闭及保留基础禁碰原因。检查接触对象对而非仅比较总数。`check_collide_with` 是辅助状态断言，不能代替真实接触或掩码检查。

模型测试覆盖无物理时先设置 false、首次 init、已有物理时切换、配置重建后保留 false、实例之间选项隔离，并保留闲置／移动选项独立性。通过生产初始化和重建路径断言，不只检查字段值。

Java 测试覆盖缺省 true、明确 false 经 copy／normalizedCopy／JSON／配置管理器不丢失；三个选项同序转发；保存同步全部同名句柄且不影响异名；首次装载使用已保存选项。新增布局测试验证第三开关在卡片内、行间无重叠、视口外不命中、小窗口滚动后可达及底部按钮不遮挡。

### 编译、JNI 与交付检查

使用仓库现有 Cargo 和 Gradle wrapper，Java 工具链按项目配置核对 JDK 21。以下是实施后执行的基础入口，不是本文已完成的结果：

```powershell
cargo check --manifest-path rust_engine/Cargo.toml
cargo test --manifest-path rust_engine/Cargo.toml --lib physics::collision_topology -- --test-threads=1
cargo test --manifest-path rust_engine/Cargo.toml --lib tail_ -- --test-threads=1
cargo test --manifest-path rust_engine/Cargo.toml --lib physics::bullet_ffi::tests -- --test-threads=1
.\gradlew.bat :common:test :fabric:compileJava :neoforge:compileJava
git diff --check
```

新增接触／生命周期测试若未被上述名称过滤器命中，另用准确测试名执行；检查实际测试数量，零项不算通过。Cargo check 只证明 Rust 编译，不验证 Java/JNI 参数顺序。Java 编译也不能证明运行中的原生库契约正确。

Java 与原生库同批构建并按现有发布流程验证产物。核对 JNI 导出符号、Java 方法描述符及三个布尔参数顺序；运行匹配版本的 JVM／游戏路径验证第三参数实际生效，覆盖与前两个参数不同的取值组合。签名变化不能搭配旧 native library。构建锁冲突或环境阻塞如实记录，不删除缓存／锁或终止无关进程。

### 游戏验收与完成边界

选取实际含动态尾巴和严格裙摆、且 PMX 双向掩码允许碰撞的模型，记录资产路径／哈希、动画、相机、帧率和物理配置。替换匹配的 Java／原生产物并重启游戏后，在相同条件下比较开关两态，覆盖静止、行走、跑步和保存后的即时切换；至少包含初始重叠样例。检查尾巴推挤裙摆、抖动、关节过冲和穿模，并确认身体阻挡、长发及两个受力开关保持现状。

核对退出重进、模型重载及全局物理配置重建后选项保留；UI 小窗口滚动、保存、重置和重新打开显示正确。若没有合适资产或游戏环境，报告已通过的代码／测试证据与未完成验收，不伪造样例或将编译、字段检查、静态掩码审计当作视觉通过。

交付说明包含实际改动、测试命令及数量、JNI 配套产物、游戏对照结果和剩余限制。代码检查、真实接触验证和游戏观感分别报告。
