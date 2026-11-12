# 1.20.1 分支遗留 1.21 平台代码清理

日期：2026-11-13

## 范围与前置检查

- 分支：`codex/port-1.20.1`。
- `settings.gradle` 当前只包含 `common`、`forge`；`fabric/`、`neoforge/` 不参与本分支构建。
- 清理前两棵目标目录没有 tracked 或 untracked 工作树改动。`README.md`、`index.md` 等既有工作区修改保留，本轮只在现状上同步平台说明。
- 只删除 `git ls-files` 返回的 1.21 Fabric/NeoForge 生产源码、配置和构建资源，共 90 个文件。
- `fabric/build/`、`neoforge/build/`、忽略的 run/gradle 数据、历史文档、许可证、公共 API、当前 `forge/` 实现及其他未提交改动均保留。
- 按要求未运行 Gradle、测试、探针或游戏。

## 清理清单

### Fabric：45 个跟踪文件

- 根配置 1 个：`fabric/build.gradle`。
- 平台入口与适配源码 18 个：
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/MmdSkinFabric.java`
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/MmdSkinFabricClient.java`
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/YsmCompat.java`
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/compat/modmenu/ModMenuIntegration.java`
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/config/` 下 7 个文件
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/maid/MaidCompatMixinPlugin.java`
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/network/MmdSkinNetworkPack.java`
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/register/` 下 4 个文件
  - `fabric/src/main/java/com/shiroha/mmdskin/fabric/stage/FabricStageSessionRegistry.java`
- Mixin 源码 22 个：`fabric/src/main/java/com/shiroha/mmdskin/mixin/fabric/` 下 13 个 Mixin（含 1 个 accessor）和 `compat/` 下 9 个兼容 Mixin。
- 资源 4 个：
  - `fabric/src/main/resources/fabric.mod.json`
  - `fabric/src/main/resources/icon.png`
  - `fabric/src/main/resources/mmdskin-fabric.mixins.json`
  - `fabric/src/main/resources/mmdskin-maid.mixins.json`

### NeoForge：45 个跟踪文件

- 根配置 2 个：`neoforge/build.gradle`、`neoforge/gradle.properties`。
- 平台入口与适配源码 19 个：
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/MmdSkinNeoForge.java`
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/MmdSkinNeoForgeClient.java`
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/YsmCompat.java`
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/config/` 下 7 个文件
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/entity/MobReplacementRenderEventHandler.java`
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/maid/` 下 2 个文件
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/network/MmdSkinNetworkPack.java`
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/register/` 下 4 个文件
  - `neoforge/src/main/java/com/shiroha/mmdskin/neoforge/stage/NeoForgeStageSessionRegistry.java`
- Mixin 源码 19 个：`neoforge/src/main/java/com/shiroha/mmdskin/mixin/neoforge/` 下 11 个入口 Mixin 和 `compat/` 下 8 个兼容 Mixin。
- 资源 5 个：
  - `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
  - `neoforge/src/main/resources/icon.png`
  - `neoforge/src/main/resources/mmdskin-neoforge-refmap.json`
  - `neoforge/src/main/resources/mmdskin-neoforge.mixins.json`
  - `neoforge/src/main/resources/pack.mcmeta`

NeoForge 合计为 2 + 19 + 19 + 5 = 45 个；Fabric 合计为 1 + 18 + 22 + 4 = 45 个。

## 保留证据

- `fabric/build/` 清理前后均为 128 个文件，含 13 个 JAR 和 1 个 Windows 原生库。
- `neoforge/build/` 清理前后均为 114 个文件，含 7 个 JAR。
- 两棵 `build/` 的路径、长度、UTC 修改时间聚合签名前后均为 `012fdc6d002102127b913e5f5d811fc54bf0ddffec29268c6b057b39a23eb6a7`。
- `fabric/build/` 与 `neoforge/build/` 继续留在原位置，供历史产物、原生资源及探针依赖使用。
- `forge/build.gradle` 只消费 `:common` 的源码输出和自身资源；`settings.gradle` 仍只包含 `common`、`forge`，Forge 构建依赖路径未指向已删除平台目录。
- `doc/`、`plans/` 等历史迁移记录不随本次清理改写，以免丢失旧版本证据。
