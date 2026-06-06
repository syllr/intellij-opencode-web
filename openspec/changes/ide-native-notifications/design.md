# ide-native-notifications — Design(代码同步版)

> **状态说明**:本文件是 2026-05-15 创建的 OpenSpec change 的设计文档。**原 plan 中的多项设计未在代码中实施**(见「落地 vs 原 plan 差异」附录)。本文档已根据代码现状重写,以代码为唯一事实来源。原 plan 保留在 git 历史中。

## Context

OpenCode IDE 插件通过 SSE 接收 OpenCode 服务端事件(`/event?directory=...`)。原实现只处理文件相关事件(`session.diff` / `file.edited` / `file.watcher.updated` / `message.part.updated`),其余事件忽略。

本 change 目标:复用同一 SSE 连接,扩展消费**部分**通知事件,通过 IntelliJ 原生通知 API 发送。本 change 接管了原先由 `@mohak34/opencode-notifier` 处理的"IDE 侧通知"职责(点击可跳转到对应 IDE)。

## 实际落地的架构

```
OpenCode 服务端
  │  GET /event?directory=.../event?directory=<encoded>
  │  Body: {id, type, properties:{...}}  (Direct BusEvent, 单一格式)
  ▼
OpenCodeSSEConsumer.onMessage(event, messageEvent)
  │
  ├── 1. SSEEventParser.parse() → ParsedSSEEvent
  │     - 12 个白名单事件才完整 Gson 解析
  │     - 通知相关 4 个白名单: session.idle, session.status, permission.asked, question.asked
  │
  ├── 2. eventId (root-level id) LRU 去重, max 1000
  │
  ├── 3. type 路由:
  │     - server.heartbeat → HealthMonitor 健康信号
  │     - session.created / session.updated → 缓存 session title (per-instance ConcurrentHashMap)
  │     - message.updated(role=user) → 重置 idle 抑制
  │
  ├── 4. 通知事件分发 (in when (type)):
  │     - permission.asked → dispatchNotification("permission")
  │     - session.status(type=idle) → handleSessionIdle()
  │     - session.idle → handleSessionIdle()
  │     - question.asked → dispatchNotification("question")
  │
  ├── 5. handleSessionIdle() 三层去重:
  │     - L1: SUBAGENT_TITLE_REGEX 匹配 session title → 抑制 (subagent 误发)
  │     - L2: idleNotifiedSessions Set → 已通知过则抑制 (per-session 首次)
  │     - → 命中任一抑制则跳过;否则 dispatchNotification("complete")
  │
  └── 6. dispatchNotification() → OpenCodeNotificationRouter.notify() → OpenCodeNotificationService.send()

                │
                ▼
OpenCodeNotificationService.send()
  │
  ├── 工具窗口聚焦抑制: ToolWindowManager.getToolWindow("OpenCodeWeb")?.isActive == true → 跳过
  ├── minDuration 过滤: complete 事件 + OpenCodeConfig.minDuration > 0 → 短会话跳过
  ├── 1s Session 维度防抖: tryRecordAndCheckDedup() LRU (key = (sessionID, eventType))
  │
  └── invokeLater (EDT):
        ├── projectWindowActive == true → Notification("OpenCodeWeb.notifications", ...).notify(project)
        │   (实际 displayType="TOOL_WINDOW", 见 plugin.xml:51)
        └── IDE not active → SystemNotifications.getInstance().notify(group, title, body)
            (macOS 走 Foundation JNA, IDE 内置 API)
```

## 关键模块设计(代码现状)

### `OpenCodeSSEConsumer`(`listeners/OpenCodeSSEConsumer.kt`)

**职责**:SSE 事件分发到通知 / 文件 / bash 处理器。

**per-instance 状态**(故意放实例字段,非 companion object):

```kotlin
private val sessionTitles: ConcurrentHashMap<String, String>   // sessionID → title
private val idleNotifiedSessions: MutableSet<String>             // LRU 1000
```

**`handleSessionIdle()` 三层去重**(从外到内,任一命中即跳过):

1. **L1 — subagent 标题识别**:`sessionTitles[sessionID]` 匹配 `SUBAGENT_TITLE_REGEX = Regex("""@\w+ subagent""")` → 抑制(避免 subagent 误触发主会话 complete)
2. **L2 — per-session 首次抑制**:`idleNotifiedSessions.add(sessionID)` 返回 false → 抑制(同一 session 已通知过)
3. **重置条件**:`message.updated(role=user)` 事件移除 `idleNotifiedSessions` 中的 sessionID

**为什么不依赖 subagent HTTP 追踪**:

- 原 plan 设计了 `subagentSessionIds: Set<String>`(基于 `session.created(parentID)` / `session.deleted`)
- 实际改用 title 正则匹配,因为:
  - 实现简单,不依赖 `session.created` 事件
  - 与 opencode 服务端的 session title 格式强绑定(已知格式: `"(@explore subagent)"`)
  - 风险:依赖 opencode 服务端 title 命名约定,若服务端变更格式会失效(已知 trade-off)

### `OpenCodeNotificationService`(`utils/OpenCodeNotificationService.kt`)

**职责**:构造通知内容 + 发送通知 + 状态查询缓存。

**核心方法 `send(eventType, properties, project)`** 五层守卫:

1. **全局开关**:`OpenCodeConfig.notificationEnabled == false` → 直接返回
2. **1s Session 防抖**:`tryRecordAndCheckDedup(sessionID, eventType)` 1s 内同 (session, type) 抑制
3. **工具窗口聚焦抑制**:OpenCodeWeb 工具窗口可见 + 活跃 → 跳过
4. **minDuration 过滤**(仅 `eventType == "complete"`):`OpenCodeConfig.minDuration > 0` 时,通过 `SessionInfoCache.getOrFetch(sessionID)` 取 `timeCreated`,差值小于阈值则跳过
5. **构造 + 发送**:title (`OpenCode [projectName]`) + body (模板占位符替换) + 颜色 (`permission/question` → WARNING, 其余 → INFORMATION)

**`invokeLater` 内发送逻辑**(`send()` 第 101-113 行):

```kotlin
ApplicationManager.getApplication().invokeLater {
    try {
        if (projectWindowActive && !project.isDisposed) {
            val notification = Notification(NOTIFICATION_GROUP, title, body, resolveType(eventType))
            addClickAction(notification, eventType, project)
            notification.notify(project)
        } else if (!ApplicationManager.getApplication().isActive()) {
            SystemNotifications.getInstance().notify(NOTIFICATION_GROUP, title, body)
        }
    } catch (e: Exception) {
        logger.warn("[OpenCodeNotificationService] Failed to send notification: ${e.message}")
    }
}
```

**关键差异 vs 原 plan**:

- 原 plan 设计**先 BALLOON** (前台) + **再 SystemNotifications** (后台) 双重发
- 实际**互斥**:`if (projectWindowActive) ... else if (!isActive) ...`,只发其一
- 原 plan 设计 `setImportant(true)` → **未实施**
- 原 plan 设计 `setSuggestionType(true)` → **未实施**

**`SessionInfoCache`**(30s TTL):消除通知路径上的同步 HTTP 调用,避免阻塞 SSE 事件线程。`ConcurrentHashMap<sessionID, Entry(info, cachedAt)>`,过期懒删除。

**`addClickAction()`**:仅 `permission` / `question` 事件添加"打开"按钮 → `ToolWindowManager.getToolWindow("OpenCodeWeb")?.activate(null)`。

**`formatMessage()`**:模板占位符替换:

- `{sessionTitle}` → `SessionInfoCache.getOrFetch(sessionID)?.title`
- `{projectName}` → `project.name`(空时为 "未知项目")
- `{timestamp}` → `LocalTime` HH:MM:SS
- `{agentName}` → 从 session title 提取 `(@name subagent)` 中的 name(若 `OpenCodeConfig.showSessionTitle == false` 则不查)

**当前硬编码模板**(`formatMessage()` 的 `when (eventType)`):

- `permission` → "权限申请: {sessionTitle}"
- `complete` → "回答完成: {sessionTitle}"
- `question` → "询问用户: {sessionTitle}"
- 未知 → `eventType` 原样

### `OpenCodeNotificationRouter`(`utils/OpenCodeNotificationRouter.kt`)

**实际实现:10 行委托**(无路由表):

```kotlin
object OpenCodeNotificationRouter {
    fun notify(eventType: String, properties: Map<*, *>?, project: Project) {
        OpenCodeNotificationService.send(eventType, properties, project)
    }
}
```

**与原 plan 差异**:

- 原 plan 设计完整的 `projectRegistry: ConcurrentHashMap<String, Project>`,通过 `ProjectManagerListener` 双保险注册
- 实际**project 由 SSE Consumer 直接传入**(`OpenCodeSSEConsumer.dispatchNotification()` 调用时已拿到 `project` 引用)
- 没有多项目路由逻辑(实际场景下,SSE Consumer 是 per-project 实例,`project` 闭包已正确)

### `OpenCodeConfig`(`utils/OpenCodeConfig.kt`)

**实际实现:PropertiesComponent 包装,只 4 个 setting**:

| Key                                  | 类型    | 默认   | 说明                          |
| ------------------------------------ | ------- | ------ | ----------------------------- |
| `opencode.settings.notification`     | Boolean | `true` | 全局通知开关                  |
| `opencode.settings.showProjectName`  | Boolean | `true` | 通知标题显示项目名            |
| `opencode.settings.showSessionTitle` | Boolean | `true` | 通知 body 显示 session 标题   |
| `opencode.settings.minDuration`      | Int     | `0`    | 最短会话时长秒(过滤 complete) |

**与原 plan 差异**:

- 原 plan:11 事件开关 + 11 消息模板 + 4 通用 = 28 keys
- 实际:**只 4 keys**
- 11 事件开关未实现 → 哪些事件触发通知由 **`OpenCodeSSEConsumer.onMessage()` 的 `when (type)` 硬编码**
- 11 消息模板未实现 → 模板内容由 **`OpenCodeNotificationService.formatMessage()` 硬编码**
- Settings UI(`OpenCodeConfigurable.kt`)**未实施**

### `SSEEventParser`(`listeners/SSEEventParser.kt`)

**实际白名单事件**(12 个):

```
4 通知: session.idle, session.status, permission.asked, question.asked
6 业务: session.created, session.updated, message.updated, message.part.updated,
       file.edited, file.watcher.updated, session.diff
1 健康: server.heartbeat
1 流式:  (message.part.delta 由调用方特殊处理,不在白名单内,parser 早退)
```

**`parse()` 行为**:

- 非白名单事件 → 早退,`parsedMap = null`
- 白名单内 → 完整 Gson 解析为 `Map<String, Any>`

**`extractSessionID()` 三级 fallback**:

1. `properties.sessionID`
2. `properties.info.sessionID`
3. `properties.info.id`

**与原 plan 差异**:

- 原 plan 设计 SyncEvent V2 解析(`payload.syncEvent.type` / `payload.syncEvent.data` 提取)
- 实际**未实施 SyncEvent V2**;只支持 Direct BusEvent(`payload.type` / `payload.properties.*` 路径)
- 新 wire 格式:`{id, type, properties}` 直出,无外层包装(`{payload: {id, type, properties}}`)

### `plugin.xml`(`src/main/resources/META-INF/plugin.xml`)

**第 51 行通知组注册**:

```xml
<notificationGroup id="OpenCodeWeb.notifications" displayType="TOOL_WINDOW" toolWindowId="OpenCodeWeb"/>
```

**与原 plan 差异**:

- 原 plan: `displayType="BALLOON"`(设计文档全文一致)
- 实际: `displayType="TOOL_WINDOW" toolWindowId="OpenCodeWeb"`(commit `60ebd1f` 修改)
- 影响:通知在 OpenCodeWeb 工具窗口按钮旁显示,**任何按键或鼠标点击立即消失**,与 `BALLOON`(10s 自动消失)或 `STICKY_BALLOON`(永不自动消失)行为均不同

## 双模式通知的实际行为

| 场景                                       | 实际行为                                                      | 备注                      |
| ------------------------------------------ | ------------------------------------------------------------- | ------------------------- |
| IDE 前台 + 项目窗口有焦点 + 工具窗口未聚焦 | `notification.notify(project)` (TOOL_WINDOW 气泡)             | 几秒自动消失              |
| IDE 前台 + 项目窗口有焦点 + 工具窗口聚焦   | ❌ 抑制                                                       | 用户正在对话,跳过         |
| IDE 前台 + 项目窗口有焦点 + 工具窗口关闭   | `notification.notify(project)` (TOOL_WINDOW 气泡)             | 几秒自动消失              |
| IDE 后台(切到浏览器等)                     | `SystemNotifications.getInstance().notify()` (macOS 通知中心) | 由 macOS 系统决定停留时间 |
| 窗口最小化                                 | 走 SystemNotifications(项目窗口 isActive=false 触发)          | 由 macOS 系统决定         |

**与原 plan 差异**:

- 原 plan 设计 `setImportant(true)` 增强视觉重要性 → 未实施
- 原 plan 设计 `setSuggestionType(true)` 配合 STICKY_BALLOON → 未实施(因 displayType 改为 TOOL_WINDOW,即使设置也无视觉差异)
- 原 plan 设计 BALLOON 10s 后由 IDE 自动接管 SystemNotifications → 实际是代码层互斥分支

## 单元测试覆盖

| 文件                                                            | 测试目标                       | 用例数 |
| --------------------------------------------------------------- | ------------------------------ | ------ |
| `src/test/.../utils/NotificationServiceSendLogicTest.kt`        | `resolveType()` 纯函数         | 4      |
| `src/test/.../utils/NotificationServiceExtractSessionIdTest.kt` | `extractSessionID()` 纯函数    | 7      |
| `src/test/.../utils/SessionInfoCacheTest.kt`                    | `SessionInfo` 字段构造         | 2      |
| `src/test/.../listeners/AllowParseEventTypesTest.kt`            | 12 个白名单事件 + 非白名单早退 | 16     |

**未测试**:

- `send()` 整体流程(依赖 IDE 服务)
- `handleSessionIdle()` 三层去重的端到端集成(只有单元覆盖解析和纯函数)

## 落地 vs 原 plan 差异(汇总)

| 维度                              | 原 plan                                                       | 实际落地                                                                          |
| --------------------------------- | ------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `<notificationGroup displayType>` | `BALLOON`                                                     | **`TOOL_WINDOW` + `toolWindowId="OpenCodeWeb"`**                                  |
| Settings UI                       | `OpenCodeConfigurable` 注册                                   | **未实施**(文件不存在)                                                            |
| 11 事件开关                       | `opencode.event.{type}.enabled` PropertiesComponent           | **未实施**;事件范围由 SSE Consumer `when (type)` 硬编码                           |
| 11 消息模板                       | `opencode.message.{type}` PropertiesComponent                 | **未实施**;模板由 `formatMessage()` 硬编码                                        |
| SyncEvent V2 解析                 | `payload.syncEvent.*` 提取                                    | **未实施**;只支持 Direct BusEvent                                                 |
| 通知事件类型                      | 11 种                                                         | **4 种**(`permission` / `complete` / `question` / 预留 error)                     |
| `subagent_complete` 通知          | 独立通知类型                                                  | **未实施**;改用 subagent title 正则抑制主会话 complete                            |
| `session_started` 通知            | 独立通知类型                                                  | **未实施**                                                                        |
| `user_message` 通知               | 独立通知类型                                                  | **未实施**(只用于重置 idle 抑制)                                                  |
| `client_connected` 通知           | 独立通知类型                                                  | **未实施**                                                                        |
| `error` / `user_cancelled` 通知   | 独立通知类型                                                  | **未实施**;`session.error` SSE 事件未分发                                         |
| `plan_exit` 通知                  | 通过 `session.next.tool.called`                               | **未实施**;`plan_exit` 工具调用无通知                                             |
| 多项目路由                        | `projectRegistry` + `ProjectManagerListener` 双保险           | **简化为 10 行委托**,project 由 SSE Consumer 传入                                 |
| subagent 追踪                     | `subagentSessionIds` Set(基于 `parentID` / `session.deleted`) | **改用 title 正则** `SUBAGENT_TITLE_REGEX`                                        |
| idle debounce                     | 350ms debounce + 2s `idleLastFired` window                    | **改用 1s LRU dedup** + `idleNotifiedSessions` Set + `message.updated(user)` 重置 |
| 双模式发送                        | BALLOON + SystemNotifications 同时调                          | **互斥分支**(if-else)                                                             |
| `setImportant(true)`              | Event Log 突出显示                                            | **未实施**                                                                        |
| `setSuggestionType(true)`         | STICKY_BALLOON 按钮风格                                       | **未实施**                                                                        |

## 已知 trade-off

1. **subagent 检测依赖 title 格式**:`SUBAGENT_TITLE_REGEX` 假设 opencode 服务端 session title 形如 `"(@explore subagent)"`。若服务端改名或格式变更,误判会导致 subagent 误触发主会话 complete。**风险等级:中**(依赖外部约定)
2. **idle 抑制重置依赖 `message.updated(role=user)`**:若 SSE 漏掉该事件,后续 idle 不会重发。**风险等级:低**(该事件高频率,opencode 用户每次发送消息必发)
3. **`minDuration` 过滤走 HTTP 同步调用**:`SessionInfoCache` 30s TTL 缓解,但首次未命中仍同步 HTTP 8s 超时。**风险等级:中**(已通过 30s TTL 显著降低,实测不阻塞 SSE 线程)
4. **`TOOL_WINDOW` 显示类型**:任何按键/点击立即消失,用户可能错过。**风险等级:高**(已知 UX 问题,但**未在本 change 修复**,需后续单独 change)
5. **没有 Settings UI**:用户无法调整 4 个 setting(只能改 PropertiesComponent 配置文件)。**风险等级:中**(目前 4 个默认配置合理,但失去调节能力)

## 回溯说明

- 本 change 计划于 2026-05-15 创建,2026-05-31 critic 通过
- 实际代码落地分散在多个 commit:`3df951c` (feat: implement ide-native-notifications core) 等
- 偏离原 plan 的主要 commit:
  - `60ebd1f` (fix: change notification display type to tool window)
  - `1a7a03b` (fix: replace idle session debounce timer with dedup window)
- 完整设计意图保留在原 plan / 原 design.md 的 git 历史中
- SPEC.md "GAP-8: ide-native-notifications 0/11 tasks" 描述**不再准确**,实际有部分实施,只是偏离原 plan
