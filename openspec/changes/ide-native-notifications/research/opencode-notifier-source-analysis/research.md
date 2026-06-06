# opencode-notifier 源码分析(代码同步版)

> **状态说明**:本文件是 2026-05-15 调研结果,`@mohak34/opencode-notifier` 源码分析结论仍然有效。**实际落地时,本插件未完整对标 notifier 的 11 事件**,只采用了其中 4 类的简化方案。

## 来源

- GitHub: https://github.com/mohak34/opencode-notifier
- 完整阅读 src/index.ts (584行)、src/config.ts、src/notify.ts、src/focus.ts

## 研究发现

### 事件注册方式(三种)

**1. event handler(总线事件 → SSE 可达)**

```typescript
return {
  event: async ({ event }) => {
    if (event.type === "session.created") { ... }        // → session_started
    if (event.type === "session.updated") { ... }        // → subagent 追踪
    if (event.type === "session.deleted") { ... }        // → subagent 清理
    if ((event as any).type === "permission.asked") { ... }  // → permission
    if (event.type === "session.idle") { ... }            // → complete(350ms 延迟)
    if (event.type === "session.status" && busy) { ... } // → 内部标记 busy
    if (event.type === "session.error") { ... }           // → error / user_cancelled
    if (event.type === "message.updated" && user) { ... } // → user_message
  }
}
```

**2. permission.ask SDK 钩子(NOT 在 SSE 中)**

```typescript
"permission.ask": async () => { ... }
```

区别:permission.asked(总线事件)在权限被询问后触发,permission.ask(SDK 钩子)在决定询问前触发。可用 `permission.asked` 替代。

**3. tool.execute.before SDK 钩子(NOT 在 SSE 中)**

```typescript
"tool.execute.before": async (input) => {
    if (input.tool === "question") { ... }   // → question
    if (input.tool === "plan_exit") { ... }  // → plan_exit
}
```

需寻找 SSE 等价事件。

### plan_exit 和 question 的 SSE 替代

OpenCode V2 定义了 `session.next.tool.called` 事件(`v2/session-event.ts`):

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

该事件通过 `EventV2.define` 创建 SyncEvent,经 `sync/index.ts` 的 `process` 函数桥接到 GlobalBus → SSE。
⚠️ 尚未通过 curl 实测验证 SSE 可达性。

### subagent 追踪方案

notifier 通过 event handler 跟踪 subagent:

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

12 种事件,每种有独立 sound/notification/command/bell 开关。

## 实际落地与 notifier 的差异

> **本节为代码同步版新增**

| notifier 行为                                  | 本插件实际                                       | 差异                     |
| ---------------------------------------------- | ------------------------------------------------ | ------------------------ |
| `session.created` → `session_started` 通知     | **未实施**                                       | ❌ 只缓存 session title  |
| `session.updated` → subagent 追踪              | **未实施**(改用 title 正则)                      | 🟠 改方案                |
| `session.deleted` → subagent 清理              | **未实施**                                       | ❌                       |
| `permission.asked` → `permission` 通知         | **已实施**                                       | ✅                       |
| `session.idle` (350ms 延迟) → `complete`       | **已实施但简化**                                 | 🟠 改 1s LRU dedup + Set |
| `session.status(busy)` 内部状态                | **未实施**                                       | ❌                       |
| `session.error` → `error` / `user_cancelled`   | **未实施**(`session.error` 不在白名单)           | 🔴                       |
| `message.updated(user)` → `user_message` 通知  | **未实施**(只用于重置 idle 抑制)                 | 🟠 改用途                |
| `permission.ask` SDK 钩子                      | **未实施**(用 BusEvent 替代)                     | ✅ 调研结论正确          |
| `tool.execute.before(question)` → `question`   | **未用 SDK 钩子**,改用 `question.asked` BusEvent | 🟠 改来源                |
| `tool.execute.before(plan_exit)` → `plan_exit` | **未实施**                                       | 🔴                       |
| `subagent_complete` 通知                       | **未实施**                                       | ❌(改用 title 正则抑制)  |
| 12 种事件独立开关                              | **未实施**                                       | ❌                       |
| 12 种事件独立 message 模板                     | **未实施**                                       | ❌(硬编码)               |

## 调研结论的有效性

- ✅ 事件注册方式三种分类的调研**仍然有效**(本插件用 BusEvent + 不实现 SDK 钩子)
- ✅ 12 种事件类型列表**仍然有效**(但本插件只实施 4 类的子集)
- ✅ `session.next.tool.called` 是 plan_exit/question 的潜在 SSE 等价事件,调研**仍然有效**(本插件未采用)
- ✅ subagent 追踪方案 `subagentSessionIds` Set 的设计**仍然有效**(本插件改用 title 正则)
- ✅ 配置结构(12 类事件 + 4 通用 setting)调研**仍然有效**(本插件只 4 通用 setting)

## 结论

- notifier 12 种通知事件理论上均可通过 SSE 获取或替代
- 本插件**仅实施 4 类**(`permission` / `complete` / `question` + 预留 `error`),且对 `session.idle` 采用了简化的去重方案(无 350ms debounce,改用 1s LRU + per-session Set)
- `plan_exit` / `session_started` / `user_message` / `client_connected` / `subagent_complete` / `error` / `user_cancelled` 7 类通知**完全未实施**
- 12 类事件独立开关 + 独立 message 模板**未实施**(硬编码 + 无 UI)
- 关键待验证项 `session.next.tool.called` 是否在 SSE 流中**仍未实测**,本插件也未依赖该事件
