## Context

当前 IntelliJ 插件通过 SSE 连接 `http://127.0.0.1:12396/global/event` 接收 OpenCode 服务端事件，但目前只处理文件相关事件（`session.diff`、`file.edited`、`file.watcher.updated`、`message.part.updated`），其余所有事件均被忽略。

通知功能依赖于服务端插件 `@mohak34/opencode-notifier`（运行在 OpenCode 进程内），通过 Plugin SDK 注册事件监听器和钩子（`tool.execute.before`、`permission.ask`）。该插件发送 macOS 系统级通知（osascript），点击后无法跳转到对应 IDE。

方案是通过 SSE 流直接消费以下事件，用 IntelliJ 原生通知替代：

- 总线事件（BusEvent）：`server.connected`、`permission.asked`、`session.status`、`session.error`、`message.updated`
- V2 事件（SyncEvent）：`session.created`、`session.next.tool.called`、`session.updated`、`session.deleted`

全部 11 种通知事件均有 SSE 等价形式（包括 plan_exit 通过 `session.next.tool.called` 的 `tool: "plan_exit"` 字段检测）。

## Goals / Non-Goals

**Goals:**

- 实现与 opencode-notifier 1:1 的事件通知覆盖（11 种事件类型）
- 通知走 IntelliJ 原生通知通道，macOS 上正确跳转到对应 IDE
- 提供可配置的 Setting UI，支持按事件类型开关通知
- 不需要打开工具窗口也能收到通知（后台 SSE 一直在运行）
- 每项目（Project）独立实例化，避免多 IDE 冲突

**Non-Goals:**

- 不支持音效（与 notifier 不同，IDE 通知没有音效 API）
- 不支持自定义命令钩子（command 配置项，IDE 插件无执行外部命令需求）
- 不涉及 `server.heartbeat` 事件的通知（仅用于保活）
- 不是一次性替换 @mohak34/opencode-notifier 的完全替代（服务端仍有其他用途）

## Decisions

### Decision: SSE 事件分发架构 — 在 OpenCodeSSEConsumer 中扩展，而非新建 SSE 连接

**Rationale**:
当前已经有一个持久化的 SSE 连接到 `/global/event`，该连接接收所有总线事件（包括通知相关事件）。复用现有连接可以减少资源消耗，避免两个独立 SSE 连接的竞争问题。只需要在 `onMessage` 中增加对通知事件的分发逻辑即可。

**Alternatives Considered**:

- 新建独立 SSE 连接 → 增加复杂度和资源消耗，且需要额外管理连接生命周期
- 通过 HTTP 轮询 → 实时性差，且浪费带宽

### Decision: 通知服务设计为模块化服务 — OpenCodeNotificationService

**Rationale**:
采用单例服务模式，与项目绑定（per-project）。在 `MyToolWindowFactory` 或 `OpenCodeSSEConsumer` 的启动/停止生命周期中管理。提供 `notify(eventType, title, message, ...)` 方法供 SSE Consumer 调用，内部根据用户配置决定是否显示。

**Alternatives Considered**:

- 直接在 SSE Consumer 中写通知逻辑 → 违反单一职责，代码膨胀
- 使用 Listener 广播模式 → 过度设计，本场景没有多消费者需求

### Decision: 事件 → 通知类型映射采用查找表

**Rationale**:
从 SSE payload.type 到通知事件类型的映射关系固定，使用 `Map<String, NotificationEventType>` 或 `when` 表达式即可。V2 SyncEvent 需要额外解析 `syncEvent.type`（去掉 ".1" 版本号后缀）来获取真实类型。

### Decision: Setting UI 采用 IntelliJ Configurable API

**Rationale**:
IntelliJ 平台提供标准的 `Configurable` 接口，在 Settings → Tools 下注册。通知配置作为主页面的一部分，使用 Section 分割，为后续更多配置项预留空间。配置数据存储在 `PropertiesComponent`（IDE 级别，跨项目共享），同时支持文件配置 `opencode-ide.json`（与 opencode-notifier 一致的 `~/.config/opencode/` 路径）。

**Alternatives Considered**:

- 仅用文件配置 → 没有 UI，用户不友好
- 仅用 PropertiesComponent → 配置文件粒度不足

### Decision: plan_exit 通过 session.next.tool.called 检测

**Rationale**:
notifier 通过 Plugin SDK 的 `tool.execute.before` 钩子在工具执行前触发通知。该钩子无 SSE 等价形式，但 V2 事件 `session.next.tool.called` 包含 `tool: "plan_exit"` 字段，可在工具已调用时触发通知。延迟在毫秒级，用户无感知。

**Alternatives Considered**:

- JcefJsInjector 注入 JS 监听前端事件 → 依赖前端页面，不稳定
- 直接放弃 plan_exit 通知 → 用户要求 1:1 复刻

### Decision: 配置文件位置

**Rationale**:
notifier 使用 `~/.config/opencode/opencode-notifier.json`。为保持统一，本插件使用 `~/.config/opencode/opencode-ide.json`。配置文件结构与 notifier 的 events/messages 配置结构保持一致，便于用户迁移。

**Alternatives Considered**:

- IDE 项目目录下 → 每项目配一次，重复劳动
- 插件配置目录 → 用户不易发现

## SSE 事件 → 通知映射设计

```text
SSE payload.type              → 通知事件类型   → 默认消息
──────────────────────────────────────────────────────────────
server.connected              → client_connected   "OpenCode 已连接"
session.created (无 parentID) → session_started    "新会话已开始"
session.status (type=idle)    → complete/subagent   "回答完成: {title}"
session.error                 → error/user_cancelled"执行错误: {title}"
permission.asked              → permission         "需要权限: {描述}"
message.updated (role=user)   → user_message       "用户已发送消息"
session.next.tool.called
  └ tool="question"           → question           "需要回答: {问题}"
  └ tool="plan_exit"          → plan_exit          "Plan 制定完成"
```

## 通知事件配置模型

```kotlin
data class NotificationEventConfig(
    val notification: Boolean = true,   // 是否发送通知
)

data class NotifierConfig(
    val notification: Boolean = true,   // 全局开关
    val showProjectName: Boolean = true,
    val showSessionTitle: Boolean = true,
    val events: Map<String, NotificationEventConfig> = defaultEvents(),
    val messages: Map<String, String> = defaultMessages(),
)

fun defaultEvents() = mapOf(
    "permission" to NotificationEventConfig(true),
    "complete" to NotificationEventConfig(true),
    "subagent_complete" to NotificationEventConfig(false),
    "error" to NotificationEventConfig(true),
    "question" to NotificationEventConfig(true),
    "user_cancelled" to NotificationEventConfig(false),
    "plan_exit" to NotificationEventConfig(false),
    "session_started" to NotificationEventConfig(false),
    "user_message" to NotificationEventConfig(false),
    "client_connected" to NotificationEventConfig(true),
)
```

## 架构

```
OpenCode Server
│
├── SSE /global/event ──────────────────────────→ IntelliJ 插件
│                                                     │
│   BusEvent:                                       OpenCodeSSEConsumer.onMessage()
│     server.connected                                   │
│     permission.asked                                   ├── session.diff/file.edited → 文件刷新（已有）
│     session.status                                     │
│     session.error                                      ├── message.part.updated → Bash 处理（已有）
│     message.updated                                    │
│   V2 SyncEvent:                                       ├── 通知事件 → dispatchNotifications()
│     session.created.1                                  │       │
│     session.next.tool.called.1                         │       ▼
│                                                        │  ┌─────────────────────────┐
│                                                        │  │OpenCodeNotification     │
│                                                        │  │  Service                │
│                                                        │  │                         │
│                                                        │  │ ① 查配置 → 是否开启     │
│                                                        │  │ ② 构建通知内容          │
│                                                        │  │ ③ NotificationGroup     │
│                                                        │  │   .createNotification() │
│                                                        │  │ ④ .notify(project)      │
│                                                        │  └─────────────────────────┘
│                                                        │
│  HTTP API                                              │  ┌─────────────────────────┐
│  GET /session/:id           ←──── 查询session信息────│  │OpenCodeNotification     │
│  GET /session/list          ←──── 查询会话列表───────│  │  Config (File+UI)       │
│                                                        │  │  ~/.config/opencode/    │
│                                                        │  │  opencode-ide.json      │
│                                                        │  └─────────────────────────┘
│                                                        │
│                                                        │  ┌─────────────────────────┐
│                                                        │  │Settings UI              │
│                                                        │  │  Tools → OpenCode       │
│                                                        │  │  └ 通知配置 Section      │
│                                                        │  └─────────────────────────┘
```

## Risks / Trade-offs

| 风险                                                      | 缓解措施                                                                   |
| --------------------------------------------------------- | -------------------------------------------------------------------------- |
| **事件重复**：BusEvent 和 SyncEvent 同时收到同一事件      | 在 `SSEEventParser` 中去重，优先处理 Direct BusEvent，跳过对应的 SyncEvent |
| **通知过多**：用户被频繁通知打扰                          | 默认只开启 permission/complete/error 三个高频事件，其余默认关闭            |
| **多项目多 IDE**：同一 SSE 收到跨项目事件                 | 已有 directory 过滤机制，仅处理匹配本项目的通知事件                        |
| **SSE 重连后丢失事件**：断连期间的事件无法恢复            | SSE 重连后不补发已过事件，客户端只处理实时事件。acceptable loss            |
| **session.next.tool.called 格式不确定**：未实测确认字段名 | 实现时加容错解析，不同的工具事件格式可能略有差异                           |

## Open Questions

1. **点击通知的行为**：点击 IDE 通知后跳转到什么？工具窗口（ToolWindow）？聚焦 IDE 窗口本身？不同事件应有不同行为（如 permission 通知点击后应打开工具窗口查看权限请求，complete 通知点击可仅聚焦 IDE）

2. **opencode-ide.json 的 watch 机制**：用户手动修改配置文件后是否需要热加载？还是重启 IDE 生效？

3. **未读通知计数**：需要 Balloon 通知还是 Badge 数字？notifier 使用的是系统通知（可理解为 Balloon），IDE 原生通知默认也是 Balloon 样式

4. **Session 标题获取**：对于 session.status(idle)/session.error 事件，可能需要在收到事件后通过 HTTP API `GET /session/:id` 额外获取 session 标题用于通知内容
