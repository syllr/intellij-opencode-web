# PROJECT KNOWLEDGE BASE

**Generated:** 2026-04-27
**Commit:** c96e033
**Branch:** main

## OVERVIEW
IntelliJ Platform 插件，为 OpenCode Web UI 提供 JetBrains IDE 集成（fork 版本）。

## STRUCTURE
```
intellij-opencode-web/
├── src/main/kotlin/com/github/xausky/opencodewebui/
│   ├── toolWindow/      # JCEF + 服务器管理
│   ├── actions/         # IDE actions（重启、快捷键、Copy as Prompt）
│   ├── gutter/          # 行标记（Prompt 评论）
│   ├── services/        # 平台服务
│   ├── listeners/       # 生命周期监听器
│   ├── startup/        # 启动活动
│   └── utils/          # Session 辅助工具
├── src/main/resources/  # 图标、plugin.xml、消息
├── .github/workflows/   # CI/CD（构建、发布、UI测试）
└── build.gradle.kts     # Gradle 配置
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| 核心逻辑 | toolWindow/MyToolWindowFactory.kt | JCEF + 服务器管理 |
| IDE Actions | actions/ | RestartServer、PassToJcef、CopyAsPrompt |
| Gutter Icons | gutter/ | PromptLineMarkerProvider、PromptCommentDialog |
| CI/CD | .github/workflows/ | 构建、发布、UI测试 |
| 构建配置 | build.gradle.kts, gradle.properties | 依赖、版本 |
| 测试 | src/test/ | 单元测试 + 集成测试 |
| **IntelliJ 参考** | `references/intellij-platform/` | 官方文档 |

## CODE MAP

| Symbol | Type | Location | Role |
|--------|------|----------|------|
| MyToolWindowFactory | ToolWindowFactory | toolWindow/ | JCEF 浏览器 + 服务器管理 |
| OpenCodeServerManager | Object | toolWindow/ | 服务器生命周期管理 |
| OpenCodeApi | Class | toolWindow/ | HTTP API 调用 |
| SessionHelper | Object | utils/ | 会话恢复 |
| PromptLineMarkerProvider | LineMarkerProvider | gutter/ | 行标记图标 |
| PromptCommentDialog | DialogWrapper | gutter/ | 评论对话框 |
| PassToJcefAction | AnAction | actions/ | 快捷键传递 |
| CopyAsPromptAction | AnAction | actions/ | 复制为 Prompt |
| MyStartupActivity | StartupActivity | startup/ | 启动时注册 Disposable |
| PromptToolWindowFactory | ToolWindowFactory | toolWindow/ | Prompt 编辑器工具窗口 |
| PromptToolWindowPanel | JPanel | toolWindow/ | Prompt 编辑器面板 |

## CONVENTIONS
- Gradle 版本目录 (`gradle/libs.versions.toml`)
- JDK 21, Kotlin 2.3.20
- Qodana + Kover 代码质量
- SemVer 版本格式
- **包名不匹配**: 源码 `com.github.xausky.opencodewebui` vs `pluginGroup = com.shenyuanlaolarou`（已知问题，暂未修复）
- **Git 提交**: Agent 禁止自动提交，必须用户显式调用
- **发布**: Agent 禁止自动发布，必须用户显式调用

## ANTI-PATTERNS (THIS PROJECT)
- ~~静态全局服务器状态~~ → 已修复：使用 AtomicReference/AtomicBoolean
- ~~弃用的 JBCefBrowser~~ → 已修复：使用 JBCefBrowserBuilder
- ~~SQLite JDBC 会话管理~~ → 已修复：使用 HTTP API
- ~~单个大文件~~ → 已修复：拆分为多个文件

## TOOLWINDOW 配置说明
- **PromptEditor** 工具窗口通过 `secondary="true"` 和 `order="after Bookmarks"` 配置显示在左侧边栏下方
- IntelliJ 的 `secondary` 属性控制工具窗口显示在主组（上方）还是辅助组（下方）
- `side_tool="true"` 是 IntelliJ 运行时状态，由 IDE 根据 `secondary` 属性和用户拖动操作写入配置文件
- 如果 `secondary` 不生效，用户手动拖动后 IDE 会写入 `side_tool="true"` 到 workspace.xml

## UNIQUE STYLES
- Emacs 风格 JCEF 键盘快捷键
- 首次打开工具窗口时自动重启服务器
- ESC 键焦点修复（JCEF）
- 端口 12396（非标准）
- 中文输入法修复：移除 `e.consume()`
- 外部链接在系统浏览器打开
- 会话恢复（通过 HTTP API）
- **Copy as Prompt**：复制选中文本为 Prompt 格式

## COMMANDS
```bash
./gradlew buildPlugin          # 构建插件
./gradlew check                 # 测试 + Qodana
./gradlew verifyPlugin          # 验证插件结构
./gradlew runIde                # 运行带插件的 IDE
./gradlew publishPlugin         # 发布到 Marketplace
./gradlew qodana                # 代码质量检查
```

## NOTES
- Fork 自 xausky/intellij-opencode-web-ui
- 插件 ID: `com.shenyuanlaolarou.opencodewebui`
- 调试日志: `thisLogger().info/warn/error()`
- 日志文件: `build/idea-sandbox/IU-2025.3.4/log/`

## REFERENCES
| Resource | URL |
|----------|-----|
| DevGuide | https://plugins.jetbrains.com/docs/intellij/ |
| JCEF Docs | https://chromiumembedded.github.io/java-cef/ |
| Platform Samples | https://github.com/JetBrains/intellij-sdk-code-samples |
