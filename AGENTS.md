# PROJECT KNOWLEDGE BASE

IntelliJ Platform 插件 (Kotlin)，为 OpenCode Web UI 提供 JetBrains IDE 集成。
Fork 自 `xausky/intellij-opencode-web-ui`；本仓库 `syllr/intellij-opencode-web`。

## STACK

- Gradle 9.3.1 · JDK 21 · Kotlin 2.3.20 · IntelliJ Platform Gradle Plugin 2.14.0
- 目标平台: 2026.1（`pluginSinceBuild=261`，`pluginUntilBuild` 留空）
- 依赖: Gson 2.10.1（JSON 解析）、okhttp-eventsource 4.1.0（SSE）、JUnit 4.13.2
- Qodana（`qodana-jvm-community:2024.3`）+ Kover 0.9.5 + CodeCov
- 版本号在 `gradle.properties` 的 `pluginVersion`；分支名（如 `1.0.18`）常与 `pluginVersion` 不同步，不要据此推断
- `CHANGELOG.md` 由 `org.jetbrains.changelog` Gradle 插件管理（`patchChangelog` 任务），不要手改

## STRUCTURE

源码根: `src/main/kotlin/com/github/xausky/opencodewebui/`

| 目录                           | 内容                                                                                              |
| ------------------------------ | ------------------------------------------------------------------------------------------------- |
| `toolWindow/`                  | JCEF 浏览器 + 服务器进程管理 + 键盘拦截（核心，9 文件）                                           |
| `listeners/`                   | SSE 事件消费 + 文件刷新协调（4 文件）                                                             |
| `actions/`                     | IDE Actions：复制/添加到 Prompt、IdeaVim 集成（2 文件）                                           |
| `utils/`                       | HTTP API、JS 注入、通知路由/服务、配置（8 文件）                                                  |
| `settings/`                    | Settings UI：`OpenCodeConfigurable`（1 文件）                                                     |
| 根                             | `OpenCodeConstants`（端口 12396 等）+ `MyBundle`（i18n）                                          |
| `src/main/resources/META-INF/` | `plugin.xml`：toolWindow / notificationGroup / applicationConfigurable / postStartupActivity 注册 |

`src/test/kotlin/.../{actions,utils,listeners}/` 含 JUnit 4 单测；`MyPluginTest` 继承 `BasePlatformTestCase`，测试数据在 `src/test/testData/`。

## WHERE TO LOOK

| 想做的事                      | 改哪里                                                         |
| ----------------------------- | -------------------------------------------------------------- |
| 工具窗口入口/布局             | `toolWindow/MyToolWindowFactory.kt`                            |
| 浏览器面板/生命周期           | `toolWindow/MyToolWindow.kt` + `BrowserPanel.kt`               |
| 服务器进程启停                | `toolWindow/OpenCodeServerManager.kt`                          |
| 健康检查/自动重连             | `toolWindow/HealthMonitor.kt`                                  |
| 键盘快捷键（ESC/Cmd+K/Emacs） | `toolWindow/JcefKeyboardInterceptor.kt` + `EmacsKeyHandler.kt` |
| JCEF 右键菜单                 | `toolWindow/LinkContextMenuHandler.kt`                         |
| 项目启动/通知路由             | `toolWindow/OpenCodeProjectActivity.kt`                        |
| SSE 事件消费与降噪            | `listeners/OpenCodeSSEConsumer.kt`                             |
| SSE 解析                      | `listeners/SSEEventParser.kt`                                  |
| Bash 工具事件                 | `listeners/BashCommandHandler.kt`                              |
| 文件刷新（生产者-消费者）     | `listeners/FullRefreshCoordinator.kt`                          |
| HTTP API（健康/session 等）   | `utils/OpenCodeApi.kt` + `OpenCodeApiResult.kt`                |
| 通知发送（IDEA 原生）         | `utils/OpenCodeNotificationService.kt`                         |
| 通知路由（事件→类型）         | `utils/OpenCodeNotificationRouter.kt`                          |
| 通知配置/状态                 | `utils/OpenCodeConfig.kt`                                      |
| JCEF JS 注入                  | `utils/JcefJsInjector.kt`                                      |
| IdeaVim visual 模式选区       | `utils/IdeaVimIntegration.kt`                                  |
| 复制为 Prompt 格式            | `actions/CopyAsPromptAction.kt`                                |
| 选中代码 → Prompt 编辑器      | `actions/AddToPromptAction.kt`                                 |
| 端口/超时/间隔常量            | `OpenCodeConstants.kt`                                         |
| Settings UI                   | `settings/OpenCodeConfigurable.kt`                             |

## HARD RULES

- **包名不匹配（已知）**: 源码在 `com.github.xausky.opencodewebui`，但 `pluginGroup=com.shenyuanlaolarou`。`plugin.xml` 用 FQN 引用类，不要"修复"成 `com.shenyuanlaolarou.*`
- **禁止静态全局可变状态**: 用 `AtomicReference<Process>` / `AtomicBoolean` / `@Volatile`，不要 `object` 里挂 `var`
- **禁止 Regex 解析 HTTP/JSON**: 所有 HTTP 响应体走 Gson（或同等 JSON 解析器），禁止 `Regex` / `Pattern` / 字符串切割取字段
- **Git 提交/push**: AI 禁止自动 commit/push，必须用户显式调用（项目根另有 `git-commit-block` rule）
- **发布**: AI 禁止自动 `publishPlugin`，必须用户显式调用
- **Type 抑制**: 禁止 `as Any` / `@ts-ignore` / `@ts-expect-error`（Kotlin 实际无此语法，但等价物同样禁用）

## 通知降噪设计决策（`OpenCodeSSEConsumer`）

读这块代码前必须理解 3 条规则：

1. **父 session complete 抑制**: 用 `sessionIdleFired` 集合抑制 agent 循环中的重复 idle 通知；重置信号是 `message.updated(role=user)`，不是 `session.status(busy)` —— 这样 agent 循环中的中间 idle 不会重复通知
2. **`session.deleted` 不移除追踪**: 不在 `session.deleted` 中移除 `subagentSessionIds`，避免时序竞态导致子 agent idle 被误判为 `complete`。该集合仅在 SSE 重连时通过 `onClosed()` 清空
3. **原则 2 通知不受影响**: `permission.asked` / `question.asked` / `session.next.tool.called(tool=question)` 是 `when(eventType)` 的独立分支，**不受** `sessionIdleFired` 抑制逻辑控制

## UNIQUE STYLES / 约定

- 端口 **12396**（非标准，避免与 4096 冲突）
- 首次打开工具窗口时自动重启服务器，确保 opencode 是最新版本
- 外部链接走系统浏览器
- 会话恢复通过 HTTP API（`OpenCodeApi.getLatestSessionId`），不用 SQLite/JDBC
- 工具窗口锚定 `right`，实现 `DumbAware`
- 多项目实例用 `ConcurrentHashMap<Project, MyToolWindow>` 管理
- 共享 JBCefClient 实例（`sharedJBCefClient`）
- `AddToPromptAction` 中 im-select 路径和输入法 ID 是硬编码（待重构为可配置）
- 日志: `thisLogger().info/warn/error()`；日志文件: `build/idea-sandbox/IU-2026.1/log/`

## OPENSPEC 工作流（重要）

本仓库用 **OpenSpec** 管理所有变更（不只是文档）。CLI: `openspec`（已安装 v1.4.0）。

```
openspec/
├── changes/                       # 进行中的 change
│   ├── <change-name>/
│   │   ├── proposal.md            # Why / What / Capabilities / Impact
│   │   ├── design.md              # 技术设计
│   │   ├── tasks.md               # 实现任务清单
│   │   ├── specs/<capability>/spec.md   # 增量 spec（delta）
│   │   └── research/              # 调研材料
│   └── archive/                   # 已完成的 change
└── specs/                         # 已同步到主 spec 的 capability
    ├── idle-notification-suppression/
    └── subagent-complete-detection-fix/
```

**触发方式**: 用户说"创建 change / 继续 / 实现 / 验证 / 归档"等任何 OpenSpec 意图时，**必须调用对应 skill**（`.opencode/skills/openspec-*`），禁止自编自写 artifact。

| 用户意图    | 调用的 skill               | 一次性生成                                            |
| ----------- | -------------------------- | ----------------------------------------------------- |
| 新建 change | `openspec-new-change`      | 1 个 artifact（**生成完立即停下等 review**）          |
| 继续/下一步 | `openspec-continue-change` | 1 个 artifact（**生成完立即停下等 review**）          |
| 全部生成    | `openspec-ff-change`       | 所有剩余（**仅 `/openspec-ff-change` 斜杠命令触发**） |
| 开始实现    | `openspec-apply-change`    | 逐个 task                                             |
| 验证        | `openspec-verify-change`   | -                                                     |
| 归档        | `openspec-archive-change`  | -                                                     |
| 探索        | `openspec-explore`         | -                                                     |
| 同步 specs  | `openspec-sync-specs`      | -                                                     |

**FF 模式铁律**: 任何自然语言措辞（包括"全部生成"、"一次性"、"跳过 review"、"一口气"）都**不构成** ff 触发。ff 只能通过用户显式调用 `/openspec-ff-change` 斜杠命令进入。

**禁止**: 跳过 skill 直接生成 artifact 文件 / 自创 todo 列表冒充 change。

`.opencode/skills/` 里有 11 个项目级 openspec-\* skills，可直接 `skill(name="openspec-new-change")` 等加载。

## COMMANDS

```bash
./gradlew buildPlugin           # 构建插件 zip（输出 build/distributions/）
./gradlew check                  # 单元测试 + Kover + Qodana
./gradlew verifyPlugin           # 验证插件结构
./gradlew runIde                 # 启动带插件的 IDE（开发调试）
./gradlew runIdeForUiTests       # UI 测试专用 IDE（robot-server 端口 8082）
./gradlew publishPlugin          # 发布到 Marketplace（需 env: PUBLISH_TOKEN, CERTIFICATE_CHAIN, PRIVATE_KEY, PRIVATE_KEY_PASSWORD）
./gradlew qodana                 # 代码质量（独立任务）
./gradlew patchChangelog         # CHANGELOG.md → publishPlugin 自动依赖
```

**签名/发布 env**（定义在 `local.properties`，已 gitignore）: `PUBLISH_TOKEN`、`CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`。`cert/` 目录也在 gitignore。本地签名材料绝不能进 git。

**单测**: 走 IntelliJ Platform TestFramework。`./gradlew check` 跑全部；要跑单个测试类，目前配置下用 IDE 测试运行器或 `./gradlew check --tests "<FQCN>"`（需先确认 `test` 任务的 filter 配置）。

## TESTING

| 类型     | 框架                                     | 位置                                                 |
| -------- | ---------------------------------------- | ---------------------------------------------------- |
| 单元测试 | JUnit 4 + opentest4j                     | `src/test/.../actions/FormatAsPromptTest.kt`         |
|          |                                          | `src/test/.../listeners/SSEEventParserTest.kt`       |
| 集成测试 | `BasePlatformTestCase` + `@TestDataPath` | `src/test/.../MyPluginTest.kt`                       |
| UI 测试  | Robot Server（`runIdeForUiTests`）       | CI: `run-ui-tests.yml` workflow（本地无 `.github/`） |

**CI 状态**: `.github/` 在 gitignore —— 本地没有 workflows 目录。所有 CI（Build / Publish / UI Tests）由**上游仓库** `syllr/intellij-opencode-web` GitHub Actions 跑。改 workflow 不在本仓库内。

## DEBUGGING

- 插件日志: `build/idea-sandbox/IU-2026.1/log/idea.log`（`./gradlew runIde` 启动后生成）
- JCEF 浏览器日志级别在 `JcefJsInjector` / `BrowserPanel` 中控制
- HTTP/SSE 错误: 用 Gson 解析错误体，定位到 `OpenCodeApi` / `SSEEventParser`

## ANTI-PATTERNS THAT ARE NOW FIXED（不要再走回头路）

- ~~静态全局服务器状态~~ → `AtomicReference<Process>` + `AtomicBoolean`
- ~~弃用的 `JBCefBrowser`~~ → `JBCefBrowserBuilder`
- ~~SQLite JDBC 会话管理~~ → HTTP API 持久化 session
- ~~单文件 900+ 行（MyToolWindowFactory）~~ → 拆成 `toolWindow/` 9 个文件
- ~~正则解析 HTTP body~~ → 统一 Gson
- ~~JCEF GPU 在多 IDE 下导致 CPU 飙升（`OpenCodeCefArgsProvider`）~~ → 1.0.18 启用 Metal ANGLE，旧方案已 revert

## REFERENCES

- 官方开发指南: https://plugins.jetbrains.com/docs/intellij/
- JCEF 文档: https://chromiumembedded.github.io/java-cef/
- 本地参考: `references/intellij-platform/`（含 `PLATFORM_ACTIONS_SUMMARY.md`）
- 上游 CI: `syllr/intellij-opencode-web` GitHub Actions
