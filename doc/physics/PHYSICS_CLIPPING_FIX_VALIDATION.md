# 穿模代码修复与验证（2026-10-04）

已修复 PMX 碰撞掩码反读、恢复帧遗漏物理骨骼回写，以及远端动画 LOD 反复关闭/开启物理的问题。原有未提交修改保留；本次没有改写蝶律、Oguri 的 PMX、游戏配置或默认动作。下面分别记录代码回归、实际资产离线对照和仍需游戏验收的内容。

## 改动与回归

`rust_engine/src/physics/mmd_rigid_body.rs` 直接保留 PMX 原始允许掩码，`garment_contacts.rs` 使用同一语义建立裙摆兼容计划。原值 0 全禁碰，FFFF 全允许；BFFF 禁止内部组14，0038 允许内部组3/4/5。编辑器“不冲突群组”勾选对应原始位0。合成身体碰撞体从旧的0改成FFFF，保留原先的全允许意图。审计器和运行探针同步纠正计算和字段标签，网格探针移除调查期间仅用于中和旧代码的内存翻转参数。

回归读取真实 Bullet 接触，验证蝶律 CBE0 的下半身与 BFFF 的裙片可以碰撞，而两个 BFFF 的组14裙片不能互碰；另测两个0038的组3刚体可以互碰。Stable 兼容仅补目标身体与裙摆的双向位，新增位意外接通的袖/胫骨配对仍被精确过滤；Strict 保留原始掩码，全局关闭碰撞输出0。人体壳的 pelvis/thigh 缩放资格旁路保留：它只做尺寸分类，不是 Bullet 的碰撞掩码规则。

`rust_engine/src/model/runtime/physics.rs` 将物理骨骼回写提为共同步骤，跳帧、非有限时间、零/负步长和显式重同步分支均在返回前回写。非有限时间和零步求值不会吞掉待处理的显式重同步；零/负步长保留速度，下一正常帧仍继续运动。大时间步继续保留动态求解姿态和关节结构，不重新摆放动态链；诊断日志带 `physical_bones_written=true`。独立 `physics_tests.rs` 覆盖骨骼和最终蒙皮矩阵，包括模式1与模式2。

`common/.../render/policy/WorldRenderPolicy.java` 将物理启用与动画 shouldUpdate 解耦。动画 LOD 跳帧不再制造 false→true 的物理生命周期切换，实际物理预算仍可单独关闭物理。Java 回归覆盖跳帧序列与预算关闭。

原超过1000行的 `body_collider_synthesis_tests.rs` 已机械拆分出 `body_collider_synthesis_probe_tests.rs`，保留测试行为；本次涉及的代码文件均低于1000行。项目索引已补充掩码语义、回写回归与资产交接文档。

## 已完成验证

| 验证 | 结果 |
| --- | --- |
| Rust：`cargo test --offline --lib -- --test-threads=1` | 307通过，0失败 |
| Java：`:common:test --tests com.shiroha.mmdskin.render.policy.WorldRenderPolicyTest --offline` | 2通过，0失败；common源码与测试编译成功 |
| 四个诊断 examples 离线编译 | 成功 |
| Release 原生 DLL：`cargo build --release --offline --lib` | 成功 |
| NeoForge：`:neoforge:remapJar --offline` | 成功 |
| Jar内 DLL 与新 Release DLL 的 SHA-256 | 一致 |
| 两个原始 PMX 的 SHA-256 | 与修复前一致 |
| `git diff --check` | 通过 |

Java 构建使用进程内 Zulu JDK21 与原有共享 Gradle 缓存；未修改全局 Java 或缓存设置。日志中仍有既有 MSVC 库输出、审计 example 的未使用项提示，以及 Java 测试编译时 Fabric EnvType 类缺失提示，均不阻碍上述构建与测试。

## 实际资产对照

回弹探针对绑定姿态预热180帧，再输入一次100毫秒时间步，随后恢复1/60秒。输入模型文件均未修改。

| 模型 | 修复前回到绑定位置的动态关联骨骼 | 修复后 | 修复后最大显示跳变 | 求解器关节锚点变化 |
| --- | ---: | ---: | ---: | ---: |
| 蝶律（406动态关联骨骼） | 371 | 0 | 0 | 0 |
| Oguri（57动态关联骨骼） | 36 | 0 | 0 | 0 |

裙腿网格检测使用60 Hz渲染与物理、预热2秒、后3秒取45个样本。修复后蝶律 sprint、身体倍率1.0：Stable 相交候选均值360.222、最大947；Strict 均值272.756、最大619。修复前同配置 Stable 均值936.867，修复后下降约61.6%。这是三角形几何相交候选，不是可见穿模百分比；未逐像素排除透明贴图，也受骨权重部位分类影响。

Oguri walk、倍率0.8、Stable 修复后均值604.533、最大996；45个采样帧仍有候选交叉。其资产的原始裙腿掩码仍是双向禁碰，Stable 仅补骨盆/大腿，不能代替资产修复。不能以代码回归通过声称此模型已经无穿模；请按 [Oguri资产标准](OGURI_PHYSICS_ASSET_STANDARD.md) 在模型工程中制作候选并验收。

机器对照 `OGURI_PHYSICS_MASK_REFERENCE.json` 保存全部84个刚体。枚举3486对双向掩码资格，最小候选恰好新增100对裙腿与5对尾部阻挡，共31个刚体的单个位变更；其他配对资格不变。该候选尚未写入 PMX，也未进行游戏画面验收。

## 修复构建与证据

修复 Jar 快照：`build/physics-fix-20261004/mmdskin-neoforge-1.10alpha-1.21.1-physics-fix.jar`。它来自当前完整工作区，包含工作区已有修改；没有安装到 D 实例或替换运行中的游戏。`neoforge/build/libs/` 同时有正常命名的 remap Jar；旧 sources Jar 不是本轮重新生成的源代码包。

Jar SHA-256：`9BE7B460830A098C9C5D43712E1E2E442A9D8C3BB481C3B95D7AE3CBA9C3ACB1`。

Jar内及 Release DLL SHA-256：`A0149F74E6D72015D04BAE67A2A8CDF290D50BD106F5884CEEF87A42190F4358`。

原始日志与网格快照保存于 `.tmp/physics-fix-20261004/`：`cargo-test.log`、`java-lod-test.xml`、`cargo-release.log`、`gradle-neoforge.log`、`gap-dielv.txt`、`gap-oguri.txt`、`dielv-sprint-*.txt/json`、`oguri-walk-stable.txt/json`、修正标签后的 `audit-oguri.txt`，以及资产/构建摘要。此前 `.tmp/physics-diagnosis-20261004/` 与诊断报告属于修复前调查记录，不覆盖。

## 验收边界与同组碰撞

用户新截图中 Tokai Teio 的刚体在编辑器第4组，但第4组未勾“不冲突”，意味着它可以与同组其他刚体接触。一个刚体不会与自己碰撞。若多个发束的碰撞壳重叠又被关节约束，组内接触可能互相挤压并引起抖动或弹开；同组接触也可能是作者有意的设计，不能一律认定错误。本引擎创建关节时禁用直接相连刚体互碰，Stable再增加拓扑过滤，但跨发束/跨链并不一定被屏蔽。Oguri标准列出按实测决定的同组抑制候选，区分内部零基编号与编辑器显示编号；不自动修改资产同组位。

尚未获得原舞台包或远端同帧日志，因此“约两分钟为什么触发一次大时间步”的来源仍未知。本次证明并修复了100毫秒间隔引发显示回弹的路径，不能据此把GC、自动保存或其他定时事件定为现场来源。新构建还需游戏中长时间舞台、静止、walk/sprint 与跨发束接触的画面对照。
