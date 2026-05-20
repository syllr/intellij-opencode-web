# PROJECT KNOWLEDGE BASE

**Generated:** 2026-05-10
**Commit:** f5a95b4
**Branch:** 1.0.17

## OVERVIEW

IntelliJ Platform 插件，为 OpenCode Web UI 提供 JetBrains IDE 集成（fork 版本）。

## STRUCTURE

```
intellij-opencode-web/
├── src/main/kotlin/com/github/xausky/opencodewebui/
│   ├── toolWindow/      # JCEF 浏览器 + 服务器管理 + 键盘处理【核心】
│   ├── listeners/       # SSE 事件消费 + 文件刷新协调
│   │   └── 参考/         # OpenCode Message API 参考文档
│   ├── actions/         # IDE actions（快捷键传递、Copy/Add to Prompt）
│   └── utils/           # HTTP API、JS 注入、IdeaVim 集成
├── src/main/resources/  # plugin.xml、图标、i18n 消息
├── src/test/            # 单元测试 + 集成测试
├── .github/workflows/   # CI/CD（构建、发布、UI测试）
└── references/          # IntelliJ 平台参考文档
```

## WHERE TO LOOK

| Task                | Location                              | Notes                                        |
| ------------------- | ------------------------------------- | -------------------------------------------- |
| 工具窗口入口        | toolWindow/MyToolWindowFactory.kt     | JCEF + 服务器管理                            |
| 工具窗口面板        | toolWindow/MyToolWindow.kt            | 浏览器生命周期管理                           |
| 服务器管理          | toolWindow/OpenCodeServerManager.kt   | 单例对象，启停管理                           |
| IDE Actions         | actions/                              | 快捷键传递、Copy/Add to Prompt               |
| SSE 事件 + 通知降噪 | listeners/OpenCodeSSEConsumer.kt      | session.diff/file.edited + idle 事件抑制逻辑 |
| 文件刷新            | listeners/FullRefreshCoordinator.kt   | 生产者-消费者模式                            |
| 快捷键传递          | toolWindow/JcefKeyboardInterceptor.kt | ESC/Cmd+K/Cmd+, → JCEF                       |
| Emacs 按键          | toolWindow/EmacsKeyHandler.kt         | Ctrl+N/P/E/A/B/F                             |
| 右键菜单            | toolWindow/LinkContextMenuHandler.kt  | JCEF 右键菜单                                |
| 健康监控            | toolWindow/HealthMonitor.kt           | 服务器定时轮询                               |
| 常量定义            | root/OpenCodeConstants.kt             | 端口、超时等                                 |
| CI/CD               | .github/workflows/                    | 构建、发布、UI测试                           |
| 构建配置            | build.gradle.kts, gradle.properties   | 依赖、版本                                   |
| 测试                | src/test/                             | 单元测试 + 集成测试                          |
| **IntelliJ 参考**   | `references/intellij-platform/`       | 官方文档                                     |

## CODE MAP

### toolWindow/（8 文件，核心模块）

| Symbol                  | Type                         | Role                                      |
| ----------------------- | ---------------------------- | ----------------------------------------- |
| MyToolWindowFactory     | ToolWindowFactory            | 工具窗口入口，管理共享 JBCefClient        |
| MyToolWindow            | Panel                        | 工具窗口面板，协调浏览器生命周期          |
| BrowserPanel            | Panel                        | JCEF 浏览器 Panel，CefBrowser 创建/销毁   |
| OpenCodeServerManager   | Object                       | 服务器进程启停管理（AtomicReference）     |
| HealthMonitor           | Class                        | 服务器健康检查定时轮询                    |
| JcefKeyboardInterceptor | Class                        | 将 ESC/Cmd+K/Cmd+, 传递给 JCEF 浏览器     |
| EmacsKeyHandler         | Object                       | Emacs 风格按键映射（Ctrl+N/P/E/A/B/F）    |
| LinkContextMenuHandler  | CefContextMenuHandlerAdapter | JCEF 右键菜单（刷新/浏览器打开/复制链接） |

### listeners/（6 文件 + 参考/，SSE + 文件事件）

| Symbol                 | Type                   | Role                                        |
| ---------------------- | ---------------------- | ------------------------------------------- |
| OpenCodeSSEConsumer    | BackgroundEventHandler | SSE 事件消费（session.diff/file.edited 等） |
| FullRefreshCoordinator | Object                 | 全量刷新协调器（生产者-消费者）             |
| RefreshDeduplicator    | Class                  | 文件刷新去重（时间窗口）                    |
| SSEEventParser         | Object                 | SSE 事件 JSON 解析                          |
| BashCommandHandler     | Object                 | Bash 工具 SSE 事件处理                      |
| OpenCodeDiffRefresher  | Object                 | 文件刷新器（LocalFileSystem）               |

### actions/（3 文件）

| Symbol                        | Type     | Role                                       |
| ----------------------------- | -------- | ------------------------------------------ |
| JcefShortcutPassthroughAction | AnAction | 快捷键传递（ESC/Cmd+K/Cmd+,）到 JCEF       |
| CopyAsPromptAction            | AnAction | 复制选中文本为 Prompt 格式（带路径和行号） |
| AddToPromptAction             | AnAction | 添加选中代码到 Prompt 编辑器，支持 IdeaVim |

### utils/（4 文件）

| Symbol             | Type   | Role                                   |
| ------------------ | ------ | -------------------------------------- |
| OpenCodeApi        | Object | HTTP API 调用（健康检查/获取 session） |
| JcefJsInjector     | Object | 向 JCEF 页面注入 JavaScript            |
| IdeaVimIntegration | Object | IdeaVim 集成（获取 visual 模式选区）   |
| SSEConsumerFactory | Object | OpenCodeSSEConsumer 工厂               |

### root

| Symbol            | Type   | Role                             |
| ----------------- | ------ | -------------------------------- |
| OpenCodeConstants | Const  | 常量（端口 12396、超时、间隔等） |
| MyBundle          | Bundle | i18n 资源绑定                    |

## CONVENTIONS

- Gradle 版本目录 (`gradle/libs.versions.toml`)
- JDK 21, Kotlin 2.3.20, IntelliJ Platform 2.14.0
- 平台版本 2026.1 (sinceBuild 261)
- Qodana + Kover 代码质量，CodeCov 覆盖率上传
- SemVer 版本格式
- 使用 `thisLogger().info/warn/error()` 记录日志
- 日志文件: `build/idea-sandbox/IU-2026.1/log/`
- 构建: `./gradlew buildPlugin`，测试: `./gradlew check`
- **包名不匹配**: 源码 `com.github.xausky.opencodewebui` vs `pluginGroup = com.shenyuanlaolarou`（已知问题，暂未修复）
- **Git 提交**: Agent 禁止自动提交，必须用户显式调用
- **发布**: Agent 禁止自动发布，必须用户显式调用

## ANTI-PATTERNS (THIS PROJECT)

- ~~静态全局服务器状态~~ → 已修复：使用 `AtomicReference<Process>` + `AtomicBoolean` / `@Volatile`
- ~~弃用的 JBCefBrowser~~ → 已修复：使用 `JBCefBrowserBuilder`
- ~~SQLite JDBC 会话管理~~ → 已修复：通过 HTTP API (`OpenCodeApi.getLatestSessionId`) 实现会话持久化
- ~~单个大文件 (MyToolWindowFactory.kt 900+ 行)~~ → 已修复：拆分为多个文件（toolWindow/ 8 文件）
- ~~正则表达式解析 HTTP 响应体~~ → 已修复：统一使用 Gson 解析 JSON 响应。所有 HTTP API 返回的 body 必须通过 Gson 或类似的 JSON 解析器处理，禁止使用 `Regex` / `Pattern` / 字符串操作来提取 JSON 字段

### 开发注意事项

- **禁止静态全局可变状态** - 使用 AtomicReference/AtomicBoolean 替代
- **禁止 Regex 解析 JSON** - 必须用 Gson 或类似 JSON 解析器
- **AddToPromptAction** 中 im-select 路径和输入法 ID 硬编码，需改为可配置（待处理）

### 通知降噪设计决策

- **`handleSessionIdle` 抑制逻辑**: 父 session 的 complete 通知使用 `sessionIdleFired` 集合抑制 agent 循环中的重复 idle。重置信号为 `message.updated(role=user)`，而非 `session.status(busy)`，以确保 agent 循环中的中间 idle 不重复通知
- **`session.deleted`不移除追踪**: `session.deleted` 中不移除 `subagentSessionIds`，防止时序竞态导致子 agent idle 被误判为 `complete`。集合仅在 SSE 重连时通过 `onClosed()` 清空
- **原则2通知不受影响**: `permission.asked`、`question.asked`、`session.next.tool.called(tool=question)` 在 `when(eventType)` 中是独立分支，不受 `sessionIdleFired` 抑制逻辑影响

## UNIQUE STYLES

- Emacs 风格 JCEF 键盘快捷键（Ctrl+N/P/E/A/B/F）
- 快捷键传递：ESC/Cmd+K/Cmd+, 在 JCEF 焦点时传递给网页而非 IDE
- 首次打开工具窗口时自动重启服务器
- 端口 12396（非标准，避免冲突）
- 中文输入法修复：移除 `e.consume()`
- 外部链接在系统浏览器打开
- 会话恢复（通过 HTTP API）
- **Copy as Prompt**：选中代码后右键复制为 Prompt 格式
- **Add to Prompt**：选中代码后添加到 Prompt 编辑器，支持 IdeaVim
- **IdeaVim 集成**：与 IdeaVim 插件配合，正确获取 visual 模式选区
- **JCEF JS 注入**：注入 JavaScript 到浏览器页面
- **HealthMonitor**：定时轮询服务器健康状态，自动重连

## TOOLWINDOW 配置说明

- **OpenCodeWeb** 工具窗口通过 `anchor="right"` 配置显示在右侧边栏
- 实现 `DumbAware` 接口，确保索引更新期间工具窗口可正常使用
- 使用 `sharedJBCefClient` 共享 JBCefClient 实例，减少资源占用
- 通过 `myToolWindowInstances: ConcurrentHashMap<Project, MyToolWindow>` 管理多项目实例
- 服务未启动时自动启动，服务崩溃时自动重连

## COMMANDS

```bash
./gradlew buildPlugin          # 构建插件
./gradlew check                 # 测试 + Qodana
./gradlew verifyPlugin          # 验证插件结构
./gradlew runIde                # 运行带插件的 IDE
./gradlew publishPlugin         # 发布到 Marketplace
./gradlew qodana                # 代码质量检查
```

## TESTING

| 类型     | 框架                          | 位置                                       |
| -------- | ----------------------------- | ------------------------------------------ |
| 单元测试 | JUnit 4                       | src/test/.../actions/FormatAsPromptTest.kt |
| 集成测试 | IntelliJ BasePlatformTestCase | src/test/.../MyPluginTest.kt               |
| UI 测试  | Robot + IntelliJ 测试框架     | .github/workflows/run-ui-tests.yml         |

- 测试数据放在 `src/test/testData/` 目录
- 集成测试继承 `BasePlatformTestCase`，使用 `@TestDataPath`
- Kover 代码覆盖率集成至 `check` 任务

## NOTES

- Fork 自 xausky/intellij-opencode-web-ui
- 插件 ID: `com.shenyuanlaolarou.opencodewebui`
- 调试日志: `thisLogger().info/warn/error()`
- 日志文件: `build/idea-sandbox/IU-2026.1/log/`
- 默认端口: 12396，默认主机: 127.0.0.1

## REFERENCES

| Resource         | URL                                                    |
| ---------------- | ------------------------------------------------------ |
| DevGuide         | https://plugins.jetbrains.com/docs/intellij/           |
| JCEF Docs        | https://chromiumembedded.github.io/java-cef/           |
| Platform Samples | https://github.com/JetBrains/intellij-sdk-code-samples |
