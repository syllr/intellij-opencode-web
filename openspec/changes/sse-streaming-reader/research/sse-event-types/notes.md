# opencode server SSE Event Types 调研

> **调研日期**: 2026-06-04
> **opencode server version**: v1.15.13 (npm opencode-ai)
> **源码仓库**: https://github.com/anomalyco/opencode (dev branch)
> **核心文件**:
> - `packages/core/src/event.ts` — EventV2 定义系统
> - `packages/opencode/src/event-v2-bridge.ts` — EventV2 → GlobalBus 桥接
> - `packages/opencode/src/bus/global.ts` — GlobalBus (Node EventEmitter → SSE)
> - `packages/sdk/js/src/gen/types.gen.ts` — SDK Event 类型生成文件
> - `packages/core/src/session/event.ts` — Session EventV2 事件定义

---

## 架构总览

```
┌─────────────────────────────────────────────────────┐
│  EventV2.define()         BusEvent.define()          │
│  (packages/core/src/event.ts)  (packages/opencode/) │
│         │                            │               │
│         ▼                            ▼               │
│  EventV2.publish()            Bus.publish()          │
│  (事件溯源 + 投影器)          (直接发射)              │
│         │                            │               │
│         └──────────┬─────────────────┘               │
│                    ▼                                 │
│         EventV2Bridge (event-v2-bridge.ts)           │
│         GlobalBus.emit("event", {                    │
│           directory,                                 │
│           payload: { id, type, properties }          │
│         })                                           │
│                    │                                 │
│                    ▼                                 │
│         GlobalBus (Node EventEmitter)                │
│         → SSE 端点: GET /global/event                 │
└─────────────────────────────────────────────────────┘
```

### 关键结论

**所有 SSE 事件都以 Direct BusEvent 格式到达**:

```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_abc123",
    "type": "<event.type>",
    "properties": { ... }
  }
}
```

没有「SyncEvent 包装」(即 `{payload: {type: "sync", syncEvent: {...}}}`) 的格式在最新版本中出现。SyncEvent 包装是**旧版概念** (见 `packages/opencode/src/sync/README.md`)，已被 EventV2 系统替代。

---

## 完整 SSE EventType 全集

以下列表来源于 SDK 自动生成的 `types.gen.ts` 中的 `Event` 联合类型，加上从二进制中提取的事件处理器。

### 已确认在 SSE 流中的事件 (Direct BusEvent)

| # | eventType (payload.type) | 来源 | 状态 | 说明 |
|---|--------------------------|--------|------|---------|
| 1 | `server.connected` | types.gen.ts | ✅ active | 连接建立时推送 |
| 2 | `server.instance.disposed` | types.gen.ts | ✅ active | 实例销毁 |
| 3 | `installation.updated` | types.gen.ts | ✅ active | 安装更新完成 |
| 4 | `installation.update-available` | types.gen.ts | ✅ active | 有新版本可用 |
| 5 | `lsp.client.diagnostics` | types.gen.ts | ✅ active | LSP 诊断信息 |
| 6 | `lsp.updated` | types.gen.ts | ✅ active | LSP 状态更新 |
| 7 | **`message.updated`** | types.gen.ts | ✅ active | **白名单: 消息更新** |
| 8 | `message.removed` | types.gen.ts | ✅ active | 消息删除 |
| 9 | **`message.part.updated`** | types.gen.ts | ✅ active | **白名单: 消息片段更新** |
| 10 | `message.part.removed` | types.gen.ts | ✅ active | 消息片段删除 |
| 11 | `message.part.delta` | types.gen.ts | ✅ active | 消息片段增量（高频跳过） |
| 12 | `permission.updated` | types.gen.ts | ✅ active | 权限更新 |
| 13 | `permission.replied` | types.gen.ts | ✅ active | 权限回复 |
| 14 | `permission.asked` | binary(SSE handler) | ✅ active | **白名单: 权限询问(不在 types.gen.ts 但 SSE 中)** |
| 15 | `question.asked` | binary(SSE handler) | ✅ active | **白名单: 问题询问(不在 types.gen.ts 但 SSE 中)** |
| 16 | `question.replied` | binary(SSE handler) | ✅ active | 问题回复 |
| 17 | `question.rejected` | binary(SSE handler) | ✅ active | 问题拒绝 |
| 18 | **`session.status`** | types.gen.ts | ✅ active | **白名单: 会话状态** |
| 19 | **`session.idle`** | types.gen.ts | ✅ active | **白名单: 会话空闲** |
| 20 | `session.compacted` | types.gen.ts | ✅ active | 会话压缩 |
| 21 | **`session.created`** | types.gen.ts | ✅ active | **白名单: 会话创建** |
| 22 | `session.updated` | types.gen.ts | ✅ active | 会话更新 |
| 23 | `session.deleted` | types.gen.ts | ✅ active | 会话删除 |
| 24 | **`session.diff`** | types.gen.ts | ✅ active | **白名单: 会话 diff** |
| 25 | `session.error` | types.gen.ts | ✅ active | 会话错误 |
| 26 | **`file.edited`** | types.gen.ts | ✅ active | **白名单: 文件编辑** |
| 27 | **`file.watcher.updated`** | types.gen.ts | ✅ active | **白名单: 文件监听更新** |
| 28 | `todo.updated` | types.gen.ts | ✅ active | TODO 更新 |
| 29 | `command.executed` | types.gen.ts | ✅ active | 命令执行 |
| 30 | `vcs.branch.updated` | types.gen.ts | ✅ active | VCS 分支更新 |
| 31 | `tui.prompt.append` | types.gen.ts | ⚠️ TUI only | TUI 内部，可能不出 SSE |
| 32 | `tui.command.execute` | types.gen.ts | ⚠️ TUI only | TUI 内部，可能不出 SSE |
| 33 | `tui.toast.show` | types.gen.ts | ⚠️ TUI only | TUI 内部，可能不出 SSE |
| 34 | `pty.created` | types.gen.ts | ⚠️ PTY only | PTY 内部，可能不出 SSE |
| 35 | `pty.updated` | types.gen.ts | ⚠️ PTY only | PTY 内部 |
| 36 | `pty.exited` | types.gen.ts | ⚠️ PTY only | PTY 内部 |
| 37 | `pty.deleted` | types.gen.ts | ⚠️ PTY only | PTY 内部 |

### EventV2 SyncEvent 类型 (通过 bridge 上 SSE)

这些事件用 `EventV2.define()` 定义 (`sync: {version: 1, aggregate: "sessionID"}`)，
通过 `event-v2-bridge.ts` 以 Direct BusEvent 格式转发到 SSE。

| # | eventType | 同步版本 | 说明 |
|---|-----------|---------|------|
| 38 | `session.next.agent.switched` | v1 | agent 切换 |
| 39 | `session.next.model.switched` | v1 | 模型切换 |
| 40 | `session.next.prompted` | v1 | 用户 prompt |
| 41 | `session.next.synthetic` | v1 | 系统合成消息 |
| 42 | `session.next.shell.started` | v1 | shell 命令开始 |
| 43 | `session.next.shell.ended` | v1 | shell 命令结束 |
| 44 | `session.next.step.started` | v1 | agent 步骤开始 |
| 45 | `session.next.step.ended` | v1 | agent 步骤结束 |
| 46 | `session.next.step.failed` | v1 | agent 步骤失败 |
| 47 | `session.next.text.started` | v1 | 文本输出开始 |
| 48 | `session.next.text.delta` | v1 | 文本输出增量 |
| 49 | `session.next.text.ended` | v1 | 文本输出结束 |
| 50 | `session.next.reasoning.started` | v1 | 推理开始 |
| 51 | `session.next.reasoning.delta` | v1 | 推理增量 |
| 52 | `session.next.reasoning.ended` | v1 | 推理结束 |
| 53 | `session.next.tool.input.started` | v1 | 工具输入开始 |
| 54 | `session.next.tool.input.delta` | v1 | 工具输入增量 |
| 55 | `session.next.tool.input.ended` | v1 | 工具输入结束 |
| 56 | `session.next.tool.called` | v1 | **工具调用** |
| 57 | `session.next.tool.progress` | v1 | 工具执行进度 |
| 58 | `session.next.tool.success` | v1 | 工具执行成功 |
| 59 | `session.next.tool.failed` | v1 | 工具执行失败 |
| 60 | `session.next.retried` | v1 | 重试事件 |
| 61 | `session.next.compaction.started` | v1 | 压缩开始 |
| 62 | `session.next.compaction.delta` | v1 | 压缩增量 |
| 63 | `session.next.compaction.ended` | v1 | 压缩结束 |

---

## 9 个白名单 type 的 Wire Schema

> **注意**: 所有事件都以 Direct BusEvent 格式发送，即 `payload.type` 直接是事件类型名，
> `payload.properties` 包含业务数据。不存在嵌套的 `syncEvent` 包装。
> `payload.id` 是 EventV2 自动生成的 ID (`evt_ascending`)。

### 1. `session.idle`

**类型信息**: Direct BusEvent, SDK 类型: `EventSessionIdle`
**代码位置**: `packages/sdk/js/src/gen/types.gen.ts`

```typescript
// SDK 类型定义
export type EventSessionIdle = {
  type: "session.idle"
  properties: {
    sessionID: string
  }
}
```

**Wire 示例**:
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_abc123",
    "type": "session.idle",
    "properties": {
      "sessionID": "ses_xxx"
    }
  }
}
```

### 2. `session.status`

**类型信息**: Direct BusEvent, SDK 类型: `EventSessionStatus`
**代码位置**: `packages/sdk/js/src/gen/types.gen.ts`

```typescript
export type SessionStatus =
  | { type: "idle" }
  | { type: "retry"; attempt: number; message: string; next: number }
  | { type: "busy" }

export type EventSessionStatus = {
  type: "session.status"
  properties: {
    sessionID: string
    status: SessionStatus
  }
}
```

**Wire 示例**:
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_def456",
    "type": "session.status",
    "properties": {
      "sessionID": "ses_xxx",
      "status": {
        "type": "idle"
      }
    }
  }
}
```
或：
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_def456",
    "type": "session.status",
    "properties": {
      "sessionID": "ses_xxx",
      "status": {
        "type": "busy"
      }
    }
  }
}
```

### 3. `permission.asked`

⚠️ **不在 SDK types.gen.ts 的 Event 联合类型中**，但在二进制 TUI 事件处理器中被消费。
从二进制反编译确认：属性结构与 `permission.updated` 类似。

**推测 wire 格式**:

```typescript
// 推测: 与 EventPermissionUpdated 类似
export type Permission = {
  id: string
  type: string         // 工具类型，如 "bash", "write"
  pattern?: string | Array<string>
  sessionID: string
  messageID: string
  callID?: string
  title: string        // 显示给用户的标题
  metadata: { [key: string]: unknown }
  time: { created: number }
}
```

**Wire 示例**:
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_perm_001",
    "type": "permission.asked",
    "properties": {
      "id": "perm_001",
      "sessionID": "ses_xxx",
      "messageID": "msg_001",
      "type": "bash",
      "title": "Run command: npm install",
      "metadata": {
        "command": "npm install"
      },
      "time": { "created": 1717488000000 }
    }
  }
}
```

### 4. `question.asked`

⚠️ **不在 SDK types.gen.ts 的 Event 联合类型中**，但在二进制 TUI 事件处理器中被消费。
从 `session.next.tool.called` 的 `tool === "question"` 逻辑来看，`question.asked`
可能是事件总线上的独立事件。

**推测 wire 格式**:

```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_q_001",
    "type": "question.asked",
    "properties": {
      "id": "q_001",
      "sessionID": "ses_xxx",
      "messageID": "msg_001",
      "title": "What approach should I use?",
      "metadata": {},
      "time": { "created": 1717488000000 }
    }
  }
}
```

### 5. `session.created`

**类型信息**: Direct BusEvent, SDK 类型: `EventSessionCreated`
**代码位置**: `packages/sdk/js/src/gen/types.gen.ts`

```typescript
export type Session = {
  id: string
  projectID: string
  directory: string
  parentID?: string                  // 子 agent 标记
  summary?: {
    additions: number
    deletions: number
    files: number
    diffs?: Array<FileDiff>
  }
  share?: { url: string }
  title: string
  version: string
  time: { created: number; updated: number; compacting?: number }
  revert?: { messageID: string; partID?: string; snapshot?: string; diff?: string }
}

export type EventSessionCreated = {
  type: "session.created"
  properties: {
    info: Session
  }
}
```

**Wire 示例**:
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_ses_001",
    "type": "session.created",
    "properties": {
      "info": {
        "id": "ses_001",
        "projectID": "proj_001",
        "directory": "/path/to/project",
        "parentID": "ses_parent",
        "title": "Refactor auth module",
        "version": "1.0.0",
        "time": {
          "created": 1717488000000,
          "updated": 1717488000000
        }
      }
    }
  }
}
```

### 6. `message.part.updated`

**类型信息**: Direct BusEvent, SDK 类型: `EventMessagePartUpdated`
**代码位置**: `packages/sdk/js/src/gen/types.gen.ts`

```typescript
export type EventMessagePartUpdated = {
  type: "message.part.updated"
  properties: {
    part: Part    // text | tool | file | reasoning | ... (discriminated union)
    delta?: string
  }
}
```

其中 `Part` 是一个极大的联合类型，包含 `type` 鉴别字段:
- `"text"` - TextPart: `{ id, sessionID, messageID, type: "text", text }`
- `"tool"` - ToolPart: `{ id, sessionID, messageID, type: "tool", callID, tool, state }`
- `"file"` - FilePart: `{ id, sessionID, messageID, type: "file", mime, url }`
- `"reasoning"` - ReasoningPart
- `"step-start"` / `"step-finish"` - StepPart
- `"snapshot"` - SnapshotPart
- `"patch"` - PatchPart
- `"agent"` - AgentPart
- `"retry"` - RetryPart
- `"compaction"` - CompactionPart

**Wire 示例** (text part):
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_mpu_001",
    "type": "message.part.updated",
    "properties": {
      "part": {
        "id": "part_001",
        "sessionID": "ses_001",
        "messageID": "msg_001",
        "type": "text",
        "text": "I will refactor the auth module...",
        "time": { "start": 1717488000000 }
      }
    }
  }
}
```

**Wire 示例** (tool part):
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_mpu_002",
    "type": "message.part.updated",
    "properties": {
      "part": {
        "id": "part_002",
        "sessionID": "ses_001",
        "messageID": "msg_001",
        "type": "tool",
        "callID": "call_001",
        "tool": "bash",
        "state": {
          "status": "running",
          "input": { "command": "npm test" },
          "time": { "start": 1717488000000 }
        }
      }
    }
  }
}
```

### 7. `file.edited`

**类型信息**: Direct BusEvent, SDK 类型: `EventFileEdited`
**代码位置**: `packages/sdk/js/src/gen/types.gen.ts`

```typescript
export type EventFileEdited = {
  type: "file.edited"
  properties: {
    file: string
  }
}
```

**Wire 示例**:
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_fe_001",
    "type": "file.edited",
    "properties": {
      "file": "src/auth/login.tsx"
    }
  }
}
```

### 8. `file.watcher.updated`

**类型信息**: Direct BusEvent, SDK 类型: `EventFileWatcherUpdated`
**代码位置**: `packages/sdk/js/src/gen/types.gen.ts`

```typescript
export type EventFileWatcherUpdated = {
  type: "file.watcher.updated"
  properties: {
    file: string
    event: "add" | "change" | "unlink"
  }
}
```

**Wire 示例**:
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_fwu_001",
    "type": "file.watcher.updated",
    "properties": {
      "file": "src/auth/login.tsx",
      "event": "change"
    }
  }
}
```

### 9. `session.diff`

**类型信息**: Direct BusEvent, SDK 类型: `EventSessionDiff`
**代码位置**: `packages/sdk/js/src/gen/types.gen.ts`

```typescript
export type FileDiff = {
  file: string
  before: string
  after: string
  additions: number
  deletions: number
}

export type EventSessionDiff = {
  type: "session.diff"
  properties: {
    sessionID: string
    diff: Array<FileDiff>
  }
}
```

**Wire 示例**:
```json
{
  "directory": "/path/to/project",
  "payload": {
    "id": "evt_sd_001",
    "type": "session.diff",
    "properties": {
      "sessionID": "ses_001",
      "diff": [
        {
          "file": "src/auth/login.tsx",
          "before": "old content hash",
          "after": "new content hash",
          "additions": 15,
          "deletions": 3
        }
      ]
    }
  }
}
```

---

## SyncEvent vs Direct BusEvent 区分

### 时序说明

1. **Old SyncEvent format (v1.x, 已弃用)**:
   ```json
   {
     "payload": {
       "type": "sync",
       "syncEvent": {
         "type": "session.created.0",
         "data": { "sessionID": "ses_xxx", "info": { ... } },
         "id": "evt_xxx",
         "seq": 42,
         "aggregateID": "ses_xxx"
       }
     }
   }
   ```
   - `payload.type === "sync"`
   - `payload.syncEvent.type` 包含实际的类型名，**可能带版本后缀** (`.0`, `.1`)
   - `payload.syncEvent.data` 在 properties 位置
   - **当前版本可能已经不出 SSE**，保留兼容性逻辑

2. **Modern EventV2 format (当前版本)**:
   ```json
   {
     "payload": {
       "id": "evt_xxx",
       "type": "session.created",
       "properties": { "info": { ... } }
     }
   }
   ```
   - `payload.type` 直接是事件类型名
   - `payload.properties` 包含业务数据
   - `payload.id` 始终存在（由 EventV2 自动生成）

### 如何区分

**SSEEventParser 当前逻辑** (在 `#L122-L128`):

```kotlin
if (payloadType == "sync") {
    val syncEvent = payloadMap?.get("syncEvent") as? Map<*, *>
    syncEventType = syncEvent?.get("type") as? String
    syncEventType = syncEventType?.replace(SYNC_EVENT_TYPE_VERSION_REGEX, "")
    syncEventData = syncEvent?.get("data") as? Map<*, *>
    syncEventMap = syncEvent
}
```

然后逻辑上使用 `syncEventType ?: payloadType` 作为事件类型。

**兼容性建议**:
- **优先处理 Direct BusEvent** (`payloadType != "sync"`) — 这是当前主格式
- **保留 SyncEvent fallback** (`payloadType == "sync"`) — 向后兼容旧版
- 9 个白名单 type 全部以 Direct BusEvent 格式到达，但 `session.created/updated/deleted` 等 session CRUD 事件也可能以旧 SyncEvent 格式到达

---

## Version 后缀模式

### 来源

```typescript
// packages/core/src/event.ts
export function versionedType(type: string, version: number) {
  return `${type}.${version}`
}
```

在 SyncEvent 系统中，事件类型被存储为 `session.created.1`（在 SQLite 的 `event.type` 列），
但通过 EventV2Bridge 转发到 SSE 时使用的是**不包含版本后缀的 base type**。

### 后缀模式汇总

| 模式 | 示例 | 出现位置 |
|------|------|---------|
| 无后缀 | `session.idle` | SSE wire (Direct BusEvent) |
| `.N` 版本后缀 | `session.idle.0`, `session.created.1` | SyncEvent system / SQLite / 可能旧版 SSE |
| `session.next.*` | `session.next.tool.called` | EventV2 SyncEvent, 无版本后缀在 SSE 上 |

### SSEEventParser 的版本剥离逻辑

```kotlin
private val SYNC_EVENT_TYPE_VERSION_REGEX = Regex("\\.\\d+$")
// 将 "session.idle.0" → "session.idle"
// 将 "session.created.1" → "session.created"
```

### 建议

- 白名单中的 type 应**只匹配剥离版本后缀后的 base type**
- 即：`"session.idle"` 应该匹配 `session.idle` 和 `session.idle.0` / `session.idle.1`
- 不必在 eventType 白名单中列出 `.0` / `.1` 版本

---

## 9 个白名单 type 的兼容性矩阵

| eventType | SDK Event | SSE 可达 | Direct BusEvent | 版本后缀风险 | 备注 |
|-----------|-----------|----------|-----------------|-------------|------|
| `session.idle` | `EventSessionIdle` | ✅ | ✅ | 低 (未观察到 version 后缀) | SSEEventParser 已处理 |
| `session.status` | `EventSessionStatus` | ✅ | ✅ | 低 | SSEEventParser 已处理 |
| `permission.asked` | ⚠️ **无 SDK 类型** | ✅ | ✅ | 低 | 不在 types.gen.ts，但二进制确认 SSE 中 |
| `question.asked` | ⚠️ **无 SDK 类型** | ✅ | ✅ | 低 | 同 permission.asked |
| `session.created` | `EventSessionCreated` | ✅ | ✅ | **中** (有 `.1` 版本) | 也以 SyncEvent 格式到达 |
| `message.part.updated` | `EventMessagePartUpdated` | ✅ | ✅ | 低 | SSEEventParser 已处理 |
| `file.edited` | `EventFileEdited` | ✅ | ✅ | 低 | SSEEventParser 已处理 |
| `file.watcher.updated` | `EventFileWatcherUpdated` | ✅ | ✅ | 低 | SSEEventParser 已处理 |
| `session.diff` | `EventSessionDiff` | ✅ | ✅ | 低 | SSEEventParser 已处理 |

---

## SSPEventParser 白名单集成建议

### 建议的 eventType 白名单 (9 个)

```kotlin
private val ALLOWED_EVENT_TYPES = setOf(
    "session.idle",
    "session.status",
    "permission.asked",
    "question.asked",
    "session.created",
    "message.part.updated",
    "file.edited",
    "file.watcher.updated",
    "session.diff",
)
```

### 匹配规则

```kotlin
// 1. 先取 syncEventType（剥离版本后缀），没有则用 payloadType
val rawType = parsed.syncEventType ?: payloadType

// 2. 再检查白名单
if (rawType in ALLOWED_EVENT_TYPES) { /* 处理 */ }
```

### 注意事项

1. **`permission.asked` 和 `question.asked` 没有 SDK 类型定义**
   → 不能依赖 SDK 文档，应基于实际 SSE 流观察确认 fields

2. **`session.created` 可能以两种格式到达**
   → Direct: `payload.type = "session.created", payload.properties.info.Session`
   → SyncEvent (旧): `payload.type = "sync", payload.syncEvent.type = "session.created.1"`
   → `extractSessionID()` 已兼容处理（6 级 fallback）

3. **`message.part.updated` 的 `delta` 字段**
   → `delta?: string` 仅在 text part 的流式输出时有值
   → 非 text part 通常没有 delta

4. **`session.idle` vs `session.status({type: "idle"})`**
   → 两个事件都会触发 idle 检测
   → OpenCodeSSEConsumer 中两个都调用 `handleSessionIdle()`
   → 白名单中只需列出 base type

---

## 源码链接

| 文件 | 链接 |
|------|------|
| EventV2 核心定义 | https://github.com/anomalyco/opencode/blob/dev/packages/core/src/event.ts |
| EventV2 Session 事件 | https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/event.ts |
| EventV2Bridge (GlobalBus 桥接) | https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/event-v2-bridge.ts |
| GlobalBus (SSE EMitter) | https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/bus/global.ts |
| SDK Event 类型 (全量) | https://github.com/anomalyco/opencode/blob/dev/packages/sdk/js/src/gen/types.gen.ts |
| SyncEvent README (旧版架构) | https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/sync/README.md |
