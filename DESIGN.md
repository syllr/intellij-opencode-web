# DESIGN.md — IntelliJ OpenCode Web 插件设计文档

> **本文档定位**:项目级**架构设计**文档,描述当前生产系统的设计决策、关键机制与组件关系。
>
> **三元组关系**:
>
> - `AGENTS.md` — **How to develop**(开发宪法、流程规则、踩坑经验)
> - `DESIGN.md` — **How it's built**(架构设计、组件关系、关键决策)—— 本文档
> - `SPEC.md` — **What it must do**(系统级行为规范、SLA、安全契约、数据不变量)
>
> **范围**:聚焦**插件整体架构**,包括 SSE 消费、通知路由、subagent 追踪、进程管理。

---

## 0. 项目背景与子系统地图

### 0.1 项目概述

**IntelliJ OpenCode Web 插件**是 JetBrains IDE(2026.1+)的插件,在 IDE 侧边栏嵌入 OpenCode CLI 的 Web UI,并通过 IDE 原生通知通道显示 agent 任务进度。

核心能力:

- 工具窗口(右侧边栏)嵌入 JCEF 浏览器显示 OpenCode Web UI
- SSE 消费 opencode server 事件,实现 4 种 IDE 原生通知(permission / complete / question / 其他模板 fallback)
- 文件变更检测同步 IDE VFS 刷新
- Bash 工具执行检测,决定是否触发 VFS 刷新
- Emacs 风格键盘快捷键 + JCEF 键盘拦截(ESC/Cmd+K/Cmd+,)
- IdeaVim visual 模式兼容(`AddToPromptAction`)
- Go-style Singleflight 防抖,合并重复点击的服务器启动请求

### 0.2 子系统清单

| 子系统         | 路径                                 | 角色                                                                            |
| -------------- | ------------------------------------ | ------------------------------------------------------------------------------- |
| **toolWindow** | `toolWindow/`                        | 工具窗口 + JCEF 浏览器 + 服务器进程 + 键盘拦截 + Singleflight(核心 8 文件)      |
| **listeners**  | `listeners/`                         | SSE 事件消费 + 文件刷新协调(4 文件)                                             |
| **actions**    | `actions/`                           | IDE Actions:Copy as Prompt / Add to Prompt(2 文件)                              |
| **utils**      | `utils/`                             | HTTP API、JS 注入、通知路由/服务、配置、IdeaVim 集成、SSE consumer 工厂(8 文件) |
| **根**         | `OpenCodeConstants.kt` `MyBundle.kt` | 端口/超时/间隔常量 + i18n                                                       |

> **历史**: `settings/OpenCodeConfigurable.kt` 与 `OpenCodeProjectActivity.kt` 已分别在 commit `a5eafc4`(1.0.20)和 commit `95d7faf`(HEAD)整删;`HealthMonitor.kt` 已在 commit `2e7b302`(Part D)整删。

### 0.3 部署拓扑

```
┌────────────────────────────────────────────────────────────────────┐
│                      JetBrains IDE(单进程)                          │
│                                                                    │
│  ┌──────────────┐    ┌──────────────────┐    ┌──────────────────┐    │
│  │  ToolWindow  │───▶│ OpenCodeSSECons- │───▶│ OpenCodeNotifi-  │    │
│  │  (JCEF)      │    │ umer(每 Project   │    │ cationRouter     │    │
│  │              │    │  一个实例)        │    │  (object 10 行)   │    │
│  │  ┌────────┐  │    │   - 文件事件      │    └─────────┬───────┘    │
│  │  │JCEF    │  │    │   - 通知事件      │              │            │
│  │  │Browser │  │    │   - Bash 事件      │              ▼            │
│  │  └────────┘  │    └──────────────────┘    ┌──────────────────┐    │
│  └──────────────┘              │              │ OpenCodeNotifi-  │    │
│                                │              │ cationService    │    │
│                                │              │  - 通知发送     │    │
│                                │              │  - 模板占位符    │    │
│                                │              └──────────────────┘    │
│                                ▼                                          │
│                  ┌────────────────────┐                                  │
│                  │  FullRefreshCoord-  │                                  │
│                  │  inator(防抖+调度)  │                                  │
│                  └────────────────────┘                                  │
│                                                                    │
│         IDE 进程内(单 JBR 运行时)                                   │
└──────────────┬─────────────────────────────────────────────────────┘
                │ HTTP + SSE  (127.0.0.1:12396/event?directory=<path>)
                ▼
┌────────────────────────────────────────┐
│      OpenCode CLI 进程(用户启动)        │
│      - HTTP /global/health             │
│      - SSE  /event?directory=...        │
│      - POST /global/dispose            │
│      - GET  /session/:id               │
└────────────────────────────────────────┘
```

**关键事实**:

- IDE 进程与 OpenCode CLI 是**两个独立进程**,通过 localhost:12396 通信
- 每 Project 一个 `OpenCodeSSEConsumer` 实例(`SSEConsumerFactory.create(project)`),独立维护 `sessionTitles` / `idleNotifiedSessions` 状态
- OpenCode CLI 启动方式: `/bin/zsh -l -c "source ~/.zshrc && opencode serve --hostname 127.0.0.1 --port 12396"`(为加载用户 `.zshrc` 中的 PATH)

---

## 1. 架构核心决策

### 1.1 核心矛盾(已解决)

| 矛盾                                        | 解决方案                                                                                |
| ------------------------------------------- | --------------------------------------------------------------------------------------- |
| 单 IDE 实例多 Project 打开,SSE 事件如何隔离 | 每 Project 独立 `OpenCodeSSEConsumer` 实例,经 `SSEConsumerFactory.create(project)` 获取 |
| Subagent 通知会污染主会话,需区分            | `OpenCodeSSEConsumer.sessionTitles` + title 正则(`SUBAGENT_TITLE_REGEX`)本地追踪        |
| OpenCode 进程可能崩溃,IDE 必须能清理        | `OpenCodeServerManager` 4 阶段关闭策略(§3.2)                                            |
| HTTP 调用阻塞 SSE 事件分发                  | 1h TTL `SessionInfoCache` + 3 级 fallback 字段提取                                      |

### 1.2 关键决策

| 决策                        | 方案                                                                                                                                           | 收益                                                         |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| **进程隔离**                | IDE 进程内仅做客户端消费,OpenCode CLI 独立进程                                                                                                 | 故障隔离 + 利用 OpenCode 已有的能力                          |
| **SSE 复用**                | 复用单个 `/event?directory=<path>` SSE 连接消费所有事件(文件 + 通知 + bash),连接按 project 路径分流                                            | 一个连接处理所有事件类型                                     |
| **每 Project 一 consumer**  | 通过 `SSEConsumerFactory.create(project)` 单例工厂创建独立 consumer(全局 SSE 连接由 `OpenCodeApi.createEventSource(directory)` 按目录路径建立) | 多项目状态隔离 + 简化协调                                    |
| **Subagent 追踪**           | 本地 `sessionTitles: ConcurrentHashMap<String, String>` + title 正则(`OpenCodeSSEConsumer.SUBAGENT_TITLE_REGEX`)+ `session.created/updated` 增 | 无 HTTP 调用,无竞态                                          |
| **通知去抖**                | per-consumer `idleNotifiedSessions` 集合(LRU 1000),`add() return false` 原子拦截重复 idle(`OpenCodeSSEConsumer.kt:286`)                        | 防止 `session.idle` + `session.status(idle)` 双发            |
| **手动启动 + Singleflight** | Go-style `Singleflight.kt` leader/follower 模式 + `MyToolWindow.isServerReadyHandled` CAS 守卫拦截重复 `onServerReady` 回调                    | 并发/重复点击合并为单次进程启动                              |
| **通知双模式**              | IDE 前台 + 项目窗口有焦点 → BALLOON;IDE 后台 → macOS 系统通知;多显示器无焦点 → 当前实现静默丢弃(已知限制,详见 §6.2 / `SPEC.md §4.4`)           | 覆盖主要使用场景 + 点击跳转正确 IDE                          |
| **配置存储**                | `PropertiesComponent`(IDE 级别 key-value),无独立文件                                                                                           | 零开销读 + 自动持久化                                        |
| **进程启动**                | zsh login shell(`-l -c "source ~/.zshrc && opencode serve..."`)                                                                                | 加载用户 `.zshrc` 中的 PATH,确保 opencode 可执行文件可被找到 |
| **HTTP 客户端**             | `java.net.http.HttpClient` by lazy 共享(无 OkHttp 依赖)                                                                                        | JDK 内置,自动 keep-alive 连接池                              |
| **工厂解耦**                | `SSEConsumerFactory.create(project)` 单例工厂                                                                                                  | 工具类 `toolWindow` → `listeners` 依赖单向,无循环引用        |

### 1.3 设计原则

1. **职责单一**:每个类只做一件事(消费 / 路由 / 发送 / 存储)
2. **副作用最小化**:SSE 事件处理纯函数化,只在需要时触发副作用
3. **可降级**:HTTP 调用失败 → 占位符模板;SSE 断开 → 自动重连;进程崩溃 → 4 阶段关闭
4. **可观测**:所有关键路径都有 `thisLogger()` 日志(INFO/WARN/ERROR/DEBUG),DEBUG 日志在生产关闭时零开销(`isDebugEnabled` 守卫)

---

## 2. 关键子系统

### 2.1 `OpenCodeServerManager`(全局单例 `object`)

**职责**:opencode 子进程的生命周期管理 + SSE consumer 单例管理。

**状态字段**(全部用 `AtomicReference` / `AtomicBoolean` / `@Volatile` ):

```kotlin
object OpenCodeServerManager {
    private val serverProcess: AtomicReference<Process?>
    @Volatile private var sseConsumer: OpenCodeSSEConsumer?
    private val consumerProjectRef: AtomicReference<WeakReference<Project>?>
    private val shutdownInProgress: AtomicBoolean
}
```

**关键方法**:

- `startServer(project, onStarted, onFailed)`:异步启 opencode + 等健康 + 创建 SSE consumer
- `ensureSSEConsumer(project)`:获取或创建 SSE consumer(单例),处理多项目
- `disposeForProject(project)`:项目关闭时清理(只在属于该 project 时停止)
- `stopServer()`:IDE 退出时关闭(`serverProcess` 引用路径)
- `shutdownServer()`:用户手动 Shutdown(`findProcessByPort` 路径)
- 共享 `gracefulShutdown(acquireHandle, killFallback, errorTag)`:两个关闭路径共享

### 2.2 `OpenCodeSSEConsumer`(BackgroundEventHandler)

**职责**:消费 SSE 事件流,按事件类型分发给:文件刷新 / 通知 / bash 处理。**每 Project 一个实例**,独立维护 `sessionTitles` / `idleNotifiedSessions` 状态。

**事件分发架构**(在 `onMessage()` line 153+):

```
onMessage(event, messageEvent)
    │
    ├── 1. SSEEventParser.parse() → ParsedSSEEvent
    │
    ├── 2. dedup 检查 (payload.id)
    │
    ├── 3. eventType = payload.type ?: return
    │
    ├── 4. session.created / session.updated → sessionTitles.put(sessionID, title)
    │      session.deleted → DEBUG 日志(不删除追踪,防竞态)
    │
    ├── 5. when(eventType) 通知事件分发
    │      session.status(idle)  → handleSessionIdle
    │      session.idle          → handleSessionIdle
    │      permission.asked      → permission 通知
    │      question.asked        → question 通知
    │      message.updated(user) → idleNotifiedSessions.remove(不触发通知)
    │
    ├── 6. 文件事件处理
    │      session.diff / file.edited / file.watcher.updated → FullRefreshCoordinator
    │      message.part.updated → BashCommandHandler
    │
    └── 7. directory 匹配检查 (eventDir == projectDir)
```

**两层去重(`handleSessionIdle`)**(从外到内,任一命中即跳过):

1. **子 agent 识别**:title 匹配 `SUBAGENT_TITLE_REGEX`(匹配 `@<agent> subagent` 模式) → 抑制 complete 通知(`OpenCodeSSEConsumer.kt:277`)
2. **父 session 抑制**:`!idleNotifiedSessions.add(sessionID)` → 抑制(`OpenCodeSSEConsumer.kt:286`)
3. **用户新消息重置**:`message.updated(role=user)` 时 `idleNotifiedSessions.remove(sessionID)`(`OpenCodeSSEConsumer.kt:235`)

### 2.3 `SSEEventParser`(object)

**职责**:解析 SSE 事件 JSON,统一为 `ParsedSSEEvent`。

**3 级 sessionID fallback**(`extractSessionID()` 扩展函数,`SSEEventParser.kt:37-43`):

```kotlin
fun ParsedSSEEvent.extractSessionID(): String? {
    val props = parsedMap?.get("properties") as? Map<*, *>
    val info = props?.get("info") as? Map<*, *>
    return props?.get("sessionID") as? String        // 1. payload.properties.sessionID
        ?: info?.get("sessionID") as? String         // 2. payload.properties.info.sessionID
        ?: info?.get("id") as? String                // 3. payload.properties.info.id
}
```

新 wire 格式(`/event?directory=...` 端点)直出 `{id, type, properties}` 三键,无外层包装,故只需在 `properties.*` 路径内做 3 级 fallback。**已废弃**:旧 wire 格式的 `data.*` / `syncEvent.aggregateID` 路径不再使用(代码 grep 0 命中)。

完整字段位置解释见 `SSEEventParser.kt:21-43` 的 KDoc。

**`parse()` 高频事件快速路径**(`message.part.delta` 跳过 Map 转换):节省每事件 ~2-5KB 对象分配。

### 2.4 `OpenCodeNotificationRouter`(object,极简直通)

**职责**:作为 utils 与 listeners 之间的薄层解耦点。当前实现是单行转发。

**实现**(`OpenCodeNotificationRouter.kt:1-10`):

```kotlin
object OpenCodeNotificationRouter {
    fun notify(eventType: String, properties: Map<*, *>?, project: Project) {
        OpenCodeNotificationService.send(eventType, properties, project)
    }
}
```

**注意**:文档早期版本描述的"按 directory 多项目路由"机制**当前未实现**。多项目隔离通过 `OpenCodeSSEConsumer` per-instance 状态(`sessionTitles`、`idleNotifiedSessions`)实现,每个 Project 持有独立 consumer,无需 directory → Project 注册表。

### 2.5 `OpenCodeNotificationService`(object)

**职责**:构造通知内容(模板 + 占位符) + 双模式发送(BALLOON + SystemNotifications)。

**1h TTL SessionInfoCache**:消除通知路径上的同步 HTTP 调用,见 `SPEC.md §1.2`。

**双模式通知流程**(参考 `OpenCodeNotificationService.kt:62-113`):

```kotlin
fun send(eventType: String, properties: Map<*, *>?, project: Project) {
    // 1. 工具窗口聚焦抑制(用户正在与 AI 对话,无需打扰)
    val tw = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")
    if (tw?.isVisible == true && tw.isActive) return

    // 2. minDuration 过滤(仅 complete;subagent_complete 未实现)
    if (eventType == "complete" && OpenCodeConfig.minDuration > 0) {
        val info = SessionInfoCache.getOrFetch(sessionID) ?: ...
        if (duration < minDuration) return
    }

    // 3. 构造 title + body

    // 4. 焦点感知路由(双模式 + 已知多显示器限制,见 SPEC.md §4.4)
    val frame = WindowManager.getInstance().getFrame(project)
    val projectWindowActive = frame != null && frame.isActive
    ApplicationManager.getApplication().invokeLater {
        try {
            if (projectWindowActive && !project.isDisposed) {
                // BALLOON(项目窗口有焦点)
                val notification = Notification(NOTIFICATION_GROUP, title, body, resolveType(eventType))
                addClickAction(notification, eventType, project)
                notification.notify(project)
            } else if (!ApplicationManager.getApplication().isActive()) {
                // macOS 系统通知(IDE 在后台)
                SystemNotifications.getInstance().notify(NOTIFICATION_GROUP, title, body)
            }
            // 注意:frame 不活跃 && application 活跃(多显示器无焦点) → 当前实现静默丢弃,详见 SPEC §4.4
        } catch (e: Exception) {
            logger.warn("[OpenCodeNotificationService] Failed to send notification: ${e.message}")
        }
    }
}
```

**消息模板占位符**:`{sessionTitle}` `{projectName}` `{timestamp}` `{agentName}`

### 2.6 `OpenCodeConfig`(object,PropertiesComponent 包装)

**职责**:

- 4 个通用设置(`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`)
- **未实现**的事件级别开关(11 个事件类型)和消息模板(11 个模板),总计预期 26 个 key-value —— 当前 GAP-1

**Key 命名**:

- `opencode.settings.{name}` — 4 个通用设置

**当前实现**(25 行,`OpenCodeConfig.kt`):

```kotlin
object OpenCodeConfig {
    var notificationEnabled: Boolean
    var showProjectName: Boolean
    var showSessionTitle: Boolean
    var minDuration: Int
}
```

**未实现**:`isEventEnabled` / `setEventEnabled` / `getMessageTemplate` / `setMessageTemplate` API;`ALL_EVENT_TYPES` / `defaultEvents()`;Settings UI(`plugin.xml` 无 `<applicationConfigurable>` 注册)。详见 SPEC 附录 A GAP-1。

### 2.7 `MyToolWindowFactory`(ToolWindowFactory)

**职责**:工具窗口入口,创建 JCEF 浏览器面板并注册各类 listener。

**焦点恢复 listener**:在 `createToolWindowContent()` 末尾通过 `toolWindow.component.addFocusListener(FocusAdapter)` 监听焦点获得事件,在用户主动切换到 OpenCodeWeb 工具窗口 tab 时自动调用 `MyToolWindowFactory.resetToolWindow(project)`(hide + activate 窗口级焦点恢复)。**历史方案曾用 `ToolWindowManagerListener.stateChanged`(位于 `com.intellij.openapi.wm.ex` 包),但被 JetBrains Marketplace Validator 标为 internal API,1.0.25 publish 报告确认。已替换为完全公开 API**。防抖机制:1.5s `AtomicLong`(`lastResetAt`)阻挡 reset 内部 activate 循环 + 内部 input 切换误触。listener 通过 `Disposer.register(toolWindow.disposable)` 反注册(IntelliJ Platform 标准 disposable 模式),工具窗口关闭时自动清理,无内存泄漏。

---

## 3. 关键时序

### 3.1 启动时序

```
用户点击 IDE 侧边栏 OpenCode 图标
    │
    ▼
MyToolWindowFactory.createToolWindowContent()
    │  - 创建 BrowserPanel
    │  - OpenCodeServerManager.ensureSSEConsumer(project)
    │      │
    │      └─ sseConsumer == null → startServer()
    │            │
    │            └─ Backgroundable task:
    │                  │
    │                  ├─ OpenCodeApi.isServerHealthySync()
    │                  │   ├─ true  → ensureSSEConsumer → onStarted
    │                  │   └─ false → startOpenCodeProcess()
    │                  │             │
    │                  │             └─ /bin/zsh -l -c "source ~/.zshrc && opencode serve --hostname 127.0.0.1 --port 12396"
    │                  │
    │                  ├─ OpenCodeApi.waitForServerHealthy(30s) (轮询 2s)
    │                  │   └─ healthy → ensureSSEConsumer → onStarted
    │                  │   └─ timeout → killProcessTreeByHandle → onFailed
    │                  │
    │                  └─ onStarted() 回调 → MyToolWindowFactory.openOpenCodeWebToolWindow
    │
    └─ 工具窗口显示 JCEF 浏览器 → opencode web UI 加载
```

### 3.2 SSE 事件流时序(以 subagent 完成为例)

```
OpenCode CLI
    │
    ├─ session.created [subagent]  ─────────→ OpenCodeSSEConsumer
    │                                              │
    │                                              ├─ extractSessionID() → "ses_sub"
    │                                              ├─ sessionTitles.put("ses_sub", "@code-reviewer subagent: review foo")
    │                                              └─ return
    │
    ├─ [subagent 工作流 ... file.edited, message.part.delta ...]
    │
    ├─ session.status(type=idle) [subagent] ───→ handleSessionIdle
    │                                              │
    │                                              ├─ title 匹配 SUBAGENT_TITLE_REGEX → 抑制 complete 通知
    │                                              └─ return
    │
    ├─ message.updated(role=user) ──────────────→ idleNotifiedSessions.remove(如果父 session 发新消息)
    │
    └─ session.status(type=idle) [parent] ──────→ handleSessionIdle
                                                    │
                                                    ├─ title 不匹配 SUBAGENT_TITLE_REGEX
                                                    ├─ idleNotifiedSessions.add(parentSID) 返回 true
                                                    ├─ 发 complete 通知
                                                    └─ return
```

### 3.3 关闭时序(IDE 退出)

```
IDE 退出触发 OpenCodeServerManager.stopServer()
    │
    ├─ shutdownInProgress CAS (true) → 防重入
    │
    ├─ 停止 SSE consumer
    │   synchronized(this) { sseConsumer?.stop(); sseConsumer = null }
    │
    ├─ serverProcess.getAndSet(null) → Process
    │   if null → return (server was externally started)
    │
    ├─ startDisposeThread() 异步发 POST /global/dispose (2s 客户端超时,不等)
    │
    ├─ handle.onExit().get(5s)  ← 真实退出时间
    │   ├─ 成功 → return
    │   └─ TimeoutException / InterruptedException → 继续
    │
    ├─ handle.destroy() (SIGTERM)
    │   handle.onExit().get(2s)
    │   ├─ 成功 → return
    │   └─ TimeoutException / InterruptedException → 继续
    │
    └─ killProcessTreeByPort() (SIGKILL)  ← 兜底
```

---

## 4. 核心机制

### 4.1 Subagent 本地追踪

**Why**:用 `session.created(parentID)` 识别子 agent,本地 Map 追踪,避免 HTTP API 查 `parentID` 的竞态。

**How**:

- 加入:`session.created` / `session.updated` 事件携带 `properties.info.title` → `sessionTitles.put(sid, title)`(`OpenCodeSSEConsumer.kt:225-227`)
- 查询:`handleSessionIdle` 中检查 `title` 是否匹配 `SUBAGENT_TITLE_REGEX`(匹配 `@<agent> subagent` 模式)决定是否抑制 complete 通知
- 清理:**不**在 `session.deleted` 移除(防 `session.deleted` 先于 `session.status(idle)` 到达的竞态),只在 SSE 重连和 `stop()` 时清空

**容量**:无硬性 LRU 上限(per-instance,典型场景 < 1000)。

### 4.2 通知两层去重

参考 `SPEC.md §7.1`。

| 层级 | 触发条件                                               | 抑制目标                                               |
| ---- | ------------------------------------------------------ | ------------------------------------------------------ |
| 1    | title 匹配 `SUBAGENT_TITLE_REGEX`(`@<agent> subagent`) | 子 agent 误发 complete(`OpenCodeSSEConsumer.kt:277`)   |
| 2    | `!idleNotifiedSessions.add(sessionID)`                 | 父 session 重复 complete(`OpenCodeSSEConsumer.kt:286`) |
| -    | `message.updated(role=user)` 重置抑制                  | 用户新消息(`OpenCodeSSEConsumer.kt:235`)               |

### 4.3 SessionInfo 1h LRU 缓存

**Why**:通知路径上 `getSession` 同步 HTTP 调用会阻塞 SSE 事件线程。

**How**:

- `SessionInfoCache` 私有 `object`,`ConcurrentHashMap<String, Entry>`
- `Entry(info: SessionInfo, cachedAt: Long)`,TTL 1h
- `get(sessionID)` 命中且未过期 → 返回;过期或未命中 → 调 `OpenCodeApi.getSession`
- **不**缓存会变的字段(title),只缓存稳定的 `timeCreated`

### 4.4 SSE Watchdog + 自动重连

```
watchdogThread (daemon)
    │
    └─ while (!interrupted) {
         Thread.sleep(SSE_WATCHDOG_INTERVAL_MS = 5s)
         if (now - lastEventAt > SSE_IDLE_TIMEOUT_MS = 30s) reconnect()
       }
```

**重连流程**:

1. `eventSourceRef.getAndSet(null)`
2. `startSseConnection()` 新连接
3. `old?.close()` 旧连接
4. `connectionGen.incrementAndGet()` 旧 consumer 的 `onClosed` 自动短路(generation 不匹配)

**重连后清理**(在 `onClosed()`,per-instance 状态):

- `sessionTitles.clear()` (`OpenCodeSSEConsumer.kt:308`)
- `idleNotifiedSessions.clear()` (`OpenCodeSSEConsumer.kt:309`)

**`SSEEventParser.dedupCache` 不在 `onClosed()` 清空**,仅由 `stop()` 清空(`SSEEventParser.clearCache()`,`SSEEventParser.kt:98`)。

### 4.5 ProcessHandle 进程树清理

`OpenCodeServerManager.killProcessTreeByHandle(process)`(用于 `startServer` 启动 30s 超时):

```kotlin
val descendants = process.toHandle().descendants().toList()
descendants.reversed().forEach { it.destroyForcibly() }  // 叶子到根
process.toHandle().destroyForcibly()
handle.onExit().get(3s)  // 等待确认
```

**只杀目标进程的后代**,不会误杀同 PGID 的无关进程。

`OpenCodeServerManager.killProcessTreeByPort()`(用于 `gracefulShutdown` 兜底):

通过 `lsof -tiTCP:$PORT` 找主进程 PID,递归调用 `pgrep -P $pid` 找所有子进程,按从叶到根顺序发 SIGKILL,sh 脚本整体超时 5s。

---

## 5. SSE 事件协议详解

### 5.1 当前 wire 格式(`/event?directory=...` 端点)

```json
{
  "id": "evt_e208271300012MZGbu2gNc47xU",
  "type": "session.status",
  "properties": {
    "sessionID": "ses_xxx",
    "info": {
      "id": "ses_xxx",
      "parentID": "ses_parent",
      "title": "@code-reviewer subagent: review foo"
    },
    "status": { "type": "idle" }
  }
}
```

- `id` 用于去重(`SSEEventParser.dedupCache`)
- `type` 用于直接路由(无 SyncEvent 嵌套)
- `properties` 包含 sessionID / info.title / parentID / status.type
- **已废弃**:旧 SyncEvent V2 格式(`payload.type == "sync"` + `syncEvent.aggregateID`)不再使用

### 5.2 完整事件 → 通知映射

| SSE 事件                               | 通知事件类型 | 触发条件                                                                      |
| -------------------------------------- | ------------ | ----------------------------------------------------------------------------- |
| `session.status(type=idle)`            | `complete`   | 父 session 完成(`OpenCodeSSEConsumer.kt:286`)                                 |
| `session.status(type=idle)`            | (抑制)       | 子 agent 完成(title 匹配 `SUBAGENT_TITLE_REGEX`,`OpenCodeSSEConsumer.kt:277`) |
| `session.idle`(deprecated)             | 同上         | 兼容旧格式                                                                    |
| `permission.asked`                     | `permission` | OpenCode 申请权限                                                             |
| `question.asked`                       | `question`   | 询问用户                                                                      |
| `message.updated(role=user)`           | (无通知)     | 仅移除 `idleNotifiedSessions`(重置抑制)                                       |
| `file.edited` / `file.watcher.updated` | (无通知)     | VFS 刷新                                                                      |
| `session.diff`                         | (无通知)     | VFS 刷新                                                                      |
| `message.part.updated`                 | (无通知)     | BashCommandHandler 检测 bash 工具                                             |
| `message.part.delta`                   | (无通知)     | 高频流式文本增量,SKIP_PARSE 快速路径直接 return                               |

**未实现的映射**(`OpenCodeNotificationService.formatMessage` 中 hardcode 3 个事件模板 `permission` / `complete` / `question`,其余 fallback 到 `eventType` 字符串本身):

- `session.error` → `error` / `user_cancelled`
- `message.updated(role=user)` → `user_message`
- `server.connected` → `client_connected`
- `session.next.tool.called(tool=plan_exit)` → `plan_exit`
- `session.created`(无 parentID) → `session_started`
- `subagent_complete`(独立分支)

---

## 6. 通知路由与去重

### 6.1 路由总览(简化,详见 §2.4)

```
OpenCodeSSEConsumer.onMessage()
    │
    ▼
OpenCodeNotificationRouter.notify(eventType, properties, project)  // 10 行直通
    │
    ▼
OpenCodeNotificationService.send()
    │
    ├─ 工具窗口聚焦抑制 → 跳过
    ├─ notificationEnabled 关 → 跳过
    ├─ minDuration 过滤 (仅 complete)
    └─ invokeLater (EDT) 发送
```

### 6.2 通知双模式(决策表,实现见 §2.5)

| 场景                                         | 通知方式                                               |
| -------------------------------------------- | ------------------------------------------------------ |
| OpenCodeWeb 工具窗口可见且活跃               | ❌ 抑制(用户正在与 AI 对话,无需通知)                   |
| IDE 在前台 + 项目窗口有焦点                  | BALLOON(右下角 IDE 弹窗)                               |
| IDE 在后台(切到浏览器/Slack)+ 项目窗口无焦点 | macOS 系统通知(`SystemNotifications`)                  |
| IDE 在前台 + 项目窗口无焦点(多显示器)        | ❌ 静默丢弃(已知行为,详见 `SPEC.md §4.4` 焦点感知路由) |
| 窗口最小化                                   | ⚠️ 依赖 macOS + IntelliJ 行为(可能触发系统通知)        |

### 6.3 通知去重

| 抑制类型                                     | 机制                                                                   |
| -------------------------------------------- | ---------------------------------------------------------------------- |
| 同一事件 BusEvent + SyncEvent 双发           | `dedupCache`(payload.id,LRU 1000)                                      |
| `session.idle` + `session.status(idle)` 双发 | `idleNotifiedSessions` per-consumer 集合(`add() return false` 拦截)    |
| 父 session 重复 complete                     | `idleNotifiedSessions` 集合 + `message.updated(user)` 重置             |
| subagent 误发 complete                       | `sessionTitles` + `SUBAGENT_TITLE_REGEX` + `handleSessionIdle` 第 1 层 |

---

## 7. 资源管理

### 7.1 Process 资源

| 资源                                            | 释放方式                                                                         |
| ----------------------------------------------- | -------------------------------------------------------------------------------- |
| `OpenCodeServerManager.serverProcess`           | `gracefulShutdown` 4 阶段(§3.3) + `killProcessTreeByPort` 兜底                   |
| `OpenCodeServerManager.startDisposeThread`      | daemon Thread,`isDaemon = true`                                                  |
| `OpenCodeServerManager.pipeToLogger` (×2)       | daemon Thread 读 stdout/stderr,`IOException` 静默退出,其他异常 warn 日志         |
| `OpenCodeServerManager.findProcessByPort`       | `try-finally` 关闭 Process + `waitFor(3s)`,超时 `destroyForcibly`                |
| `OpenCodeServerManager.killProcessTreeByHandle` | `try` 内递归杀后代 + 根,`onExit(3s)` 等待;`catch` 兜底走 `killProcessTreeByPort` |
| `OpenCodeServerManager.killProcessTreeByPort`   | `try-finally` 关闭 shell 进程 + `waitFor(5s)`,超时 `destroyForcibly`             |

### 7.2 SSE / HTTP 资源

| 资源                                            | 释放方式                                              |
| ----------------------------------------------- | ----------------------------------------------------- |
| `okhttp-eventsource` SSE 连接(`eventSourceRef`) | `eventSourceRef.getAndSet(null)?.close()` in `stop()` |
| `OpenCodeApi.sharedHttpClient`(`by lazy`)       | IDE 退出时 JVM 关闭                                   |
| `BufferedReader` in `findProcessByPort`         | `try-finally` 关闭(实测短命进程,GC 兜底)              |

### 7.3 LRU 缓存

| 集合                                           | 容量                  | 线程安全                                     |
| ---------------------------------------------- | --------------------- | -------------------------------------------- |
| `SSEEventParser.dedupCache`                    | 1000                  | `Collections.synchronizedMap(LinkedHashMap)` |
| `OpenCodeSSEConsumer.sessionTitles`            | 无界                  | `ConcurrentHashMap`                          |
| `OpenCodeSSEConsumer.idleNotifiedSessions`     | 1000                  | `Collections.synchronizedSet(LinkedHashMap)` |
| `OpenCodeNotificationService.SessionInfoCache` | 无界(1h TTL 自然淘汰) | `ConcurrentHashMap`                          |

### 7.4 Thread 管理

| Thread                                     | 类型                       | 清理                        |
| ------------------------------------------ | -------------------------- | --------------------------- |
| `OpenCodeSSEConsumer.watchdogThread`       | daemon                     | `interrupt()` in `stop()`   |
| `OpenCodeServerManager.startDisposeThread` | daemon (one-shot)          | 自然结束                    |
| `OpenCodeServerManager.pipeToLogger` (×2)  | daemon                     | 进程退出时 IOException 退出 |
| `FullRefreshCoordinator.scheduler`         | `ScheduledExecutorService` | `shutdownNow()` in `stop()` |

### 7.5 Alarm / Editor / MessageBusConnection

| 资源                                                   | 释放方式                                                                                                            |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- |
| `JcefJsInjector` 注入 JS 的重试 `Alarm`                | 不显式 dispose,`Alarms` 在项目关闭时自动清理                                                                        |
| `BrowserPanel.disposeBrowser()` 释放 3 个 JCEF handler | `contextMenuHandler` / `loadHandler` / `displayHandler` 全部 `remove*Handler` + `null` 引用清理                     |
| `MyToolWindowFactory.init` 块的 `MessageBusConnection` | 无 parent disposable,跟随 application 生命周期                                                                      |
| `MyToolWindowFactory.contentManagerListeners`          | 项目关闭时移除(配对 `try-catch IllegalStateException` 防御项目已 dispose)                                           |
| `MyToolWindowFactory.sharedJBCefClient`                | JVM 退出时清理(全局单例,`by lazy` 初始化时设置 `ide.browser.jcef.gpu.disable=false` + `--use-angle=metal` 系统属性) |
| `MyToolWindow` `focusListenerWindow` + `focusListener` | 浏览器 panel 重新加入 hierarchy 时移除旧 listener,防累积                                                            |

---

## 8. 关键文件路径速查

| 想做的事                                      | 改哪里                                                      |
| --------------------------------------------- | ----------------------------------------------------------- |
| 工具窗口入口/布局/JCEF 焦点恢复               | `toolWindow/MyToolWindowFactory.kt`(含 `resetToolWindow()`) |
| 浏览器面板/生命周期                           | `toolWindow/MyToolWindow.kt` + `BrowserPanel.kt`            |
| 服务器进程启停(4 阶段关闭)                    | `toolWindow/OpenCodeServerManager.kt`                       |
| Singleflight 防抖(手动启动去重)               | `toolWindow/Singleflight.kt`                                |
| SSE 消费、降噪、自动重连                      | `listeners/OpenCodeSSEConsumer.kt`                          |
| SSE 解析(含 3 级 sessionID fallback)          | `listeners/SSEEventParser.kt`                               |
| Bash 工具事件检测                             | `listeners/BashCommandHandler.kt`                           |
| 文件刷新(生产者-消费者)                       | `listeners/FullRefreshCoordinator.kt`                       |
| HTTP API(健康/session 等)                     | `utils/OpenCodeApi.kt` + `OpenCodeApiResult.kt`             |
| 通知发送(IDEA 原生 + macOS 系统)              | `utils/OpenCodeNotificationService.kt`                      |
| 通知路由(直通薄层)                            | `utils/OpenCodeNotificationRouter.kt`                       |
| 通知配置(4 个通用设置)                        | `utils/OpenCodeConfig.kt`                                   |
| SSE consumer 单例工厂                         | `utils/SSEConsumerFactory.kt`                               |
| JCEF JS 注入                                  | `utils/JcefJsInjector.kt`                                   |
| IdeaVim visual 模式选区(注入 JS 路径)         | `utils/IdeaVimIntegration.kt`                               |
| 复制为 Prompt 格式                            | `actions/CopyAsPromptAction.kt`                             |
| 选中代码 → Prompt 编辑器(含 im-select 硬编码) | `actions/AddToPromptAction.kt`                              |
| 端口/超时/间隔常量(端口 12396)                | `OpenCodeConstants.kt`                                      |

---

## 附录 A:已知的设计权衡

| 决策                                                                                         | 权衡                                                                                                       |
| -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| 每 Project 一个 `OpenCodeSSEConsumer` 实例                                                   | 多项目状态天然隔离,无需 directory → Project 注册表                                                         |
| 静态 `companion object` 集合(`idleNotifiedSessions` / `dedupCache` / `SUBAGENT_TITLE_REGEX`) | 违反 AGENTS.md "禁止静态全局可变状态" HARD RULE,但有前置条件(per-consumer 单例约束),已加 NOTE              |
| `OpenCodeConfig` 用 `PropertiesComponent` 而非 `PersistentStateComponent`                    | 简单 key-value 无需对象序列化,代价是不支持嵌套结构(本项目无此需求)                                         |
| `SessionInfoCache` 1h TTL                                                                    | stale 1h 可接受(只缓存 timeCreated,title 不缓存),代价是错过 1h 内的实时变更                                |
| `zsh -l` 启动 opencode                                                                       | 必须 login shell 加载 `.zshrc` 中的 PATH,代价是启动慢 0.5-2s(原 `logDiagnosticEnvironment` 调试代码已删除) |
| `LruSet` 用 `Collections.synchronizedMap(LinkedHashMap)` 而非 `ConcurrentHashMap`            | access-order LRU 需要整体同步(LinkedHashMap 不支持),synchronizedMap 在低竞争下开销可接受                   |
| `OpenCodeNotificationRouter` 当前是 10 行直通(无 directory 路由)                             | 历史上预期按 directory → Project 路由,实际未实现(SPEC GAP-5);当前 per-consumer 隔离已覆盖需求              |
| `JcefKeyboardInterceptor.interceptKeysRecursive` 死代码                                      | 保留为工具方法供未来递归场景使用,当前只调单层 `interceptKeys`(SPEC GAP-6)                                  |
