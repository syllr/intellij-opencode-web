## ADDED Requirements

### Requirement: 使用 IntelliJ 原生通知 API

系统 MUST 使用 IntelliJ Platform 的 `NotificationGroupManager` 发送原生通知。

#### Scenario: 通知创建

- **WHEN** 有事件需要通知
- **THEN** 系统通过 `NotificationGroupManager.getInstance().getNotificationGroup("OpenCodeWeb.notifications")` 获取通知组并创建 `Notification`

#### Scenario: 通知展示

- **WHEN** 创建 `Notification` 后
- **THEN** 系统调用 `notification.notify(project)` 展示通知

#### Scenario: 通知类型映射

- **WHEN** 收到 `error` 或 `user_cancelled` 事件
- **THEN** 通知类型为 `NotificationType.ERROR`

- **WHEN** 收到 `permission` 或 `question` 事件
- **THEN** 通知类型为 `NotificationType.WARNING`

- **WHEN** 收到其他事件
- **THEN** 通知类型为 `NotificationType.INFORMATION`

### Requirement: 通知点击行为

系统 SHALL 支持通知点击后跳转到 OpenCodeWeb 工具窗口。

#### Scenario: 点击通知跳转

- **WHEN** 用户点击 IDE 通知
- **THEN** 系统调用 `ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")?.activate(null)` 激活工具窗口

### Requirement: OpenCodeNotificationService 服务类

系统 MUST 提供 `OpenCodeNotificationService` 类，封装通知逻辑。

#### Scenario: 服务初始化

- **WHEN** `OpenCodeSSEConsumer` 启动
- **THEN** `OpenCodeNotificationService` 被创建并持有当前 `Project` 引用

#### Scenario: 事件分发

- **WHEN** `OpenCodeSSEConsumer` 收到通知事件
- **THEN** 调用 `openCodeNotificationService.notify(eventType, properties)` 处理通知

#### Scenario: 配置检查

- **WHEN** 事件到达通知服务
- **THEN** 服务检查用户配置中该事件类型是否允许通知，关闭则不发送

### Requirement: Session 标题查询

系统 SHALL 在需要时通过 HTTP API 查询 session 信息以获取通知标题。

#### Scenario: session.status(idle) 时查询标题

- **WHEN** 收到 `session.status(idle)` 事件且用户配置 `showSessionTitle = true`
- **THEN** 系统调用 HTTP API `GET /session/:sessionID` 获取 session title

#### Scenario: session.error 时查询标题

- **WHEN** 收到 `session.error` 事件且用户配置 `showSessionTitle = true`
- **THEN** 系统调用 HTTP API `GET /session/:sessionID` 获取 session title
