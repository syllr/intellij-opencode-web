## Context

当前通知功能通过 `OpenCodeSSEConsumer` 监听 SSE 事件流，在 `onMessage()` 方法中路由到各个事件处理器。其中 `session.status(idle)` 和 `session.idle` 事件进入 `handleSessionIdle()`，根据 sessionID 是否在 `subagentSessionIds` 集合中区分 `subagent_complete` 和 `complete`。

现有的通知过滤层共五层，按执行顺序：

```
[payloadId 去重] → [isEventEnabled 开关] → [工具窗口活跃抑制] → [minDuration] → [实际发送]
```

问题是缺少"agent 循环中的 idle 抑制"层。当主 agent 调用多次 task() 时，父 session 经历多次 idle→busy 循环，每次 idle 都触发 complete 通知（默认开启）。加上 `session.deleted` 在 idle 之前到达时导致子 agent 的 idle 事件被误判为 complete，噪音问题叠加。

## Goals / Non-Goals

**Goals:**

- 父 session 在 agent 循环中的中间 idle 不再触发 complete 通知
- 用户发新消息后，首次 idle 正常触发 complete 通知
- 消除 `session.deleted` 时序竞态导致的子 agent idle 误判为 complete 的问题
- 原则 2（permission/question/error 通知）不受影响
- 所有状态在 SSE 重连后正确重置

**Non-Goals:**

- 不修改 `subagent_complete` 的默认配置（保持关闭）
- 不引入新配置项
- 不修改通知发送方式（BALLOON / SystemNotifications）
- 不修改消息模板或通知内容

## Decisions

### Decision: 用 `message.updated(role=user)` 而非 `session.status(busy)` 作为重置信号

**Rationale**:

如果使用 `session.status(busy)` 重置标记，在 agent 循环中 busy→idle 会反复清除和设置标记，导致每次 idle 都通知——这与原来的噪音问题完全相同。`message.updated(role=user)` 是真正语义正确的信号：

- 用户发消息 → 新一轮交互开始 → 应允许下次 idle 通知
- agent 内部 busy（task() 调用、tool call 等）→ 不是用户交互 → 不应重置

**Alternatives Considered**:

- `session.status(busy)` 重置 → 在 agent 循环中无效，每次 busy 都清除标记，idle 又通知
- 纯时间窗口去重（加大 `idleLastFired` 的 2 秒窗口）→ 无法区分真实完成和中间 idle，agent 循环中长间隔的 task 无法覆盖
- 移除所有 idle 通知 → 太激进，用户需要知道首次完成

### Decision: 永久保留 `subagentSessionIds` 中的子 agent 记录，不在 `session.deleted` 时移除

**Rationale**:

`session.deleted` 和 `session.status(idle)` 是两个独立事件，OpenCode 可能先发 deleted 再发 idle。如果在 deleted 时移除追踪，idle 事件到达时就无法查到记录，退化为 complete 通知。

每条记录仅 ~36 字节（UUID 字符串），10,000 次子 agent 调用仅 ~360KB。SSE 重连时 `onClosed()` 会完整清空，不存在无限增长问题。

**Alternatives Considered**:

- 用第二个持久集合 `knownSubagentSessions` 做永久追踪 → 增加复杂度，收益相同
- 保留现有 remove 逻辑 + 增加时序检查 → 复杂度高，收益不确定

### Decision: 在 `handleSessionIdle` 中注入抑制逻辑，而非增加新的事件路由

**Rationale**:

`session.status(idle)` 和 `session.idle` 两条路径都进入 `handleSessionIdle`。在函数入口增加抑制逻辑，一处修改覆盖两条路径，无需在两个 `when` 分支中重复。

**Alternatives Considered**:

- 分别在 `session.status` 和 `session.idle` 的 `when` 分支中处理 → 代码重复，维护风险
- 在 `OpenCodeNotificationService.send()` 中过滤 → 无法区分 complete 和 subagent_complete（此时 sessionID 信息已丢失）

### Decision: `sessionIdleFired` 使用 `ConcurrentHashMap.newKeySet()`，与现有 `subagentSessionIds` 保持一致

**Rationale**:

现有代码中 `subagentSessionIds` 和 `idleLastFired` 都使用 ConcurrentHashMap，线程安全且与 IntelliJ 的多线程模型兼容。新集合保持同样的线程安全策略，无需引入锁机制。

## 修改点总览

```
OpenCodeSSEConsumer.kt
├── companion object
│   ├── subagentSessionIds (=)
│   └── sessionIdleFired (新增)
│
├── onMessage()
│   ├── session.deleted 分支
│   │   └── 删除 subagentSessionIds.remove(sid)
│   ├── session.status 分支
│   │   ├── busy → 无操作（原有：忽略）
│   │   └── idle → handleSessionIdle
│   ├── session.idle 分支
│   │   └── → handleSessionIdle
│   └── message.updated(role=user) 分支
│       └── sessionIdleFired.remove(sessionID)（新增）
│
├── handleSessionIdle()
│   ├── 子 agent → subagent_complete（不变）
│   ├── 父 session 且已通知过 → return（新增）
│   └── 父 session 未通知过 → dispatch + sessionIdleFired.add（新增）
│
├── idleLastFired 去重（不变）
│
└── onClosed()
    ├── subagentSessionIds.clear()
    ├── idleLastFired.clear()
    └── sessionIdleFired.clear()（新增）
```

### 关键伪代码

```kotlin
// 新增：追踪已发送过 complete 的父 session
companion object {
    private val sessionIdleFired = ConcurrentHashMap.newKeySet<String>()
}

// session.deleted 中删除：
// subagentSessionIds.remove(sid)  — 此行删除，不清理追踪

// message.updated(role=user) 中新增：
"message.updated" -> {
    val payload = parsedMap?.get("payload") as? Map<*, *>
    val props = payload?.get("properties") as? Map<*, *>
    val info = props?.get("info") as? Map<*, *>
    if (info?.get("role") == "user") {
        val sessionID = props?.get("sessionID") as? String
        if (sessionID != null) {
            sessionIdleFired.remove(sessionID)  // 重置标记
        }
        dispatchNotification("user_message", parsedMap, eventDir)
    }
}

// handleSessionIdle 中新增抑制逻辑：
private fun handleSessionIdle(parsedMap: Map<*, *>?, eventDir: String?) {
    val sessionID = extractSessionID(parsedMap) ?: return

    // 子 agent：走现有 subagent_complete 路径
    if (sessionID in subagentSessionIds) {
        dispatchNotification("subagent_complete", parsedMap, eventDir)
        return
    }

    // 父 session 抑制：已发过 complete 则跳过
    if (sessionID in sessionIdleFired) {
        logger.debug("[OpenCodeSSEConsumer] Suppressing repeated idle for session $sessionID")
        return
    }

    // 首次 idle：发通知 + 标记
    dispatchNotification("complete", parsedMap, eventDir)
    sessionIdleFired.add(sessionID)
}

// onClosed 中新增：
override fun onClosed() {
    SSEEventParser.clearCache()
    subagentSessionIds.clear()
    idleLastFired.clear()
    sessionIdleFired.clear()  // 新增
}
```

## 事件流对比

### 变更前（当前行为）

```
用户发消息
  → message.updated(role=user)       → user_message 通知（默认关）
  → session.status(idle)             → complete 通知 ✅
  → main agent 调用 task()
  → session.status(idle)             → complete 通知 ❌ 噪音
  → session.status(busy)             → 无操作
  → main agent 再调用 task()
  → session.status(idle)             → complete 通知 ❌ 噪音
  → session.status(busy)             → 无操作
  → session.status(idle)             → complete 通知 ❌ 噪音
```

### 变更后（目标行为）

```
用户发消息
  → message.updated(role=user)
      → sessionIdleFired.remove(sid)  ← 重置标记
      → user_message 通知（默认关）
  → session.status(idle)
      → sessionIdleFired 无此 session → dispatch complete
      → sessionIdleFired.add(sid)     ← 标记
      → complete 通知 ✅
  → main agent 调用 task()
  → session.status(idle)
      → sessionIdleFired 已有 sid → return  ← 抑制
      → ❌ 不通知
  → session.status(busy)              → 无操作（不重置标记）
  → session.status(idle)
      → sessionIdleFired 已有 sid → return
      → ❌ 不通知
```

### 子 agent 竞态修复

```
变更前：
  session.deleted(child_sid)          → subagentSessionIds.remove(child_sid)
  session.status(idle, child_sid)     → child_sid NOT in set → complete ❌

变更后：
  session.deleted(child_sid)          → 不移除（此行删除）
  session.status(idle, child_sid)     → child_sid IN set → subagent_complete（默认关） ✅
```

## Risks / Trade-offs

| 风险                                                                                                  | 缓解措施                                                                                                                 |
| ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `message.updated(role=user)` 事件的 JSON 结构中可能没有 `sessionID` 字段，导致无法按 session 重置标记 | 实现前用 curl 实测 SSE 事件结构；如果确实无此字段，降级为 `sessionIdleFired.clear()`（清空所有标记，在单项目场景下安全） |
| `session.status(idle)` 在 agent 循环中首次触发的时间可能偏早（agent 刚调用 task() 就 idle）           | 这是 SSE 事件模型的限制，没有 `session.fully.completed` 事件。消息模板中不使用"回答完成"等绝对性表述                     |
| `sessionIdleFired` 集合在长时间运行后积累大量 session ID                                              | `onClosed()` 在 SSE 重连时清空；UUID 字符串内存开销可忽略                                                                |
| 用户期望每次工具调用都收到通知                                                                        | 由 `subagent_complete` 事件承载（默认关闭，用户可在 Settings 中开启）                                                    |
| `session.idle`（旧格式）路径被遗漏                                                                    | 两条路径都调用 `handleSessionIdle`，在函数入口统一抑制                                                                   |

## Migration Plan

1. 修改 `OpenCodeSSEConsumer.kt`（全部改动在一个文件中）
2. 无数据库变更，无配置变更，无 API 变更
3. 回滚：git revert 或直接还原文件

## Open Questions

1. `message.updated(role=user)` 事件的完整 JSON 结构需要实测确认，尤其验证 `payload.properties.sessionID` 是否存在
2. agent 循环中父 session 是否会产生 `session.status(idle)` 事件——这影响首次 idle 通知的时机精确性。如果某些场景下 agent 调用 task() 时父 session 不 idle, 则首次通知的时机就是最终完成的时机, 用户体验更好
3. 是否需要考虑 `sessionIdleFired` 的 TTL 过期机制（超过一定时间后自动重置）？当前设计不包含，因为用户发消息是唯一的重置信号。如果用户长时间不操作且 session 持续 idle，则不需要通知（用户已离开）
