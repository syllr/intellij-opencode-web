# SPEC.md — IntelliJ OpenCode Web 插件规格

> **本文档定位**:项目级**系统行为规范**。定义"插件必须满足什么",不描述"插件如何实现"。
>
> **三元组关系**(项目根元文档):
>
> - `AGENTS.md` — **How to develop**(开发宪法、流程规则、踩坑经验)
> - `DESIGN.md` — **How it's built**(架构设计、组件关系、关键决策)
> - `SPEC.md` — **What it must do**(系统级行为规范、SLA、安全契约、数据不变量)—— 本文档
>
> **与 `openspec/specs/` 的关系**:
>
> - `openspec/specs/<capability>/spec.md` — 单个 capability 的可观察行为(WHERE/WHEN/THEN scenarios)
> - `openspec/specs/` 仍是 capability 级 source of truth
> - 本文档只写**跨多个 capability、横切系统层**的硬性要求(性能/可用性/安全/数据一致性/部署),不重复 capability 细节
>
> **阅读顺序**:`AGENTS.md` → `SPEC.md` → `DESIGN.md` → `openspec/specs/<capability>/`

---

## 0. 系统概览

**IntelliJ OpenCode Web 插件**是一个 JetBrains IDE 插件(2026.1+),为 OpenCode CLI 提供嵌入式 Web UI 集成 + IDE 原生通知。

| 能力                    | 路径                       | 协议/机制                                              |
| ----------------------- | -------------------------- | ------------------------------------------------------ |
| JCEF 嵌入式 Web UI      | IDE 侧边栏 ↔ OpenCode CLI  | WSS/HTTP(由 OpenCode 决定)                             |
| IDE 原生通知            | IDE 通知中心 ↔ 用户        | `Notification.notify(project)` + `SystemNotifications` |
| 文件变更同步            | OpenCode ↔ IDE VFS         | SSE `session.diff` / `file.edited`                     |
| 子 agent 追踪与通知降噪 | OpenCode 事件流 ↔ 本地 Set | SSE + 本地状态                                         |

**核心数据流**(详见 `DESIGN.md §0.3`):

```
OpenCode CLI (port 12396)
    │  SSE /global/event
    ▼
IntelliJ Plugin
    │
    ├── OpenCodeSSEConsumer ──→ 文件事件 → VFS 刷新
    │              │            ──→ 通知事件 → OpenCodeNotificationService
    │              │            ──→ bash 事件 → BashCommandHandler
    │              ▼
    │         OpenCodeNotificationRouter ──→ Project 路由
    │              ▼
    │         OpenCodeNotificationService ──→ IDE 通知 / macOS 系统通知
    │
    └── HTTP API (/session/:id) ──→ SessionInfo 查询
```

---

## 1. 系统级 SLA(性能 + 可用性)

### 1.1 事件处理时延

| 阶段                         | 时延预算    | 来源                              |
| ---------------------------- | ----------- | --------------------------------- |
| SSE 事件到达 → JSON 解析     | < 5ms       | `SSEEventParser.parse()`          |
| JSON 解析 → 通知路由分发     | < 2ms       | `OpenCodeSSEConsumer.onMessage()` |
| 通知 Service `send()` 全链路 | < 50ms      | 含 `getSession` HTTP 缓存命中     |
| 文件事件 → VFS 刷新          | 2s 防抖窗口 | `FullRefreshCoordinator`          |

**Spec**:

- 系统 MUST 在 SSE 事件高频突发(>100 事件/s)时,事件处理时延不显著增长
- 系统 MUST 在 `session.status(idle)` / `session.idle` 事件间用 2s 时间窗口去抖(参考 `OpenCodeSSEConsumer.idleDedupWindowMs` 常量)

### 1.2 HTTP API 调用时延

| 端点                                     | P99 时延目标    | 备注                                     |
| ---------------------------------------- | --------------- | ---------------------------------------- |
| `GET /global/health`(健康检查)           | < 100ms         | HEAD 请求                                |
| `GET /session/:id`(SessionInfo 查询)     | < 200ms         | 30s LRU 缓存命中后零开销                 |
| `POST /global/dispose`(优雅关闭)         | < 2s 客户端超时 | 异步 fire-and-forget, 不阻塞主线程       |
| `GET /session/:id`(SessionInfo 查询)     | < 200ms         | 30s LRU 缓存命中后零开销                 |
| `GET /session?directory=...`(列 session) | < 200ms         | 启动期 `MyToolWindow.loadProjectPage` 用 |

**Spec**:

- 系统 MUST 用 `SessionInfoCache`(30s TTL)消除通知路径上的同步 HTTP 调用
- 系统 MUST 在 `getSession` 失败时降级为不带标题的占位符模板,不抛异常中断通知

### 1.3 可用性目标

| 指标                               | 目标      | 备注                                             |
| ---------------------------------- | --------- | ------------------------------------------------ |
| OpenCodeSSEConsumer SSE 连接可用性 | ≥ 99%(日) | 30s watchdog 超时自动重连                        |
| OpenCodeServerManager 关闭成功率   | 100%      | 5s onExit → SIGTERM(2s) → SIGKILL 兜底           |
| 多项目路由正确性                   | 100%      | `OpenCodeNotificationRouter` 按 `directory` 路由 |

**Spec**:

- 系统 MUST 在 SSE 连接断开后 30s 内检测到并自动重连(`SSE_IDLE_TIMEOUT_MS`)
- 系统 MUST 在重连后清空 `subagentSessionIds` / `sessionIdleFired` / `idleLastFired` 三个静态集合

### 1.4 容量与并发

| 维度             | 当前目标                                                      | 备注                                |
| ---------------- | ------------------------------------------------------------- | ----------------------------------- |
| 同时打开的项目数 | ≤ 10                                                          | 受 `OpenCodeServerManager` 单例约束 |
| SSE 事件频率     | 100-500 事件/s                                                | 正常 agent 循环                     |
| LRU 缓存容量     | dedupCache 1000 / subagentSessionIds 1000 / idleLastFired 500 | 防止无界增长                        |

**Spec**:

- 所有 LRU 集合 MUST 是有界(最大容量固定)
- `subagentSessionIds` 容量 1000 / `sessionIdleFired` 容量 1000 / `idleLastFired` 容量 500 / `dedupCache` 容量 1000

---

## 2. 安全规范

### 2.1 包名隔离(HARD RULE)

- **MUST** 使用 `com.shenyuanlaolarou.opencodewebui` 包名(也是 `pluginGroup` / plugin id / vendor 命名空间)
- **MUST NOT** 使用其他包名
- **MUST NOT** 引入旧 fork 残留的 `com.github.xausky.opencodewebui`(AGENTS.md "anti-patterns" 已记录)

### 2.2 自动化约束(HARD RULE)

| 规则                                  | 说明                                                           |
| ------------------------------------- | -------------------------------------------------------------- |
| AI 禁止自动 `git commit` / `git push` | 必须用户显式授权(`/git-commit` 或 prompt 中 `commit` / `push`) |
| AI 禁止自动 `publishPlugin`           | 必须用户显式授权                                               |
| AI 禁止修改 `local.properties` 凭证   | PUBLISH_TOKEN / 私钥等**绝对**不能进 git                       |

### 2.3 Type 安全(HARD RULE)

- **MUST NOT** 使用 `as Any` / `@ts-ignore` / `@ts-expect-error` 等价物
- **MUST** 正确处理 nullable(用 `?.` / `?: return` / `lateinit` 视情况)
- **MUST** 用 IntelliJ Platform 的 `thisLogger().info/warn/error()` 记录日志,不用 `println` 或 `e.printStackTrace()`

### 2.4 静态全局状态(HARD RULE)

- **MUST NOT** 在 `object` 里挂 `var` 或非常量的 `MutableMap`
- 例外:`OpenCodeSSEConsumer` 的 `companion object` 静态集合(`subagentSessionIds` / `sessionIdleFired` / `idleLastFired`)经 AGENTS.md 标注"故意保留"
- 任何新例外 MUST 标注 KDoc 说明**为什么**违反 HARD RULE

### 2.5 HTTP/JSON 解析(HARD RULE)

- **MUST NOT** 用 Regex 解析 HTTP 响应体或 JSON
- **MUST** 所有 HTTP 响应体走 Gson(或同等 JSON 解析器)
- 例外:`BashCommandHandler` 用 Regex 切分 bash 命令(非 HTTP/JSON 解析)、`SSEEventParser.SYNC_EVENT_TYPE_VERSION_REGEX`(剥版本号后缀) — 这两类**不**是 HTTP/JSON 解析

### 2.6 CORS / 鉴权

本插件作为 OpenCode CLI 的**客户端**运行,鉴权在 OpenCode 端处理。本插件不直接对外暴露 HTTP 端点。

---

## 3. 数据一致性

### 3.1 SSE 状态机不变量

#### 3.1.1 `subagentSessionIds` 不变量

- `subagentSessionIds` MUST 仅在 `session.created` 事件携带 `parentID` 时添加 sessionID
- `subagentSessionIds` MUST **不**在 `session.deleted` 时移除(防竞态:防止 `session.deleted` 先于 `session.status(idle)` 到达时,子 agent 的 idle 事件被误判为父 session 的 complete)
- `subagentSessionIds` MUST 在 SSE 重连时(`onClosed()`)清空
- 容量 MUST 限制为 1000(LRU)

#### 3.1.2 `sessionIdleFired` 不变量

- `sessionIdleFired` MUST 仅在成功发 `complete` 通知时添加 sessionID
- `sessionIdleFired` MUST 在 `message.updated(role=user)` 时移除 sessionID(用户发新消息重置抑制)
- `sessionIdleFired` MUST **不**在 `session.status(busy)` 时移除(防误抑制)
- `sessionIdleFired` MUST 在 SSE 重连时清空

#### 3.1.3 `idleLastFired` 不变量

- `idleLastFired` MUST 仅做时间窗口去重(2s 内同 sessionID 重复 idle 抑制,key 格式 `"{sessionID}:complete"`)
- MUST **不**影响 `subagentSessionIds` 和 `sessionIdleFired` 的状态
- 容量 MUST 限制为 500(LRU)
- MUST 在 `OpenCodeSSEConsumer.stop()` 时清空(SSE 主动关闭)
- **不**在 SSE 重连时清空(`onClosed()` 只清 `subagentSessionIds` / `sessionIdleFired` / `dedupCache`);`idleLastFired` 仅由 `stop()` 清,与重连不耦合

### 3.2 进程状态不变量(`OpenCodeServerManager`)

- `serverProcess` MUST 通过 `AtomicReference<Process?>` 持有,线程安全
- `sseConsumer` MUST 在 `synchronized(this)` 块内访问,避免与 `ensureSSEConsumer` / `disposeForProject` 竞态
- `shutdownInProgress` MUST 防止 `stopServer` / `shutdownServer` 重入(用户连续点 Shutdown 时第二次起短路返回)

### 3.3 健康检查语义(`isServerHealthySync`)

- 端口不可达(`Socket.connect` 抛异常)→ MUST 返回 `false`(确定不健康)
- HTTP HEAD 异常(`sharedHttpClient.send` 抛 `IOException` 或 `InterruptedIOException` 或 `HttpTimeoutException` 等)→ MUST 返回 `true`(端口可达但 server 在启动中或 HTTP 栈暂时不可用,降级为"健康")
- HTTP 状态码 200 → MUST 返回 `true`
- HTTP 状态码 ≠ 200 → MUST 返回 `false`
- 这是**故意的反模式**(AGENTS.md 已标注"按用户决策保留"),**不**修复异常→true 的语义
- 完整调用方:`OpenCodeApi.isServerHealthySync()` 被 3 处调用 —— `HealthMonitor` 每 5s 轮询、`OpenCodeApi.waitForServerHealthy()` 启动期 2s 轮询、`MyToolWindow.checkAndLoadContent()` 工具窗口首次显示时检查

### 3.4 IDE 通知路由一致性

- `OpenCodeNotificationRouter` MUST 按 `directory`(File.canonicalPath 规范化)路由到正确 Project
- 路径比较 MUST 大小写不敏感(macOS/Windows 默认 case-insensitive)
- 路径比较 MUST 解析符号链接(`canonicalPath`)
- 找不到匹配 Project 时 MUST 静默丢弃(不抛异常)

---

## 4. 跨子系统契约

### 4.1 IDE ↔ OpenCode CLI(SSE 事件流)

#### 4.1.1 端点

- URL: `http://127.0.0.1:12396/global/event`
- 协议: SSE(`text/event-stream`)
- 客户端: `okhttp-eventsource` 4.1.0

#### 4.1.2 事件类型映射

```
SSE event 名称: message
payload.type                 → payloadType(Direct BusEvent)
payload.syncEvent.type       → syncEventType(去掉 ".N" 后缀)
payload.syncEvent.data       → syncEventData
payload.syncEvent.aggregateID → sessionID(SyncEvent 实体标识)
```

完整事件列表见 `openspec/specs/notification-events/spec.md`。

#### 4.1.3 关键事件(Spec 应满足)

| 事件                           | 触发条件                | 处理                                          |
| ------------------------------ | ----------------------- | --------------------------------------------- |
| `session.created`(有 parentID) | OpenCode 启动子 agent   | 加入 `subagentSessionIds`                     |
| `session.created`(无 parentID) | 用户创建新会话          | 触发 `session_started` 通知                   |
| `session.status(type=idle)`    | 父/子 session 完成      | 触发 `complete` / `subagent_complete`         |
| `session.idle`(deprecated)     | 同上(旧格式)            | 同上                                          |
| `session.error`                | OpenCode 错误           | 检查 error.name → `error` / `user_cancelled`  |
| `permission.asked`             | OpenCode 申请权限       | 触发 `permission` 通知                        |
| `message.updated(role=user)`   | 用户发新消息            | 触发 `user_message` + 重置 `sessionIdleFired` |
| `file.edited` / `session.diff` | 文件变更 / session diff | 触发 VFS 刷新                                 |
| `message.part.updated`         | bash 工具完成           | 触发 `BashCommandHandler`                     |

### 4.2 IDE ↔ OpenCode CLI(HTTP API)

| 端点                     | 方法 | 用途                     | 鉴权 |
| ------------------------ | ---- | ------------------------ | ---- |
| `/global/health`         | HEAD | 健康检查                 | 无   |
| `/global/dispose`        | POST | 优雅关闭(2s 客户端超时)  | 无   |
| `/session/:id`           | GET  | 查询 session 详情        | 无   |
| `/session?directory=...` | GET  | 列出 project 下 sessions | 无   |

鉴权在 OpenCode 端(本插件信任 localhost 127.0.0.1)。

### 4.3 IDE ↔ IDE 内部(JCEF 浏览器)

- `BrowserPanel` 创建的 JCEF browser MUST 注入到 `MyToolWindowFactory` 注册的 ToolWindow
- `MyToolWindowFactory.sharedJBCefClient` MUST 跨项目共享 JBCefClient
- 关闭策略见 §6.3

### 4.4 IDE ↔ IDE 内部(IDE 通知)

- 通知走 `Notification.notify(project)`(BALLOON)+ `SystemNotifications.notify()`(macOS 系统通知)双模式
- 用户需在 IntelliJ 设置中开启:Preferences → Appearance & Behavior → Notifications → "Enable system notifications"
- `OpenCodeConfigurable` 提供按事件类型独立开关

---

## 5. 端点契约

### 5.1 OpenCode CLI 端点(本插件消费)

| 端点                  | 方法 | 用途             | 错误处理                               |
| --------------------- | ---- | ---------------- | -------------------------------------- |
| `/global/health`      | HEAD | 健康检查         | 异常 → 降级返回 true(AGENTS.md 反模式) |
| `/global/dispose`     | POST | 优雅关闭         | 2s 超时不影响主流程                    |
| `/session/:id`        | GET  | SessionInfo 查询 | 失败 → 缓存 null,占位符模板降级        |
| `/session?directory=` | GET  | 列 session       | 失败 → 缓存 null                       |

### 5.2 兼容性

- 端口 `12396` MUST 不与其他常见服务冲突(从 `4096` 修改而来)
- 协议变更(如果 opencode server 端点协议变更)→ 通过 `SSEEventParser` 的字段路径 fallback 兼容,不改 IDE 端 API

---

## 6. 部署与环境约束

### 6.1 强制部署位置

- 插件 MUST 通过 JetBrains Marketplace 分发(`./gradlew publishPlugin`)
- 编译产物: `./gradlew buildPlugin` → `build/distributions/*.zip`
- 调试运行: `./gradlew runIde` / `./gradlew runIdeForUiTests`

### 6.2 端口与协议

| 服务                 | 端口/位置  | 协议                                                          |
| -------------------- | ---------- | ------------------------------------------------------------- |
| OpenCode CLI(server) | **12396**  | HTTP + SSE                                                    |
| JCEF 浏览器          | IDE 进程内 | CEF(本地)                                                     |
| 通知中心             | OS 级      | macOS NSUserNotification / Windows TrayIcon / Linux libnotify |

### 6.3 关闭策略(详见 `DESIGN.md §3.3`)

`stopServer` 与 `shutdownServer` **共享** `gracefulShutdown(acquireHandle, killFallback, errorTag)` 实现,流程:

1. `shutdownInProgress.compareAndSet(false, true)` 防重入(用户连续点 Shutdown 第二次起短路返回)
2. 在 `synchronized(this)` 内停 SSE consumer(共享锁,与 `ensureSSEConsumer` / `disposeForProject` 一致)
3. `startDisposeThread()` 异步 `POST /global/dispose`(2s 客户端超时,fire-and-forget,主线程不等 HTTP 响应)
4. `acquireHandle()` 拿 `ProcessHandle`:
   - `stopServer` 路径:`acquireServerProcessHandle()`(从 `serverProcess` 引用拿)
   - `shutdownServer` 路径:`acquirePortProcessHandle()`(用 `lsof` 找 PID)
5. `handle.onExit().get(5s)` 等真实退出 → 成功即返回
6. 超时则 `handle.destroy()`(SIGTERM)+ `handle.onExit().get(2s)` 等 → 成功即返回
7. 超时则 `killFallback()`(SIGKILL 进程树):
   - `stopServer` 路径:`killProcessTreeByPort()`
   - `shutdownServer` 路径:`killProcessTreeByPort()`

**额外场景**:`startServer` 启动 30s 超时时调 `killProcessTreeByHandle(process)` 单独清理(不走 `gracefulShutdown`)。

**`killProcessTreeByHandle`** vs **`killProcessTreeByPort`**:

- `killProcessTreeByHandle(process)`:用 `ProcessHandle.descendants()` API 递归杀(只杀目标进程后代,不误杀同 PGID 的无关进程),SIGKILL 后等 `onExit(3s)` 确认终止
- `killProcessTreeByPort()`:通过 `lsof` 找 PID 后用 shell 脚本(`pgrep -P` 递归)杀,后备方案(进程引用丢失时)

### 6.4 运行时依赖

| 依赖                            | 版本     | 说明                                                             |
| ------------------------------- | -------- | ---------------------------------------------------------------- |
| Kotlin                          | 2.3.20   | —                                                                |
| Gradle                          | 9.3.1    | —                                                                |
| JDK                             | 21       | 编译/运行(`jvmToolchain(21)`)                                    |
| IntelliJ Platform               | 2026.1   | `pluginSinceBuild=261`,`pluginUntilBuild` 留空                   |
| Gson                            | 2.10.1   | JSON 解析(`libs.gson`)                                           |
| okhttp-eventsource              | 4.1.0    | SSE 客户端(`libs.okhttpEventsource`)                             |
| JUnit                           | 4.13.2   | 测试(`libs.junit`)                                               |
| opentest4j                      | (latest) | 测试断言(`libs.opentest4j`)                                      |
| IntelliJ Platform TestFramework | 2026.1   | 平台测试(`TestFrameworkType.Platform`)                           |
| Gradle Plugins                  | —        | `kotlin` / `intelliJPlatform` / `changelog` / `qodana` / `kover` |
| Qodana JVM                      | 2024.3   | 代码质量(`qodana-jvm-community:2024.3`)                          |
| Kover                           | 0.9.5    | 覆盖率(`onCheck` 时输出 XML)                                     |

### 6.5 关键环境变量

| 变量               | 默认 | 说明                              |
| ------------------ | ---- | --------------------------------- |
| 无(全部走代码常量) | —    | 所有配置在 `OpenCodeConstants.kt` |

### 6.6 远程访问规范(不适用)

本插件**不**操作远程服务器,无需 `remote-shell`。

### 6.7 子模块(只读)

`openspec/` 下历史归档 change(`changes/archive/`)是已完成的规划文档,**不**当作当前代码参考。

---

## 7. 系统级横切场景

### 7.1 通知降噪决策(详见 `DESIGN.md §4.2`)

**Spec 满足**:

- 系统 MUST 用 `OpenCodeSSEConsumer` 的三层去重(子 agent → 父 session 抑制 → 2s 时间窗口)
- 系统 MUST 用 `subagentSessionIds: Set<String>` 追踪子 agent session ID(非 HTTP API,避免竞态)
- 系统 MUST 在 SSE 重连时清空所有静态集合

详细 spec 见 `openspec/specs/idle-notification-suppression/spec.md` 和 `subagent-complete-detection-fix/spec.md`。

### 7.2 SSE 重连处理

- **WHEN** SSE 连接断开(`onClosed()`)
- **THEN** 系统 MUST 清空 `subagentSessionIds` / `sessionIdleFired` / `idleLastFired` / `SSEEventParser.dedupCache`
- **WHEN** watchdog 检测到 30s 内无事件(`SSE_IDLE_TIMEOUT_MS`)
- **THEN** 系统 MUST 强制重连

### 7.3 进程生命周期

- 启动:`OpenCodeServerManager.startServer()` 异步启 opencode → 等健康 → 创建 SSE consumer
- 运行:多项目共享单 SSE consumer(全局单例)
- 关闭:IDE 退出 → `stopServer()` → 4 阶段关闭策略(§6.3)

### 7.4 多项目路由

- 多 IntelliJ 窗口打开同一 opencode server → 单 SSE consumer + `directory` 路由到正确 Project
- 项目关闭 → `disposeForProject` 停止该项目的 SSE consumer(若属于该项目)

### 7.5 健康检查机制

**`HealthMonitor`(项目级,`toolWindow/HealthMonitor.kt`)**:

- 每个 `MyToolWindow` 实例持有一个 `HealthMonitor`,绑定 5s 轮询 + 3 次连续翻转 debounce
- 健康(healthy)状态改变 → 调 `loadProjectPage()`
- 不健康(unhealthy)状态改变 → 调 `showServerNotRunning()`(显示启动按钮)
- 在 `MyToolWindow.checkAndLoadContent()` 首次显示时启动,Dispose 项目时自动停

**`OpenCodeApi.isServerHealthySync()`**:端口检查 + HTTP HEAD 双段,见 §3.3

### 7.6 日志约定

- **MUST** 用 `thisLogger().info/warn/error()`(IntelliJ Platform Logger)
- **MUST** 日志前缀格式: `[<类名>] <消息>`(例:`[OpenCodeSSEConsumer] Subagent session tracked: $sid`)
- **MUST NOT** 输出凭证(PUBLISH_TOKEN、私钥)

### 7.7 资源清理

- `Process` MUST 在退出时 `waitFor` 或 `destroyForcibly`(避免僵尸进程)
- `BufferedReader` / `InputStream` MUST 用 `.use {}` 包裹
- `Thread`(daemon) MUST `isDaemon = true` 避免阻止 JVM 关闭
- `Alarm` / `ScheduledExecutorService` MUST 在 `stop()` 中关闭

---

## 8. SPEC.md 引用关系

| 引用                 | 出处                                  | 何时使用             |
| -------------------- | ------------------------------------- | -------------------- |
| 架构实现细节         | `DESIGN.md`                           | 想了解"怎么做"时     |
| 开发流程规则         | `AGENTS.md`                           | 日常开发时           |
| 单个 capability 行为 | `openspec/specs/<capability>/spec.md` | 改具体 capability 时 |
| Change 提案/任务     | `openspec/changes/<change>/`          | 评估/实施某个变更时  |
| 历史背景             | `openspec/changes/archive/`           | 了解"为什么改"时     |

---

## 附录 A:当前未满足项(Known Gaps)

| ID    | 描述                                                                                                                  | 严重性 | 建议                                              |
| ----- | --------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------- |
| GAP-1 | `subagent_complete` 通知默认关闭(`OpenCodeConfig.kt:54`)                                                              | ⚠️ 中  | 用户需手动在 Settings 开启                        |
| GAP-2 | subagent session 1000 LRU 容量可能在长会话中驱逐老的追踪记录                                                          | ℹ️ 低  | 长会话场景罕见,可接受                             |
| GAP-3 | im-select 硬编码开发者个人路径(`OpenCodeConstants.kt:30-31`)                                                          | ℹ️ 低  | 待抽离为插件配置(已 TODO)                         |
| GAP-4 | `idleLastFired` 未在 SSE 重连时清空(只在 `stop()` 清)                                                                 | ℹ️ 低  | 影响微小,可接受                                   |
| GAP-5 | `OpenCodeNotificationRouter` 注册只在 `OpenCodeProjectActivity.execute` 中,**非**双保险                               | ℹ️ 低  | 实际场景下足够(项目级 `ProjectActivity` 已覆盖)   |
| GAP-6 | `JcefKeyboardInterceptor.interceptKeysRecursive` 是死代码(`interceptKeys` 单层调用)                                   | ℹ️ 低  | 删除方法(不破坏接口)                              |
| GAP-7 | `OpenCodeConfigurable` 中 4 处 `!!` 强转(实测不会 NPE,IntelliJ Platform 契约保证 `createComponent` 先于 `isModified`) | ℹ️ 低  | 可加 null guard 防御性编程                        |
| GAP-8 | `ide-native-notifications` change 0/11 tasks 待实施(`openspec/changes/ide-native-notifications/tasks.md`)             | ⚠️ 中  | 实施后可去除 `@mohak34/opencode-notifier` 依赖    |
| GAP-9 | `OpenCodeConfigurable.isModified()` 只检查 `globalToggle == null`,不检查其他 toggle null                              | ℹ️ 低  | 实际不会触发(同方法体内连续初始化),防御性补全即可 |

---

## 附录 B:版本与变更

| 版本 | 日期       | 变更说明                                                                       |
| ---- | ---------- | ------------------------------------------------------------------------------ |
| 1.0  | 2026-06-03 | 初始版本。基于 AGENTS.md HARD RULES + openspec/specs/\* + design.md 整合提炼。 |
