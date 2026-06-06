## ADDED Requirements(代码同步版)

> **状态说明**:本 spec 原 plan 设计 11 事件 + SyncEvent V2,实际代码只支持 **4 事件**(permission / complete / question + 预留 error)+ **Direct BusEvent 单格式**。本文档已与代码同步,标注每个 Requirement 的实际状态。

### Requirement: SSE 通知事件监听

系统 MUST 通过已有 SSE 连接监听 OpenCode 服务端的通知相关事件。

#### Scenario: server.connected 事件

- **WHEN** SSE 收到 `server.connected` 事件
- **THEN** 系统触发 `client_connected` 通知

**实际状态**:**未实施**。`OpenCodeSSEConsumer` 不监听 `server.connected` 通知分发(仅作为 SSE 连接健康信号由 HealthMonitor 使用,见 `lastHeartbeatAt`)。

#### Scenario: session.created SyncEvent

- **WHEN** SSE 收到 `payload.type == "sync"` 且 `syncEvent.type == "session.created.1"` 且 `data.info.parentID` 为空
- **THEN** 系统触发 `session_started` 通知

**实际状态**:**未实施**。`session.created` 事件被 SSEEventParser 解析并加入白名单,但 `OpenCodeSSEConsumer` 只用其**缓存 session title**,不触发 `session_started` 通知。

#### Scenario: session.status idle 事件

- **WHEN** SSE 收到 `payload.type == "session.status"` 且 `properties.status.type == "idle"`
- **THEN** 系统查本地 `subagentSessionIds` 集合:该 sessionID 在集合中则为 `subagent_complete`,否则为 `complete`,2s 去抖窗口内同 sessionID 重复 idle 抑制

**实际状态**:**部分实施**。

- ✅ `session.status(type=idle)` 事件被监听
- ✅ 触发 `handleSessionIdle()`,进而发 `complete` 通知
- ❌ 不区分 `subagent_complete`(用 title 正则抑制,而非 `subagentSessionIds` Set)
- ❌ 2s 去抖窗口(改用 1s LRU + per-session Set + `message.updated(user)` 重置)

#### Scenario: session.idle 旧格式

- **WHEN** SSE 收到 `payload.type == "session.idle"` 且同一 sessionID 未收到过 `session.status(idle)`
- **THEN** 系统按 session.status(idle) 相同逻辑处理(2s 去抖窗口 + subagent 判断)

**实际状态**:**已实施**(语义同 session.status(idle),但去重机制是 `idleNotifiedSessions` Set + 1s LRU,而非 2s 窗口)。

#### Scenario: session.idle 与 session.status 去重

- **WHEN** 同一 sessionID 先后收到 `session.idle` 和 `session.status(idle)`
- **THEN** 以先收到为准触发,后到事件因去抖窗口而忽略

**实际状态**:**已实施**(通过 `idleNotifiedSessions` Set,先到者 add 成功,后到者 add 失败被抑制)。

#### Scenario: session.error 事件

- **WHEN** SSE 收到 `payload.type == "session.error"`
- **THEN** 系统检查 `properties.error.name`,为 `MessageAbortedError` 则触发 `user_cancelled`,否则 `error`

**实际状态**:**未实施**。`session.error` **不在 SSEEventParser 白名单**(`ALLOW_PARSE_EVENT_TYPES` 不含此类型),因此 `OpenCodeSSEConsumer` 不会收到解析后的事件,无法触发 `error` / `user_cancelled` 通知。

#### Scenario: permission.asked 事件

- **WHEN** SSE 收到 `payload.type == "permission.asked"`
- **THEN** 系统触发 `permission` 通知

**实际状态**:**已实施**。`OpenCodeSSEConsumer.onMessage()` 第 211 行:

```kotlin
"permission.asked" -> dispatchNotification("permission", parsedMap, project)
```

#### Scenario: session.next.tool.called(Direct BusEvent)

- **WHEN** SSE 收到 `payload.type == "session.next.tool.called"` 且 `properties.tool == "question"`
- **THEN** 系统触发 `question` 通知

- **WHEN** SSE 收到 `payload.type == "session.next.tool.called"` 且 `properties.tool == "plan_exit"`
- **THEN** 系统触发 `plan_exit` 通知

**实际状态**:**未实施**。`session.next.tool.called` **不在 SSEEventParser 白名单**,`OpenCodeSSEConsumer` 不分发该事件。

#### Scenario: session.next.tool.called fallback — question.asked

- **WHEN** 经实测 `session.next.tool.called` 不在 SSE 流中
- **THEN** `question` 通知改为监听 `payload.type == "question.asked"` 总线事件

- **WHEN** `syncEvent.type == "session.next.tool.called.1"` 且 `data.tool == "question"`
- **THEN** 系统触发 `question` 通知(兼容 SyncEvent 字段路径)

**实际状态**:**已实施**。`OpenCodeSSEConsumer.onMessage()` 第 220 行:

```kotlin
"question.asked" -> dispatchNotification("question", parsedMap, project)
```

SyncEvent V2 兼容**未实施**(`SSEEventParser` 不解析 `syncEvent.*` 字段)。

#### Scenario: message.updated 用户消息

- **WHEN** SSE 收到 `payload.type == "message.updated"` 且 `properties.info.role == "user"`
- **THEN** 系统触发 `user_message` 通知

**实际状态**:**未实施 `user_message` 通知,但 `message.updated(role=user)` 用于重置 idle 抑制**:

```kotlin
if (type == "message.updated") {
    val info = (parsedMap["properties"] as? Map<*, *>)?.get("info") as? Map<*, *>
    val role = info?.get("role") as? String
    if (role == "user") {
        parsed.extractSessionID()?.let { idleNotifiedSessions.remove(it) }
    }
}
```

#### Scenario: 容错处理

- **WHEN** 收到未知或不支持的事件类型
- **THEN** 系统 MUST NOT 崩溃,静默忽略

**实际状态**:**已实施**。SSEEventParser 对非白名单事件**早退**(`parsedMap = null`),`OpenCodeSSEConsumer.onMessage()` 第 173 行 `if (parsedMap == null) return`。

### Requirement: subagent 会话本地追踪

系统 MUST 通过 session.created/session.deleted 事件维护本地 subagent session ID 集合,不依赖 HTTP API。

#### Scenario: subagent 加入追溯

- **WHEN** SSE 收到 `session.created` 且 `data.info.parentID` 不为空
- **THEN** 该 sessionID 加入本地 `subagentSessionIds` 集合

#### Scenario: subagent 移出追溯

- **WHEN** SSE 收到 `session.deleted`
- **THEN** 该 sessionID 从 `subagentSessionIds` 移除

**实际状态**:**未实施**。`subagentSessionIds` Set 不存在。**改用 title 正则匹配**(`OpenCodeSSEConsumer.handleSessionIdle()`):

```kotlin
private val SUBAGENT_TITLE_REGEX = Regex("""@\w+ subagent""")
// ...
if (title != null && SUBAGENT_TITLE_REGEX.containsMatchIn(title)) {
    return  // subagent idle suppressed by title
}
```

**Trade-off**:依赖 opencode 服务端 session title 形如 `"(@explore subagent)"` 的格式约定。若服务端变更格式,误判会导致 subagent 误触发主会话 complete。

### Requirement: SyncEvent V2 解析

系统 MUST 扩展 `SSEEventParser` 以支持 SyncEvent(V2)格式。

#### Scenario: SyncEvent 格式检测

- **WHEN** `payload.type == "sync"`
- **THEN** 继续提取 `payload.syncEvent.type` 和 `payload.syncEvent.data`,去掉 `.N` 版本号后缀后作为 `syncEventType` 输出

#### Scenario: Direct BusEvent 优先

- **WHEN** 同一事件同时有 Direct BusEvent(`payload.type`)和 SyncEvent(`payload.type == "sync"`)两种格式到达
- **THEN** Direct BusEvent 优先,SyncEvent 通过 `payload.id` 去重忽略

**实际状态**:**未实施**。`SSEEventParser.ParsedSSEEvent` 只有 `eventType: String` 和 `parsedMap: Map<*, *>?` 两个字段,**无 `syncEventType` / `syncEventData`**。当前只支持 Direct BusEvent(新 wire 格式:`{id, type, properties}` 直出)。

### Requirement: 事件去重

系统 SHALL 避免同一事件被处理两次。

#### Scenario: eventID 去重

- **WHEN** 同一 `payload.id` 的 BusEvent 和 SyncEvent 先后到达
- **THEN** 以先到为准处理,后到者通过 `payload.id` 缓存忽略

**实际状态**:**已实施**(但用 root-level `id` 字段,不是 `payload.id`)。`SSEEventParser.dedupCache`:`LinkedHashMap` accessOrder=true, LRU 1000 条, `synchronizedMap` 线程安全。

```kotlin
val eventId = parsedMap["id"] as? String
if (eventId != null && SSEEventParser.isEventProcessed(eventId)) return
```

#### Scenario: LRU 实现方案

- **WHEN** 实现去重缓存
- **THEN** 使用 `LinkedHashMap` 的子类(override `removeEldestEntry`),最大容量 1000 条,同时 `synchronizedMap` 保证线程安全

**实际状态**:**已实施**(见 `SSEEventParser.dedupCache`)。

#### Scenario: SSE 重连后缓存清空

- **WHEN** SSE 连接断开后重连
- **THEN** 去重缓存清空,subagentSessionIds 集合清空(重连后状态未知,优先按非 subagent 处理)

**实际状态**:**已实施**(部分)。`SSEEventParser.clearCache()` 在 `OpenCodeSSEConsumer.stop()` 中调用。`subagentSessionIds` **不存在**,无需清空;`idleNotifiedSessions` 在 `stop()` 中也清空。

```kotlin
fun stop() {
    // ...
    SSEEventParser.clearCache()
    sessionTitles.clear()
    idleNotifiedSessions.clear()
}
```

#### Scenario: session.status idle debounce

- **WHEN** 收到 idle 事件
- **THEN** 启动 2s 时间窗口去抖,同 sessionID 的新 idle 事件因窗口内重复而忽略(参考 `OpenCodeSSEConsumer.idleDedupWindowMs` 常量)

**实际状态**:**未实施 2s 时间窗口**。改用 1s LRU dedup(`OpenCodeNotificationService.tryRecordAndCheckDedup()`, `NOTIFICATION_DEDUP_WINDOW_MS = 1000L`)+ `idleNotifiedSessions` Set(per-session 首次 idle 触发,`message.updated(role=user)` 重置)。

## 已实施 Requirement 一览

| Requirement                                                                                                           | 状态                         | 实施位置                                                        |
| --------------------------------------------------------------------------------------------------------------------- | ---------------------------- | --------------------------------------------------------------- |
| SSE 通知事件监听 (Scenario: permission.asked)                                                                         | ✅ 已实施                    | `OpenCodeSSEConsumer.onMessage:211`                             |
| SSE 通知事件监听 (Scenario: session.status idle)                                                                      | ✅ 已实施(改用 1s LRU + Set) | `OpenCodeSSEConsumer.onMessage:212-218` + `handleSessionIdle()` |
| SSE 通知事件监听 (Scenario: session.idle 旧格式)                                                                      | ✅ 已实施                    | `OpenCodeSSEConsumer.onMessage:219`                             |
| SSE 通知事件监听 (Scenario: question.asked fallback)                                                                  | ✅ 已实施                    | `OpenCodeSSEConsumer.onMessage:220`                             |
| SSE 通知事件监听 (Scenario: server.connected / session.error / session.next.tool.called / message.updated(user) 通知) | ❌ 未实施                    | —                                                               |
| 容错处理(非白名单事件早退)                                                                                            | ✅ 已实施                    | `SSEEventParser.parse()` + `OpenCodeSSEConsumer.onMessage:173`  |
| subagent 会话本地追踪(Set)                                                                                            | ❌ 未实施,改用 title 正则    | `OpenCodeSSEConsumer.SUBAGENT_TITLE_REGEX`                      |
| SyncEvent V2 解析                                                                                                     | ❌ 未实施                    | —                                                               |
| 事件去重 (LRU 1000)                                                                                                   | ✅ 已实施                    | `SSEEventParser.dedupCache`                                     |
| SSE 重连后缓存清空                                                                                                    | ✅ 已实施                    | `SSEEventParser.clearCache()` + `OpenCodeSSEConsumer.stop()`    |
