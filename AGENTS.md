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

| 目录                           | 内容                                                                                                  |
| ------------------------------ | ----------------------------------------------------------------------------------------------------- |
| `toolWindow/`                  | Dashboard 工具窗口 + 服务器进程管理 + Edge --app 启动器 + Singleflight（6 文件）                      |
| `listeners/`                   | SSE 事件消费 + 文件刷新协调（4 文件）                                                                 |
| `actions/`                     | IDE Actions：Copy as Prompt（1 文件）                                                                 |
| `utils/`                       | HTTP API、IdeaVim 集成、SSE consumer 工厂（4 文件;v2.0.0+ 通知 service/router/OpenCodeConfig 已整删） |
| 根                             | `OpenCodeConstants`（端口/超时/间隔常量）+ `MyBundle`（i18n bundle）                                  |
| `src/main/resources/META-INF/` | `plugin.xml`：toolWindow 注册                                                                         |

> **历史目录已删除**：
>
> - `settings/OpenCodeConfigurable.kt` — commit `a5eafc4`（1.0.20）整删；`plugin.xml` 无 `applicationConfigurable` 注册
> - `toolWindow/OpenCodeProjectActivity.kt` — commit `95d7faf`（HEAD 之前）整删
> - `toolWindow/HealthMonitor.kt` — commit `2e7b302`（Part D）整删；功能并入 `OpenCodeSSEConsumer.onConnectionLost/Established`
> - **v2.0.0 砍掉**：6 个 JCEF 源文件（`BrowserPanel.kt` / `JcefKeyboardInterceptor.kt` / `EmacsKeyHandler.kt` / `LinkContextMenuHandler.kt` / `JcefJsInjector.kt` / `ResizeObserverThrottler.kt`）+ 1 个 action（`AddToPromptAction.kt`）+ 2 个偏离方案文件（`OpenInBrowserAction.kt` / `CleanBrowserLauncher.kt`）+ 6 个相关测试。OpenCode Web UI 改在外部 Edge --app 模式跑,见 `DESIGN.md §0.1` + `§1.2` 决策表 |

测试根: `src/test/kotlin/.../{actions,utils,listeners,toolWindow}/`（16 个测试文件，JUnit 4）

## WHERE TO LOOK

| 想做的事                                      | 改哪里                                                                            |
| --------------------------------------------- | --------------------------------------------------------------------------------- |
| **高频改动入口**                              |                                                                                   |
| 工具窗口入口 + Dashboard 内容                 | `toolWindow/MyToolWindowFactory.kt` + `toolWindow/MyToolWindow.kt`(纯 Swing 6 区) |
| 服务器进程启停(4 阶段关闭)+ port 冲突自动升级 | `toolWindow/OpenCodeServerManager.kt`(M2-T1 health gate)                          |
| 进程启动(cwd = project.basePath)              | `toolWindow/ServerProcessLauncher.kt`(M0-T1)                                      |
| Edge --app 启动器(日常 profile 复用)          | `toolWindow/OpenCodeBrowserLauncher.kt`(M1-T2)                                    |
| Singleflight 防抖(手动启动去重)               | `toolWindow/Singleflight.kt`                                                      |
| SSE 消费、自动重连(替代旧 HealthMonitor)      | `listeners/OpenCodeSSEConsumer.kt`(v2.0.0+ 仅文件刷新 + 心跳,通知事件在白名单外)  |
| **文件 / 事件**                               |                                                                                   |
| SSE 解析(5 事件白名单)                        | `listeners/SSEEventParser.kt`                                                     |
| Bash 工具事件检测                             | `listeners/BashCommandHandler.kt`                                                 |
| 文件刷新(生产者-消费者)                       | `listeners/FullRefreshCoordinator.kt`                                             |
| HTTP API(健康/session 等)                     | `utils/OpenCodeApi.kt` + `OpenCodeApiResult.kt`                                   |
| SSE consumer 单例工厂                         | `utils/SSEConsumerFactory.kt`                                                     |
| **配置 / 常量**                               |                                                                                   |
| IdeaVim visual 模式选区(反射缓存)             | `utils/IdeaVimIntegration.kt`                                                     |
| 复制为 Prompt 格式                            | `actions/CopyAsPromptAction.kt`(M1-T5 合并 `AddToPromptAction` 后)                |
| 端口/超时/间隔常量(端口 12396)                | `OpenCodeConstants.kt`(M2-T1 加 `HEALTH_VERIFY_TIMEOUT_MS = 500L`)                |

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

**调试日志**: `build/idea-sandbox/IU-2026.1/log/idea.log`（`./gradlew runIde` 启动后生成）。HTTP/SSE 错误用 Gson 解析体，定位到 `OpenCodeApi` / `SSEEventParser`。

## TESTING + CI

| 类型     | 框架                               | 位置                                                                                                                                                                                                                   |
| -------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 单元测试 | JUnit 4 + opentest4j + mockito     | `src/test/.../actions/FormatAsPromptTest.kt`<br>`src/test/.../listeners/`（9 个 SSE/parser 测试）<br>`src/test/.../utils/`（Notification/SessionInfo/ApiResult 测试）<br>`src/test/.../toolWindow/SingleflightTest.kt` |
| 集成测试 | `BasePlatformTestCase`             | `src/test/.../MyPluginTest.kt`                                                                                                                                                                                         |
| UI 测试  | Robot Server（`runIdeForUiTests`） | 远程 CI `syllr/intellij-opencode-web` 跑（`.github/` 在 gitignore，本地无）                                                                                                                                            |

CI 全在外部仓库 `syllr/intellij-opencode-web` GitHub Actions，本仓库无 `.github/`。改 workflow 不在本仓库内。

## UNIQUE STYLES / 约定

- 端口 **12396**（非标准，避免与 4096 冲突）
- **Edge 启动必传** `--disable-sync`（在 `OpenCodeBrowserLauncher.buildOpenCommand` / `buildProcessBuilderCommand` 中已固化） — Edge Sync 服务会把"日常 Edge 已登录的微软账号"通过云端广播到新 `--user-data-dir` profile；`--user-data-dir` 文件层隔离无法阻止这一层账号状态同步，违反 SPEC §4.3 project 身份层独立约束。**禁止删除该 flag**（v2.0.x 早期尝试用 `--disable-features=msUseSingleSignOnForSso,...` 屏蔽 SSO/Identity 服务被实证无效，Edge 对未知 feature 静默忽略；`--disable-sync` 才是真正起作用的关键 flag）。
- **手动启动 server**：工具窗口只显示 "Start OpenCode Server" 按钮，不自动启动/重启；并发/重复点击经 Go-style singleflight 合并为单次进程启动（`Singleflight.kt` leader/follower 模式 + `MyToolWindow.isServerReadyHandled` CAS 守卫）
- SSE 主动关闭 → 立即显示 Start 按钮（快速通道，`onConnectionLost` 回调）；SSE 重建后 1.5s debounce 自动恢复 UI（恢复通道，`onConnectionEstablished`）
- 外部链接走系统浏览器
- 会话加载**不**自动恢复；由用户在 web UI 内手动选 session。URL `/$base64dir/session/$id` 支持加载指定 session（API 在 `OpenCodeApi.getSession`）
- 工具窗口锚定 `right`，实现 `DumbAware`
- 多项目实例用 `ConcurrentHashMap<Project, MyToolWindow>` 管理
- 每 Project 独立 `OpenCodeSSEConsumer` 实例（`SSEConsumerFactory.create(project)`）
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
- ~~`OpenCodeProjectActivity` + 焦点监听机制~~ → 整删（commits `95d7faf` + `9a959ea`;前者删 `OpenCodeProjectActivity`,后者删 `FocusAdapter` 焦点监听）
- ~~JCEF 浏览器内嵌（`BrowserPanel.kt` + JCEF OSR 性能问题）~~ → 整删（v2.0.0）,改用 Edge --app 模式 + 日常 profile 复用
- ~~`CleanBrowserLauncher` 用 `--user-data-dir=/tmp/...` 隔离 profile~~ → 整删（v2.0.0）,违反用户硬约束"浏览器不能用 --user-data-dir 隔离"（实测丢 localStorage 缓存导致项目选不中—**根因是 `/tmp` 不持久,非 `--user-data-dir` 本身的问题**）;当前方案用 `--user-data-dir=~/.config/opencode-web-ui/edge-profiles/$projectHash/user-data/`（项目隔离 + 持久化,跨 IDE 升级不丢）
- ~~in-IDE 通知 UI（`OpenCodeNotificationService` 双模式 BALLOON + SystemNotifications）~~ → **完全拆除**(v2.0.0+ M3-T4 → 当前 commit),`utils/OpenCodeNotificationService.kt` + `utils/OpenCodeNotificationRouter.kt` 整删,SSE 通知事件从白名单移除,OpenCodeSSEConsumer 的 `dispatchNotification` / `handleSessionIdle` / `sessionTitles` / `idleNotifiedSessions` / `subagentSessionIds` 等通知抑制代码全部删除,通知由 OpenCode server / Web UI 自身接管
- ~~`AddToPromptAction`（JCEF `JcefJsInjector` 注入 JS 路径）~~ → 整删（v2.0.0 M1-T5）;`CopyAsPromptAction` 直接用 `Toolkit.getDefaultToolkit().systemClipboard`

## 注意事项（容易踩的坑）

- **`local.properties` 已 gitignore 但含明文 RSA 私钥 + 完整证书链 + 发布令牌**。安全风险持续存在；改用环境变量（`build.gradle.kts` 已支持 `providers.environmentVariable()`）
- **`.omo/` 已在 `.gitignore` 中，但 10 个文件（2 plans + 8 `run-continuation/ses_*.json`）已意外提交**。要清理需 `git rm --cached -r .omo/`
- **`research/archive/`** 是已归档的规划文档（`idea-plugin-integration/` 9 个 + `performance/` 2 个），**不要**当作当前代码参考
- **`openspec/` 目录已删除**（commit `95d7faf` HEAD）：所有 `openspec/specs/<capability>/spec.md` 和 `openspec/changes/<change>/` 引用全部失效。改动 capability 行为时改 `SPEC.md` / `DESIGN.md` 即可
- **JCEF 焦点问题已不适用(v2.0.0)** — JCEF 浏览器在 v2.0.0 整删,OpenCode Web UI 改在外部 Edge --app 窗口跑,IDE 端不再持有浏览器控件,不存在"焦点卡死"问题。`resetToolWindow()` 仍保留供将来扩展使用(目前 Dashboard 的 Reset 按钮只重置 UI 状态文本,未走 hide+activate 路径)。若未来需重新引入 JCEF,见 v1.x 历史说明

## REFERENCES

- 官方开发指南: https://plugins.jetbrains.com/docs/intellij/
- 本地参考: `references/intellij-platform/`（含 `PLATFORM_ACTIONS_SUMMARY.md`）
- 本仓库 CI: `syllr/intellij-opencode-web` GitHub Actions
