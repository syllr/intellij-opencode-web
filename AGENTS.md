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

> 详细阅读顺序与文档关系见 `SPEC.md §8`。

## STACK

- Gradle 9.3.1 · JDK 21 · Kotlin 2.3.20 · IntelliJ Platform Gradle Plugin 2.14.0
- 目标平台: 2026.1（`pluginSinceBuild=261`，`pluginUntilBuild` 留空）
- 运行依赖: Gson 2.10.1（JSON）、okhttp-eventsource 4.3.0（SSE）
- 测试依赖: JUnit 4.13.2、opentest4j 1.3.0、mockito 5.19.0、mockito-kotlin 6.3.0
- 静态分析: Qodana（linter `jetbrains/qodana-jvm-community:2024.3`，Gradle plugin `2025.3.1`）+ Kover 0.9.5 + CodeCov
- 版本号在 `gradle.properties` 的 `pluginVersion`（当前 `1.0.22`）；分支名常与 `pluginVersion` 不同步，不要据此推断
- `CHANGELOG.md` 由 `org.jetbrains.changelog` Gradle 插件（`patchChangelog` 任务）管理，不要手改

## STRUCTURE

源码根: `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/`

| 目录                           | 内容                                                                             |
| ------------------------------ | -------------------------------------------------------------------------------- |
| `toolWindow/`                  | JCEF 浏览器 + 服务器进程管理 + 键盘拦截 + Singleflight（核心，8 文件）           |
| `listeners/`                   | SSE 事件消费 + 文件刷新协调（4 文件）                                            |
| `actions/`                     | IDE Actions：Copy as Prompt / Add to Prompt（2 文件）                            |
| `utils/`                       | HTTP API、JS 注入、通知路由/服务/配置、IdeaVim 集成、SSE consumer 工厂（8 文件） |
| 根                             | `OpenCodeConstants`（端口/超时/间隔常量）+ `MyBundle`（i18n bundle）             |
| `src/main/resources/META-INF/` | `plugin.xml`：toolWindow + notificationGroup 注册                                |

> **历史目录已删除**：
>
> - `settings/OpenCodeConfigurable.kt` — commit `a5eafc4`（1.0.20）整删；`plugin.xml` 无 `applicationConfigurable` 注册
> - `toolWindow/OpenCodeProjectActivity.kt` — commit `95d7faf`（HEAD）整删
> - `toolWindow/HealthMonitor.kt` — commit `2e7b302`（Part D）整删；功能并入 `OpenCodeSSEConsumer.onConnectionLost/Established`

测试根: `src/test/kotlin/.../{actions,utils,listeners,toolWindow}/`（16 个测试文件，JUnit 4）

## WHERE TO LOOK

| 想做的事                                            | 改哪里                                                                 |
| --------------------------------------------------- | ---------------------------------------------------------------------- |
| **高频改动入口**                                    |                                                                        |
| 工具窗口入口/布局/JCEF 焦点恢复                     | `toolWindow/MyToolWindowFactory.kt`（含 `resetToolWindow()` 焦点恢复） |
| 浏览器面板/生命周期                                 | `toolWindow/MyToolWindow.kt` + `BrowserPanel.kt`                       |
| 服务器进程启停（4 阶段关闭）                        | `toolWindow/OpenCodeServerManager.kt`                                  |
| Singleflight 防抖（手动启动去重）                   | `toolWindow/Singleflight.kt`                                           |
| SSE 消费、降噪、自动重连（替代旧 HealthMonitor）    | `listeners/OpenCodeSSEConsumer.kt`                                     |
| **键盘 / 输入**                                     |                                                                        |
| 键盘拦截（ESC/Cmd+K/Cmd+,）                         | `toolWindow/JcefKeyboardInterceptor.kt`                                |
| Emacs 风格按键（Ctrl+N/P/E/A/B/F）                  | `toolWindow/EmacsKeyHandler.kt`                                        |
| JCEF 右键菜单（Reset/Refresh/Back）                 | `toolWindow/LinkContextMenuHandler.kt`                                 |
| Add to Prompt 含 im-select 硬编码（macOS IME 切换） | `actions/AddToPromptAction.kt`                                         |
| **通知 / 事件**                                     |                                                                        |
| SSE 解析（含 3 级 `extractSessionID` fallback）     | `listeners/SSEEventParser.kt`                                          |
| Bash 工具事件检测                                   | `listeners/BashCommandHandler.kt`                                      |
| 文件刷新（生产者-消费者）                           | `listeners/FullRefreshCoordinator.kt`                                  |
| HTTP API（健康/session 等）                         | `utils/OpenCodeApi.kt` + `OpenCodeApiResult.kt`                        |
| 通知发送（IDEA 原生 + macOS 系统）                  | `utils/OpenCodeNotificationService.kt`                                 |
| 通知路由（10 行直通薄层）                           | `utils/OpenCodeNotificationRouter.kt`                                  |
| 通知配置（4 个通用设置，无 UI）                     | `utils/OpenCodeConfig.kt`                                              |
| SSE consumer 单例工厂                               | `utils/SSEConsumerFactory.kt`                                          |
| **配置 / 常量**                                     |                                                                        |
| JCEF JS 注入                                        | `utils/JcefJsInjector.kt`                                              |
| IdeaVim visual 模式选区（注入 JS 路径）             | `utils/IdeaVimIntegration.kt`                                          |
| 复制为 Prompt 格式                                  | `actions/CopyAsPromptAction.kt`                                        |
| 端口/超时/间隔常量（端口 12396）                    | `OpenCodeConstants.kt`                                                 |

## HARD RULES

- **包名 = `com.shenyuanlaolarou.opencodewebui`**: 本仓库规范包名，**也**是 `pluginGroup` / `plugin id` / `vendor` 的命名空间。新代码必须用 `com.shenyuanlaolarou.*`
- **禁止静态全局可变状态**: 用 `AtomicReference<Process>` / `AtomicBoolean` / `@Volatile`，不要 `object` 里挂 `var`
- **禁止 Regex 解析 HTTP/JSON**: 所有 HTTP 响应体走 Gson（或同等 JSON 解析器），禁止 `Regex` / `Pattern` / 字符串切割取字段
- **Git 提交/push**: AI 禁止自动 commit/push，必须用户显式调用（受 OpenCode 全局 `git-commit-block` rule 约束）
- **发布**: AI 禁止自动 `publishPlugin`，必须用户显式调用
- **Type 抑制**: 禁止 `as Any` / Kotlin 等价的 type erase 绕过 / 滥用 `!!` 强转。`!!` 只在 IntelliJ Platform 契约保证非空的场景下允许（如 `createComponent` 先于 `isModified` 调用）

## COMMANDS

```bash
./gradlew runIde                  # 启动带插件的 IDE（开发调试，最常用）
./gradlew runIdeForUiTests        # UI 测试专用 IDE（robot-server 端口 8082）
./gradlew check                   # 单元测试 + Kover + Qodana
./gradlew buildPlugin             # 构建插件 zip（输出 build/distributions/）
./gradlew verifyPlugin            # 验证插件结构
./gradlew qodana                  # 代码质量（独立任务）
./gradlew patchChangelog          # CHANGELOG.md → publishPlugin 自动依赖
./gradlew publishPlugin           # 发布到 Marketplace（需 env: PUBLISH_TOKEN 等）
```

**签名/发布 env**（定义在 `local.properties`，已 gitignore）: `PUBLISH_TOKEN`、`CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`。`cert/` 也在 gitignore。**绝对不能**进 git。**优先用环境变量**（Gradle `providers.environmentVariable()` 已支持），不要长期把私钥/令牌明文落在磁盘。

**单测**: 走 IntelliJ Platform TestFramework。`./gradlew check` 跑全部；要跑单个测试类用 `./gradlew check --tests "<FQCN>"`。

**调试日志**: `build/idea-sandbox/IU-2026.1/log/idea.log`（`./gradlew runIde` 启动后生成）。JCEF 浏览器日志级别在 `JcefJsInjector` / `BrowserPanel` 中控制。HTTP/SSE 错误用 Gson 解析体，定位到 `OpenCodeApi` / `SSEEventParser`。

## TESTING + CI

| 类型     | 框架                               | 位置                                                                                                                                                                                                                   |
| -------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 单元测试 | JUnit 4 + opentest4j + mockito     | `src/test/.../actions/FormatAsPromptTest.kt`<br>`src/test/.../listeners/`（9 个 SSE/parser 测试）<br>`src/test/.../utils/`（Notification/SessionInfo/ApiResult 测试）<br>`src/test/.../toolWindow/SingleflightTest.kt` |
| 集成测试 | `BasePlatformTestCase`             | `src/test/.../MyPluginTest.kt`                                                                                                                                                                                         |
| UI 测试  | Robot Server（`runIdeForUiTests`） | 远程 CI `syllr/intellij-opencode-web` 跑（`.github/` 在 gitignore，本地无）                                                                                                                                            |

CI 全在外部仓库 `syllr/intellij-opencode-web` GitHub Actions，本仓库无 `.github/`。改 workflow 不在本仓库内。

## UNIQUE STYLES / 约定

- 端口 **12396**（非标准，避免与 4096 冲突）
- **手动启动 server**：工具窗口只显示 "Start OpenCode Server" 按钮，不自动启动/重启；并发/重复点击经 Go-style singleflight 合并为单次进程启动（`Singleflight.kt` leader/follower 模式 + `MyToolWindow.isServerReadyHandled` CAS 守卫）
- SSE 主动关闭 → 立即显示 Start 按钮（快速通道，`onConnectionLost` 回调）；SSE 重建后 1.5s debounce 自动恢复 UI（恢复通道，`onConnectionEstablished`）
- 外部链接走系统浏览器
- 会话加载**不**自动恢复；由用户在 web UI 内手动选 session。URL `/$base64dir/session/$id` 支持加载指定 session（API 在 `OpenCodeApi.getSession`）
- 工具窗口锚定 `right`，实现 `DumbAware`
- 焦点恢复：用户切到 OpenCodeWeb tab 时 `ToolWindowManagerListener` 自动调用 `resetToolWindow`（1.5s `AtomicLong` 防抖）
- 多项目实例用 `ConcurrentHashMap<Project, MyToolWindow>` 管理
- 共享 JBCefClient 实例（`sharedJBCefClient`）
- 每 Project 独立 `OpenCodeSSEConsumer` 实例（`SSEConsumerFactory.create(project)`），独立维护 `sessionTitles` / `idleNotifiedSessions` per-consumer 状态
- `AddToPromptAction` 中 `IM_SELECT_PATH` 和 `IM_SELECT_ARG_EN` 是硬编码（macOS 输入法切换，仅开发者本机有效；其他开发者 clone 后 im-select 不存在时降级为 noop，待重构为可配置，SPEC GAP-3）
- 日志: `thisLogger().info/warn/error()`；日志前缀 `[<类名>] <消息>`（SPEC §7.6）
- `OpenCodeApiResult` sealed class（`Success` / `Failure` / `Unavailable` / `Unauthorized`）统一 HTTP 调用错误处理，调用方用 `.dataOrNull()` 取值
- SSE watchdog: `lastEventAt` 30s 没更新 → 强制重连（`SSE_WATCHDOG_INTERVAL_MS=5s`，`SSE_IDLE_TIMEOUT_MS=30s`）

## ANTI-PATTERNS THAT ARE NOW FIXED（不要再走回头路）

- ~~静态全局服务器状态~~ → `AtomicReference<Process>` + `AtomicBoolean`
- ~~弃用的 `JBCefBrowser`~~ → `JBCefBrowserBuilder`
- ~~SQLite JDBC 会话管理~~ → HTTP API 持久化 session
- ~~单文件 900+ 行（MyToolWindowFactory）~~ → 拆成 `toolWindow/` 8 个文件
- ~~正则解析 HTTP body~~ → 统一 Gson
- ~~`OpenCodeCefArgsProvider` JCEF GPU 在多 IDE 下 CPU 飙升~~ → 1.0.18 启用 Metal ANGLE，旧方案已 revert
- ~~多端口（按 IDE 类型分配 12396-12412）~~ → 全部用 12396。多 server 并发反而更卡（实测确认）
- ~~`HealthMonitor.kt` 5s 轮询健康检查~~ → 改由 `OpenCodeSSEConsumer.onConnectionLost/Established` SSE 回调替代（Part D, commit `2e7b302`）
- ~~Settings 配置面板 `OpenCodeConfigurable` + `applicationConfigurable` 注册~~ → 整删（1.0.20, commit `a5eafc4`）
- ~~首次打开工具窗口自动重启服务器~~ → 改为手动点击 + Singleflight 防抖（1.0.20, commit `685cc9b`）
- ~~`OpenCodeProjectActivity` + 焦点监听机制~~ → 整删（HEAD, commit `95d7faf`）

## 注意事项（容易踩的坑）

- **`local.properties` 已 gitignore 但含明文 RSA 私钥 + 完整证书链 + 发布令牌**。安全风险持续存在；改用环境变量（`build.gradle.kts` 已支持 `providers.environmentVariable()`）
- **`.omo/` 已在 `.gitignore` 中，但 10 个文件（2 plans + 8 `run-continuation/ses_*.json`）已意外提交**。要清理需 `git rm --cached -r .omo/`
- **`research/archive/`** 是已归档的规划文档（`idea-plugin-integration/` 9 个 + `performance/` 2 个），**不要**当作当前代码参考
- **`openspec/` 目录已删除**（commit `95d7faf` HEAD）：所有 `openspec/specs/<capability>/spec.md` 和 `openspec/changes/<change>/` 引用全部失效。改动 capability 行为时改 `SPEC.md` / `DESIGN.md` 即可
- **JCEF 焦点问题**（"OpenCodeWeb 面板键盘不响应 / 输入法卡顿"）唯一的实测有效恢复手段是 `MyToolWindowFactory.resetToolWindow(project)`（hide + activate ToolWindow）。`requestBrowserFocus()`（仅调 Swing `requestFocus()`）和 `FocusAdapter` 方向都**实测无效**，**不要**再尝试。`MyToolWindowFactory` 已预留 `ToolWindowManagerListener` 接入点（500ms `lastResetAt` 防抖），未来如需自动化按 SPEC §3.3 / DESIGN §1.1 描述的伪代码接入

## REFERENCES

- 官方开发指南: https://plugins.jetbrains.com/docs/intellij/
- JCEF 文档: https://chromiumembedded.github.io/java-cef/
- 本地参考: `references/intellij-platform/`（含 `PLATFORM_ACTIONS_SUMMARY.md`）
- 本仓库 CI: `syllr/intellij-opencode-web` GitHub Actions
