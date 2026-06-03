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

⚠️ `session.next.tool.called` 的 SSE 可达性尚未通过 curl 实测验证（未触发 question/permission/plan_exit 场景）。**这是 P0 风险，实施前必须用 curl 验证。**

- 如果验证通过：question 和 plan_exit 通过 `session.next.tool.called` 的 `tool` 字段检测
- 如果验证不通过：question 降级为 `question.asked` BusEvent（100% 可用）；plan_exit 降级为不支持，Settings UI 中灰色标注

### 3. SyncEvent 解析方案

`SSEEventParser` 当前只解析 `payload.type` 路径。需要扩展以支持 V2 SyncEvent 格式。

**当前 `ParsedSSEEvent` 结构**：

```kotlin
data class ParsedSSEEvent(
    val eventType: String,      // SSE 事件名（固定为 "message"）
    val directory: String?,
    val file: String?,
    val payloadType: String?,   // payload.type → "server.connected", "sync", 等
    val parsedMap: Map<*, *>?
)
```

**需要扩展为**：

```kotlin
data class ParsedSSEEvent(
    val eventType: String,
    val directory: String?,
    val file: String?,
    val payloadType: String?,       // payload.type（如 "server.connected", "sync"）
    val syncEventType: String?,     // payload.syncEvent.type，如 "session.created.1"，去掉 ".1" 后缀
    val syncEventData: Map<*, *>?,  // payload.syncEvent.data
    val parsedMap: Map<*, *>?
)
```

**解析逻辑**：

1. 先按现有方式提取 `payload.type` → 存入 `payloadType`
2. 如果 `payload.type == "sync"`，则继续提取 `payload.syncEvent.type` 和 `payload.syncEvent.data`
3. 从 `syncEvent.type` 中去掉 `.N` 版本号后缀（如 `session.created.1` → `session.created`），存入 `syncEventType`
4. 下游消费者判断：`payloadType == "sync"` 时使用 `syncEventType` 做事件路由，否则使用 `payloadType`

对比了两种方案：

| 维度        | PropertiesComponent | PersistentStateComponent        |
| ----------- | ------------------- | ------------------------------- |
| 复杂度      | 低，直接 get/set    | 高，需 @State/@Storage + 序列化 |
| 15 个配置项 | 非常适合            | 大材小用                        |
| 热加载      | 天然（读内存值）    | 需额外通知机制                  |
| 嵌套结构    | ❌ 不支持           | ✅ 支持                         |

**结论**：使用 `PropertiesComponent.getInstance()`（IDE 级别，跨项目共享），Key 命名 `opencode.settings.{name}`、`opencode.event.{type}.enabled`、`opencode.message.{type}`。每次通知前直接读取 PropertiesComponent，零开销，配置变更即生效。

### 4. 多项目/多窗口通知路由调研

**关键发现：`OpenCodeServerManager` 是全局单例 `object`，内部只有一个 `sseConsumer`。**

这意味着所有 IntelliJ 项目（窗口）共享同一个 SSE 连接。该 SSE 消费者创建时绑定到**第一个**初始化的项目。后续打开的项目窗口的 `ensureSSEConsumer()` 返回同一个消费者实例。

**对当前文件事件的影响**：现有目录过滤（`eventDir == projectDir`）使单个消费者能正确过滤出匹配的项目事件，因此文件刷新在各种场景下工作正常——但仅对第一个项目的目录有效。

**对通知的影响**：通知需要精确路由到事件所属项目的窗口。单消费者 + 单项目引用的模式无法满足多项目需求。

**方案**：利用 SSE 事件中的 `directory` 字段做路由。单消费者收到所有事件后，通过 directory 匹配当前打开的多个项目，将通知路由到正确的项目窗口。

**多项目/多显示器窗口焦点调研**：

使用 `java.awt.Window.isActive()`（JFrame 继承自 Window）判断特定项目窗口是否有焦点：

```kotlin
import com.intellij.openapi.wm.WindowManager

fun isProjectWindowActive(project: Project): Boolean {
    val frame = WindowManager.getInstance().getFrame(project) ?: return false
    return frame.isActive  // java.awt.Window.isActive()
}
```

关键 API：

- `WindowManager.getInstance().getFrame(project)` → 获取该项目的 JFrame
- `JFrame.isActive` (继承自 `Window.isActive`) → 该窗口是否为当前焦点窗口
- `ApplicationManager.getApplication().isActive()` → IDE 是否为当前活跃 App

多显示器场景下（3 个显示器，各一个 IntelliJ 窗口）：

| 窗口位置                 | IDE App 状态 | 该窗口 isActive | 应该通知方式       |
| ------------------------ | ------------ | --------------- | ------------------ |
| 显示器 A（有焦点）       | 活跃         | `true`          | BALLOON ✅         |
| 显示器 B（可见，无焦点） | 活跃         | `false`         | **macOS 系统通知** |
| 显示器 C（可见，无焦点） | 活跃         | `false`         | **macOS 系统通知** |
| 全部最小化               | 后台         | `false`         | macOS 系统通知     |
| 全部在另一个 Space       | 后台         | `false`         | macOS 系统通知     |

### 5. IntelliJ 通知类型与 macOS 集成调研 + E2E 测试验证

通过查阅 IntelliJ Platform SDK 官方文档（2026.3）和源码分析，确认了全部通知 displayType，并经过 e2e 测试验证：

**核心发现**：`com.intellij.ui.SystemNotifications` 是 IntelliJ 官方提供的系统通知 API。在 macOS 上，它通过 `MacOsNotifications`（内部使用 Foundation JNA 调用 NSUserNotification）发送 macOS 原生通知。在 Windows 上使用 `SystemTrayNotifications`。

**e2e 测试结果（macOS）**：

- `SystemNotifications` → ✅ macOS 通知中心弹窗，IntelliJ 图标，点击跳转到正确项目
- `BALLOON` → ❌ IDE 后台时无任何显示
- `STICKY_BALLOON` → ❌ IDE 后台时无任何显示
- `TOOL_WINDOW` → ❌ IDE 后台时无任何显示

**前提条件**：用户需在 IntelliJ 设置中开启：Preferences → Appearance & Behavior → Notifications → "Enable system notifications"

**双模式策略**：

```text
IDE 前台 → Notification.notify(project) BALLOON（窗口内弹窗）
IDE 后台 → SystemNotifications.notify() macOS 系统通知（通知中心）
```

| **STICKY_BALLOON** | 窗口右下角，带按钮 | ❌ 需用户操作 | ✅ Suggestions | ❌ 不可见 | IDE后台时自动 |
| **TOOL_WINDOW** | 工具窗口按钮旁 | 点击/按键后 | ❌ 默认不记录 | ❌ 不可见 | 无 |
| **NONE** | 无弹窗 | — | ✅ 仅日志 | ✅ 始终记录 | 无 |

关键认知：

- **所有 displayType 都依赖窗口的 NSView 可见性**。窗口最小化 = 没有可见的 NSView → 任何弹窗都无法渲染
- macOS 通知中心推送**不由 displayType 控制**，而是由 IntelliJ IDE 自身管理：IDE 在后台时自动通过 `NSUserNotification` 推送
- **插件只需调用 `notification.notify(project)`**，剩下的 IDE 负责
- 使用 `BALLOON` 是最合适的：窗口聚焦时弹窗，IDE 后台时 macOS 通知中心自动接管

此外，官方文档指出较新的 API 是直接 `Notification("groupId", "content", type).notify(project)`，`NotificationGroupManager` 已标记为 obsolete。

### 6. plugin.xml 需求调研

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

### Decision: 通知策略 — 双模式（BALLOON + SystemNotifications）

**Rationale**:
`OpenCodeServerManager` 是全局单例，只有一个 SSE 消费者。因此需要两层设计：先路由到正确项目，再按前后台状态选择通知方式。

**第一层：项目路由** — OpenCodeNotificationRouter

```kotlin
object OpenCodeNotificationRouter {
    // directory → Project 映射，File.canonicalPath 规范化
    private val projectRegistry = ConcurrentHashMap<String, Project>()

    fun register(project: Project) {
        val path = normalize(project.basePath) ?: return
        projectRegistry[path] = project
    }

    fun unregister(project: Project) {
        val path = normalize(project.basePath)
        if (path != null) projectRegistry.remove(path)
    }

    fun notify(eventType: String, properties: Map<*, *>?, eventDir: String?) {
        val dir = normalize(eventDir) ?: return
        val project = projectRegistry[dir] ?: return
        OpenCodeNotificationService.send(eventType, buildContent(eventType, properties), project)
    }

    private fun normalize(path: String?): String? = try {
        java.io.File(path ?: return null).canonicalPath
    } catch (_: Exception) { path }
}
```

**注册时机**：双保险机制。

1. **项目加载时**：通过 `ProjectManagerListener` 监听项目打开事件，立即注册 `project.basePath → Project`
2. **工具窗口创建时**：在 `createToolWindowContent()` 中再次确认注册（幂等），确保无论用户是否手动打开过工具窗口都已有注册

路由流程：

1. 每个项目窗口初始化时，Router 注册 `project.basePath → project`（`createToolWindowContent` 入口）
2. SSE Consumer 收到事件 → `Router.notify(eventType, props, eventDir)`
3. Router 使用 `File.canonicalPath` 规范化路径后查找，支持符号链接和大小写
4. 找到匹配 Project 后调用 OpenCodeNotificationService.send()
5. 找不到则静默丢弃（该项目可能未在 IDE 中打开）

**第二层：双模式通知** — BALLOON + SystemNotifications

- macOS: 通过 `MacOsNotifications` (Foundation JNA → NSUserNotification) 发送 macOS 通知中心弹窗
- Windows: 通过 `SystemTrayNotifications` (AWT TrayIcon) 发送系统托盘通知
- Linux: 通过 `LibNotifyWrapper` (libnotify) 发送通知

**核心机制** — `SystemNotificationsImpl.notify()` 内部自带守卫条件：

```java
// SystemNotificationsImpl.java (IntelliJ Platform 源码)
public void notify(String notificationName, String title, String text) {
    // 两个条件必须同时满足：
    // 1. 用户在设置中开启了系统通知 (默认关闭)
    // 2. IDE 当前不是活跃 App（已切换到后台）
    if (SYSTEM_NOTIFICATIONS_ENABLED && !ApplicationManager.getApplication().isActive()) {
        macNotifier.notify(title, text);  // 真正发送 macOS 通知
    }
    // 前台时什么也不做，静默跳过
}
```

**前提条件**：用户需在 IntelliJ 设置中开启：
**Preferences → Appearance & Behavior → Notifications → "Enable system notifications"**

**完整通知流程**：

```kotlin
fun send(eventType: String, title: String, body: String, type: NotificationType, project: Project) {
    // ===== 第1层: 工具窗口聚焦抑制 =====
    val tw = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")
    if (tw?.isVisible == true && tw.isActive) return

    // ===== 第2层: 判断该项目的窗口是否有焦点 =====
    // Window.isActive() = true 表示该窗口是当前焦点窗口（键盘/鼠标）
    val frame = WindowManager.getInstance().getFrame(project)
    val projectWindowInactive = frame == null || !frame.isActive

    // ===== 第3层: 前台 BALLOON =====
    // 如果该项目窗口有焦点（在活跃显示器上），弹 BALLOON
    if (!projectWindowInactive && !project.isDisposed) {
        Notification("OpenCodeWeb.notifications", title, body, type)
            .addAction(NotificationAction.createSimpleExpiring(MyBundle.message("notification.action.open")) {
                ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")?.activate(null)
            })
            .notify(project)
    }

    // ===== 第4层: macOS 系统通知 =====
    // 两种场景走系统通知：
    //   a) IDE 整体在后台（切到浏览器）
    //   b) IDE 在前台，但该项目窗口无焦点（多显示器，在另一个屏幕上）
    // SystemNotifications 内部检查 isActive()，场景 a 时会发通知
    // 场景 b 需要由我们主动检测 frame.isActive
    if (projectWindowInactive || !ApplicationManager.getApplication().isActive()) {
        SystemNotifications.getInstance().notify("OpenCodeWeb", title, body)
    }
}
```

**为什么同时调用两者是安全的**：
调用 `SystemNotifications.notify()` 在前台时**什么也不做**（源码级保证）。`isActive()` 返回 `true` 时整个函数直接 return。所以同时调用 Notification.notify + SystemNotifications.notify 不会产生重复通知。

**为什么不会出现"前台 BALLOON 弹了又弹 macOS 通知"**：
因为 `SystemNotificationsImpl` 内部有 `!ApplicationManager.getApplication().isActive()` 守卫——前台时直接 return，不执行任何代码。

**多显示器行为矩阵**：

| 场景                                 | frame.isActive | 通知方式               |
| ------------------------------------ | -------------- | ---------------------- |
| 项目窗口聚焦（键盘鼠标在本窗口）     | `true`         | ✅ BALLOON             |
| 项目窗口在另一显示器上（可见无焦点） | `false`        | ✅ SystemNotifications |
| IDE 整体后台（切到浏览器/Slack）     | `false`        | ✅ SystemNotifications |
| 窗口最小化                           | `false`        | ✅ SystemNotifications |
| OpenCodeWeb 工具窗口活跃             | （不拘）       | ❌ 抑制                |

| 场景                               | 效果                      | 结论                             |
| ---------------------------------- | ------------------------- | -------------------------------- |
| IDE 前台（窗口聚焦），只有 BALLOON | ✅ 右下角弹窗，无系统通知 | SystemNotifications 正确抑制     |
| 切换至浏览器，10秒后               | ✅ macOS 通知中心弹窗     | SystemNotifications 正确触发     |
| 通知显示 IntelliJ 图标             | ✅                        | JNA 调用从 IntelliJ 进程发出     |
| 点击通知跳转                       | ✅ 跳转到正确的项目窗口   | 多项目路由验证通过               |
| 多个 IDE 窗口（项目 A/B）          | ✅ 仅接收自己项目的通知   | SSE directory 过滤 + Router 确认 |

**多显示器场景下 SystemNotifications 的行为已验证**：

由于 `SystemNotifications` 内部检查 `ApplicationManager.getApplication().isActive()`，该 API 返回 `true` 只要**任意一个 IntelliJ 窗口**是前台 App。这意味着：

- 3 个显示器上各有 1 个 IntelliJ 窗口 → `isActive() == true` → SystemNotifications **不会自动发通知**
- 但 Project B 的窗口在另一显示器上，用户可能看不到 BALLOON

**解决方案**：我们的 `send()` 方法中已经增加了 `WindowManager.getInstance().getFrame(project).isActive` 检查。`SystemNotifications` 兜不住的多显示器场景，由我们的代码主动送 `SystemNotifications.getInstance().notify()`。效果：

| 场景                            | IDE App  | 目标窗口 (frame) | SystemNotifications (内部) | 我们的额外逻辑 | 最终行为    |
| ------------------------------- | -------- | ---------------- | -------------------------- | -------------- | ----------- |
| IDE 聚焦 + 目标窗口有焦点       | active   | isActive=true    | 跳过                       | → 不送         | ✅ BALLOON  |
| IDE 聚焦 + 目标窗口在另一显示器 | active   | isActive=false   | 跳过                       | → 主动送       | ✅ 系统通知 |
| IDE 后台（浏览器）              | inactive | isActive=false   | → 送                       | → 主动送       | ✅ 系统通知 |
| IDE 聚焦 + 目标窗口最小化       | active   | isActive=false   | 跳过                       | → 主动送       | ✅ 系统通知 |

```kotlin
fun shouldSuppressForActiveToolWindow(project: Project): Boolean {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")
        ?: return false         // 没有工具窗口 → 不抑制
    if (!toolWindow.isVisible) return false  // 窗口没打开 → 不抑制
    return toolWindow.isActive  // 工具窗口有焦点 → 抑制通知
}
```

状态判断矩阵（针对当前 project）：

| OpenCodeWeb 工具窗口状态 | 项目窗口状态 | 是否发通知                      |
| ------------------------ | ------------ | ------------------------------- |
| **聚焦活跃**（正在对话） | 聚焦         | ❌ 抑制（用户在聊，能看到回复） |
| 打开但未聚焦（编辑器中） | 聚焦         | ✅ 发送                         |
| 关闭（不在侧边栏）       | 聚焦         | ✅ 发送                         |
| **聚焦活跃**（正在对话） | 后台/最小化  | ✅ 发送（跨窗口通过 Router）    |
| 任意状态                 | 后台 visible | ✅ macOS 通知中心               |
| 任意状态                 | minimized    | ⚠️ 平台限制                     |

行为矩阵：

| 项目窗口状态           | OpenCodeWeb 工具窗口 | 通知方式                                | 用户感知     |
| ---------------------- | -------------------- | --------------------------------------- | ------------ |
| focused                | **聚焦活跃**         | ❌ 抑制                                 | 不需要通知   |
| focused                | 未聚焦               | ✅ BALLOON (Notification.notify)        | 右下角弹窗   |
| 后台 visible(其他 App) | 任意                 | ✅ macOS 系统通知 (SystemNotifications) | 通知中心     |
| minimized              | 任意                 | ⚠️ 依赖 macOS + IntelliJ 行为           | 可能通知中心 |

**Alternatives Considered**:

- 直接用 osascript 发送 macOS 系统通知（opencode-notifier 的做法）→ 点击后无法跳转到对应 IDE（用户原痛点）
- 全部使用 SystemNotifications → 前台时也会走系统通知，不符合 IDE 内通知习惯
- JNA 直接调用 NSUserNotification → 与 IntelliJ 内部 Foundation API 等效，但使用内部 API 更稳定
- 改为 Per-project SSE 消费者 → 需重构 `OpenCodeServerManager`，超出本次变更范围

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
                         instance="com.shenyuanlaolarou.opencodewebui.config.OpenCodeConfigurable"
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
permission.asked                    → permission            "权限申请: {sessionTitle}"
message.updated (role=user)         → user_message          "用户已发送消息"
session.next.tool.called
  └ tool="question"                 → question              "询问用户: {sessionTitle}"
  └ tool="plan_exit"                → plan_exit             "Plan 制定完成: {sessionTitle}"
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

| 风险                                              | 缓解措施                                                                                         |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| BusEvent 和 SyncEvent 同时到达                    | 以 `payload.id` 去重，已处理的 ID 缓存在 LRU 集合                                                |
| 通知过多                                          | 默认只开启 permission/complete/error/client_connected 四个高频事件                               |
| SSE 重连丢失事件 + subagentSessionIds 清空        | 不补发实时事件；重连后清空 subagent 集合，idle 事件优先按 complete 处理                          |
| session.next.tool.called 不存在于 SSE 流          | 实现前实测确认；question 走 question.asked fallback，plan_exit 降级                              |
| session.idle 和 session.status 双事件冲突         | 先到者处理，后到者去重                                                                           |
| LRU 缓存无限增长                                  | 使用 LinkedHashMap(accessOrder=true) + removeEldestEntry，最大 1000 条，synchronizedMap 保证并发 |
| 多平台（Windows/Linux）SystemNotifications 未实测 | 实施后至少在 Windows CI 上验证一次；SystemNotifications 调用包在 try/catch 中                    |
| Router 路径不匹配（符号链接/大小写）              | 使用 `File.canonicalPath` 规范化注册和查询的路径                                                 |

## Open Questions

1. **未读通知计数**：需要 Balloon 通知还是 Badge 数字？IDE 原生通知默认 Balloon 样式
2. **Session 标题获取延迟**：session idle 后立即调用 `GET /session/:id` 可能遇到服务端写入延迟，建议重试 1-2 次
