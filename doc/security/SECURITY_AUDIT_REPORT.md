# MC-MMD-Rust 代码安全审计与健壮性审查报告

本报告汇集了多个审查智能体对项目在原生计算引擎、多端网络通信、跨语言调用接口以及资产文件加载等维度的系统性排查结果。审查目的在于发现代码库中存在的内存安全缺陷、网络拒绝服务隐患、路径解析边界不足以及依赖管理风险，为后续架构重构与加固提供明确的技术依据。

---

## 1. 原生引擎与底层内存安全审查

代码库最底层的计算核心位于 `rust_engine`，承担着模型解析、骨骼解算、布料模拟与 JNI 零拷贝传输等关键任务。排查表明，尽管 Rust 语言本身具备强内存安全保证，但在跨语言 FFI 边界、裸指针解引用以及不可信外部资产解析方面，依然存在多处引发崩溃或未定义行为的隐患。

### 依赖库与外部 C++ 组件评估
项目通过 [build.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/build.rs) 静态编译了 Bullet3 物理引擎（commit 63c4d67e）。经比对，C++ 侧仅参与构建了 LinearMath、BulletCollision 与 BulletDynamics 三个纯数学与求解子集，未编译包含历史漏洞的外部文件解析工具（如 CVE-2025-8854 涉及的 VHACD 模块），且模型加载均由 Rust 自研模块处理，Bullet3 层面无直接漏洞暴露。

但在图像与模型资产解析组件中存在资源消耗无界的风险。在 [fbx_parser.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/src/animation/fbx_parser.rs) 中，解析器读取不可信流中的解压尺寸声明后，未进行阈值上限约束便直接预分配内存并调用 ZlibDecoder 进行解压。当遇到恶意膨胀的解压数据时，会导致内存瞬间耗尽。类似地，[loader.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/src/texture/loader.rs) 中加载外部贴图时直接调用了 image 库的解码函数，未通过 Limits 显式设定图片宽高上限与最大分配阈值，在多模型同时载入或极端尺寸图片输入时容易造成内存抖动。此外，项目配置中存在旧版 wgpu 0.17 与新版 22.1 并存的情况，引入了重复依赖与过时代码分支，后续应统一提升至主流稳定版本。

### JNI 接口与裸指针内存安全
跨语言桥接集中在 [native_func.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/src/jni_bridge/native_func.rs)，共导出了 159 个 JNI 方法。排查发现此处是内存隐患的高发区：

第一，直接物理内存读取与越界解引用。接口 `Java_com_shiroha_mmdskin_NativeFunc_ReadByte` 以及 `CopyDataToByteBuffer` 接收 Java 传入的长整型地址与偏移，函数内部直接将其强转为原生指针进行解引用读取。此类裸指针接口使 Java 侧只要传入非法地址或溢出偏移，便能直接触发操作系统的段错误或访问违规异常，造成游戏进程闪退。

第二，直接内存缓冲复制时的源长度校验缺失。在 `CopyBoneIndicesToBuffer`、`CopyOriginalPositionsToBuffer` 等批量拷贝函数中，代码仅校验了写入目标缓冲区的容量是否充足，但完全忽略了校验传入的顶点数量是否超出了模型自身的实际顶点数。更严重的是，传入的顶点数为有符号 32 位整数，如果传入负数或超过模型容量的数值，强转为无符号整型后将导致底层拷贝函数直接从 Rust 堆中越界读取相邻内存并填充至 Java 缓冲区，构成堆内存越界读取与信息泄漏隐患。

第三，直接内存指针非对齐访问。在 `GetCameraTransform` 与 `CopyBonePositionsToBuffer` 等方法中，代码将 Java 传入的堆外缓冲指针直接强转为浮点指针或切片。由于 Java 端基于切片偏移生成的 Direct ByteBuffer 并不保证严格满足 4 字节或 8 字节对齐，在 ARM64 架构（如移动端容器与苹果芯片）下，此类未对齐解引用会触发底层硬件总线错误。

第四，暴露裸指针导致悬垂访问风险。接口 `GetPositions`、`GetNormals` 等方法在临时获取读锁后，直接将内部向量的裸指针返回给 Java 侧，随后释放锁与引用计数。若 Java 端另一线程并发执行销毁模型或模型内部触发重分配，Java 保存的指针将立即沦为悬垂指针，后续读写便会触发释放后使用问题。

第五，跨 FFI 边界的未捕获异常与锁中毒。整个 JNI 导出层存在 260 余处 unwrap 调用，且完全未配置 `std::panic::catch_unwind` 异常捕获机制。一旦底层物理计算或锁操作发生异常，未捕获的 panic 穿透 C-ABI 边界将直接导致整个进程被操作系统强制终止，且中毒的互斥锁会造成后续 JNI 调用全面连锁瘫痪。

### 资产解析鲁棒性与计算防死锁
在骨骼与动画算法实现中，同样存在数处逻辑漏洞：
在 [bone_set.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/src/skeleton/bone_set.rs) 与 [ik_solver.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/src/physics/ik_solver.rs) 的层级变换递归计算中，未对骨骼父子拓扑进行有向无环图校验。若模型文件中故意构造了互相指向为父节点的循环骨骼，递归遍历将陷入死循环并瞬间耗尽线程栈，触发不可恢复的栈溢出崩溃。

在 [helpers.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/deps/mmd-rs/src/pmx/reader/helpers.rs) 及子模块读取 PMX 计数时，直接将文件读取出的数值作为容器预分配容量，未核验其是否为负数或超出文件真实剩余体积，极易被利用伪造畸形计数导致内存超额申请；在 [vmd_loader.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/src/animation/vmd_loader.rs) 中，解析循环吞掉了截断错误，在文件提前结束时仍会空转数十亿次；而在 IK 求解器中，由于未对模型声明的迭代轮数进行边界收敛截断，畸形数值将使渲染线程每帧执行数万次多余的矩阵求解，导致客户端彻底卡死。

---

## 2. Java 模组网络通信与联机协议安全审查

Java 端的审查覆盖了 `common`、[fabric](file:///c:/tmp/mc-mmd-rust/fabric/src/main/java/com/shiroha/mmdskin/fabric/network/MmdSkinNetworkPack.java) 与 [neoforge](file:///c:/tmp/mc-mmd-rust/neoforge/src/main/java/com/shiroha/mmdskin/neoforge/network/MmdSkinNetworkPack.java) 模块的网络数据包定义、编解码边界及状态同步逻辑。

### 数据包反序列化长度无界与内存耗尽
在两端网络包的解码逻辑中，当操作码为骨骼同步（BONE_SYNC）时，代码直接调用了无参的 `buffer.readByteArray()`。在 Minecraft 网络底层，该方法依据传入的变长整数分配堆内存，若数据包声明了极大长度，服务端与客户端解码线程会在堆上分配超大字节数组，造成即时的堆内存耗尽。

在字符串载荷的读取上，统一部署了默认限制为 32767 字符的 `readUtf()`。这一设计存在两面失配：普通的模型与动作名允许长达上万字符，极大地消耗了内存并增加了垃圾对象分配；而对于舞台多人协议，由于通过 Base64 编码的 JSON 字符串传递，当舞台成员或动作文件增多时，字符串容易突破 32767 字符阈值，触发反序列化异常并直接将正常玩家踢出服务器。

### 盲目广播、流量放大与权限绕过
在服务端的网络包分发实现中，除少数请求外，对接收到的自定义操作码均采取全服广播策略，且完全缺乏基于玩家连接的发送速率限制。当恶意客户端高频发送同步数据包时，服务端会成倍地向全服其他玩家分发流量，造成服务端网络缓冲区阻塞及客户端带宽拥塞。值得注意的是，客户端甚至根本没有对骨骼同步包进行逻辑处理，但服务端依然在盲目分发空耗资源。

在特定实体管理上，车万女仆相关的模型与动作数据包直接携带了实体运行时数字编号，服务端在转发时未核验该实体是否为女仆、更未核验该实体的所有者是否为发送者，导致任意玩家可以伪造数据包接管甚至篡改他人实体的显示与动作状态。

在联机会话控制方面，[StageServerSessionService.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/stage/server/application/StageServerSessionService.java) 的邀请响应方法在查询成员时使用了 `computeIfAbsent`。这导致任何未经邀请的玩家只要构造一个接受邀请的响应包，服务端就会直接为其就地创建成员并确认为正式加入，从而旁路绕过了私密舞台的邀请鉴权机制。此外，舞台动作文件列表在同步时未对数组元素个数设置上限，过多的文件列表会导致客户端主线程在渲染循环中同步发起大量的磁盘探测与 JNI 合并计算，造成严重的画面冻结。

在客户端缓存方面，[AnimationLibrary.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/model/runtime/AnimationLibrary.java) 中的句柄哈希映射缺乏容量淘汰策略。若网络中持续收到随机生成的动画名称，未命中的记录均会以默认值长期驻留在内存中，导致客户端堆内存持续缓慢泄漏。

---

## 3. 文件系统、资源加载与依赖安全审查

文件系统审查聚焦于模型扫描、纹理获取、压缩包解压以及动态链接库的安全性。

### 路径遍历与本地文件未授权探测
在 [loader.rs](file:///c:/tmp/mc-mmd-rust/rust_engine/src/model/loader.rs) 中，解析 PMX 材质贴图与球面贴图相对路径时使用了 `model_dir.join(&tex_path)`。在标准库规范中，若贴图路径为驱动器绝对路径或包含跨层目录符号，拼接后将保留逃逸路径。这些路径经由 JNI 传递给 Java 层的纹理加载器与图像解码器，导致程序可能跳出模型根目录，读取本地磁盘上的敏感图片并在游戏中渲染，或在日志中打印文件存在性信息。

在联机驱动的动画与表情加载中也存在同样缺陷。[MorphSyncHelper.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/player/sync/MorphSyncHelper.java) 根据网络同步传入的表情名直接拼接路径进行文件检测；[AnimationLibrary.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/model/runtime/AnimationLibrary.java) 的公开加载方法也遗漏了相对路径过滤，使得远端数据包可以指示本地探测任意目录下的动画文件。

### 引导解压前缀绕过与哈希校验缺失
在资源自动引导模块 [MmdClientResourceBootstrap.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/MmdClientResourceBootstrap.java) 中，解压方法对解压条目路径进行了校验，但使用了 `canonicalPath.startsWith(canonicalDir)`。由于规范化路径末尾不包含路径分隔符，形如 `3d-skin-evil` 的同级目录名称依然能通过此前缀检查，构成典型的路径穿越解压隐患。同时，下载的压缩包未核验固定的哈希签名便直接进行解压，缺乏传输完整性保护。

此外，[ModelConfigManager.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/config/ModelConfigManager.java) 在清洗配置持久化名称时，遗漏了对 Windows 冒号（涉及 NTFS 数据流）以及保留设备名（如 CON, NUL 等）的处理，特殊名称在 Windows 下可能引发文件句柄异常或写入失败。

### 动态库加载与 JLayer 外部依赖
[NativeLibraryLoader.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/NativeLibraryLoader.java) 负责提取并加载原生动态库。当前实现在 Linux 环境下创建临时目录时未配置 POSIX 权限，使得生成的动态库处于系统全局可读状态；代码虽然计算了 SHA-256，但仅用于日志打印，未在执行 `System.load` 之前比对内置的可信基线签名，存在被同机低权限进程替换篡改的时间差隐患；且当运行时版本号不匹配时仅打印了警告日志，缺乏强制中断保护。

在外部依赖方面，项目引入了发布于 2008 年的旧版音频库 `javazoom:jlayer:1.0.1`。需说明的是，公开数据库中提及的 CVE-2023-39017 系安全平台针对 Quartz 调度包的误匹配，并非 JLayer 本身问题。然而在实际调用中，模组将 MP3 解码后的 PCM 音频流无限制地写入内存缓冲区，缺少播放时长与输出体积保护，面对超长或畸形音频流时存在堆内与堆外内存大幅膨胀的风险。

---

## 4. 防御加固与重构建议路线

为彻底消除上述架构隐患，建议按模块分阶段实施加固：

1. JNI 内存边界与异常网关重构：废弃直接暴露裸指针和物理地址的接口，所有数据读写统一通过模型句柄在受管锁周期内进行；对 `CopyData` 接口强制加入源顶点数与非负数断言；统一采用非对齐内存读写操作；为全部 JNI 导出方法包装异常捕获宏，防止底层 panic 击垮 JVM。
2. 资产解析防御性校验：在骨骼装配前引入拓扑环路检测并限制递归层数；为 PMX 计数读取与 CCD-IK 迭代轮数设置合理的硬性上限；对 FBX 解压与贴图解码设置单次处理内存与尺寸配额。
3. 网络协议与权限加固：为网络包读取设定严格的最大字节数与字符串长度上限；在服务端引入发包频控拦截全服广播雪崩；修复舞台会话成员查询中的动态创建缺陷；增加车万女仆等实体的所有权校验。
4. 文件系统与安全加载治理：在模型贴图与表情解析中引入严格的白名单清洗，确保最终路径严格限定在模型专属根目录之下；修正资源引导中的解压路径判断并补齐 SHA-256 哈希校验；在动态库加载前核对指纹并在版本不匹配时主动终止。
