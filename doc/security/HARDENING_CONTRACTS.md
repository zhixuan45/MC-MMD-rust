# MC-MMD-Rust 安全加固契约规范

修订日期：2026-10-17。本契约作为项目安全加固的基准规范，统一部署跨语言调用、资源消耗限额、联机协议交互以及本地文件沙箱的边界要求。后续各阶段的实现与自动化测试必须严格遵循本契约，消除实现者的主观猜测，确保防御逻辑的一致性与向后兼容性。

---

## 1. JNI 方法迁移与跨语言数据契约

跨语言数据交互的基本安全原则是受管复制与锁域生命周期绑定。Java 端禁止直接持有或解引用底层 Rust 堆内存、线程本地存储区（TLS）或静态段的裸指针；所有网格、法线、UV、索引及纹理像素的读取，均改为由 Java 端提供 Direct ByteBuffer，在 Rust 侧安全持有模型实例锁（`Arc<Mutex<MmdModel>>`）的闭包内执行受控边界校验与批量复制。

### 1.1 接口迁移对照

以下梳理所有废弃的裸指针接口及其受管替换方法。替换接口统一以写入元素数或写入字节数作为非负整型返回值，若传入非法句柄或参数则返回负数错误码，严禁静默忽略或返回零值伪称成功。

| 历史废弃符号 | 新受管接口符号 | 参数契约与签名定义 | 调用方覆盖范围 |
| --- | --- | --- | --- |
| `NativeFunc.GetPoss` | `CopyPositions` | `(long model, ByteBuffer dst, int offset, int maxCount) -> int`<br>Rust: `(jlong, JByteBuffer, jint, jint) -> jint` | [OpenGlModelRenderer.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/opengl/OpenGlModelRenderer.java)、[FullModelPreview.java](file:///c:/tmp/mc-mmd-rust/tools/material-texture-probe/FullModelPreview.java) |
| `NativeFunc.GetNormals` | `CopyNormals` | `(long model, ByteBuffer dst, int offset, int maxCount) -> int`<br>Rust: `(jlong, JByteBuffer, jint, jint) -> jint` | `OpenGlModelRenderer.java`、`FullModelPreview.java` |
| `NativeFunc.GetUVs` | `CopyUVs` | `(long model, ByteBuffer dst, int offset, int maxCount) -> int`<br>Rust: `(jlong, JByteBuffer, jint, jint) -> jint` | `OpenGlModelRenderer.java`、[GpuSkinningModelInstance.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/gpu/GpuSkinningModelInstance.java)、`FullModelPreview.java` |
| `NativeFunc.GetIndices` | `CopyIndices` | `(long model, ByteBuffer dst, int offset, int maxCount) -> int`<br>Rust: `(jlong, JByteBuffer, jint, jint) -> jint` | [OpenGlModelFactory.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/opengl/OpenGlModelFactory.java)、`GpuSkinningModelInstance.java`、`FullModelPreview.java` |
| `NativeFunc.GetFirstPersonIndices` | `CopyFirstPersonIndices` | `(long model, ByteBuffer dst, int offset, int maxCount) -> int`<br>Rust: `(jlong, JByteBuffer, jint, jint) -> jint` | `OpenGlModelRenderer.java`、`GpuSkinningModelInstance.java` |
| `NativeFunc.GetTextureData` | `CopyTextureData` | `(long tex, ByteBuffer dst, int offset, int maxBytes) -> int`<br>Rust: `(jlong, JByteBuffer, jint, jint) -> jint` | [TextureGpuLoader.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/texture/runtime/TextureGpuLoader.java)、[NativeTextureBridge.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/bridge/runtime/NativeTextureBridge.java) |
| `NativeFunc.ReadByte` | 彻底移除 | 无替代，历史遗留单字节物理读取接口，生产链路无调用者 | 历史残留声明，在阶段 4 统一删除 |
| `NativeFunc.CopyDataToByteBuffer` | 彻底移除 | 历史通用裸地址拷贝入口，由各具名受管复制方法分别取代 | `OpenGlModelRenderer.java`、`OpenGlModelFactory.java`、`FullModelPreview.java` |
| `NativeFunc.GetMaterialAmbient`<br>`GetMaterialDiffuse`<br>`GetMaterialSpecular` | `CopyMaterialColors` | `(long model, int matIndex, float[] outRgba12) -> int`<br>直接填充 Java 浮点数组，废弃 TLS 静态切片导出 | [NativeRuntimeBridge.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/bridge/runtime/NativeRuntimeBridge.java) |

### 1.2 缓冲校验、对齐与字节序规范

所有接收 Direct ByteBuffer 的本地方法在执行写入前必须严格完成前置核验。首先，DirectBuffer 实例不得为 null，且其底层物理地址必须有效；其次，传入的写入起始偏移 `offset` 与请求元素数 `maxCount` 必须非负，换算为目标字节跨度时必须使用防溢出算术（`checked_mul` 与 `checked_add`）；最后，目标缓冲区的剩余容量 `capacity - offset` 必须大于或等于所需写入字节数，且请求复制的元素数不得超过模型内部当前缓冲的实际元素总量。

在内存对齐与写入操作上，针对浮点坐标、法线和矩阵等数据，严禁直接将目标缓冲首地址强转为具备对齐假设的 Rust 引用（例如 `&mut [f32]`）。当目标地址可能存在未对齐偏移时，底层统一通过 `std::ptr::copy_nonoverlapping` 按字节流复制，或者采用平台中立的非对齐写入指令（`write_unaligned`），杜绝在 ARM64 等精细对齐架构下触发总线异常。Java 端分配的所有传输缓冲均显式配置 `ByteOrder.nativeOrder()`，以保证跨平台浮点与整型数据的机器字节序一致性。

### 1.3 ABI 握手与版本演进契约

为了彻底防范因加载旧版本动态库或跨版本符号残留造成的内存崩溃，模组引入双向 ABI 强校验握手。NativeFunc 导出整数协议版本方法 `GetAbiVersion() -> int`，当前基线初始安全协议版本定为 `2`。
在 Java 客户端与加载器引导阶段，`NativeLibraryLoader` 在完成动态库装载后，必须优先调用 `GetAbiVersion()` 进行强校验。若底层动态库缺失该符号，或者返回的版本号与 Java 端编译常量不一致，引导流程必须立刻抛出 `UnsatisfiedLinkError` 并彻底终止模组生命周期，禁止在版本不匹配的状态下继续派发任何模型或渲染指令。

---

## 2. 资源消耗限额与边界配额契约

为抵御恶意构造的超大资产文件、非预期递归调用以及压缩包炸弹，各数据入口必须确立明确的分配预算。配额设定以工程现有真实资产规模为基准，在保留充足创作裕量的前提下确立硬性防护墙。

| 资产类型与入口点 | 计量维度 | 单项硬性上限 | 单资产累计上限 | 超限处理动作 | 正常基线对照依据 |
| --- | --- | --- | --- | --- | --- |
| PMX 顶点与面解析<br>`loader.rs` / `mmd-rs` | 顶点数 / 面索引数 | 120,000 顶点<br>200,000 面的索引 | 单模型解压占用 ≤ 128 MB | 拒绝载入并抛出带文件名与实际数值的解析异常 | 现有典型模型 10,000～15,000 顶点，120,000 顶点提供近 8 倍裕量 |
| PMX 骨骼与材质骨架<br>`bone.rs` / `material.rs` | 骨骼数 / 材质数 / 刚体数 | 2,048 根骨骼<br>512 个材质<br>2,048 个刚体/关节 | 骨架总元数据 ≤ 16 MB | 截断非法多余段并输出告警，严重畸形拒绝载入 | 现有测试模型骨骼 150～350 根，材质 10～35 个 |
| PMX 字符串与名称<br>`helpers.rs` | 文本字节长度 | 单个字符串 ≤ 1,024 字节 | 全局字符串池 ≤ 2 MB | 拒绝负数尺寸，超限强行截断至 1,024 字节 | PMX 骨骼与材质名通常小于 64 字节 |
| VMD 动作关键帧<br>`vmd_loader.rs` | 轨道与关键帧计数 | 单轨道关键帧 ≤ 65,536<br>全文件关键帧 ≤ 262,144 | 单动作文件 ≤ 16 MB | 遇到截断或超限停止解析并报错，禁止空转循环 | 现有长蓄力弓箭动作含 36,103 关键帧，文件约 4.0 MB |
| FBX 模型与节点树<br>`fbx_parser.rs` | 节点深度 / 属性数组 / 解压体积 | 递归深度 ≤ 64 层<br>节点总数 ≤ 32,768<br>单数组 ≤ 32 MB | 全文件解压累计 ≤ 128 MB | 拒绝继续递归，终止解压并抛出解析错误 | 标准骨骼绑定层级在 8～15 层之间 |
| 纹理图像解码<br>`texture/loader.rs` | 像素分辨率 / 内存体积 | 宽、高均 ≤ 4,096 像素<br>单张未压缩 ≤ 64 MB | 实例并发解码峰值 ≤ 256 MB | 放弃解码并回退至内置缺图粉黑棋盘格，记录警告 | 现有最高清主服装贴图为 2048x2048 RGBA（16 MB） |
| 骨骼层级变换与 IK 求解<br>`bone_set.rs` / `ik_solver.rs` | 树递归深度 / IK 迭代轮数 | 遍历深度 ≤ 64 层<br>单 IK 链迭代 ≤ 64 次 | 拓扑图执行有向无环图环路检测 | 检测到环路直接报错拒绝更新；IK 迭代超限自动钳位至 64 次 | PMX 规范标准迭代通常为 4～10 次，极少超过 40 次 |
| 舞台音频解码<br>`StageAudioPlayer.java` | 解码时长 / PCM 缓冲峰值 | 音频时长 ≤ 15 分钟（900 秒）<br>单文件 PCM 缓冲 ≤ 160 MB | 堆外 DirectBuffer 解码池 ≤ 256 MB | 改为分块受限解码，达到预算上限截断并释放资源 | 典型背景音乐时长 2～5 分钟，44.1kHz 双声道 16bit 约 30～50 MB |
| 客户端资源自动引导<br>`MmdClientResourceBootstrap.java` | 压缩包条目数 / 解压体积 | 单文件 ≤ 50 MB<br>条目数 ≤ 4,096 个 | 累计解压总体积 ≤ 200 MB<br>下载流上限 ≤ 150 MB | 超限立即中止解压，清理当前暂存目录，不覆盖已有文件 | 默认 3d-skin 资源包压缩体积约 20～40 MB |

---

## 3. 联机网络通信与操作码安全契约

网络协议安全的核心要求是全链路身份认证、发送频率控制、载荷长度强制约束以及消除无差别广播引发的流量放大。网络包反序列化统一采用带有显式长度上限的读取方法，禁止任何无参的大容量内存分配。

### 3.1 操作码权限与流向矩阵

| 操作码与枚举名称 | 通信流向 | 身份与权限校验策略 | 载荷长度与类型约束 | 频控阈值与分发范围 |
| --- | --- | --- | --- | --- |
| 1: `CUSTOM_ANIM` | 双向 | 验证发送者已认证，且连接 UUID 与包内 `playerUUID` 完全一致 | 动画标识 UTF 字符串 ≤ 256 字符 | 5 次/秒/玩家；分发至以发送者为中心的视觉追踪范围内其他玩家 |
| 2: `RESET_PHYSICS` | 双向 | 验证发送者已认证，且连接 UUID 与包内 `playerUUID` 一致 | 参数 `arg0` 为 4 字节整数 | 2 次/秒/玩家；局部视觉追踪范围组播 |
| 3: `MODEL_SELECT` | 双向 | 验证发送者与 `playerUUID` 一致，更新服务端状态缓存 | 模型标识 UTF 字符串 ≤ 256 字符 | 2 次/秒/玩家；全服玩家组播通知更新 |
| 4: `MAID_MODEL` | 双向 | 严格校验 `arg0` 对应的实体必须存在，且发送者必须为该实体的合法 Owner UUID | 模型标识 UTF 字符串 ≤ 256 字符 | 2 次/秒/玩家；仅在校验归属权通过后向视觉范围玩家广播 |
| 5: `MAID_ACTION` | 双向 | 同上，严格校验女仆实体的归属权，非 Owner 客户端直接丢弃 | 动作标识 UTF 字符串 ≤ 256 字符 | 5 次/秒/玩家；仅在校验归属权通过后向视觉范围玩家广播 |
| 6: `MORPH_SYNC` | 双向 | 验证发送者与 `playerUUID` 一致 | 表情载荷 UTF 字符串 ≤ 256 字符 | 10 次/秒/玩家；局部视觉追踪范围组播 |
| 10: `REQUEST_ALL_MODELS` | 仅 C2S | 验证发送者已认证；服务端查询全局注册表 | 空或参数 ≤ 64 字符 | 冷却时间 10 秒/玩家；仅对发送者单播回复当前模型列表，严禁全服广播 |
| 11: `STAGE_MULTI` | 双向 | 会话状态机校验；邀请响应必须确认玩家已被房主列入邀请名单，拒绝未受邀自主确认 | Base64 载荷文本 ≤ 32,768 字符；动作文件列表 ≤ 32 个，单文件名 ≤ 256 字符 | 20 次/秒/玩家；邀请协商为定向单播，时间线同步仅限同一舞台会话成员 |
| 12: `BONE_SYNC` | 待隔离 | 校验发送者身份；当前客户端无处理逻辑，暂时停止服务端盲目广播转发 | 二进制载荷限制 `readByteArray(4096)`，严禁无参分配 | 默认禁用服务端盲目中继；待未来动捕模块启用后再开放定向受控同步 |

### 3.2 拒绝服务防护与异步安全

针对网络包的反序列化阶段，所有 `FriendlyByteBuf` 读取均替换为具备安全边界的方法。普通标识字符串统一调用 `buffer.readUtf(256)`，舞台会话的 Base64 聚合载荷统一调用 `buffer.readUtf(32768)`，二进制载荷必须显式指定最大字节容量 `buffer.readByteArray(4096)`。

服务端频控逻辑部署于网络 I/O 线程解码之后、派发到主线程业务之前。采用基于玩家连接粒度的令牌桶算法，对超出频率限制的恶意数据包直接在网络层丢弃并记录限频日志，防止主线程工作队列因堆积昂贵的磁盘扫描或实体反射操作而陷入卡顿。

在车万女仆与特定模组实体兼容处理上，服务端必须通过反射调用其归属权接口（如 `getOwnerUUID()`）。若依赖不存在、反射调用失败、实体不存在或发送玩家 UUID 与实体主人不符，服务端必须立刻终止处理并记录审计信息，严禁未经所有权验证修改任何外部实体的显示模型。

在舞台会话邀请逻辑中，彻底废弃 `computeIfAbsent` 动态自建成员的缺陷逻辑。当服务端接收到 `INVITE_RESPONSE` 且响应为接受时，必须断言该玩家在当前会话的成员表中已经存在，且当前状态严格为 `StageMemberState.INVITED`。对于未曾受邀而擅自发送接受包的客户端，服务端直接拒绝其加入并丢弃数据包。

---

## 4. 文件路径沙箱、配置清洗与资源生命周期契约

文件系统的核心安全目标是确保磁盘操作严格局限在预设的安全沙箱内部，彻底消除路径穿越读取本地敏感文件以及配置键非法注入的可能。同时确立动态库与缓存资源的全生命周期治理规范。

### 4.1 路径解析与沙箱隔离规则

模组所访问的磁盘文件划分为三个明确的根目录作用域：模型根目录 `.minecraft/3d-skin/`、配置根目录 `.minecraft/config/mmdskin/` 以及系统临时隔离区。任何针对相对路径的解析均必须遵守下列沙箱约束：

无论是 PMX 模型引用的贴图与球面纹理，还是通过网络同步的动作（VMD）与表情（VPD）文件路径，均禁止包含绝对路径前缀（如 Windows 盘符 `C:`、网络共享 UNC 路径 `\\` 或 POSIX 根路径 `/`）。路径组件中严禁出现父目录逃逸序列 `..`。

在 Rust 引擎与 Java 业务层中，凡涉及路径拼接操作，拼接完成后必须经过严格的路径规范化校验。对于模型内部纹理，最终解析得到的绝对规范化路径必须以当前模型专属子目录的规范化路径为前缀；若规范化路径逃出模型目录，引擎必须拒绝加载该资源并返回空路径或缺省材质，不得触发任何针对沙箱外部文件的磁盘读取与日志打印。

在资源引导解压模块（`MmdClientResourceBootstrap`）中，验证解压目标路径时必须补全目录分隔符约束。校验规则必须为 `targetFile.toPath().normalize().startsWith(targetDir.toPath().normalize())`，杜绝利用同名前缀目录（如 `3d-skin-attacker`）绕过沙箱检查的 Zip-Slip 漏洞。同时，解压过程必须采用临时目录暂存机制，待全部文件解压并完成安全校验后，以原子事务方式提交至正式目录；若解压中断或校验失败，仅清理当前所属的临时暂存文件，绝不破坏用户原有的 `3d-skin` 资产。

### 4.2 配置键清洗与 Windows 保留名处理

在持久化单模型配置文件（`ModelConfigManager`）以及表情名称处理中，必须对输入的标识字符串进行严格清洗。清洗规则除了将路径分隔符与目录穿越符号替换为下划线外，还必须全面覆盖 Windows 文件系统的特殊限制：

首先，严格过滤半角冒号 `:`，防止外部输入利用 NTFS Alternate Data Streams（交替数据流）机制创建或隐藏非法文件；其次，严格过滤 ASCII 控制字符（0x00 至 0x1F）以及 Windows 非法字符（`*`、`?`、`"`、`<`、`>`、`|`）；最后，针对 Windows 历史保留设备名称（如 `CON`、`PRN`、`AUX`、`NUL`、`COM1`～`COM9`、`LPT1`～`LPT9` 及其附带扩展名的形式，如 `CON.json`），必须增加前缀修饰转换为安全名称（如 `_reserved_CON.json`），确保在所有文件系统上均能安全创建与读写。

### 4.3 动态库落地与安全加载契约

跨平台原生动态库的提取与装载必须保证完整性与隔离性。在 Linux 与 macOS 等 POSIX 兼容系统下，解压创建临时目录时必须通过 `Files.setPosixFilePermissions` 设置严格的单用户访问权限（目录权限配置为 `rwx------`，即 `0700`；动态库文件权限配置为 `r-x------`，即 `0500`），杜绝同机其他非特权进程在动态库落地与装载的时间差内篡改库文件。

在执行 `System.load` 之前，必须在内存中完成文件校验。提取出的临时动态库在落地后，其 SHA-256 哈希值必须与 Jar 包内原始流的哈希值进行双向比对，确保磁盘文件完全未被篡改。动态库提取与加载逻辑必须能够优雅处理多客户端实例并发启动时的文件锁定竞争，并在 JVM 正常退出或异常终止时，由退出钩子完成临时文件的安全释放。

### 4.4 内存缓存与原生句柄生命周期

Java 端的缓存结构必须设置容量上限与明确的淘汰机制。`AnimationLibrary` 中的动画句柄缓存必须从无界的 `ConcurrentHashMap` 重构为带有最大容量限制（如最多容纳 256 个活跃动作）的有界缓存。对于网络中收到的未知动画名称，未命中加载失败记录采用短生存期（TTL 为 5 秒）的有界容量缓存进行保护，防止攻击者利用海量随机字符串造成客户端堆内存泄漏与反复昂贵的磁盘探测。

在原生句柄生命周期方面，所有通过 JNI 创建的原生对象句柄（如模型句柄、动作句柄、纹理句柄），其删除操作必须具备幂等性与线程安全性。Rust 端的全局对象注册表在接收到释放指令时，必须在全局锁保护下原子移出句柄，并依靠 `Arc` 引用计数机制保证正在其他线程执行渲染或计算的在途任务平稳结束；所有 Bullet3 刚体、关节、碰撞形状以及 OpenAL 音频源，均必须在对象析构时完成确定性释放，杜绝跨语言悬垂指针与原生内存泄漏。
