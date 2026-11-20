# 版本与下载 / Versions and downloads

简体中文 · [English](#english) · [返回 README](../README.md)

## 维护分支

| Minecraft | 加载器 | Java | 源码分支 | Actions 构建 |
| --- | --- | --- | --- | --- |
| 1.21.1 | Fabric / NeoForge | 21 | [`feat/1.21.1-neoforge`](https://github.com/zhixuan45/MC-MMD-rust/tree/feat/1.21.1-neoforge) | [1.21.1 构建](https://github.com/zhixuan45/MC-MMD-rust/actions/workflows/build-mod.yml?query=branch%3Afeat%2F1.21.1-neoforge) |
| 1.20.1 | Forge | 17 | [`codex/port-1.20.1`](https://github.com/zhixuan45/MC-MMD-rust/tree/codex/port-1.20.1) | [1.20.1 构建](https://github.com/zhixuan45/MC-MMD-rust/actions/workflows/build-mod.yml?query=branch%3Acodex%2Fport-1.20.1) |

## 选择安装包

1. 先到 [Releases](https://github.com/zhixuan45/MC-MMD-rust/releases) 查看发布说明，从 **Assets** 选择匹配 Minecraft 版本和加载器的模组 JAR。
2. 想试用分支上的新改动时，打开上表对应的 Actions 构建，进入具体运行记录，从 **Artifacts** 下载模组产物。下载为 ZIP 时，解压后把模组 JAR 放入实例的 `mods/`。
3. 如果文件名带有 `windows-x64`、`linux-arm64` 等后缀，按操作系统和 Java 运行环境的架构选择。每个平台包只包含该平台的原生库。

反馈问题时，请附上 Minecraft 版本、加载器及其版本、完整的模组 JAR 文件名，以及 Release 标签或 Actions 运行链接。这样可以定位到具体构建。

## English

[简体中文](#版本与下载--versions-and-downloads) · [Back to the English README](../README.en.md)

| Minecraft | Mod loader | Java | Source branch | Actions builds |
| --- | --- | --- | --- | --- |
| 1.21.1 | Fabric / NeoForge | 21 | [`feat/1.21.1-neoforge`](https://github.com/zhixuan45/MC-MMD-rust/tree/feat/1.21.1-neoforge) | [1.21.1 builds](https://github.com/zhixuan45/MC-MMD-rust/actions/workflows/build-mod.yml?query=branch%3Afeat%2F1.21.1-neoforge) |
| 1.20.1 | Forge | 17 | [`codex/port-1.20.1`](https://github.com/zhixuan45/MC-MMD-rust/tree/codex/port-1.20.1) | [1.20.1 builds](https://github.com/zhixuan45/MC-MMD-rust/actions/workflows/build-mod.yml?query=branch%3Acodex%2Fport-1.20.1) |

1. For a published build, read the notes on [Releases](https://github.com/zhixuan45/MC-MMD-rust/releases) and choose the mod JAR under **Assets** that matches your Minecraft version and loader.
2. To try changes from a maintenance branch, open its Actions link above, select a run, and download the mod artifact under **Artifacts**. If the download is a ZIP, extract it and put the mod JAR in your instance's `mods/` folder.
3. If the filename ends in a platform suffix such as `windows-x64` or `linux-arm64`, match it to your OS and Java runtime architecture. Each platform package contains native libraries for that platform.

When reporting a problem, include your Minecraft version, your loader and its version, the full mod JAR filename, and the Release tag or Actions run link so the build can be identified.
