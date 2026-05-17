## ADDED Requirements

### Requirement: 使用 IntelliJ 原生通知 API

系统 MUST 使用 `Notification` 构造函数（新 API，IntelliJ Platform 推荐用法）发送 IDE 原生通知，而非已废弃的 `NotificationGroupManager`。

#### Scenario: 通知创建与展示

- **WHEN** 有事件需要通知
- **THEN** 通过 `Notification("OpenCodeWeb.notifications", title, body, type).notify(project)` 创建并展示

#### Scenario: 通知类型映射

- **WHEN** 事件类型为 `error` 或 `user_cancelled`
- **THEN** 通知类型为 `NotificationType.ERROR`

- **WHEN** 事件类型为 `permission` 或 `question`
- **THEN** 通知类型为 `NotificationType.WARNING`

- **WHEN** 事件类型为其他
- **THEN** 通知类型为 `NotificationType.INFORMATION`

### Requirement: 多项目通知路由

系统 MUST 支持多项目场景，通知路由到事件对应项目的窗口。

#### Scenario: 多窗口路由

- **WHEN** IDE 打开了 3 个窗口（项目 A、B、C），SSE 收到项目 B 的通知事件
- **THEN** 通知只出现在项目 B 的窗口，不干扰其他窗口

#### Scenario: 项目未打开或 Router 未注册

- **WHEN** SSE 收到一个未在 Router 中注册的项目的通知事件（项目未打开或工具窗口尚未初始化）
- **THEN** 系统静默忽略该通知

#### Scenario: 项目注册（双保险机制）

- **WHEN** 项目加载时（通过 `ProjectManagerListener` 监听项目打开事件）
- **THEN** 立即向 Router 注册 `project.basePath → Project`

- **WHEN** 项目工具窗口首次创建时（`createToolWindowContent`）
- **THEN** 再次确认 Router 中已有该项目的注册（幂等操作）

#### Scenario: 项目注销

- **WHEN** 项目关闭时
- **THEN** 从 Router 注销对应映射

#### Scenario: 窗口最小化兜底

- **WHEN** 项目窗口 minimized 且通知到达
- **THEN** 系统调用 `notification.notify(project)`，依赖 IntelliJ 的 macOS 通知集成策略（此行为由 IDE 控制，插件不做额外判断）

### Requirement: 工具窗口聚焦抑制

系统 SHALL 在 OpenCodeWeb 工具窗口聚焦活跃时，跳过当前项目的通知。

#### Scenario: 工具窗口活跃时不发通知

- **WHEN** OpenCodeWeb 工具窗口 `isActive == true`（用户正在对话）
- **THEN** 当前项目的通知被抑制，不发送

#### Scenario: 工具窗口打开但未聚焦时仍发通知

- **WHEN** OpenCodeWeb 工具窗口 `isVisible == true` 但 `isActive == false`（用户在其他标签页）
- **THEN** 正常发送通知

#### Scenario: 工具窗口关闭时正常通知

- **WHEN** OpenCodeWeb 工具窗口 `isVisible == false`
- **THEN** 正常发送通知

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
