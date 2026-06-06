## ADDED Requirements(代码同步版)

> **状态说明**:本 spec 原 plan 设计 BALLOON + SystemNotifications 双模式 + 11 通知事件 + 28 个 PropertiesComponent keys,实际代码采用 **TOOL_WINDOW + SystemNotifications 双模式** + **4 通知事件** + **4 个 PropertiesComponent keys**。本文档已与代码同步,标注每个 Requirement 的实际状态。

### Requirement: 使用 IntelliJ 原生通知 API

系统 MUST 使用 `Notification` 构造函数(新 API,IntelliJ Platform 推荐用法)发送 IDE 原生通知,而非已废弃的 `NotificationGroupManager`。

#### Scenario: 通知创建与展示

- **WHEN** 有事件需要通知
- **THEN** 通过 `Notification("OpenCodeWeb.notifications", title, body, type).notify(project)` 创建并展示

**实际状态**:**已实施**(但 displayType 是 `TOOL_WINDOW`,不是 plan 中的 `BALLOON`)。`OpenCodeNotificationService.send()` 第 104 行:

```kotlin
val notification = Notification(NOTIFICATION_GROUP, title, body, resolveType(eventType))
addClickAction(notification, eventType, project)
notification.notify(project)
```

#### Scenario: 通知类型映射

- **WHEN** 事件类型为 `error` 或 `user_cancelled`
- **THEN** 通知类型为 `NotificationType.ERROR`

- **WHEN** 事件类型为 `permission` 或 `question`
- **THEN** 通知类型为 `NotificationType.WARNING`

- **WHEN** 事件类型为其他
- **THEN** 通知类型为 `NotificationType.INFORMATION`

**实际状态**:**已实施**。`OpenCodeNotificationService.resolveType()`:

```kotlin
internal fun resolveType(eventType: String): NotificationType = when (eventType) {
    "permission", "question" -> NotificationType.WARNING
    else -> NotificationType.INFORMATION
}
```

`error` / `user_cancelled` → `ERROR` 映射**未实施**(因为 `error` 通知事件本身未触发)。

### Requirement: 多项目通知路由

系统 MUST 支持多项目场景,通知路由到事件对应项目的窗口。

#### Scenario: 多窗口路由

- **WHEN** IDE 打开了 3 个窗口(项目 A、B、C),SSE 收到项目 B 的通知事件
- **THEN** 通知只出现在项目 B 的窗口,不干扰其他窗口

**实际状态**:**已实施**(简化方案)。`OpenCodeSSEConsumer` 是 **per-project 实例**,每个 Project 创建独立的 `OpenCodeSSEConsumer(project)`,`project` 闭包直接传入 `OpenCodeNotificationService.send()`。没有 `projectRegistry` 路由表。

#### Scenario: 项目未打开或 Router 未注册

- **WHEN** SSE 收到一个未在 Router 中注册的项目的通知事件(项目未打开或工具窗口尚未初始化)
- **THEN** 系统静默忽略该通知

**实际状态**:**不适用**。`OpenCodeNotificationRouter` 是 10 行委托,无注册机制。SSE 本身是 per-project 连接(`URI = "http://host:port/event?directory=<encoded>"`),SSE Consumer 是 per-project 实例,因此**只会收到本项目的事件**。

#### Scenario: 项目注册(双保险机制)

- **WHEN** 项目加载时(通过 `ProjectManagerListener` 监听项目打开事件)
- **THEN** 立即向 Router 注册 `project.basePath → Project`

- **WHEN** 项目工具窗口首次创建时(`createToolWindowContent`)
- **THEN** 再次确认 Router 中已有该项目的注册(幂等操作)

**实际状态**:**未实施**。`OpenCodeNotificationRouter` 无 `register()` / `unregister()` 方法。`OpenCodeProjectActivity` **不调用** Router(只处理 `contentManagerListeners` 清理)。

#### Scenario: 项目注销

- **WHEN** 项目关闭时
- **THEN** 从 Router 注销对应映射

**实际状态**:**未实施**。Router 无 `unregister()`。

#### Scenario: 窗口最小化兜底

- **WHEN** 项目窗口 minimized 且通知到达
- **THEN** 系统调用 `notification.notify(project)`,依赖 IntelliJ 的 macOS 通知集成策略(此行为由 IDE 控制,插件不做额外判断)

**实际状态**:**已实施**(分支逻辑)。`OpenCodeNotificationService.send()` 第 98-99 行:

```kotlin
val frame = WindowManager.getInstance().getFrame(project)
val projectWindowActive = frame != null && frame.isActive
```

窗口最小化时 `frame.isActive == false`,走 `SystemNotifications` 路径(IDE 后台分支)。

### Requirement: 工具窗口聚焦抑制

系统 SHALL 在 OpenCodeWeb 工具窗口聚焦活跃时,跳过当前项目的通知。

#### Scenario: 工具窗口活跃时不发通知

- **WHEN** OpenCodeWeb 工具窗口 `isActive == true`(用户正在对话)
- **THEN** 当前项目的通知被抑制,不发送

**实际状态**:**已实施**。`OpenCodeNotificationService.send()` 第 68-71 行:

```kotlin
val tw = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")
if (tw?.isVisible == true && tw.isActive) {
    return
}
```

#### Scenario: 工具窗口打开但未聚焦时仍发通知

- **WHEN** OpenCodeWeb 工具窗口 `isVisible == true` 但 `isActive == false`(用户在其他标签页)
- **THEN** 正常发送通知

**实际状态**:**已实施**(上面的 `isVisible && isActive` 复合条件决定抑制,仅当两个都为 true 才抑制)。

#### Scenario: 工具窗口关闭时正常通知

- **WHEN** OpenCodeWeb 工具窗口 `isVisible == false`
- **THEN** 正常发送通知

**实际状态**:**已实施**。

### Requirement: 通知点击行为差异化

系统 SHALL 根据事件类型采用不同点击行为。

#### Scenario: permission/question/error 跳转工具窗口

- **WHEN** 用户点击 `permission`、`question` 或 `error` 类型通知
- **THEN** 系统调用 `ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")?.activate(null)`

**实际状态**:**部分实施**。`OpenCodeNotificationService.addClickAction()` 第 116-125 行:

```kotlin
internal fun addClickAction(notification: Notification, eventType: String, project: Project) {
    when (eventType) {
        "permission", "question" -> {
            notification.addAction(NotificationAction.createSimpleExpiring("打开") {
                ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")?.activate(null)
            })
        }
        else -> { /* 无操作 */ }
    }
}
```

- ✅ `permission` / `question` 已实施
- ❌ `error` 未实施(因为 `error` 通知未触发)

#### Scenario: complete/subagent_complete 仅聚焦 IDE

- **WHEN** 用户点击 `complete` 或 `subagent_complete` 通知
- **THEN** 系统不做额外操作

**实际状态**:**已实施**(`complete` 不在 `addClickAction` 的 when 分支中,默认无操作)。`subagent_complete` 通知**未实施**,但即便实施也会走 else 分支。

#### Scenario: 其他事件无操作

- **WHEN** 用户点击其他类型通知
- **THEN** 系统不做额外操作

**实际状态**:**已实施**。

### Requirement: minDuration 过滤

系统 SHALL 支持配置最短会话时长,低于该值时跳过 complete/subagent_complete 通知。

#### Scenario: 短会话过滤

- **WHEN** 收到 `complete` 或 `subagent_complete` 事件且 `minDuration > 0`
- **THEN** 通过 HTTP API 计算会话耗时,小于 minDuration 则丢弃通知

**实际状态**:**部分实施**。

- ✅ `complete` 事件 + `minDuration > 0` 过滤已实施(`OpenCodeNotificationService.send()` 第 74-86 行)
- ❌ `subagent_complete` 事件未实施(改用 title 正则抑制 subagent 主会话 complete)
- HTTP 调用经 `SessionInfoCache` 30s TTL 包装,避免 SSE 线程阻塞

### Requirement: Session 标题查询

系统 SHALL 在需要时通过 HTTP API 查询 session 信息用于通知内容。

#### Scenario: 标题查询与重试

- **WHEN** 收到 idle/error 事件且 `showSessionTitle = true`
- **THEN** 调用 HTTP API `GET /session/:sessionID` 获取 title,失败重试 1 次

**实际状态**:**部分实施**。

- ✅ HTTP API `GET /session/:sessionID` 已实现为 `OpenCodeApi.getSession()`
- ✅ 30s TTL `SessionInfoCache` 包装
- ❌ **失败重试 1 次未实施**(`OpenCodeApiResult` sealed class 统一错误处理,无重试)
- ❌ `error` 事件未触发,所以该场景只在 `complete` 路径走

#### Scenario: session 不存在

- **WHEN** HTTP API 返回 404
- **THEN** `{sessionTitle}` 使用空字符串,静默跳过

**实际状态**:**已实施**。`OpenCodeNotificationService.lookupSessionTitle()` 返回 `String?`,`formatMessage()` 用空字符串 fallback:

```kotlin
val sessionTitle = lookupSessionTitle(properties)
result = result.replace("{sessionTitle}", sessionTitle ?: "")
```

### Requirement: 项目卸载保护

系统 SHALL 在 project 已卸载时跳过通知发送。

#### Scenario: project.isDisposed

- **WHEN** IDEA 关闭过程中有新事件到达且 `project.isDisposed == true`
- **THEN** 不调用 `notification.notify(project)`,静默忽略

**实际状态**:**已实施**。`OpenCodeNotificationService.send()` 第 103 行:

```kotlin
if (projectWindowActive && !project.isDisposed) {
    val notification = Notification(NOTIFICATION_GROUP, title, body, resolveType(eventType))
    addClickAction(notification, eventType, project)
    notification.notify(project)
} else if (!ApplicationManager.getApplication().isActive()) {
    SystemNotifications.getInstance().notify(NOTIFICATION_GROUP, title, body)
}
```

## 已实施 vs 未实施 汇总

| Requirement                                | 状态      | 备注                                                     |
| ------------------------------------------ | --------- | -------------------------------------------------------- |
| 使用 IntelliJ 原生通知 API                 | ✅ 已实施 | 但 displayType 是 TOOL_WINDOW,不是 BALLOON               |
| 通知类型映射(permisson/question → WARNING) | ✅ 已实施 | error → ERROR 未触发                                     |
| 多项目通知路由(简化方案)                   | ✅ 已实施 | per-project SSE Consumer,无路由表                        |
| 多窗口路由 / 窗口最小化兜底                | ✅ 已实施 | frame.isActive 检测                                      |
| 工具窗口聚焦抑制                           | ✅ 已实施 | isVisible && isActive                                    |
| 通知点击行为(permission/question)          | ✅ 已实施 | error 未触发                                             |
| 通知点击行为(complete 无操作)              | ✅ 已实施 | 默认走 else                                              |
| minDuration 过滤(complete)                 | ✅ 已实施 | subagent_complete 未实施                                 |
| Session 标题查询(SessionInfoCache)         | ✅ 已实施 | 失败重试未实施                                           |
| 项目卸载保护(project.isDisposed)           | ✅ 已实施 | 互斥分支                                                 |
| 多项目 Router 注册/反注册(双保险)          | ❌ 未实施 | 简化为 10 行委托                                         |
| setImportant(true) 视觉强化                | ❌ 未实施 | IntelliJ 2026.1 实际对 BALLOON 停留时间无影响            |
| setSuggestionType(true) 按钮风格           | ❌ 未实施 | STICKY_BALLOON 未采用,设置无效                           |
| 11 事件类型完整支持                        | ❌ 未实施 | 只 4 事件(permission / complete / question / 预留 error) |
| 11 事件独立开关                            | ❌ 未实施 | 事件范围由 SSE Consumer when 硬编码                      |
| 11 消息模板自定义                          | ❌ 未实施 | 模板由 formatMessage 硬编码                              |

## 关键偏离:BALLOON → TOOL_WINDOW

**原 plan 设计**: `displayType="BALLOON"`(右下角气泡,10s 后自动消失)
**实际代码**: `displayType="TOOL_WINDOW" toolWindowId="OpenCodeWeb"`(工具窗口按钮旁,任何按键/点击立即消失)

**影响**:

- 通知在 OpenCodeWeb 工具窗口按钮旁显示,不在 IDE 主窗口右下角
- 用户**任何**键盘/鼠标活动立即让通知消失
- 通知**不会**进 macOS 系统通知中心(因为 TOOL_WINDOW 类型在 IDE 后台时不可见)
- 实际通知呈现与 `BALLOON` 差异显著,带来 UX 问题(用户反映"通知只停留几秒")

**修复建议(非本 change 范围)**:

- 改 `STICKY_BALLOON` + `setSuggestionType(true)` 实现永久显示
- 或改回 `BALLOON`(10s 自动消失)
- 见 `critic.md` 第 5 节后续变更建议

**commit 溯源**: `60ebd1f` (fix: change notification display type to tool window)
