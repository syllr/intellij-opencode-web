# SPEC.md — IntelliJ OpenCode Web 插件规格

> **本文档定位**:项目级**系统行为规范**。定义"插件必须满足什么",不描述"插件如何实现"。
>
> **三元组关系**(项目根元文档):
>
> - `AGENTS.md` — **How to develop**(开发宪法、流程规则、踩坑经验)
> - `DESIGN.md` — **How it's built**(架构设计、组件关系、关键决策)
> - `SPEC.md` — **What it must do**(系统级行为规范、SLA、安全契约、数据不变量)—— 本文档
>
> **阅读顺序**:`AGENTS.md` → `SPEC.md` → `DESIGN.md`

---

## 0. 系统概览

**IntelliJ OpenCode Web 插件**是一个 JetBrains IDE 插件(2026.1+),为 OpenCode CLI 提供嵌入式 Web UI 集成 + IDE 原生通知。

| 能力                    | 路径                       | 协议/机制                                              |
| ----------------------- | -------------------------- | ------------------------------------------------------ |
| JCEF 嵌入式 Web UI      | IDE 侧边栏 ↔ OpenCode CLI  | WSS/HTTP(由 OpenCode 决定)                             |
| IDE 原生通知            | IDE 通知中心 ↔ 用户        | `Notification.notify(project)` + `SystemNotifications` |
| 文件变更同步            | OpenCode ↔ IDE VFS         | SSE 事件 → `FullRefreshCoordinator`                    |
| 子 agent 追踪与通知降噪 | OpenCode 事件流 ↔ 本地状态 | SSE + 本地 per-consumer 状态集合                       |

**核心数据流**(详见 `DESIGN.md §0.3`):

```
OpenCode CLI (port 12396)
    │  SSE /event?directory=<path>
    ▼
IntelliJ Plugin
    │
    ├── OpenCodeSSEConsumer(每 Project 一个实例)
    │              │
    │              ├─ 文件事件 → VFS 刷新
    │              ├─ 通知事件 → OpenCodeNotificationService
    │              └─ bash 事件 → BashCommandHandler
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
- 系统 MUST 在 `session.status(idle)` / `session.idle` 事件间用 per-consumer `idleNotifiedSessions` 集合防重复通知(`OpenCodeSSEConsumer.kt:286`)

### 1.2 HTTP API 调用时延

| 端点                                 | P99 时延目标    | 备注                                                       |
| ------------------------------------ | --------------- | ---------------------------------------------------------- |
| `GET /global/health`(健康检查)       | < 100ms         | HEAD 请求;**实际超时上限由 `HTTP_TIMEOUT_MS=8000ms` 决定** |
| `GET /session/:id`(SessionInfo 查询) | < 200ms         | 1h LRU 缓存命中后零开销                                    |
| `POST /global/dispose`(优雅关闭)     | < 2s 客户端超时 | 异步 fire-and-forget, 不阻塞主线程                         |

**Spec**:

- 系统 MUST 用 `SessionInfoCache`(1h TTL)消除通知路径上的同步 HTTP 调用
- 系统 MUST 在 `getSession` 失败时降级为不带标题的占位符模板,不抛异常中断通知

### 1.3 可用性目标

| 指标                               | 目标      | 备注                                                           |
| ---------------------------------- | --------- | -------------------------------------------------------------- |
| OpenCodeSSEConsumer SSE 连接可用性 | ≥ 99%(日) | 30s watchdog 超时自动重连                                      |
| OpenCodeServerManager 关闭成功率   | 100%      | 5s onExit → SIGTERM(2s) → SIGKILL 兜底                         |
| 多项目 SSE consumer 隔离           | 100%      | 每个 Project 独立 `OpenCodeSSEConsumer` 实例,per-instance 状态 |

**Spec**:

- 系统 MUST 在 SSE 连接断开后 30s 内检测到并自动重连(`SSE_IDLE_TIMEOUT_MS`)
- 系统 MUST 在重连(`onClosed()`)和 `stop()` 时清空 `sessionTitles` / `idleNotifiedSessions` 两个 per-instance 状态集合(`OpenCodeSSEConsumer.kt:115-116,308-309`)

### 1.4 容量与并发

| 维度             | 当前目标                                    | 备注                                |
| ---------------- | ------------------------------------------- | ----------------------------------- |
| 同时打开的项目数 | ≤ 10                                        | 受 `OpenCodeServerManager` 单例约束 |
| SSE 事件频率     | 100-500 事件/s                              | 正常 agent 循环                     |
| LRU 缓存容量     | dedupCache 1000 / idleNotifiedSessions 1000 | 防止无界增长;`sessionTitles` 无界   |

**Spec**:

- 所有 LRU 集合 MUST 是有界(最大容量固定);`sessionTitles` 例外(per-instance,典型场景 < 1000)
- `idleNotifiedSessions` 容量 1000(LRU)/ `SSEEventParser.dedupCache` 容量 1000(LRU)

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

- **MUST NOT** 使用 `as Any` / Kotlin 等价的 type erase 绕过 / 滥用 `!!`
- **MUST** 正确处理 nullable(用 `?.` / `?: return` / `lateinit` 视情况)
- **MUST** 用 IntelliJ Platform 的 `thisLogger().info/warn/error()` 记录日志,不用 `println` 或 `e.printStackTrace()`

### 2.4 静态全局状态(HARD RULE)

- **MUST NOT** 在 `object` 里挂 `var` 或非常量的 `MutableMap`
- 例外:`OpenCodeSSEConsumer.companion object` 的 `idleNotifiedSessions`(per-consumer 单例约束,已标注);`SSEEventParser.companion object` 的 `dedupCache` 与 `SUBAGENT_TITLE_REGEX`(SSE 事件级语义,与 Project 无关)
- 任何新例外 MUST 标注 KDoc 说明**为什么**违反 HARD RULE

### 2.5 HTTP/JSON 解析(HARD RULE)

- **MUST NOT** 用 Regex 解析 HTTP 响应体或 JSON
- **MUST** 所有 HTTP 响应体走 Gson(或同等 JSON 解析器)
- 例外:`BashCommandHandler.BASH_SPLIT_REGEX` / `WHITESPACE_REGEX`(切分 bash 命令,非 HTTP/JSON 解析);`OpenCodeSSEConsumer.SUBAGENT_TITLE_REGEX` / `OpenCodeNotificationService.SUBAGENT_REGEX`(title 文本模式匹配,非 HTTP/JSON 解析) — 这两类**不**是 HTTP/JSON 解析

### 2.6 CORS / 鉴权

本插件作为 OpenCode CLI 的**客户端**运行,鉴权在 OpenCode 端处理。本插件不直接对外暴露 HTTP 端点。

---

## 3. 数据一致性

### 3.1 SSE 状态机不变量

#### 3.1.1 `sessionTitles` 不变量

- `sessionTitles: ConcurrentHashMap<String, String>` MUST 仅在 `session.created` / `session.updated` 事件携带 `properties.info.title` 时写入 `(sessionID, title)`(`OpenCodeSSEConsumer.kt:225-227`)
- `sessionTitles` MUST **不**在 `session.deleted` 时移除(防竞态:防止 `session.deleted` 先于 `session.status(idle)` 到达时,子 agent 的 idle 事件被误判为父 session 的 complete)
- `sessionTitles` MUST 在 SSE 重连时(`onClosed()`)和 `stop()` 时清空(`OpenCodeSSEConsumer.kt:115,308`)
- 容量无硬性 LRU 上限(per-instance,项目生命周期内累计;典型场景 < 1000)

#### 3.1.2 `idleNotifiedSessions` 不变量

- `idleNotifiedSessions: MutableSet<String>`(LRU-backed,容量 1000) MUST 仅在成功发 `complete` 通知时通过 `add() return false` 原子操作添加 sessionID(`OpenCodeSSEConsumer.kt:92,286`)
- `idleNotifiedSessions` MUST 在 `message.updated(role=user)` 时移除 sessionID(用户发新消息重置抑制,`OpenCodeSSEConsumer.kt:235`)
- `idleNotifiedSessions` MUST **不**在 `session.status(busy)` 时移除(防误抑制)
- `idleNotifiedSessions` MUST 在 SSE 重连时和 `stop()` 时清空
- 集合本身防重复通知,无需时间窗

#### 3.1.3 `SSEEventParser.dedupCache` 不变量

- `dedupCache: Collections.synchronizedMap`(companion object,跨实例共享) MUST 仅做 eventID 去重(`SSEEventParser.kt:65,94`)
- MUST **不**影响 `sessionTitles` 和 `idleNotifiedSessions` 的状态
- 容量 MUST 限制为 1000(LRU)
- MUST 在 `OpenCodeSSEConsumer.stop()` 时清空(SSE 主动关闭) `SSEEventParser.clearCache()` (`SSEEventParser.kt:98`)
- **不**在 SSE 重连时清空(`onClosed()` 只清 `sessionTitles` / `idleNotifiedSessions`);`dedupCache` 仅由 `stop()` 清,与重连不耦合

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
- 完整调用方:`OpenCodeApi.isServerHealthySync()` 被 1 处调用 —— `OpenCodeApi.waitForServerHealthy()` 启动期 2s 轮询。`HealthMonitor` 已在 Part D 整删(改由 `OpenCodeSSEConsumer.onConnectionEstablished` 1.5s debounce 替代)

### 3.4 IDE 通知路由一致性(多项目隔离)

- 每个 Project 持有**独立** `OpenCodeSSEConsumer` 实例(`SSEConsumerFactory.create(project)`),无需 directory → Project 注册表
- SSE 事件的 `directory` 字段用于 `OpenCodeSSEConsumer` 内单 Project 内的 session 归属判断
- 多 IDE 窗口打开同一 opencode server 时,每个窗口独立 `OpenCodeSSEConsumer` 通过独立 SSE 连接消费(通过 `OpenCodeApi.createEventSource(directory)` 按 directory 路径区分);路径规范化用 `File.canonicalPath`(处理符号链接)
- 找不到匹配 Project 时 MUST 静默丢弃(不抛异常)

---

## 4. 跨子系统契约

### 4.1 IDE ↔ OpenCode CLI(SSE 事件流)

#### 4.1.1 端点

- URL: `http://127.0.0.1:12396/event?directory=<canonical-path>`
- 协议: SSE(`text/event-stream`)
- 客户端: `okhttp-eventsource` 4.3.0

#### 4.1.2 事件类型映射

```
SSE event 名称: message
payload.id           → dedupCache key(去重)
payload.type         → eventType(直接路由)
payload.properties   → properties(包含 sessionID / info.title / parentID / status.type)
```

新 wire 格式(`/event?directory=...` 端点)直出 `{id, type, properties}` 三键,无外层包装。

#### 4.1.3 关键事件(Spec 应满足)

| 事件                                                    | 触发条件                   | 处理                                                                           |
| ------------------------------------------------------- | -------------------------- | ------------------------------------------------------------------------------ |
| `session.created` / `session.updated`                   | OpenCode 创建/更新 session | 缓存 `(sessionID, title)` 到 `sessionTitles`(`OpenCodeSSEConsumer.kt:225-227`) |
| `session.status(type=idle)`                             | 父/子 session 完成         | `handleSessionIdle`(title 正则 → subagent 抑制;否则触发 `complete` 通知)       |
| `session.idle`(deprecated)                              | 同上(旧格式)               | 同上                                                                           |
| `permission.asked`                                      | OpenCode 申请权限          | 触发 `permission` 通知                                                         |
| `question.asked`                                        | 询问用户                   | 触发 `question` 通知                                                           |
| `message.updated(role=user)`                            | 用户发新消息               | 移除 `idleNotifiedSessions` 中的 sessionID(不触发通知)                         |
| `message.part.updated`                                  | bash 工具完成              | `BashCommandHandler` 触发 VFS 刷新                                             |
| `file.edited` / `session.diff` / `file.watcher.updated` | 文件变更                   | `FullRefreshCoordinator` 触发 VFS 刷新                                         |

### 4.2 IDE ↔ OpenCode CLI(HTTP API)

| 端点              | 方法 | 用途                    | 鉴权 |
| ----------------- | ---- | ----------------------- | ---- |
| `/global/health`  | HEAD | 健康检查                | 无   |
| `/global/dispose` | POST | 优雅关闭(2s 客户端超时) | 无   |
| `/session/:id`    | GET  | 查询 session 详情       | 无   |

鉴权在 OpenCode 端(本插件信任 localhost 127.0.0.1)。

### 4.3 IDE ↔ IDE 内部(JCEF 浏览器)

- `BrowserPanel` 创建的 JCEF browser MUST 注入到 `MyToolWindowFactory` 注册的 ToolWindow
- `MyToolWindowFactory.sharedJBCefClient` MUST 跨项目共享 JBCefClient
- 关闭策略见 §6.3

### 4.4 IDE ↔ IDE 内部(IDE 通知)

- 通知走 `Notification.notify(project)`(BALLOON)+ `SystemNotifications.notify()`(macOS 系统通知)双模式
- 用户需在 IntelliJ 设置中开启:Preferences → Appearance & Behavior → Notifications → "Enable system notifications"
- `OpenCodeConfig.notificationEnabled`(默认 true)提供通知总开关(**当前未实现** Settings UI;`plugin.xml` 无 `<applicationConfigurable>` 注册)

**焦点感知路由**(决策 MUST,实现见 `OpenCodeNotificationService.send()`):

| 焦点状态                                                      | 行为                                |
| ------------------------------------------------------------- | ----------------------------------- |
| OpenCodeWeb 工具窗口可见且活跃(`tw.isVisible && tw.isActive`) | 抑制(用户正在与 AI 对话,无需通知)   |
| 项目窗口有焦点(`frame.isActive == true`)+ IDE 在前台          | BALLOON(右下角 IDE 弹窗)            |
| 项目窗口无焦点 + IDE 在后台(`!Application.isActive()`)        | macOS 系统通知(切到浏览器/Slack 时) |
| 项目窗口无焦点 + IDE 在前台(多显示器场景)                     | ❌ 静默丢弃(已知行为,详见下方)      |

- `OpenCodeNotificationService.send()` MUST 按上表决策:工具窗口活跃抑制 → IDE 在后台时升级系统通知 → 其余 BALLOON
- "多显示器项目窗口无焦点但 IDE 在前台"场景 MUST 走当前实现(静默丢弃),已知限制;`OpenCodeConfig.notificationEnabled = false` 可彻底关通知

---

## 5. 端点契约

### 5.1 OpenCode CLI 端点(本插件消费)

| 端点                | 方法      | 用途                              | 错误处理                               |
| ------------------- | --------- | --------------------------------- | -------------------------------------- |
| `/global/health`    | HEAD      | 健康检查                          | 异常 → 降级返回 true(AGENTS.md 反模式) |
| `/global/dispose`   | POST      | 优雅关闭                          | 2s 超时不影响主流程                    |
| `/session/:id`      | GET       | SessionInfo 查询                  | 失败 → 缓存 null,占位符模板降级        |
| `/event?directory=` | GET (SSE) | SSE 事件流(按 directory 路径分流) | 断线 → 自动重连(30s watchdog)          |

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
| okhttp-eventsource              | 4.3.0    | SSE 客户端(`libs.okhttpEventsource`)                             |
| JUnit                           | 4.13.2   | 测试(`libs.junit`)                                               |
| opentest4j                      | 1.3.0    | 测试断言(`libs.opentest4j`)                                      |
| mockito                         | 5.19.0   | 测试 mock(`libs.mockito`)                                        |
| mockito-kotlin                  | 6.3.0    | Kotlin DSL for mockito(`libs.mockitoKotlin`)                     |
| IntelliJ Platform TestFramework | 2026.1   | 平台测试(`TestFrameworkType.Platform`)                           |
| Gradle Plugins                  | —        | `kotlin` / `intelliJPlatform` / `changelog` / `qodana` / `kover` |
| Qodana linter (Docker image)    | 2024.3   | `jetbrains/qodana-jvm-community:2024.3`                          |
| Qodana Gradle plugin            | 2025.3.1 | `org.jetbrains.qodana`                                           |
| Kover                           | 0.9.5    | 覆盖率(`onCheck` 时输出 XML)                                     |

### 6.5 关键环境变量

| 变量               | 默认 | 说明                              |
| ------------------ | ---- | --------------------------------- |
| 无(全部走代码常量) | —    | 所有配置在 `OpenCodeConstants.kt` |

### 6.6 远程访问规范(不适用)

本插件**不**操作远程服务器,无需 `remote-shell`。

### 6.7 历史归档(只读)

`research/archive/` 是已归档的规划文档,**不要**当作当前代码参考。

---

## 7. 系统级横切场景

### 7.1 通知降噪决策(详见 `DESIGN.md §4.2`)

**Spec 满足**:

- 系统 MUST 用 `OpenCodeSSEConsumer` 的两层去重(subagent title 正则 → 父 session 抑制)
- 系统 MUST 用 `sessionTitles: ConcurrentHashMap<String, String>` 追踪子 agent session 的 title(非 HTTP API,避免竞态)
- 系统 MUST 用 `idleNotifiedSessions` per-instance 集合防止 `session.idle` + `session.status(idle)` 双发
- 系统 MUST 在 SSE 重连时清空所有 per-consumer 状态集合

### 7.2 SSE 重连处理

- **WHEN** SSE 连接断开(`onClosed()`)
- **THEN** 系统 MUST 清空 `sessionTitles` / `idleNotifiedSessions`(`OpenCodeSSEConsumer.kt:308-309`);`SSEEventParser.dedupCache` **不**清空(只由 `stop()` 清)
- **WHEN** watchdog 检测到 30s 内无事件(`SSE_IDLE_TIMEOUT_MS`)
- **THEN** 系统 MUST 强制重连

### 7.3 进程生命周期

- 启动:`OpenCodeServerManager.startServer()` 异步启 opencode → 等健康 → 创建 SSE consumer
- 运行:每 Project 一个 SSE consumer 实例(`SSEConsumerFactory.create(project)`),独立状态
- 关闭:IDE 退出 → `stopServer()` → 4 阶段关闭策略(§6.3)

### 7.4 多项目路由

- 多 IntelliJ 窗口打开同一 opencode server → 每 Project 独立 `OpenCodeSSEConsumer`,各自通过 `OpenCodeApi.createEventSource(directory)` 按 directory 路径消费
- 项目关闭 → `disposeForProject` 停止该项目的 SSE consumer

### 7.5 健康检查机制(Part D 改造后)

**`HealthMonitor` 已在 shutdown-server-fast-path change 中整删**,原 5s 轮询 + 3 次翻转 debounce 语义由两个 SSE 回调替代:

- **`OpenCodeSSEConsumer.onConnectionLost`(快速通道)**:server 主动 shutdown 时 SSE 关闭→`stop()` 主动触发→`showServerNotRunning()` 立即显示 Start 按钮
- **`OpenCodeSSEConsumer.onConnectionEstablished`(恢复通道)**:SSE 重建后 `onOpen()` 末尾 1.5s debounce 触发→`loadProjectPage(force = true)` 自动恢复 UI

**`OpenCodeApi.isServerHealthySync()`**:端口检查 + HTTP HEAD 双段,见 §3.3。**仅在启动期 `waitForServerHealthy` 内部使用**,无运行时轮询。

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

| 引用         | 出处        | 何时使用         |
| ------------ | ----------- | ---------------- |
| 架构实现细节 | `DESIGN.md` | 想了解"怎么做"时 |
| 开发流程规则 | `AGENTS.md` | 日常开发时       |

---

## 附录 A:当前未满足项(Known Gaps)

| ID    | 描述                                                                                                                    | 严重性 | 建议                                              |
| ----- | ----------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------- |
| GAP-1 | `OpenCodeConfigurable` Settings UI 缺失(commit `a5eafc4` 1.0.20 整删);`OpenCodeConfig` 4 个通用设置当前无法通过 UI 配置 | ⚠️ 中  | 重新实现 Settings UI 并补 11 个事件开关/11 个模板 |
| GAP-2 | `sessionTitles` 无硬性 LRU 上限,可能在超长会话中无界增长                                                                | ℹ️ 低  | 典型场景 < 1000,可接受                            |
| GAP-3 | im-select 硬编码开发者个人路径(`OpenCodeConstants.kt:28-29`)                                                            | ℹ️ 低  | 待抽离为插件配置(已 TODO)                         |
| GAP-4 | `subagent_complete` 通知未实现(代码中无该分支)                                                                          | ⚠️ 中  | 配合 GAP-1 Settings UI 补齐事件开关               |
| GAP-5 | `OpenCodeSSEConsumer.sessionTitles` 注册只在 SSE consumer 启动路径中,**非**多保险                                       | ℹ️ 低  | 实际场景下足够(每 Project 独立 consumer 已覆盖)   |
| GAP-6 | `JcefKeyboardInterceptor.interceptKeysRecursive` 是死代码(`interceptKeys` 单层调用)                                     | ℹ️ 低  | 删除方法(不破坏接口)                              |

---

## 附录 B:版本与变更

| 版本   | 日期       | 变更说明                                                                                   |
| ------ | ---------- | ------------------------------------------------------------------------------------------ |
| 1.0.22 | 2026-06-09 | 文档审计:订正 SSE 端点路径、移除不存在的 settings/ProjectActivity 引用、修正集合名与版本号 |
