# 使用须知验证

本分支适配 Minecraft 1.21.1（Fabric / NeoForge）。

自动化回归：`UsageNoticeConfigTest` 覆盖首次未确认、保存后重新加载、无效/非布尔记录、目录缺失及保存失败后重试。
执行 Java 检查：`./gradlew :common:test :fabric:compileJava :neoforge:compileJava`。

游戏内验收（两个加载器均需检查）：

1. 使用没有 `config/mmdskin/usage_notice.json` 的游戏目录启动；主菜单不应弹窗。
2. 进入单人世界或多人服务器，等待加载完成，确认两段文字、仓库完整地址、中文换行和“确认”按钮可见。
3. ESC 不应记为确认；若断线或退出游戏，重新进世界仍应显示。
4. 点击“确认”后关闭提示；切换世界、维度、重连、重启均不再提示。
5. 删除或损坏记录后应重新提示。将配置目录设为不可写，点击确认应显示保存失败且允许重试；恢复权限后确认应成功。
6. 在最小窗口和各 GUI 缩放下检查文字、错误提示与按钮可见；打开其他菜单或资源加载界面时不应被提示覆盖。
7. 检查发布 JAR 内 `META-INF/licenses/mmdskin/` 包含本许可、CC 基础条文、原 MIT 许可与 NOTICE。

本次通过 GitHub 连接编辑；托管开发环境启动失败，提交时尚未运行 Gradle 或游戏内验收。CI 结果以 GitHub Actions 为准。
