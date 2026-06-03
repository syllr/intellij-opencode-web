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
- SSE 消费 opencode server 事件,实现 11 种 IDE 原生通知
- 文件变更检测同步 IDE VFS 刷新
- Bash 工具执行检测,决定是否触发 VFS 刷新
- Emacs 风格键盘快捷键 + JCEF 键盘拦截(ESC/Cmd+K/Cmd+,)
- IdeaVim visual 模式兼容(`AddToPromptAction`)

### 0.2 子系统清单

| 子系统         | 路径                                 | 角色                                                        |
| -------------- | ------------------------------------ | ----------------------------------------------------------- |
| **toolWindow** | `toolWindow/`                        | 工具窗口 + JCEF 浏览器 + 服务器进程 + 键盘拦截(核心 9 文件) |
| **listeners**  | `listeners/`                         | SSE 事件消费 + 文件刷新协调(4 文件)                         |
| **actions**    | `actions/`                           | IDE Actions:复制/添加到 Prompt、IdeaVim 集成(2 文件)        |
| **utils**      | `utils/`                             | HTTP API、JS 注入、通知路由/服务、配置(8 文件)              |
| **settings**   | `settings/`                          | Settings UI:`OpenCodeConfigurable`(1 文件)                  |
| **根**         | `OpenCodeConstants.kt` `MyBundle.kt` | 端口/超时/间隔常量 + i18n                                   |

### 0.3 部署拓扑

```
┌────────────────────────────────────────────────────────────────────┐
│                      JetBrains IDE(单进程)                          │
│                                                                    │
│  ┌──────────────┐    ┌──────────────────┐    ┌──────────────────┐    │
│  │  ToolWindow  │───▶│ OpenCodeSSECons- │───▶│ OpenCodeNotifi-  │    │
│  │  (JCEF)      │    │ umer (单例)       │    │ cationRouter     │    │
│  │              │    │   - 文件事件      │    │   - Project 路由 │    │
│  │  ┌────────┐  │    │   - 通知事件      │    └─────────┬───────┘    │
│  │  │JCEF    │  │    │   - Bash 事件      │              │            │
│  │  │Browser │  │    └──────────────────┘    ┌───────────▼──────┐    │
│  │  └────────┘  │              │                │ OpenCodeNotifi-  │    │
│  └──────────────┘              │                │ cationService    │    │
│                                ▼                │   - 通知发送     │    │
│                  ┌────────────────────┐         │   - 路由到 Project │    │
│                  │  FullRefreshCoord-  │         └──────────────────┘    │
│                  │  inator(防抖+调度)  │                                │
│                  └────────────────────┘                                │
│                                                                    │
│         IDE 进程内(单 JBR 运行时)                                   │
└──────────────┬─────────────────────────────────────────────────────┘
               │ HTTP + SSE  (127.0.0.1:12396)
               ▼
┌────────────────────────────────────────┐
│      OpenCode CLI 进程(用户启动)        │
│      - HTTP /global/health             │
│      - SSE  /global/event               │
│      - POST /global/dispose            │
│      - GET  /session/:id              │
│      - GET  /session?directory=...     │
└────────────────────────────────────────┘
```

**关键事实**:

- IDE 进程与 OpenCode CLI 是**两个独立进程**,通过 localhost:12396 通信
- 单 IDE 实例 → 单 OpenCodeServerManager 单例 → 单 SSE consumer(共享给所有打开的 Project)
- OpenCode CLI 启动方式: `/bin/zsh -l -c "source ~/.zshrc && opencode serve --hostname 127.0.0.1 --port 12396"`(为加载用户 `.zshrc` 中的 PATH)

---

## 1. 架构核心决策

### 1.1 核心矛盾(已解决)

| 矛盾                                        | 解决方案                                                     |
| ------------------------------------------- | ------------------------------------------------------------ |
| 单 IDE 实例多 Project 打开,SSE 事件如何路由 | `OpenCodeNotificationRouter` 用 `directory → Project` 注册表 |
| Subagent 通知会污染主会话,需区分            | `OpenCodeSSEConsumer` 静态 `subagentSessionIds` 本地追踪     |
| OpenCode 进程可能崩溃,IDE 必须能清理        | `OpenCodeServerManager` 4 阶段关闭策略(§3.2)                 |
| HTTP 调用阻塞 SSE 事件分发                  | 30s TTL `SessionInfoCache` + 三级 fallback 字段提取          |

### 1.2 关键决策

| 决策                | 方案                                                                                    | 收益                                                         |
| ------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| **进程隔离**        | IDE 进程内仅做客户端消费,OpenCode CLI 独立进程                                          | 故障隔离 + 利用 OpenCode 已有的能力                          |
| **SSE 复用**        | 复用已有 `/global/event` SSE 连接消费所有事件(文件 + 通知 + bash)                       | 一个连接处理所有事件类型                                     |
| **单 SSE consumer** | 全局共享一个 consumer(全局单例),按 `directory` 路由通知                                 | 资源效率 + 简化协调                                          |
| **Subagent 追踪**   | 本地 `Set<String>` + `session.created` 增 / `session.status(idle)` 查                   | 无 HTTP 调用,无竞态                                          |
| **通知去抖**        | 2s 时间窗口内同 sessionID 重复 idle 抑制(`idleLastFired` LRU 500)                       | 防止 `session.idle` + `session.status(idle)` 双发            |
| **通知双模式**      | IDE 前台 + 项目窗口有焦点 → BALLOON;IDE 后台 → macOS 系统通知;多显示器无焦点 → 系统通知 | 覆盖所有使用场景 + 点击跳转正确 IDE                          |
| **配置存储**        | `PropertiesComponent`(IDE 级别 key-value),无独立文件                                    | 零开销读 + 自动持久化                                        |
| **进程启动**        | zsh login shell(`-l -c "source ~/.zshrc && opencode serve..."`)                         | 加载用户 `.zshrc` 中的 PATH,确保 opencode 可执行文件可被找到 |
| **HTTP 客户端**     | `java.net.http.HttpClient` by lazy 共享(无 OkHttp 依赖)                                 | JDK 内置,自动 keep-alive 连接池                              |
| **工厂解耦**        | `SSEConsumerFactory.create(project)` 单例工厂                                           | 工具类 `toolWindow` → `listeners` 依赖单向,无循环引用        |

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

**职责**:消费 SSE 事件流,按事件类型分发给:文件刷新 / 通知 / bash 处理。

**事件分发架构**(在 `onMessage()` line 153+):

```
onMessage(event, messageEvent)
    │
    ├── 1. SSEEventParser.parse() → ParsedSSEEvent
    │
    ├── 2. dedup 检查 (payload.id)
    │
    ├── 3. eventType = syncEventType ?: payloadType ?: return
    │
    ├── 4. session.created → subagentSessionIds 追踪
    │      session.deleted → DEBUG 日志(不删除追踪,防竞态)
    │
    ├── 5. when(eventType) 通知事件分发
    │      session.status(idle) → handleSessionIdle
    │      session.idle        → handleSessionIdle
    │      session.error       → error / user_cancelled
    │      permission.asked    → permission
    │      session.next.tool.called(question) → question
    │      session.next.tool.called(plan_exit) → plan_exit
    │      question.asked      → question(fallback)
    │      message.updated(user) → user_message + sessionIdleFired.remove
    │      server.connected    → client_connected
    │
    ├── 6. 文件事件处理
    │      session.diff / file.edited / file.watcher.updated → FullRefreshCoordinator
    │      message.part.updated → BashCommandHandler
    │
    └── 7. directory 匹配检查 (eventDir == projectDir)
```

**三层去重(`handleSessionIdle`)**(从外到内,任一命中即跳过):

1. **子 agent 识别**:`sessionID in subagentSessionIds` → 发 `subagent_complete`,返回
2. **父 session 抑制**:`sessionID in sessionIdleFired` → 抑制
3. **时间窗口**:`idleLastFired` 在 2s 内已记录 → 抑制

### 2.3 `SSEEventParser`(object)

**职责**:解析 SSE 事件 JSON,统一为 `ParsedSSEEvent`。

**6 级 sessionID fallback**(`extractSessionID()` 扩展函数):

```kotlin
fun ParsedSSEEvent.extractSessionID(): String? {
    return props?.get("sessionID")                                  // 1. payload.properties.sessionID
        ?: data?.get("sessionID")                                   // 2. data.sessionID
        ?: data?.get("id")                                          // 3. data.id
        ?: (data?.get("info") as? Map<*, *>)?.get("sessionID")       // 4. data.info.sessionID
        ?: (data?.get("info") as? Map<*, *>)?.get("id")              // 5. data.info.id
        ?: syncEvent?.get("aggregateID")                             // 6. syncEvent.aggregateID(SyncEvent V2 实际)
}
```

完整字段位置解释见 `SSEEventParser.kt:21-43` 的 KDoc。

**`parse()` 高频事件快速路径**(`message.part.delta` 跳过 Map 转换):节省每事件 ~2-5KB 对象分配。

### 2.4 `OpenCodeNotificationRouter`(object)

**职责**:按 SSE 事件的 `directory` 字段路由到正确 Project 的通知 Service。

**注册时机**(实际只在 1 处,**非**双保险):

- `OpenCodeProjectActivity.execute(project)` 在 `postStartupActivity` 时机注册(line 13)
- `MyToolWindowFactory.createToolWindowContent()` **不**直接调 `register`,只通过 `OpenCodeServerManager.ensureSSEConsumer(project)` 间接建立 SSE consumer
- **SPEC GAP-5**:理论上的双保险机制未实施;实际场景下足够(项目级 `ProjectActivity` 在所有项目打开时都会触发)

**反注册**:`OpenCodeProjectActivity.projectClosing(project)` 调 `unregister` + 移除 `contentManagerListeners`

**路径规范化**:`File.canonicalPath`,处理符号链接 + 大小写不敏感。

### 2.5 `OpenCodeNotificationService`(object)

**职责**:构造通知内容(模板 + 占位符) + 双模式发送(BALLOON + SystemNotifications)。

**30s TTL SessionInfoCache**:消除通知路径上的同步 HTTP 调用,见 `SPEC.md §1.2`。

**双模式通知流程**(参考 `ide-native-notifications/design.md`):

```kotlin
fun send(eventType: String, properties: Map<*, *>?, project: Project) {
    // 1. 工具窗口聚焦抑制
    if (toolWindow?.isVisible == true && toolWindow.isActive) return

    // 2. minDuration 过滤 (complete / subagent_complete)
    if (eventType == "complete" || eventType == "subagent_complete" && minDuration > 0) {
        val info = SessionInfoCache.getOrFetch(sessionID) ?: ...
        if (duration < minDuration) return
    }

    // 3. 构造 title + body
    // 4. invokeLater (EDT) 发送
    ApplicationManager.getApplication().invokeLater {
        if (projectWindowActive && !project.isDisposed) {
            // BALLOON (项目窗口有焦点)
            Notification("OpenCodeWeb.notifications", title, body, type).notify(project)
        } else if (!ApplicationManager.getApplication().isActive()) {
            // macOS 系统通知 (IDE 后台)
            SystemNotifications.getInstance().notify(NOTIFICATION_GROUP, title, body)
        }
    }
}
```

**消息模板占位符**:`{sessionTitle}` `{projectName}` `{timestamp}` `{agentName}`

### 2.6 `OpenCodeConfig`(object,PropertiesComponent 包装)

**职责**:

- 4 个通用设置(`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`)
- 11 个事件开关(`isEventEnabled` / `setEventEnabled`)
- 11 个消息模板(`getMessageTemplate` / `setMessageTemplate`)
- 总计 26 个 key-value

**Key 命名**:

- `opencode.settings.{name}` — 4 个通用设置
- `opencode.event.{type}.enabled` — 11 个事件开关
- `opencode.message.{type}` — 11 个消息模板

**事件列表**(`ALL_EVENT_TYPES`,11 个):

- `permission`, `complete`, `subagent_complete`, `error`, `question`, `interrupted`, `user_cancelled`, `plan_exit`, `session_started`, `user_message`, `client_connected`

**默认开关**(`defaultEvents()`,10 个 — 注意 `interrupted` **不**在 `defaultEvents` 中,默认 `false`):

- `permission=true`, `complete=true`, `error=true`, `question=true`, `plan_exit=true`
- `client_connected=false`, `subagent_complete=false`, `interrupted=false`, `user_cancelled=false`, `session_started=false`, `user_message=false`
- **SPEC GAP-1**:`subagent_complete` 默认关闭是已知问题

**UI 特殊处理**(`OpenCodeConfigurable.createComponent`):

- `interrupted` 复选框 `isEnabled = false`,`toolTipText = "当前环境不支持"`(无 SSE 事件映射)

```kotlin
object OpenCodeConfig {
    var notificationEnabled: Boolean
    var showProjectName: Boolean
    var showSessionTitle: Boolean
    var minDuration: Int
    fun isEventEnabled(eventType: String): Boolean
    fun setEventEnabled(eventType: String, enabled: Boolean)
    fun getMessageTemplate(eventType: String): String
    fun setMessageTemplate(eventType: String, template: String)
}
```

---

## 3. 关键时序

### 3.1 启动时序

```
用户点击 IDE 侧边栏 OpenCode 图标
    │
    ▼
MyToolWindowFactory.createToolWindowContent()
    │  - 创建 BrowserPanel
    │  - OpenCodeNotificationRouter.register(project)
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
    ├─ session.created.1 [subagent]  ─────────→ OpenCodeSSEConsumer
    │                                              │
    │                                              ├─ extractSessionID() → "ses_sub" (via aggregateID)
    │                                              ├─ extractParentID() → "ses_parent"
    │                                              └─ subagentSessionIds.add("ses_sub")
    │
    ├─ [subagent 工作流 ... file.edited, message.part.delta ...]
    │
    ├─ session.status(type=idle) [subagent] ───→ handleSessionIdle
    │                                              │
    │                                              ├─ subagentSessionIds 命中 → 发 subagent_complete
    │                                              └─ return
    │
    ├─ message.updated(role=user) ──────────────→ sessionIdleFired.remove (如果父 session 发新消息)
    │
    └─ session.status(type=idle) [parent] ──────→ handleSessionIdle
                                                   │
                                                   ├─ subagentSessionIds 未命中
                                                   ├─ sessionIdleFired 未命中
                                                   ├─ 2s 时间窗口未命中
                                                   └─ 发 complete + sessionIdleFired.add
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

**Why**:用 `session.created(parentID)` 识别子 agent,本地 Set 追踪,避免 HTTP API 查 `parentID` 的竞态。

**How**:

- 加入:`session.created` 事件 `extractSessionID() + extractParentID()` 都非 null → `subagentSessionIds.add(sid)`
- 查询:`handleSessionIdle` 中 `sessionID in subagentSessionIds` 决定发 `subagent_complete` 或 `complete`
- 清理:**不**在 `session.deleted` 移除(防 `session.deleted` 先于 `session.status(idle)` 到达的竞态),只在 SSE 重连时清空

**LRU 容量**:1000,超限自动淘汰最久未访问。

### 4.2 通知三层去重

参考 `SPEC.md §7.1` + `openspec/specs/idle-notification-suppression/spec.md`。

| 层级 | 触发条件                               | 抑制目标                                     |
| ---- | -------------------------------------- | -------------------------------------------- |
| 1    | `sessionID in subagentSessionIds`      | 子 agent 误发 complete                       |
| 2    | `sessionID in sessionIdleFired`        | 父 session 重复 complete                     |
| 3    | `now - lastFired < 2s` (idleLastFired) | `session.idle` + `session.status(idle)` 双发 |

### 4.3 SessionInfo 30s LRU 缓存

**Why**:通知路径上 `getSession` 同步 HTTP 调用会阻塞 SSE 事件线程。

**How**:

- `SessionInfoCache` 私有 `object`,`ConcurrentHashMap<String, Entry>`
- `Entry(info: SessionInfo, cachedAt: Long)`,TTL 30s
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

**重连后清理**(在 `onClosed()`):

- `SSEEventParser.clearCache()` (dedup cache)
- `subagentSessionIds.clear()`
- `sessionIdleFired.clear()`
- `idleLastFired.clear()`

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

### 5.1 Direct BusEvent 格式

```json
{
  "directory": "/Users/yutao/IdeaProjects/xxx",
  "project": "hash",
  "payload": {
    "id": "evt_e208271300012MZGbu2gNc47xU",
    "type": "server.connected",
    "properties": {}
  }
}
```

- `payload.id` 用于去重(`SSEEventParser.dedupCache`)
- `payload.type` 用于 Direct BusEvent 路由
- `directory` 用于多项目路由

### 5.2 SyncEvent V2 格式

```json
{
  "directory": "/Users/yutao/IdeaProjects/xxx",
  "project": "hash",
  "payload": {
    "type": "sync",
    "syncEvent": {
      "type": "session.created.1",
      "id": "evt_xxx",
      "seq": 0,
      "aggregateID": "ses_xxx",
      "data": { "info": { "parentID": "ses_parent" } }
    },
    "id": "evt_xxx"
  }
}
```

- `payload.type == "sync"` → SyncEvent 路径
- `syncEvent.type` 去掉 `.N` 后缀后用于路由(例: `session.created.1 → session.created`)
- `syncEvent.aggregateID` = session 实体 ID(subagent 追踪关键)
- `syncEvent.data` = 业务数据

### 5.3 完整事件 → 通知映射

| SSE 事件                                   | 通知事件类型                     | 默认开关           | 触发条件                                                            |
| ------------------------------------------ | -------------------------------- | ------------------ | ------------------------------------------------------------------- |
| `server.connected`                         | `client_connected`               | ❌ false           | SSE 连接建立                                                        |
| `session.created`(无 parentID)             | `session_started`                | ❌ false           | 用户新会话                                                          |
| `session.status(type=idle)`                | `complete` / `subagent_complete` | ✅ true / ❌ false | 父/子 session 完成                                                  |
| `session.idle`(deprecated)                 | 同上                             | 同上               | 兼容旧格式                                                          |
| `session.error`                            | `error` / `user_cancelled`       | ✅ true / ❌ false | error.name == "MessageAbortedError" → user_cancelled,否则 error     |
| `permission.asked`                         | `permission`                     | ✅ true            | OpenCode 申请权限                                                   |
| `message.updated(role=user)`               | `user_message`                   | ❌ false           | 用户发新消息 + 重置 `sessionIdleFired`                              |
| `session.next.tool.called(tool=question)`  | `question`                       | ✅ true            | 询问用户                                                            |
| `session.next.tool.called(tool=plan_exit)` | `plan_exit`                      | ✅ true            | Plan 完成                                                           |
| `question.asked`                           | `question`(fallback)             | ✅ true            | `session.next.tool.called` 不可用时                                 |
| **`interrupted`**(无 SSE 事件)             | `interrupted`                    | ❌ false(灰色 UI)  | 配置兼容 opencode-notifier 旧配置,Settings 中标灰("当前环境不支持") |
| `file.edited` / `file.watcher.updated`     | (无通知)                         | —                  | VFS 刷新                                                            |
| `session.diff`                             | (无通知)                         | —                  | VFS 刷新                                                            |
| `message.part.updated`                     | (无通知)                         | —                  | BashCommandHandler 检测 bash 工具                                   |
| `message.part.delta`                       | (无通知)                         | —                  | 高频流式文本增量,SKIP_PARSE 快速路径直接 return                     |

---

## 6. 通知路由与去重

### 6.1 多项目路由

```
SSE event (directory=/p/A)
    │
    ▼
OpenCodeNotificationRouter.notify(eventType, properties, directory)
    │
    ├─ normalize(directory) = File.canonicalPath
    ├─ projectRegistry[dir] = Project?
    │   ├─ null → 静默丢弃
    │   └─ non-null → OpenCodeNotificationService.send(eventType, properties, project)
    │
    ▼
OpenCodeNotificationService.send()
    │
    ├─ 工具窗口聚焦抑制 → 跳过
    ├─ 事件开关 (OpenCodeConfig.isEventEnabled) → 关闭则跳过
    ├─ minDuration 过滤 (complete / subagent_complete)
    └─ invokeLater (EDT) 发送
```

### 6.2 通知双模式

| 场景                            | 通知方式                                      |
| ------------------------------- | --------------------------------------------- |
| IDE 前台 + 项目窗口有焦点       | BALLOON(右下角弹窗)                           |
| IDE 后台(切到浏览器/Slack)      | macOS 系统通知(`SystemNotifications`)         |
| 多显示器 + 目标窗口在另一显示器 | macOS 系统通知(我们主动检测 `frame.isActive`) |
| OpenCodeWeb 工具窗口活跃        | ❌ 抑制(用户正在对话,无需通知)                |
| 窗口最小化                      | ⚠️ 依赖 macOS + IntelliJ 行为(可能系统通知)   |

### 6.3 通知去重

| 抑制类型                                     | 机制                                                    |
| -------------------------------------------- | ------------------------------------------------------- |
| 同一事件 BusEvent + SyncEvent 双发           | `dedupCache`(payload.id,LRU 1000)                       |
| `session.idle` + `session.status(idle)` 双发 | `idleLastFired` 2s 时间窗口                             |
| 父 session 重复 complete                     | `sessionIdleFired` 集合 + `message.updated(user)` 重置  |
| subagent 误发 complete                       | `subagentSessionIds` 集合 + `handleSessionIdle` 第 1 层 |

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

| 集合                                           | 容量                   | 线程安全                                     |
| ---------------------------------------------- | ---------------------- | -------------------------------------------- |
| `SSEEventParser.dedupCache`                    | 1000                   | `Collections.synchronizedMap(LinkedHashMap)` |
| `OpenCodeSSEConsumer.subagentSessionIds`       | 1000                   | `Collections.synchronizedSet(LinkedHashMap)` |
| `OpenCodeSSEConsumer.sessionIdleFired`         | 1000                   | 同上                                         |
| `OpenCodeSSEConsumer.idleLastFired`            | 500                    | `Collections.synchronizedMap(LinkedHashMap)` |
| `OpenCodeNotificationService.SessionInfoCache` | 无界(30s TTL 自然淘汰) | `ConcurrentHashMap`                          |

### 7.4 Thread 管理

| Thread                                     | 类型                       | 清理                        |
| ------------------------------------------ | -------------------------- | --------------------------- |
| `OpenCodeSSEConsumer.watchdogThread`       | daemon                     | `interrupt()` in `stop()`   |
| `OpenCodeServerManager.startDisposeThread` | daemon (one-shot)          | 自然结束                    |
| `OpenCodeServerManager.pipeToLogger` (×2)  | daemon                     | 进程退出时 IOException 退出 |
| `FullRefreshCoordinator.scheduler`         | `ScheduledExecutorService` | `shutdownNow()` in `stop()` |

### 7.5 Alarm / Editor / MessageBusConnection

| 资源                                                        | 释放方式                                                                                                            |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `JcefJsInjector` 注入 JS 的重试 `Alarm`                     | 不显式 dispose,`Alarms` 在项目关闭时自动清理                                                                        |
| `BrowserPanel.disposeBrowser()` 释放 3 个 JCEF handler      | `contextMenuHandler` / `loadHandler` / `displayHandler` 全部 `remove*Handler` + `null` 引用清理                     |
| `MyToolWindowFactory.init` 块的 `MessageBusConnection`      | 无 parent disposable,跟随 application 生命周期                                                                      |
| `OpenCodeProjectActivity.execute` 的 `MessageBusConnection` | parent = `project`,`projectClosing` 消息触发 `unregister` + 移除 `contentManagerListeners`                          |
| `OpenCodeNotificationRouter.projectRegistry`                | `OpenCodeProjectActivity.projectClosing` 调 `unregister` 清                                                         |
| `MyToolWindowFactory.contentManagerListeners`               | `OpenCodeProjectActivity.projectClosing` 移除(配对 `try-catch IllegalStateException` 防御项目已 dispose)            |
| `MyToolWindowFactory.sharedJBCefClient`                     | JVM 退出时清理(全局单例,`by lazy` 初始化时设置 `ide.browser.jcef.gpu.disable=false` + `--use-angle=metal` 系统属性) |
| `MyToolWindow` `focusListenerWindow` + `focusListener`      | 浏览器 panel 重新加入 hierarchy 时移除旧 listener,防累积                                                            |

---

## 8. 关键文件路径速查

| 想做的事                     | 改哪里                                                         |
| ---------------------------- | -------------------------------------------------------------- |
| 工具窗口入口/布局            | `toolWindow/MyToolWindowFactory.kt`                            |
| 浏览器面板/生命周期          | `toolWindow/MyToolWindow.kt` + `BrowserPanel.kt`               |
| 服务器进程启停               | `toolWindow/OpenCodeServerManager.kt`                          |
| 项目级健康检查(5s 轮询)      | `toolWindow/HealthMonitor.kt`                                  |
| SSE watchdog + 自动重连(30s) | `listeners/OpenCodeSSEConsumer.kt` 中 `startWatchdog()`        |
| 键盘快捷键(ESC/Cmd+K/Emacs)  | `toolWindow/JcefKeyboardInterceptor.kt` + `EmacsKeyHandler.kt` |
| JCEF 右键菜单                | `toolWindow/LinkContextMenuHandler.kt`                         |
| 项目启动/通知路由            | `toolWindow/OpenCodeProjectActivity.kt`                        |
| SSE 事件消费与降噪           | `listeners/OpenCodeSSEConsumer.kt`                             |
| SSE 解析                     | `listeners/SSEEventParser.kt`                                  |
| Bash 工具事件                | `listeners/BashCommandHandler.kt`                              |
| 文件刷新(生产者-消费者)      | `listeners/FullRefreshCoordinator.kt`                          |
| HTTP API(健康/session 等)    | `utils/OpenCodeApi.kt` + `OpenCodeApiResult.kt`                |
| 通知发送(IDEA 原生)          | `utils/OpenCodeNotificationService.kt`                         |
| 通知路由(事件→类型)          | `utils/OpenCodeNotificationRouter.kt`                          |
| 通知配置/状态                | `utils/OpenCodeConfig.kt`                                      |
| JCEF JS 注入                 | `utils/JcefJsInjector.kt`                                      |
| IdeaVim visual 模式选区      | `utils/IdeaVimIntegration.kt`                                  |
| 复制为 Prompt 格式           | `actions/CopyAsPromptAction.kt`                                |
| 选中代码 → Prompt 编辑器     | `actions/AddToPromptAction.kt`                                 |
| 端口/超时/间隔常量           | `OpenCodeConstants.kt`                                         |
| Settings UI                  | `settings/OpenCodeConfigurable.kt`                             |

---

## 附录 A:已知的设计权衡

| 决策                                                                              | 权衡                                                                                                       |
| --------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| 全局单例 `OpenCodeServerManager`                                                  | 多项目窗口需共享单 SSE consumer + 通过 `directory` 路由                                                    |
| 静态 `companion object` 集合(subagent 追踪)                                       | 违反 AGENTS.md "禁止静态全局可变状态" HARD RULE,但有前置条件(单 consumer 约束),已加 NOTE                   |
| `OpenCodeConfig` 用 `PropertiesComponent` 而非 `PersistentStateComponent`         | 简单 key-value 无需对象序列化,代价是不支持嵌套结构(本项目无此需求)                                         |
| `SessionInfoCache` 30s TTL                                                        | stale 30s 可接受(只缓存 timeCreated,title 不缓存),代价是错过 30s 内的实时变更                              |
| `zsh -l` 启动 opencode                                                            | 必须 login shell 加载 `.zshrc` 中的 PATH,代价是启动慢 0.5-2s(原 `logDiagnosticEnvironment` 调试代码已删除) |
| `LruSet` 用 `Collections.synchronizedMap(LinkedHashMap)` 而非 `ConcurrentHashMap` | access-order LRU 需要整体同步(LinkedHashMap 不支持),synchronizedMap 在低竞争下开销可接受                   |
| `OpenCodeConfigurable.isModified()` 用 `!!` 强转                                  | IntelliJ Platform 契约保证 `createComponent()` 先于 `isModified()`,NPE 不会触发,代码可读性 OK(SPEC GAP-7)  |
| `OpenCodeNotificationRouter` 注册单点(非双保险)                                   | 实际场景下 `OpenCodeProjectActivity.execute(postStartupActivity)` 已覆盖所有项目打开时机(SPEC GAP-5)       |
| `JcefKeyboardInterceptor.interceptKeysRecursive` 死代码                           | 保留为工具方法供未来递归场景使用,当前只调单层 `interceptKeys`(SPEC GAP-6)                                  |
