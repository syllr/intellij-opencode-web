# /event?directory=... SSE 端点 Schema 兼容性研究报告

> **来源**: opencode server v1.15.13（`anomalyco/opencode`）
> **日期**: 2026-06-04

---

## 1. 端点 URL 路径

| 端点 | 路径 | 作用域 | 说明 |
|------|------|--------|------|
| 全局事件 | `GET /global/event` | 所有实例 | 返回所有实例的所有事件，外层有 `{directory, project, workspace, payload}` 包装 |
| 实例事件 | `GET /event?directory=<path>` | 单实例 | 返回指定目录实例的事件，**直出** `{id, type, properties}` |

### 代码证据

**`EventPaths.event`** — `/event`

```typescript
// packages/opencode/src/server/routes/instance/httpapi/groups/event.ts
export const EventPaths = {
  event: "/event",
} as const
```

**`GlobalPaths.event`** — `/global/event`

```typescript
// packages/opencode/src/server/routes/instance/httpapi/groups/global.ts
export const GlobalPaths = {
  event: "/global/event",
  // ... health, config, dispose, upgrade
} as const
```

**路由挂载**: 两者挂在同一 HTTP 层，无 prefix

```typescript
// packages/opencode/src/server/routes/instance/httpapi/api.ts
export const OpenCodeHttpApi = HttpApi.make("opencode")
  .addHttpApi(RootHttpApi)      // ← 包含 GlobalApi（/global/*）
  .addHttpApi(EventApi)         // ← 包含 EventApi（/event）
  .addHttpApi(InstanceHttpApi)  // ← 所有 /v2/*, /session/* 等
```

---

## 2. Query Param 格式

### 2.1 `directory` 参数

- **参数名**: `directory`
- **编码**: **URL-encoded 纯路径**（非 base64）
- **优先级**（按 fallback 链）:
  1. `?directory=<url-encoded-path>`
  2. `x-opencode-directory` 请求头
  3. `process.cwd()`

```typescript
// packages/opencode/src/server/routes/instance/httpapi/middleware/workspace-routing.ts
function defaultDirectory(request: HttpServerRequest.HttpServerRequest, url: URL): string {
  return url.searchParams.get("directory") || request.headers["x-opencode-directory"] || process.cwd()
}
```

服务端对 directory 值做 `decodeURIComponent`（非 base64 decode）:

```typescript
// packages/opencode/src/server/routes/instance/httpapi/middleware/instance-context.ts
function decode(input: string): string {
  try { return decodeURIComponent(input) }
  catch { return input }
}
```

### 2.2 `workspace` 参数（可选）

- **参数名**: `workspace`
- **格式**: workspace ID（如 `ws_xxx`）
- **作用**: 多 workspace/远程 workspace 路由

### 2.3 完整请求示例

```http
GET /event?directory=%2Fhome%2Fuser%2Fprojects%2Fmyapp&workspace=ws_abc123
x-opencode-directory: /home/user/projects/myapp
```

或仅用 header（适用于直连单个实例的场景）:

```http
GET /event
x-opencode-directory: /home/user/projects/myapp
```

### 2.4 Schema 定义

```typescript
// packages/opencode/src/server/routes/instance/httpapi/middleware/workspace-routing.ts
export const WorkspaceRoutingQueryFields = {
  directory: Schema.optional(Schema.String),
  workspace: Schema.optional(Schema.String),
}

export const WorkspaceRoutingQuery = Schema.Struct(WorkspaceRoutingQueryFields)
```

---

## 3. Wire JSON 格式对比

### 3.1 实例事件端点 `GET /event?directory=...`

**SSE `data:` 行格式（直出，无外层包装）:**

```json
data: {"id":"evt_xxx","type":"server.connected","properties":{}}
data: {"id":"evt_xxx","type":"session.created","properties":{"id":"ses_abc"}}
data: {"id":"evt_xxx","type":"server.heartbeat","properties":{}}
data: {"id":"evt_xxx","type":"server.instance.disposed","properties":{"directory":"/path"}}
```

**生成代码:**

```typescript
// packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts
Stream.map((event) => ({ id: event.id, type: event.type, properties: event.data })),
```

SSE 事件名称固定为 `message`:

```typescript
function eventData(data: unknown): Sse.Event {
  return {
    _tag: "Event",
    event: "message",           // ← 所有事件都用 "message" 字段
    id: undefined,
    data: JSON.stringify(data), // ← data 就是 {id, type, properties}
  }
}
```

### 3.2 全局事件端点 `GET /global/event`

**SSE `data:` 行格式（外层有 `{directory, project, workspace, payload}` 包装）:**

```json
data: {"payload":{"id":"evt_xxx","type":"server.connected","properties":{}}}
data: {"directory":"/path","project":"proj_abc","workspace":"ws_123","payload":{"id":"evt_xxx","type":"session.created","properties":{"id":"ses_abc"}}}
data: {"payload":{"id":"evt_xxx","type":"server.heartbeat","properties":{}}}
```

### 3.3 Schema 对比总结

| 特性 | `/event?directory=` | `/global/event` |
|------|-------------------|-----------------|
| 外层 `{directory, project, workspace}` | ❌ 无 | ✅ 有 |
| `payload` 包装 | ❌ 无 | ✅ 有 |
| 事件格式 | `{id, type, properties}` | `payload: {id, type, properties}` |
| 过滤 | 按 `location.directory` + `workspaceID` | 不过滤，全局广播 |
| 心跳 | `server.heartbeat` | `server.heartbeat` |
| 首条事件 | `server.connected` | `server.connected` |
| 断开事件 | `server.instance.disposed` | `global.disposed` |

---

## 4. EventV2Bridge → `/event` 端点的数据流

### 4.1 发布端（EventV2Bridge）

```typescript
// packages/opencode/src/event-v2-bridge.ts
const publish: EventV2.Interface["publish"] = (definition, data, options) =>
  Effect.gen(function* () {
    if (options?.location) return yield* events.publish(definition, data, options)
    const ctx = yield* InstanceRef       // 当前实例上下文
    if (!ctx) return yield* events.publish(definition, data, options)
    const workspaceID = yield* WorkspaceRef
    return yield* events.publish(definition, data, {
      ...options,
      location: {
        directory: AbsolutePath.make(ctx.directory),  // ← 注入 directory
        ...(workspaceID ? { workspaceID } : {}),
      },
    })
  })
```

**关键点**: 如果 publish 时未传 `options.location`，EventV2Bridge 自动从 `InstanceRef` 获取当前实例的 `directory` 并注入 `location.directory`。

### 4.2 EventV2 发布核心

```typescript
// packages/core/src/event.ts
function publish<D extends Definition>(definition: D, data: Data<D>, options?: PublishOptions) {
  return Effect.gen(function* () {
    const serviceLocation = Option.getOrUndefined(yield* Effect.serviceOption(Location.Service))
    const location =
      options?.location ??
      (serviceLocation
        ? { directory: serviceLocation.directory, workspaceID: serviceLocation.workspaceID }
        : undefined)
    return yield* publishEvent({
      id: options?.id ?? ID.create(),
      type: definition.type,
      ...(location ? { location } : {}),
      data,
    })
  })
}
```

### 4.3 消费端（handler filter）

```typescript
// packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts
Stream.filter(
  (event) =>
    event.location?.directory === instance.directory &&
    (event.location.workspaceID === undefined || event.location.workspaceID === workspaceID),
),
```

**过滤逻辑**: 只保留 `event.location.directory === instance.directory` 且 workspace 匹配的事件。

### 4.4 流程图

```
EventV2Bridge.publish()
  │
  ├─ options.location 已提供? → 直接使用
  └─ 从 InstanceRef.directory 注入
      │
      ▼
EventV2.Service.publish()
  │
  ├─ Location.Service 可用? → 注入 location
  └─ 使用已有的 location
      │
      ▼
EventV2.Payload { id, type, data, location: { directory, workspaceID? } }
      │
      ▼
EventV2.listen() 回调
      │
      ▼
EventV2Bridge.listen 转发到 GlobalBus
  { directory, project, workspace, payload: { id, type, properties } }
      │
      ├─→ GlobalBus → /global/event 端点（所有事件）
      │
      └─→ EventV2.Service PubSub
                │
                ▼
          handler event.ts filter
          event.location?.directory === instance.directory
                │
                ▼
          SSE stream: { id, type, properties }
```

---

## 5. Location.Ref 类型定义

```typescript
// packages/core/src/location.ts
export const Ref = Schema.Struct({
  directory: AbsolutePath,          // Schema.String 的 branded 类型
  workspaceID: Schema.optional(Schema.String),
}).annotate({ identifier: "Location.Ref" })

export type Ref = typeof Ref.Type  // { directory: string, workspaceID?: string }

// AbsolutePath 定义
// packages/core/src/schema.ts
export const AbsolutePath = Schema.String.pipe(Schema.brand("AbsolutePath"))
```

EventV2 的 Payload 类型:

```typescript
// packages/core/src/event.ts
export type Payload<D extends Definition = Definition> = {
  readonly id: ID
  readonly type: D["type"]
  readonly data: Data<D>
  readonly version?: number
  readonly location?: Location.Ref      // ← { directory: string, workspaceID?: string }
  readonly metadata?: Record<string, unknown>
}
```

---

## 6. SSE 流生命周期

```
1. Client → GET /event?directory=/path
        │
2. WorkspaceRoutingMiddleware
   ├── 提取 directory（query/header/cwd）
   ├── 提取 workspace（query/header/flag）
   └── 提供 WorkspaceRouteContext { directory, workspaceID }
        │
3. InstanceContextMiddleware
   ├── decodeURIComponent(directory)
   ├── store.load({ directory })
   └── 提供 InstanceRef + WorkspaceRef
        │
4. eventResponse()
   ├── 创建 unbounded Queue
   ├── 注册 EventV2.listen() 回调
   ├── filter: event.location?.directory === instance.directory
   ├── Stream.map: { id, type, properties }
   ├── 合并 server.connected（首条）
   ├── 合并 server.heartbeat（每 10s）
   └── Stream.takeUntil(server.instance.disposed)
        │
5. SSE 响应（text/event-stream）
   ├── Cache-Control: no-cache, no-transform
   ├── X-Accel-Buffering: no
   ├── X-Content-Type-Options: nosniff
   └── 最后触发 event disconnected
```

### 事件类型

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| `server.connected` | 端点初始化 | 首条事件，连接成功确认 |
| `server.heartbeat` | 10 秒定时器 | 保持连接 |
| `server.instance.disposed` | 实例关闭 | 收到此事件后流终止 |
| `session.created` | 会话创建 | 具体业务事件 |
| `session.closed` | 会话关闭 | |
| `message.created` | 消息创建 | |
| ... | EventV2 注册表 | 所有通过 EventV2.define() 注册的事件 |

---

## 7. 版本兼容性矩阵

> **注意**: 以下版本信息基于代码考古 + 语义推断。实例级 `/event` 端点与 `EventV2Bridge`（用 `InstanceRef` 注入 `location.directory`）是配套设计的。

| opencode 版本 | `/global/event` | `/event?directory=` | EventV2Bridge | 说明 |
|---------------|-----------------|---------------------|---------------|------|
| **< 1.0.0** | ✅ | ❌ | ❌ | 旧版仅全局事件，使用 `EventEmitter`/`GlobalBus` |
| **1.0.0–1.0.17** | ✅ | ⚠️ 可能部分支持 | ⚠️ 可能部分支持 | 过渡期：EventV2 逐步引入，但 `EventV2Bridge` 和 `/event` 可能未完全就绪 |
| **>= 1.0.18** | ✅ | ✅ | ✅ | **目标版本**: `InstanceRef` + `EventV2Bridge` + `WorkspaceRoutingMiddleware` + `InstanceContextMiddleware` 全套就绪 |
| **1.15.13** (当前) | ✅ | ✅ | ✅ | 当前源码版本，成熟稳定 |

### 确定版本边界的方法

1. **`EventV2Bridge`** (`event-v2-bridge.ts`) 引用 `InstanceRef` 和 `WorkspaceRef` → 需要 `effect/instance-ref.ts` 存在
2. **`WorkspaceRoutingMiddleware`** (`middleware/workspace-routing.ts`) 是 `/event` 端点前置路由 → 需要 `WorkspaceRouteContext` 和 `WorkspaceRoutingQuery` 存在
3. **`InstanceContextMiddleware`** (`middleware/instance-context.ts`) 依赖 `WorkspaceRouteContext` → 必须配套
4. **`handler/event.ts`** 中 `InstanceState.context` 和 `InstanceState.workspaceID` → 需要 `effect/instance-state.ts`

**这三个文件如果不同时存在，`/event?directory=` 端点就不完整或不存在。**

### 对 intellij-opencode-web 的建议

| 场景 | 推荐端点 | 原因 |
|------|---------|------|
| 新开发（>= 1.0.18 opencode） | `GET /event?directory=<path>` | 精确过滤，直出 `{id, type, properties}`，无需解析外层包装 |
| 兼容旧版本（< 1.0.18） | `GET /global/event` | 只能获取全局事件流，需解析 `{directory, project, workspace, payload}` 包装后自行过滤 |
| 双版本兼容 | 先尝试 `/event?directory=...`，若 404 降级到 `/global/event` | 从 `payload.properties` 中对比 `directory` 字段自行过滤 |

---

## 8. 关键文件索引

| 文件 | 作用 |
|------|------|
| `packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts` | `/event` SSE 端点 handler（filter + 序列化） |
| `packages/opencode/src/server/routes/instance/httpapi/handlers/global.ts` | `/global/event` SSE 端点 handler |
| `packages/opencode/src/server/routes/instance/httpapi/groups/event.ts` | `EventPaths.event = "/event"` + middleware chain |
| `packages/opencode/src/server/routes/instance/httpapi/groups/global.ts` | `GlobalPaths.event = "/global/event"` + GlobalEventSchema |
| `packages/opencode/src/server/routes/instance/httpapi/middleware/workspace-routing.ts` | `WorkspaceRoutingQuery` + `defaultDirectory()` |
| `packages/opencode/src/server/routes/instance/httpapi/middleware/instance-context.ts` | `decode()` URI decode + `store.load()` |
| `packages/opencode/src/event-v2-bridge.ts` | EventV2Bridge publish 注入 `location.directory` |
| `packages/core/src/event.ts` | EventV2 核心（`Payload`, `Location.Ref`） |
| `packages/core/src/location.ts` | `Location.Ref` schema `{ directory, workspaceID? }` |
| `packages/opencode/src/effect/instance-ref.ts` | `InstanceRef` + `WorkspaceRef` |
| `packages/opencode/src/effect/instance-state.ts` | `InstanceState.context` + `workspaceID` |
| `packages/opencode/src/bus/global.ts` | `GlobalBus` event emitter |
| `packages/opencode/test/server/httpapi-event.test.ts` | `/event` 端点集成测试 |

---

## 9. JSON Wire 示例

### 9.1 连接 `/event?directory=/home/user/myproject`

```http
GET /event?directory=%2Fhome%2Fuser%2Fmyproject HTTP/1.1
Authorization: Bearer <token>
```

### 9.2 SSE 流输出

```
event: message
data: {"id":"evt_01J8ABC...","type":"server.connected","properties":{}}

event: message
data: {"id":"evt_01J8ABD...","type":"session.created","properties":{"id":"ses_abc"}}

event: message
data: {"id":"evt_01J8ABE...","type":"message.created","properties":{"id":"msg_xyz","role":"user","content":"..."}}

event: message
data: {"id":"evt_01J8ABF...","type":"server.heartbeat","properties":{}}

event: message
data: {"id":"evt_01J8ABG...","type":"server.instance.disposed","properties":{"directory":"/home/user/myproject"}}
```
