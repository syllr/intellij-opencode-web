## ADDED Requirements

### Requirement: 使用 IntelliJ 原生通知 API

系统 MUST 使用 `NotificationGroupManager` 发送 IDE 原生通知。

#### Scenario: 通知创建与展示

- **WHEN** 有事件需要通知
- **THEN** 通过 `NotificationGroupManager.getInstance().getNotificationGroup("OpenCodeWeb.notifications")` 创建 `Notification`，调用 `.notify(project)` 展示

#### Scenario: 通知类型映射

- **WHEN** 事件类型为 `error` 或 `user_cancelled`
- **THEN** 通知类型为 `NotificationType.ERROR`

- **WHEN** 事件类型为 `permission` 或 `question`
- **THEN** 通知类型为 `NotificationType.WARNING`

- **WHEN** 事件类型为其他
- **THEN** 通知类型为 `NotificationType.INFORMATION`

### Requirement: 通知点击行为差异化

系统 SHALL 根据事件类型采用不同点击行为。

#### Scenario: permission/question/error 跳转工具窗口

- **WHEN** 用户点击 `permission`、`question` 或 `error` 类型通知
- **THEN** 系统调用 `ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")?.activate(null)`

#### Scenario: complete/subagent_complete 仅聚焦 IDE

- **WHEN** 用户点击 `complete` 或 `subagent_complete` 通知
- **THEN** 系统不做额外操作

#### Scenario: 其他事件无操作

- **WHEN** 用户点击其他类型通知
- **THEN** 系统不做额外操作

### Requirement: minDuration 过滤

系统 SHALL 支持配置最短会话时长，低于该值时跳过 complete/subagent_complete 通知。

#### Scenario: 短会话过滤

- **WHEN** 收到 `complete` 或 `subagent_complete` 事件且 `minDuration > 0`
- **THEN** 通过 HTTP API 计算会话耗时，小于 minDuration 则丢弃通知

### Requirement: Session 标题查询

系统 SHALL 在需要时通过 HTTP API 查询 session 信息用于通知内容。

#### Scenario: 标题查询与重试

- **WHEN** 收到 idle/error 事件且 `showSessionTitle = true`
- **THEN** 调用 HTTP API `GET /session/:sessionID` 获取 title，失败重试 1 次

#### Scenario: session 不存在

- **WHEN** HTTP API 返回 404
- **THEN** `{sessionTitle}` 使用空字符串，静默跳过

### Requirement: 项目卸载保护

系统 SHALL 在 project 已卸载时跳过通知发送。

#### Scenario: project.isDisposed

- **WHEN** IDEA 关闭过程中有新事件到达且 `project.isDisposed == true`
- **THEN** 不调用 `notification.notify(project)`，静默忽略
