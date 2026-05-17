# opencode-notifier 源码分析

## 来源

- GitHub: https://github.com/mohak34/opencode-notifier
- 完整阅读 src/index.ts (584行)、src/config.ts、src/notify.ts、src/focus.ts

## 研究发现

### 事件注册方式（三种）

**1. event handler（总线事件 → SSE 可达）**

```typescript
return {
  event: async ({ event }) => {
    if (event.type === "session.created") { ... }        // → session_started
    if (event.type === "session.updated") { ... }        // → subagent 追踪
    if (event.type === "session.deleted") { ... }        // → subagent 清理
    if ((event as any).type === "permission.asked") { ... }  // → permission
    if (event.type === "session.idle") { ... }            // → complete（350ms 延迟）
    if (event.type === "session.status" && busy) { ... } // → 内部标记 busy
    if (event.type === "session.error") { ... }           // → error / user_cancelled
    if (event.type === "message.updated" && user) { ... } // → user_message
  }
}
```

**2. permission.ask SDK 钩子（NOT 在 SSE 中）**

```typescript
"permission.ask": async () => { ... }
```

区别：permission.asked（总线事件）在权限被询问后触发，permission.ask（SDK 钩子）在决定询问前触发。可用 `permission.asked` 替代。

**3. tool.execute.before SDK 钩子（NOT 在 SSE 中）**

```typescript
"tool.execute.before": async (input) => {
    if (input.tool === "question") { ... }   // → question
    if (input.tool === "plan_exit") { ... }  // → plan_exit
}
```

需寻找 SSE 等价事件。

### plan_exit 和 question 的 SSE 替代

OpenCode V2 定义了 `session.next.tool.called` 事件（`v2/session-event.ts`）：

```typescript
export const Tool.Called = EventV2.define({
  type: "session.next.tool.called",
  aggregate: "sessionID",
  schema: {
    timestamp: V2Schema.DateTimeUtcFromMillis,
    sessionID: SessionID,
    callID: Schema.String,
    tool: Schema.String,            // ← 包含工具名
    input: Schema.Record(...),
    provider: Schema.Struct({ ... }),
  },
})
```

该事件通过 `EventV2.define` 创建 SyncEvent，经 `sync/index.ts` 的 `process` 函数桥接到 GlobalBus → SSE。
⚠️ 尚未通过 curl 实测验证 SSE 可达性。

### subagent 追踪方案

notifier 通过 event handler 跟踪 subagent：

```typescript
const subagentSessionIds = new Set<string>();
// session.created(有parentID) → subagentSessionIds.add(info.id)
// session.deleted → subagentSessionIds.delete(info.id)
// session.status(idle) → subagentSessionIds.has(sessionID) ? subagent_complete : complete
```

### 配置结构

```typescript
type EventType =
  | "permission"
  | "complete"
  | "subagent_complete"
  | "error"
  | "question"
  | "interrupted"
  | "user_cancelled"
  | "plan_exit"
  | "session_started"
  | "user_message"
  | "client_connected";
```

12 种事件，每种有独立 sound/notification/command/bell 开关。

## 结论

- 全部 12 种通知事件均可通过 SSE 获取或通过替代事件实现
- 关键待验证：`session.next.tool.called` 是否在 SSE 流中
