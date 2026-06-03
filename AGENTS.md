# AGENTS.md

IntelliJ Platform 插件 (Kotlin)，为 OpenCode Web UI 提供 JetBrains IDE 集成。

## 项目级元文档

本项目根目录下三个大写元文档构成项目宪法 / 设计 / 规格三元组，AI agent 必须根据任务类型主动阅读对应文档：

| 文档                         | 关注                                                | 何时读                                            |
| ---------------------------- | --------------------------------------------------- | ------------------------------------------------- |
| **[AGENTS.md](./AGENTS.md)** | 怎么开发（流程规则、强制约束、踩坑经验）            | **第一份必读** — 任何任务开始前                   |
| **[SPEC.md](./SPEC.md)**     | 必须满足什么（SLA、安全契约、数据一致性、部署约束） | Code Review / QA / 实施可能影响 §1-7 规范的变更前 |
| **[DESIGN.md](./DESIGN.md)** | 怎么实现（架构设计、组件关系、关键决策）            | 改架构 / 加新模块 / 排查跨子系统问题时            |

**阅读顺序建议**：

1. 新加入任务 → 先读 `AGENTS.md`（开发流程、强制约束）
2. 涉及性能 / 安全 / SLA / 数据一致性 → 读 `SPEC.md` 对应章节
3. 涉及架构或子系统 → 读 `DESIGN.md` 对应章节
4. 修改单个 capability → 读 `openspec/specs/<capability>/spec.md`

> 详细阅读顺序与文档关系见 `SPEC.md` §0。

## STACK

- Gradle 9.3.1 · JDK 21 · Kotlin 2.3.20 · IntelliJ Platform Gradle Plugin 2.14.0
- 目标平台: 2026.1（`pluginSinceBuild=261`，`pluginUntilBuild` 留空）
- 依赖: Gson 2.10.1（JSON）、okhttp-eventsource 4.1.0（SSE）、JUnit 4.13.2
- Qodana（`qodana-jvm-community:2024.3`）+ Kover 0.9.5 + CodeCov
- 版本号在 `gradle.properties` 的 `pluginVersion`；分支名（如 `1.0.18`）常与 `pluginVersion` 不同步，不要据此推断
- `CHANGELOG.md` 由 `org.jetbrains.changelog` Gradle 插件管理（`patchChangelog` 任务），不要手改

## STRUCTURE

源码根: `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/`

| 目录                           | 内容                                                                        |
| ------------------------------ | --------------------------------------------------------------------------- |
| `toolWindow/`                  | JCEF 浏览器 + 服务器进程管理 + 键盘拦截（核心，9 文件）                     |
| `listeners/`                   | SSE 事件消费 + 文件刷新协调（4 文件）                                       |
| `actions/`                     | IDE Actions：复制/添加到 Prompt、IdeaVim 集成（2 文件）                     |
| `utils/`                       | HTTP API、JS 注入、通知路由/服务、配置（8 文件）                            |
| `settings/`                    | Settings UI：`OpenCodeConfigurable`（1 文件）                               |
| 根                             | `OpenCodeConstants`（端口 12396 等）+ `MyBundle`（i18n）                    |
| `src/main/resources/META-INF/` | `plugin.xml`：toolWindow / notificationGroup / applicationConfigurable 注册 |

测试根: `src/test/kotlin/.../{actions,utils,listeners}/`（JUnit 4）+ `MyPluginTest`（`BasePlatformTestCase`）

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

- **包名 = `com.shenyuanlaolarou.opencodewebui`**: 本仓库规范包名，**也**是 `pluginGroup`/`plugin id`/`vendor` 的命名空间。新代码必须用 `com.shenyuanlaolarou.*`
- **禁止静态全局可变状态**: 用 `AtomicReference<Process>` / `AtomicBoolean` / `@Volatile`，不要 `object` 里挂 `var`
- **禁止 Regex 解析 HTTP/JSON**: 所有 HTTP 响应体走 Gson（或同等 JSON 解析器），禁止 `Regex` / `Pattern` / 字符串切割取字段
- **Git 提交/push**: AI 禁止自动 commit/push，必须用户显式调用（项目根另有 `git-commit-block` rule）
- **发布**: AI 禁止自动 `publishPlugin`，必须用户显式调用
- **Type 抑制**: 禁止 `as Any` / `@ts-ignore` / `@ts-expect-error`（Kotlin 实际无此语法，但等价物同样禁用）

## 通知降噪 + 关闭策略（指针）

详细决策已搬到项目级元文档：

- **通知降噪三层去重** → `SPEC.md §3.1` + `DESIGN.md §4.2` + capability spec `openspec/specs/idle-notification-suppression/spec.md` + `openspec/specs/subagent-complete-detection-fix/spec.md`
- **关闭策略 4 阶段(`gracefulShutdown`)** → `SPEC.md §6.3` + `DESIGN.md §3.3` + `OpenCodeServerManager.gracefulShutdown` 共享实现
- **关键不变量** → `SPEC.md §3.1.1` (subagentSessionIds) / `§3.1.2` (sessionIdleFired) / `§3.1.3` (idleLastFired)

读这两块代码前**必须**先看 `SPEC.md` §3(数据一致性)和 `DESIGN.md` §4(核心机制)。

- **通知降噪三层去重** → `SPEC.md §7.1` + `DESIGN.md §4.2` + capability spec `openspec/specs/idle-notification-suppression/spec.md` + `openspec/specs/subagent-complete-detection-fix/spec.md`
- **关闭策略 4 阶段** → `SPEC.md §6.3` + `DESIGN.md §3.3` + `OpenCodeServerManager.gracefulShutdown` 共享实现

读这两块代码前**必须**先看 `SPEC.md` §3（数据一致性）和 `DESIGN.md` §4（核心机制）。

## OPENSPEC 工作流

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

## COMMANDS

```bash
./gradlew buildPlugin           # 构建插件 zip（输出 build/distributions/）
./gradlew check                  # 单元测试 + Kover + Qodana
./gradlew verifyPlugin           # 验证插件结构
./gradlew runIde                 # 启动带插件的 IDE（开发调试）
./gradlew runIdeForUiTests       # UI 测试专用 IDE（robot-server 端口 8082）
./gradlew publishPlugin          # 发布到 Marketplace（需 env: PUBLISH_TOKEN 等）
./gradlew qodana                 # 代码质量（独立任务）
./gradlew patchChangelog         # CHANGELOG.md → publishPlugin 自动依赖
```

**签名/发布 env**（定义在 `local.properties`，已 gitignore）: `PUBLISH_TOKEN`、`CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`。`cert/` 也在 gitignore。**绝对不能**进 git。**优先用环境变量**（Gradle `providers.environmentVariable()` 已支持），不要长期把私钥/令牌明文落在磁盘。

**单测**: 走 IntelliJ Platform TestFramework。`./gradlew check` 跑全部；要跑单个测试类用 `./gradlew check --tests "<FQCN>"`。

**调试日志**: `build/idea-sandbox/IU-2026.1/log/idea.log`（`./gradlew runIde` 启动后生成）。JCEF 浏览器日志级别在 `JcefJsInjector` / `BrowserPanel` 中控制。HTTP/SSE 错误用 Gson 解析体，定位到 `OpenCodeApi` / `SSEEventParser`。

## TESTING + CI

| 类型     | 框架                               | 位置                                                 |
| -------- | ---------------------------------- | ---------------------------------------------------- |
| 单元测试 | JUnit 4 + opentest4j               | `src/test/.../actions/FormatAsPromptTest.kt`         |
|          |                                    | `src/test/.../listeners/SSEEventParserTest.kt`       |
| 集成测试 | `BasePlatformTestCase`             | `src/test/.../MyPluginTest.kt`                       |
| UI 测试  | Robot Server（`runIdeForUiTests`） | CI: `run-ui-tests.yml` workflow（本地无 `.github/`） |

**CI**: `.github/` 在 gitignore —— 本地没有 workflows 目录。所有 CI（Build / Publish / UI Tests）由**本仓库** `syllr/intellij-opencode-web` GitHub Actions 跑。改 workflow 不在本仓库内。

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
- `OpenCodeApiResult` sealed class（`Success` / `Failure` / `Unavailable` / `Unauthorized`）统一 HTTP 调用错误处理，调用方用 `.dataOrNull()` 取值
- SSE watchdog: `lastEventAt` 30s 没更新 → 强制重连（`SSE_WATCHDOG_INTERVAL_MS=5s`, `SSE_IDLE_TIMEOUT_MS=30s`）

## ANTI-PATTERNS THAT ARE NOW FIXED（不要再走回头路）

- ~~静态全局服务器状态~~ → `AtomicReference<Process>` + `AtomicBoolean`
- ~~弃用的 `JBCefBrowser`~~ → `JBCefBrowserBuilder`
- ~~SQLite JDBC 会话管理~~ → HTTP API 持久化 session
- ~~单文件 900+ 行（MyToolWindowFactory）~~ → 拆成 `toolWindow/` 9 个文件
- ~~正则解析 HTTP body~~ → 统一 Gson
- ~~JCEF GPU 在多 IDE 下导致 CPU 飙升（`OpenCodeCefArgsProvider`）~~ → 1.0.18 启用 Metal ANGLE，旧方案已 revert
- ~~多端口（按 IDE 类型分配 12396-12412）~~ → 全部用 12396。多 server 并发反而更卡（实测确认）

## 注意事项（容易踩的坑）

- **`local.properties` 已 gitignore 但含明文 RSA 私钥 + 完整证书链 + 发布令牌**。安全风险持续存在；改用 env vars 消除
- **`.omo/` 已在 `.gitignore` 中，但 10 个文件（2 plans + 8 `run-continuation/ses_*.json`）已意外提交**。要清理需 `git rm --cached -r .omo/`
- **`research/archive/`** 是已归档的规划文档（idea-plugin-integration 9 个、performance 2 个），**不要**当作当前代码参考来读
- **`research/jcef-focus-ime/` 是当前活跃参考**：描述的 `FocusAdapter` / `cefBrowser.setFocus(true)` 修复**尚未实施**，`requestBrowserFocus()` 仍只调 Swing `requestFocus()`

## REFERENCES

- 官方开发指南: https://plugins.jetbrains.com/docs/intellij/
- JCEF 文档: https://chromiumembedded.github.io/java-cef/
- 本地参考: `references/intellij-platform/`（含 `PLATFORM_ACTIONS_SUMMARY.md`）
- 本仓库 CI: `syllr/intellij-opencode-web` GitHub Actions
