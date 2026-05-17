## Context

当前 IntelliJ 插件通过 SSE 连接 `http://127.0.0.1:12396/global/event` 接收 OpenCode 服务端事件，但只处理文件相关事件（`session.diff`、`file.edited`、`file.watcher.updated`、`message.part.updated`），其余全部忽略。通知功能依赖 `@mohak34/opencode-notifier`（运行在 OpenCode 进程内），通过 Plugin SDK 注册 `event` 处理器、`permission.ask` 钩子和 `tool.execute.before` 钩子来获取通知事件，使用 osascript 发送 macOS 系统通知，导致点击后无法跳转到对应 IDE。

本设计复用已有 SSE 连接，从中消费所有通知事件，走 IntelliJ 原生通知通道。

## Research

### 1. opencode-notifier 源码分析

完整阅读了 `@mohak34/opencode-notifier` 的全部源码（src/index.ts 584行），确认其事件注册方式分为三类：

**① event handler（总线事件）** — 通过 SSE 即可获取

```
session.created       → session_started
session.updated       → subagent 追踪
session.deleted       → subagent 清理
permission.asked      → permission
session.idle          → complete（已废弃）
session.status(busy)  → 内部状态追踪
session.error         → error / user_cancelled
message.updated(user) → user_message
```

**② permission.ask SDK 钩子** — NOT 在 SSE 中（用 `permission.asked` 总线事件替代，效果相同）

**③ tool.execute.before SDK 钩子** — NOT 在 SSE 中

```
input.tool === "question"  → question
input.tool === "plan_exit" → plan_exit
```

需寻找 SSE 等价替代方案。

### 2. SSE 端点实测验证

通过 curl 连接 `http://127.0.0.1:12396/global/event` 获取了实时 SSE 数据，确认了两种事件格式：

**Direct BusEvent：**

```json
{ "payload": { "id": "evt_xxx", "type": "server.connected", "properties": {} } }
```

**SyncEvent（V2）：**

```json
{ "payload": { "type": "sync", "syncEvent": { "type": "session.created.1", "data": {...} } } }
```

已验证存在于 SSE 流中的事件：`server.connected`、`server.heartbeat`、`message.part.updated`、`message.part.delta`、`message.updated`（role=user）、`session.next.agent.switched`、`session.next.model.switched`、`session.updated`、`project.updated`

通过查阅 OpenCode 源码（`packages/opencode/src/`），确认了以下事件的定义和桥接路径：

- `permission.asked` — BusEvent，定义于 `permission/index.ts`
- `question.asked` — BusEvent，定义于 `question/index.ts`
- `session.status` — BusEvent，定义于 `session/status.ts`（内含 `type=idle/busy/retry`）
- `session.idle` — BusEvent（已废弃），同上
- `session.error` — BusEvent，定义于 `session/session.ts`
- `session.created/updated/deleted` — SyncEvent，通过 `EventV2.define` 定义，经由 `sync/index.ts` 的 `process` 函数桥接到 GlobalBus
- `session.next.tool.called` — V2 Event（含 `tool` 字段），定义于 `v2/session-event.ts`，同样桥接到 GlobalBus
- 所有 BusEvent 和 SyncEvent 最终统一进入 GlobalBus → SSE 流

⚠️ `session.next.tool.called` 的 SSE 可达性尚未通过 curl 实测验证（未触发 question/permission/plan_exit 场景）。这是 P0 风险，必须在实现前验证。

### 3. IntelliJ 配置持久化调研

对比了两种方案：

| 维度        | PropertiesComponent | PersistentStateComponent        |
| ----------- | ------------------- | ------------------------------- |
| 复杂度      | 低，直接 get/set    | 高，需 @State/@Storage + 序列化 |
| 15 个配置项 | 非常适合            | 大材小用                        |
| 热加载      | 天然（读内存值）    | 需额外通知机制                  |
| 嵌套结构    | ❌ 不支持           | ✅ 支持                         |

**结论**：使用 `PropertiesComponent.getInstance()`（IDE 级别，跨项目共享），Key 命名 `opencode.settings.{name}`、`opencode.event.{type}.enabled`、`opencode.message.{type}`。每次通知前直接读取 PropertiesComponent，零开销，配置变更即生效。

### 4. plugin.xml 需求调研

确认以下内容均不需要：

- 新增 `<depends>` — `com.intellij.modules.platform` 已包含所有所需 API（NotificationGroupManager、PropertiesComponent、Configurable、ToolWindowManager）
- 注册 `<applicationService>` 或 `<projectService>` — OpenCodeConfig 为普通 object，OpenCodeNotificationService 由 SSE Consumer 管理
- 注册 listener 响应配置变更 — 每次通知前直接读 PropertiesComponent
- 修改已有的 `<notificationGroup>` — 已正确注册

**仅需增加**：在 `<extensions>` 中添加 `<applicationConfigurable parentId="tools" .../>` 注册 Settings 页面。

## Goals / Non-Goals

**Goals:**

- 实现与 opencode-notifier 1:1 的事件通知覆盖（11 种事件类型）
- 通知走 IntelliJ 原生通知，macOS 上正确跳转到对应 IDE
- 提供可配置的 Setting UI，支持按事件类型开关通知
- 不需要打开工具窗口也能收到通知（SSE 后台运行）
- 配置通过 PropertiesComponent 持久化，无需独立配置文件
- 每项目（Project）独立实例化通知服务

**Non-Goals:**

- 不支持音效（IDE 通知无音效 API）
- 不支持自定义命令钩子（command 配置项）和 bell 终端响铃
- 不支持独立配置文件（全部通过 PropertiesComponent 持久化）
- 不涉及 `server.heartbeat` 事件的通知
- 不实现配置文件文件系统 watch 热加载

## Decisions

### Decision: SSE 事件分发架构 — 在 OpenCodeSSEConsumer 中扩展，而非新建 SSE 连接

**Rationale**:
已有一个持久化的 SSE 连接到 `/global/event`，接收所有总线事件。复用现有连接减少资源消耗，避免两个独立 SSE 连接的竞争问题。只需在 `onMessage()` 中增加对通知事件的分发逻辑即可。

**Alternatives Considered**:

- 新建独立 SSE 连接 → 增加复杂度和资源消耗
- HTTP 轮询 → 实时性差、浪费带宽

### Decision: 配置通过 PropertiesComponent 持久化，不使用独立文件

**Rationale**:
全部配置（12 个事件开关 + 4 个通用设置 + 12 个消息模板）展平为 28 个 key-value 对，用 `PropertiesComponent.getInstance()`（IDE 级别，跨项目共享）存储。PropertiesComponent 基于内存 + XML 文件缓存，读操作零开销，适合每次通知前查询。Setting UI 的 Apply 写入后立即生效。Key 命名使用三层式：`opencode.settings.{name}`、`opencode.event.{type}.enabled`、`opencode.message.{type}`。

**Alternatives Considered**:

- `~/.config/opencode/opencode-ide.json` 文件 → 需文件 I/O 和解析，用户需直接编辑 JSON
- `PersistentStateComponent` → 大材小用，简单 key-value 无需对象序列化，注解增加复杂度

### Decision: 通知服务设计为模块化对象 — OpenCodeNotificationService

**Rationale**:
与 Project 绑定，非 IntelliJ Service（不在 plugin.xml 注册）。由 `OpenCodeSSEConsumer` 在启动/停止生命周期中管理。提供 `notify(eventType, properties)` 方法供 SSE Consumer 调用，内部根据 `OpenCodeConfig` 判断是否发送通知。

**Alternatives Considered**:

- 直接在 SSE Consumer 中写通知逻辑 → 违反单一职责
- 注册为 IntelliJ Service → 无多消费者场景，不需要

### Decision: plan_exit 和 question 通过 session.next.tool.called 检测（带 fallback）

**Rationale**:
notifier 通过 Plugin SDK 的 `tool.execute.before` 钩子在工具执行前触发，该钩子无 SSE 等价形式。V2 事件 `session.next.tool.called` 包含 `tool` 字段，可在工具被调用后触发。延迟在毫秒级，用户无感知。

⚠️ 当前未实测验证该事件是否在 SSE 流中。fallback：

- `question` → 切换为 `question.asked` 总线事件（OpenCode 源码中确认为 BusEvent，100% 可用）
- `plan_exit` → 降级为不可用，Setting UI 中灰色标注"当前环境不支持"

**Alternatives Considered**:

- JcefJsInjector 注入 JS 监听前端 → 依赖前端页面，不稳定

### Decision: subagent_complete 区分采用本地追踪

**Rationale**:
notifier 使用 `session.updated`/`session.deleted` 事件维护本地 `subagentSessionIds: Set<String>`。收到 `session.created` 时检查 `parentID` 加入集合，收到 `session.deleted` 移除。`session.status(idle)` 时直接查本地集合即可区分 complete/subagent_complete，无需 HTTP 调用，避免竞态条件。

**Alternatives Considered**:

- HTTP API `GET /session/:id` 查 parentID → 存在竞态；session idle 后立即查询可能遇到服务端写入延迟

### Decision: 同时兼容 session.status 和 session.idle

notifier 监听 `session.idle`（标注 deprecated），新版本使用 `session.status(type=idle)`。两个都监听，以先收到为准，后者去重忽略。

### Decision: 通知点击行为按事件类型差异化

| 事件类型                                                                   | 点击行为                                |
| -------------------------------------------------------------------------- | --------------------------------------- |
| permission, question, error                                                | 激活 OpenCodeWeb 工具窗口（用户需操作） |
| complete, subagent_complete                                                | 仅聚焦 IDE（通知一下即可）              |
| session_started, user_message, client_connected, plan_exit, user_cancelled | 无操作（纯告知）                        |

### Decision: 支持 minDuration 过滤短会话

notifier 的 `minDuration` 配置：会话完成耗时小于该值时跳过 complete/subagent_complete 通知。纯逻辑过滤，几行代码实现。默认 0（不过滤）。

### Decision: 消息模板支持 {sessionTitle}/{projectName}/{timestamp}/{agentName} 占位符

`{turn}` 需持久化计数器到文件，暂不实现。未识别占位符保留原样。

### Decision: plugin.xml 仅增加 applicationConfigurable

插件已在 `<notificationGroup id="OpenCodeWeb.notifications" displayType="BALLOON"/>` 注册了通知组。现只需在 `<extensions>` 中增加：

```xml
<applicationConfigurable parentId="tools"
                         instance="com.github.xausky.opencodewebui.config.OpenCodeConfigurable"
                         id="com.shenyuanlaolarou.opencodewebui.config"
                         displayName="OpenCode"/>
```

无需新增 `<depends>`（`com.intellij.modules.platform` 已包含所有所需 API），无需注册 service。

## SSE 事件 → 通知映射设计

```
SSE payload.type                    → 通知事件类型         → 默认消息
──────────────────────────────────────────────────────────────────────────
server.connected                    → client_connected     "OpenCode 已连接"
session.created (无parentID,Sync)   → session_started      "新会话开始: {sessionTitle}"
session.status (type=idle)          → complete / subagent   "回答完成: {sessionTitle}"
session.idle (deprecated)           → complete / subagent   (兼容旧格式)
session.error                       → error / user_cancelled
                                    (error.name==MessageAbortedError→user_cancelled)
permission.asked                    → permission            "需要权限: {sessionTitle}"
message.updated (role=user)         → user_message          "用户已发送消息"
session.next.tool.called
  └ tool="question"                 → question              "需要回答: {sessionTitle}"
  └ tool="plan_exit"                → plan_exit             "Plan 完成: {sessionTitle}"
── 以下无 SSE 映射，仅保留配置模型兼容 notifier ──
(无)                                → interrupted           默认关闭
```

## 架构

```
OpenCode Server
│
├── SSE /global/event ─────────────────→ IntelliJ 插件
│                                            │
│   BusEvent:                               OpenCodeSSEConsumer.onMessage()
│     server.connected                          │
│     permission.asked                          ├── 文件事件 → 文件刷新（已有）
│     session.status                            │
│     session.error                             ├── bash 事件 → Bash 处理（已有）
│     message.updated                           │
│   V2 SyncEvent:                               ├── 通知事件 → OpenCodeNotificationService
│     session.created.1                         │       │
│     session.next.tool.called.1                │       │ 每次通知前读取
│                                               │       ▼
│  HTTP API                              ┌──────┴──────────────┐
│  GET /session/:id ←── 查 session 标题──│  OpenCodeConfig     │
│                                        │  (PropertiesComp.) │
│                                        └─────────────────────┘
│                                        ┌─────────────────────┐
│                                        │  Settings UI        │
│                                        │  Tools → OpenCode   │
│                                        │  └ 通知配置 Section  │
│                                        └─────────────────────┘
```

## Risks / Trade-offs

| 风险                                      | 缓解措施                                                            |
| ----------------------------------------- | ------------------------------------------------------------------- |
| BusEvent 和 SyncEvent 同时到达            | 以 `payload.id` 去重，已处理的 ID 缓存在 LRU 集合                   |
| 通知过多                                  | 默认只开启 permission/complete/error/client_connected 四个高频事件  |
| SSE 重连丢失事件                          | 不补发，只处理实时事件，acceptable loss                             |
| session.next.tool.called 不存在于 SSE 流  | 实现前实测确认；question 走 question.asked fallback，plan_exit 降级 |
| session.idle 和 session.status 双事件冲突 | 先到者处理，后到者去重                                              |

## Open Questions

1. **未读通知计数**：需要 Balloon 通知还是 Badge 数字？IDE 原生通知默认 Balloon 样式
2. **Session 标题获取延迟**：session idle 后立即调用 `GET /session/:id` 可能遇到服务端写入延迟，建议重试 1-2 次
