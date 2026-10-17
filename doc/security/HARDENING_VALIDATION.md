# MC-MMD-Rust 安全加固基线与验证记录

修订日期：2026-10-17。本文件记录阶段 0 确立的工程基线状态、环境配置、现有测试运行结果、资产测量数据及验证边界。后续所有加固改动均以此基线为对照起点，防止在多模块联动修复过程中掩盖已有缺陷或引入未预期的功能回退。

---

## 1. 基线环境与提交状态

当前工作区位于 Git 分支 `feat/1.21.1-neoforge`，基线提交为 `f25beb1`（feat: 更新着色器以支持环境照明衰减和新增性能问题交接文档）。在开始阶段 0 之前，工作区除待实施的安全计划与文档草稿外无未提交代码改动，`git status` 与 `git diff --check` 显示代码树状态干净。

宿主操作系统为 64 位 Windows 11（版本 10.0 amd64）。核心编译与运行时工具链测定如下：
- Rust 工具链版本为 Cargo 1.97.1 (c980f4866 2026-06-30)，搭配对应版本 rustc。
- Java 环境由 Azul Zulu OpenJDK 21.0.9+10-LTS（build 21.0.9+10-LTS）提供，`JAVA_HOME` 指向 `C:\Program Files\Zulu\zulu-21`。
- Gradle 体系采用工程内建 Wrapper，版本为 8.11.1，构建守护进程统一复用 Zulu 21。

---

## 2. 基线测试执行与结果记录

在未实施任何安全补丁前，对各子系统分别执行了既定验证命令，实际执行结果与错误分类如下：

### 2.1 原生引擎单元测试
执行命令 `cargo test --manifest-path rust_engine/Cargo.toml`。
测试总计 239 项，其中 236 项通过，2 项忽略，1 项失败，整体退出码为 1。
失败用例为 `physics::mmd_physics::issue_diagnostics::tests::snapshot_reports_applied_filter_and_real_bullet_contact`，失败位置在 `src/physics/mmd_physics/issue_diagnostics.rs:288:9`，断言 `output.contains("active_stability=Stable")` 不成立。该失败属于现有诊断快照文本匹配的不一致问题，加固工作必须记录此既有问题，在阶段 1 涉及诊断模块时保持警惕，不可伪称原基线全部通过。

### 2.2 基础 PMX 解析库测试
执行命令 `cargo test --manifest-path rust_engine/deps/mmd-rs/Cargo.toml`。
测试退出码为 0，用例总数为 0。该模块目前未单独维护单元测试用例，解析测试均挂载于上层 `rust_engine`。

### 2.3 独立预览器检查
执行命令 `cargo check --manifest-path rust_engine/Cargo.toml --features viewer --bin viewer`。
检查退出码为 0，编译通过。仅产生 1 条死代码警告，指出 `src/bin/viewer/motion_edit.rs:46:12` 中关联函数 `MotionEdit::new` 从未被使用。

### 2.4 VR 演示程序检查
执行命令 `cargo check --manifest-path rust_engine/Cargo.toml --features vr_demo --bin vr-pmx-demo`。
检查退出码为 1，产生 56 处编译错误，全部集中在 `src/bin/vrdemo-old/vulkan.rs`。错误原因在于历史 Vulkan 代码缺失 `vk_result`、`transition_image_layout`、`color_subresource_range` 等函数实现。根据实施计划约定，此项编译失败属于工程遗留未维护状态，后续阶段若清理 `wgpu` 依赖，不可宣称顺带修复了未纳入维护的旧 Vulkan 演示程序。

### 2.5 Java 模块测试与双端编译
执行命令 `./gradlew.bat :common:test :fabric:compileJava :neoforge:compileJava`。
构建在 32 秒内完成，退出码为 0。`common` 模块的所有单元测试全部顺利通过，Fabric 与 NeoForge 平台的 Java 源码均顺利编译通过，Mixin 目标方法均正常识别。

### 2.6 模组打包构建
执行命令 `./gradlew.bat :fabric:remapJar :neoforge:shadowJar`。
构建耗时 15 秒，退出码为 0。自动化流程顺利执行 Bullet3 源码准备、Rust 原生动态库编译（`mmd_engine.dll`）、原生资源校验以及双平台 Jar 重映射与 Shadow 打包。

### 2.7 差异格式检查
执行命令 `git diff --check`。
退出码为 0，无任何残留空白、合并冲突标记或缩进异常。

---

## 3. 正常测试资产规模统计

为给后续各阶段配额设计提供客观依据，排查并统计了工程现有典型资产的物理特征，避免臆测配额阈值：

模型网格方面，现有测试 PMX 模型（包括草上飞、东海帝王、乐正绫等角色）文件体积在 1.9 MB 至 3.5 MB 之间，顶点数量分布在 10,000 至 15,000 个，三角形面数在 1.5 万至 3 万个之间，骨骼总数分布在 150 至 350 根。

贴图纹理方面，身体与主服装材质普遍采用 2048x2048 像素 RGBA 格式，解压后显存占用约 16 MB，PNG 压缩包约 600 KB 至 800 KB；面部、头发等组件贴图主要为 1024x1024 像素 RGBA，解压占用约 4 MB；高光与球面贴图多在 128x128 至 512x512 像素之间。

动画资产方面，工程内置了 24 个默认 VMD 动作文件。轻量动作（如潜行、睡眠）大小在 500 字节至 3 KB，包含 4 至 30 帧；日常机动动作（如行走、跑步、游泳）大小在 30 KB 至 115 KB，骨骼关键帧数量在 300 至 1,000 帧；最长且最复杂的持续蓄力动作（如弓箭使用）文件约为 4.0 MB，骨骼关键帧总数达到 36,103 帧。

音频资产方面，现有舞台与音效测试音频为 44.1 kHz 16-bit 双声道格式，短击音效约 36 KB 至 41 KB，器乐采样约为 588 KB。

---

## 4. 平台验证边界与未验证目标

本次基线验证仅在 Windows x64 物理开发机上完成。以下平台因当前物理环境缺失，无法在本机完成本地动态库链接与游戏运行验证，必须依赖 GitHub Actions 工作流 `build-mod.yml` 的多平台编译矩阵进行交叉验证：
- Linux x64、Linux ARM64、Linux LoongArch64 以及 Linux RISC-V64。
- macOS x64 与 macOS ARM64。
- Android ARM64 与 Android AMD64（涉及 PojavLauncher 容器环境及 `libc++_shared.so` 动态链接）。

加固交付时，跨平台成功结论以 GitHub Actions 真实编译打包日志为准，不得将 Windows 本地测试通过外推为全平台通过。
