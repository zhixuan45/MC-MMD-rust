# latest.log 源码审计与修复报告

本轮完成已授权修复及自动化验证，真实模型、动作和光影场景尚未验收。完整 SDEF/QDEF 属于独立方案；用户于 2026-10-11 确认“本轮先交付现有修复，方案暂缓”，本轮不实现该算法变更。以下将源码缺陷、日志事实、推断和未知结果分别记录，不能把测试通过理解为原日志全部问题已解决。

## 基线与证据

审计分支为 `feat/1.21.1-neoforge`，基线 commit 为 `69008d200c1b5d5cb32ce8db903dc704aa0ca176`。补丁仍在当前工作区，未提交、未部署。仓库 Minecraft 为 1.21.1、NeoForge 为 21.1.240；日志实例为 1.21.1 / NeoForge 21.1.252，模组版本同为 `1.10alpha-1.21.1`。未自动升级加载器。构建使用 Zulu JDK 21、项目 Gradle wrapper 8.11.1 与本机 Cargo/Bullet。

原始附件与 `latest.log` 只读。附件中的工程假设是待检验项，未直接作为源码根因。原日志 SHA-256 为 `32b56a49e266c4fc2054fcad78a87c0bfc15a4791b070fcd673f194579745da2`；4658 行，INFO=4420、WARN=50、ERROR=17，其中 `[MMD诊断]` 4146 行。抽样最大锚点距离 7.15513、穿透深度 2.73280、单点冲量 2887.64404；这些是日志已有样本的最大值，不是连续窗口峰值，也不能换算成画面像素错误。

审计开始时本地 release DLL 与原日志 L192 的 SHA-256 完全一致：`ec86ef3ceffdb583b3a6110ff8c9dfe2836d1d446c17b7bccfa25d148f7fe32e`。这确认二进制身份，不能证明完整 Jar 或其源码 commit 对应关系。日志最后有正常保存和 `Stopping!`，没有足以确认启动崩溃的证据，也没有最终资源归零证据。

用户确认言和方舟、洛天依方舟、梨花雪 PMX、当时 VMD 和 ComplementaryUnbound_r5.9.3 无可用路径，下载链接也已失效。因此本轮不报告虚构的真实资产 A/B、2/10/30 秒改善数据、截图、双客户端接收或进程内存趋势。

## 逐项状态

| 任务 | 本轮完成状态 | 根因判定 | 尚未完成的验收 |
| --- | --- | --- | --- |
| T1 / P0 自动裙环 | 连接筛选、拓扑分组、坐标回归和取证开关已实现 | confirmed：横向边影响纵向链、静态归属不同仍混组、无效坐标排序 panic；限定构造姿态的手性混用假设未获支持 | 具体 PMX 的组件归属、跨连消除及长尾物理/画面改善 |
| T2 / P1 碰撞与速度 | 指标语义、阶段和扫描摘要已补齐 | confirmed：旧诊断上下文不足；实际高冲量/高穿透根因 inconclusive | 同一资产逐帧阶段、持续时间与真实穿模 |
| T3 / P1 纹理 | 路径/类型/实例上下文、缺失预加载和既有回退兼容已修复 | confirmed：失败错误缺少路径；12 条具体失败文件 inconclusive | 实际解析路径与真实贴图/画面 |
| T4 / P1 生命周期 | 析构阶段记录及异常纹理清理已修复；原生 20 轮验证通过 | confirmed：旧计数在字段析构前；纹理异常路径可漏资源；原游戏最终泄漏 inconclusive | JVM/JNI 跨语言调用、游戏切换/退出及内存趋势 |
| T5 / P1 Toon | 不支持或编译失败时仅当前 pass 回到标准路径 | confirmed：旧路径会把 vanilla 四输出 Toon 用于未知 MRT 契约 | 实际 shaderpack 的 FBO/GL 状态和三组合截图 |
| T6 / P2 SDEF/QDEF | CPU/GPU 调用链与降级事实已确认；基线测试、独立方案已交付 | confirmed：SDEF→BDEF2、QDEF→BDEF4；不满足用户的完整支持目标 | 用户暂缓方案，完整 CPU/GPU 算法及参考/画面验证未实施 |
| T7 / P2 舞台 | 保留邀请式会话；修复上下文和本地起止配对 | confirmed：NONE 不能代表离线；会话先清理会影响结束参数 | 双客户端接收、重连与断线回收 |
| T8 / P3 可观测性 | 摘要、异常指标、完整快照限频和分析脚本已实现 | confirmed：重复明细可收敛；“日志导致卡顿”仍 inconclusive | 新游戏日志输出量、采样耗时和帧耗时实测 |

### T1：自动裙环与锚点

原日志 L908、L1827、L2226、L3428、L4048 等打印了发束/系带/裙摆之间的合成关节和较大锚点距离，这是日志事实。仅凭名字不能断定模型作者的真实部位归属。

源码入口为 [physics.rs](C:/tmp/mc-mmd-rust/rust_engine/src/model/runtime/physics.rs:61) → [skirt_cross_joints.rs](C:/tmp/mc-mmd-rust/rust_engine/src/physics/skirt_cross_joints.rs:45) → [skirt_cross_topology.rs](C:/tmp/mc-mmd-rust/rust_engine/src/physics/skirt_cross_topology.rs:21) → [mmd_joint.rs](C:/tmp/mc-mmd-rust/rust_engine/src/physics/mmd_joint.rs:54)。旧实现从候选刚体中的既有关节直接取链关系，横向关节也可能进入链；不同静态锚点所属链没有分开，非有限位置会使排序 `unwrap` panic。最初四项复现中三项失败，另一项等长链反向 A/B 测试原本通过，不能把该项描述为旧缺陷。

修复按骨骼祖先关系确定纵向方向，PMX 关节 A/B 视为无向；没有可用骨架时只接受明显沿 Y 的纵向边。共享部位分类、骨骼祖先筛选及 owner/family 或静态锚点集合共同分组，原始 PMX 关节保持不变。分支、多父级、环、无效位置/旋转保守跳过，不任取一个分支；排序使用 `total_cmp`，接近竖直的弦轴选择另一个参考轴保持正交。最后复核还加入 cape/cloak 合法链兼容和同 pelvis 的内外裙语义段分组回归，避免修复本身减少原有服装支持或跨层缝合。

锚点生产路径使用 PMX 左手模型局部姿态，frame 为 `initial_transform.inverse() * joint_transform`；左右手转换在骨骼 offset 边界，外部显示 root scale 不进入 Bullet frame。生产 `MmdJointData::from_pmx` / Bullet body 测试覆盖倾斜弦、共同大旋转/平移、独立移动造成的可预测分离、非均匀显示缩放和真实刚体 offset 的 `inv_z` 往返。限定这些场景，没有复现手性或外部缩放混用；不能推广为所有动画状态都不会出现锚点误差。初始化分别按各自骨骼移动刚体后，锚点确实可能分离，不能把共锚绑定姿态当作求解稳定性保证。

开启既有物理 debug 后，`SKIRT_CROSS_TOPOLOGY/GROUP` 给出分组；`SKIRT_CROSS_CREATED` 给出绑定姿态阶段、刚体 ID/名字/骨骼、mode、0-based 组和显示组、原始 mask、位置、local/world anchor 与误差。该层没有模型 handle/材质所有权，未伪造材质索引；后续 issue snapshot 附 handle，材质候选仍是有限扫描证据。`INIT_ANCHOR_PRE_STEP` 是既有初始化运行姿态诊断，两者应结合使用，不能按日志邻近行假定多个模型的严格关联。

`MMD_SKIRT_CROSS_SYNTHESIS=off` 仅跳过自动生成裙环，供 A/B 实验；原始 PMX 关节、碰撞、重力和动画继续运行。默认启用。归属仍含名称启发式；无骨骼且无静态锚点的同 family 链仍可能难以区分，未知命名、共享锚点和分支型合法衣物也可能被保守遗漏。保留文本语义段会把 `Skirt_A/B/C` 视为不同 family，可能漏掉合法列环；本轮不为了这些名字强行合并内外层。倾斜弦测试改用明确表示同族列编号的 `Skirt_0/1/2`，位置和生产 frame 数学断言不变。真实资产验收未完成，不把日志具体模型的连接错误判为已修复。

测试文件：[拓扑测试](C:/tmp/mc-mmd-rust/rust_engine/src/physics/skirt_cross_joints_topology_tests.rs)、[骨架测试](C:/tmp/mc-mmd-rust/rust_engine/src/physics/skirt_cross_joints_bones_tests.rs)、[生产锚点测试](C:/tmp/mc-mmd-rust/rust_engine/src/physics/skirt_cross_joint_anchor_tests.rs)，并保留既有 `skirt_cross_joints_tests.rs`。

### T2：接触、速度和误差的含义

L565 的 penetration=2.73280/impulse=0、L2589 的 impulse=2887.64404、L3288/L3294 的线速度约 50 都是瞬时样本。静态/运动学对象、初始化重叠、求解及限速阶段、质量、有效碰撞组和限制允许位移均会影响解释；原日志不能独立选出唯一根因。

[碰撞诊断](C:/tmp/mc-mmd-rust/rust_engine/src/physics/mmd_physics/issue_diagnostics.rs:29) 现在记录 live 速度限幅与 `clamp_phase=after_step sample_phase=instantaneous_read`，接触两端 mode/mass/group/mask、空间及 `impulse_kind=max_point_applied_impulse`。`world_anchor_separation` 与实际 `linear_limit_violation` 分开；[运行时诊断](C:/tmp/mc-mmd-rust/rust_engine/src/model/runtime/issue_diagnostics.rs:149) 在最多 8192 个已扫描刚体中汇总动态体速度峰值与非有限值，避免逐体明细截断藏住最快刚体，并保留截断标记。

新增诊断字段回归由 Rust 库测试覆盖。未调整重力、质量、限速或碰撞 mask，也未用统一关闭碰撞作为修复。未取得连续阶段数据，真实异常与峰值持续时间均为 inconclusive。

### T3：纹理路径与回退

六次加载每次两条 `os error 2` 是事实；旧 [loader.rs](C:/tmp/mc-mmd-rust/rust_engine/src/texture/loader.rs:12) 仅保留 image 错误而丢掉路径，是 confirmed 的可观测性缺陷。预加载 `lightMap.png` 后工厂再次尝试这一可选资源的路径与每次两条错误相符，但只能标为推断；无法恢复原日志 12 条错误的具体文件。L423–425 的空资源路径错误也不能直接与它们等同。

Rust 错误附清理并截断到 240 字符的输入路径；Java 传递 diffuse/light-map 和 handle/材质/阶段上下文，最多 32 条不同警告，`decode_failed` 不被写成一律“文件缺失”。[TextureRepository](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/texture/runtime/TextureRepository.java) 对不存在的预加载路径跳过解码，不做负缓存，运行时仍可重试。非空 diffuse 缺图保持既有 tex=0；空 diffuse 的材质颜色纹理、light-map 白色 16×16 回退保留。

修改还包括 [ModelRuntimeAccessPort](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/model/port/ModelRuntimeAccessPort.java)、[DefaultModelRuntimeAccessPort](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/model/runtime/DefaultModelRuntimeAccessPort.java)、[ModelLoadCoordinator](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/model/runtime/loading/ModelLoadCoordinator.java)、[MaterialTextureLoader](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/material/MaterialTextureLoader.java)、[GpuSkinningModelInstance](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/gpu/GpuSkinningModelInstance.java)、[OpenGlModelFactory](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/opengl/OpenGlModelFactory.java)。端口采用 default overload，原方法兼容。

Rust 缺图路径测试和 [TextureRepositoryTest](C:/tmp/mc-mmd-rust/common/src/test/java/com/shiroha/mmdskin/texture/runtime/TextureRepositoryTest.java) 验证缺失可选文件的预加载解码 0 次、运行时尝试 1 次。路径能否准确定位原有错误和画面回退，仍需实际实例日志。

### T4：生命周期与异常清理

旧 `MMDPhysics::drop` 在字段真正析构前打印进程全局计数，L2662、L4267–4269 的 5→4→3 不是“该实例已全部释放后”计数，更不是最终净增量。由这几行直接推导泄漏的论证不成立；原游戏是否最终有泄漏仍未知。

[MMDPhysics::drop](C:/tmp/mc-mmd-rust/rust_engine/src/physics/mmd_physics.rs:896) 按从 world 移除约束/刚体、清空约束、清空刚体/形状、销毁 world 的顺序显式清理；[BulletWorld::destroy](C:/tmp/mc-mmd-rust/rust_engine/src/physics/bullet_ffi.rs:387) 为 physics 内部幂等方法，置空后 Drop 不重复释放。仅 debug 开启打印 before_clear/before_world_drop/after_world_drop，注明 global 和 other_worlds_possible。此次没有确认原析构顺序存在 use-after-free。

[resource_lifecycle_tests.rs](C:/tmp/mc-mmd-rust/rust_engine/src/jni_bridge/resource_lifecycle_tests.rs:113) 单独串行运行 20 轮。每轮注册带 2 body、1 constraint 的模型，保留在途 Arc，调用现有 native DeleteModel 删除句柄，再重复删除；Arc 仍持有时对象不提前释放，最后 Arc 释放后 handles/world/shape/body/constraint/motion-state 回到基线。实测基线全 0，每轮净增全 0，峰值净增为 1 world、2 shapes、2 bodies、1 constraint、2 motion-states。该用例直接调用 native 导出，不创建 JVM，不覆盖 Java→JNI 参数转换、完整模型文件加载和 Minecraft 退出。

另确认 [TextureGpuLoader](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/texture/runtime/TextureGpuLoader.java:46) 的异常清理缺陷：取得 native handle 后 metadata/分配/GL 创建原在 finally 外；decode 的像素复制失败会漏临时 buffer；predecoded 上传的 GL 创建异常会跳过预解码释放。先写 metadata 故障测试，旧代码 1/1 失败；改为嵌套 finally 后两项测试通过，覆盖 metadata 失败释放 handle、copy 失败 buffer/handle 各释放一次。

最后复核又用 mock `deleteTexture` 抛异常复现 copy 成功后 buffer 尚未交给 caller 就失去清理（释放计数 0，期望 1）。修复后原生清理失败时释放未交付的 buffer；GPU 上传已成功但后续清理失败时也删除尚未登记的 GL texture。已有主异常继续传播，后续清理错误作为 suppressed 保留；最终定向 3 项通过。这个测试证明异常路径缺陷，不证明生产 native 删除实际曾抛过异常。GL 创建/上传/清理异常路径只做控制流检查，未做真实 GL 故障注入；释放函数本身失败仍可能无法回收底层资源，不能承诺任意失败均归零。测试见 [TextureGpuLoaderTest](C:/tmp/mc-mmd-rust/common/src/test/java/com/shiroha/mmdskin/texture/runtime/TextureGpuLoaderTest.java)。

### T5：Toon 与未知 DRAWBUFFERS

L420–422 记录未知/无效实体 pass 布局后仍选择 Toon，是事实。源码确认 [IrisToonCompat.prepare](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisToonCompat.java:24) 原来对 unsupported profile 选择 vanilla 四输出 Toon，这个变体不等于当前 Iris 标准实体程序，存在未知 MRT 契约风险。日志没有实际 draw-buffer 状态或像素，不能断言发生附件越界或黑块。

修复仅 unsupported profile 或兼容 Toon 编译失败时返回 false，由 CPU/GPU 既有标准绘制路径应用当前 shader；长期 Toon 配置保持原值。提示按包/程序/原因限次，记录 `action=current_pass_standard`。未直接关闭整个渲染系统或永久关闭 Toon。[IrisToonCompatTest](C:/tmp/mc-mmd-rust/common/src/test/java/com/shiroha/mmdskin/compat/iris/IrisToonCompatTest.java) 三项验证不支持时不调用 selector、编译失败不尝试 legacy fallback、去重键区分上下文；它们不建立 GL 上下文。为让测试被 Git 跟踪，`.gitignore` 只增加该测试路径的精确例外。

实际 Complementary 5.9.3 / NeOculus 1.8.7 画面、透明 pass、FBO、GL error 和状态恢复仍待验证。

### T6：完整 PMX 蒙皮方案暂缓

PMX parser 保留 SDEF 的 C/R0/R1 与 QDEF 权重。运行时 CPU 主路径忽略 SDEF 特殊参数，QDEF 做矩阵线性混合；GPU payload 只给四索引/权重，实际 Compute shader 同样线性混合。另一简化 SDEF helper 不是游戏主路径。降级是 confirmed 的实现事实，完整支持目标下是功能缺口；日志模型 QDEF 全为 0，不能宣称实际 QDEF 画面失败。

[gpu_sdef_payload.rs](C:/tmp/mc-mmd-rust/rust_engine/tests/gpu_sdef_payload.rs) 一项测试固定当前 SDEF→BDEF2 payload，并检查既有 BDEF 索引/权重布局；不是完整 SDEF/QDEF 或 GPU dispatch 验收。生产蒙皮代码本轮未修改。独立 [implementation_plan.md](C:/tmp/mc-mmd-rust/implementation_plan.md:297) 包含 CPU helper、GPU kind/sidecar、加法 JNI、dual quaternion、SSBO 能力与生命周期、参考算法核定、CPU/GPU readback 及真实模型验收。用户明确本轮暂缓，后续须批准后实施。

### T7：保留邀请式舞台会话

日志 L2810/L4049 的缺少 session 警告不证明动作没播放或时长为 38 秒。`StageRole.NONE` 可能是远端服务器上的本地独奏；服务端只接受已注册邀请式 session，不能凭客户端生成一个 UUID 伪装有效会话。

[StageNetworkPlaybackBroadcastAdapter](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/stage/client/network/StageNetworkPlaybackBroadcastAdapter.java) 按用户决定保留邀请协议：单机未公开 LAN 的 NONE 静默跳过；远端服务器/公开 LAN 未入会话限次 WARN，带 `context=multiplayer_no_stage_session reason=invitation_session_required action=skip_invalid_broadcast`。有效 start 缓存已公告 ID；本地 session 清理后 stop 使用同一 ID，重复 stop 无重复发送，切换 ID 先 stop 旧会话再 start 新会话。

[StageNetworkPlaybackBroadcastAdapterTest](C:/tmp/mc-mmd-rust/common/src/test/java/com/shiroha/mmdskin/stage/client/network/StageNetworkPlaybackBroadcastAdapterTest.java) 五项验证本地 NONE、多人 NONE、host 清理后的 stop、member、切换配对。sender 返回 void，测试证明调用参数与本地幂等，不证明远端收到；断线后服务器可能已解散 session，不能承诺迟到 stop 有效。

### T8：诊断输出和外部警告

[ModelIssueDiagnostics](C:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/debug/client/ModelIssueDiagnostics.java:55) 保持开关默认关闭；开启时每 2 秒保留摘要、接触、最差锚点、速度指标、截断标记和未知类别，已知逐体/候选等明细首次、每 30 秒或手动请求完整输出。配置/handle 改变会重建首份完整快照；`requestSnapshot()` 或切换日志开关请求快照；`-Dmmdskin.diagnostics.full=true` 恢复每 2 秒完整明细。省略数量显式记录。原生仍每 2 秒构造快照，所以本轮只证明减少明细输出，未证明采样 CPU 成本或帧耗时下降。

[ModelIssueDiagnosticsTest](C:/tmp/mc-mmd-rust/common/src/test/java/com/shiroha/mmdskin/debug/client/ModelIssueDiagnosticsTest.java) 覆盖默认关闭不调 JNI、缺接口不打断绘制、摘要保留关键及新类型、手动/30 秒/handle 切换完整快照和 full 模式兼容。具体使用见 [指南](C:/tmp/mc-mmd-rust/ISSUE_DIAGNOSTICS_GUIDE.md)。

[analyze-log.ps1](C:/tmp/mc-mmd-rust/tools/latest-log-audit/analyze-log.ps1) 实际读取原日志生成 anchor/contact/world/texture CSV 和 JSON，保留原行号、原时间文本、handle 及源 hash，未猜时区或补造纹理路径。[README](C:/tmp/mc-mmd-rust/tools/latest-log-audit/README.md) 提供复现命令、A/B 和后续游戏矩阵。Embeddium taint、NeOculus uniform/方块映射及 Realms 网络警告来自其他组件，本轮不将其归因为 Bullet 或 Rust 纹理 I/O 缺陷，也不修改这些外部组件。

## 验证记录与可复现命令

证据目录为 [Logs/latest-log-audit](C:/tmp/mc-mmd-rust/Logs/latest-log-audit)，属于忽略的本地记录，第三方模型/纹理未提交。关键 before/after 是实际测试输出，未把原游戏日志和新合成测试伪装成同一游戏场景的对照。

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 初始 P0 复现 | 4 项，3 失败 / 1 通过 | `p0-before.txt` |
| 最终裙环/生产锚点定向回归 | 17 项通过；末轮补测前 cape/cloak 与文本内外层两项失败 | `p0-review-red.txt` / `p0-review-green.txt` |
| 最终 Rust 库测试 | 182 项通过、1 项生命周期专用用例忽略并另行通过 | `rust-lib.txt` |
| 纹理异常故障注入 | before 1 失败；after 2 通过 | `texture_gpu_red.log` / `texture_gpu_green.log` |
| 原生清理异常故障注入 | before 3 项中 1 失败；after 3 项通过 | `texture_cleanup_red.log` / `texture_cleanup_green.log` |
| 最终 Java common 测试 | 100 项通过，0 failure/error/skipped | `gradle-final.txt` 及 `common/build/test-results/test/TEST-*.xml` |
| Fabric/NeoForge Java 编译 | 均通过，JDK 21 | `gradle-final.txt` |
| 原生句柄/Bullet 20 轮 | 通过，每轮全部计数回到 0 基线；在途 Arc 与重复删除通过 | `lifecycle-20.txt` |
| GPU SDEF payload 基线 | 1 项通过，只记录既有近似 | `gpu-sdef-baseline.txt` |

所有本轮触及的 Rust/Java 源码与测试文件均小于 1000 行；未修改已有 3705 行的 `native_func.rs`，生命周期测试另放专用模块。未变更公共用户 API 或跨分支 ABI；裙环入口只在 crate 内接入骨架信息。`git diff --check` 通过；CRLF 提示不是测试错误。

自动化与手动矩阵的完整命令见 README。生命周期必须单独 `--ignored --test-threads=1` 运行，以免其他物理测试争用全局计数；其余 Rust 库测试也用串行验证。Gradle 使用原有缓存，沙箱共享缓存锁访问拒绝后经授权执行，未删除锁、重建缓存或终止其他构建。

最终 `cargo build --offline --release --manifest-path rust_engine/Cargo.toml` 通过；`:common:test :fabric:compileJava :neoforge:compileJava :fabric:remapJar :neoforge:remapJar --offline --console=plain` 通过。release 仅有 Windows 链接器输出导入库的信息提示，没有编译失败；Gradle 的 Loom 版本提示为既有环境告警，未自动升级。

| 本地产物 | SHA-256 |
| --- | --- |
| [Windows x64 DLL](C:/tmp/mc-mmd-rust/rust_engine/target/release/mmd_engine.dll) | `29d25e03c5f25fd8af7f1e6cbdd19509bcec216f4bef1d1192b2cdb3353722a9` |
| [NeoForge 1.21.1 Jar](C:/tmp/mc-mmd-rust/neoforge/build/libs/mmdskin-neoforge-1.10alpha-1.21.1.jar) | `d7f6f11e89294e89919624cd4ba051114fc125233182b4c0850c3e1a1ec45d49` |
| [Fabric 1.21.1 Jar](C:/tmp/mc-mmd-rust/fabric/build/libs/mmdskin-fabric-1.10alpha-1.21.1.jar) | `43bc83c219d483a043c4b0c983d76f66a52f145b8e10a82b50a612d5834114ae` |

两个 Jar 内的 `natives/windows-x64/mmd_engine.dll` 均实际读取并核验与最终 DLL hash 相同，且包含本轮纹理、诊断、舞台和 Iris 类。详情见 [build-manifest.json](C:/tmp/mc-mmd-rust/Logs/latest-log-audit/build-manifest.json)。这两个本机构建只包含 Windows x64 native，未构建或验收其它操作系统。未安装到游戏实例、发布、提交或合并；Jar 同版本号，使用时须以本报告 hash 区分旧包。

## 剩余交付边界

本轮提交的是可评审源码补丁、自动化证据、结构化诊断及本地构建。尚未完成 R01–R08 的真实资产/游戏矩阵，不把它们标为通过。优先补同资产裙环 A/B、碰撞阶段连续数据和 20 次游戏切换/退出；再补三渲染组合与双客户端邀请会话。没有这些证据，T1–T5/T7 的游戏根因和改善幅度仍有不确定性。T6 按用户决定暂缓，后续经审批实施，不能用当前 baseline 测试替代完整支持。
