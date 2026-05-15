## ADDED Requirements

### Requirement: Setting UI 页面

系统 MUST 在 IntelliJ Settings → Tools 下注册 "OpenCode" 配置页面。

#### Scenario: 页面可见

- **WHEN** 用户打开 Settings → Tools
- **THEN** 可以看到 "OpenCode" 配置项

#### Scenario: 页面结构

- **WHEN** 用户点击 "OpenCode" 配置项
- **THEN** 显示配置页面，包含通知配置 Section，且预留额外 Section 空间

### Requirement: 通知事件开关配置

系统 MUST 支持按事件类型独立开关通知。

#### Scenario: 事件开关 UI

- **WHEN** 用户打开通知配置 Section
- **THEN** 每个事件类型（permission/complete/subagent_complete/error/question/user_cancelled/plan_exit/session_started/user_message/client_connected）都有一个复选框开关

#### Scenario: 开关状态持久化

- **WHEN** 用户修改开关状态并点击 Apply/OK
- **THEN** 配置保存到 `PropertiesComponent` 和/或 `opencode-ide.json` 文件

#### Scenario: 配置立即生效

- **WHEN** 用户修改开关状态
- **THEN** 运行中的 `OpenCodeNotificationService` 立即使用新配置，无需重启 IDE

### Requirement: 配置文件支持

系统 SHALL 支持通过 `~/.config/opencode/opencode-ide.json` 配置文件加载配置。

#### Scenario: 配置文件加载

- **WHEN** 插件启动
- **THEN** 读取 `~/.config/opencode/opencode-ide.json` 文件（如果存在），解析为 `NotifierConfig` 对象

#### Scenario: 配置文件不存在

- **WHEN** `~/.config/opencode/opencode-ide.json` 不存在
- **THEN** 使用默认配置（permission/complete/error/client_connected 开启，其余关闭）

#### Scenario: 配置合并

- **WHEN** 配置文件和 Setting UI 同时存在配置
- **THEN** 文件配置为基础，UI 配置为覆盖（UI 优先）

### Requirement: 默认通知消息模板

系统 MUST 提供默认的通知消息模板，支持 `{sessionTitle}` 占位符。

#### Scenario: 默认消息

- **WHEN** 未自定义消息模板
- **THEN** 使用如下默认模板：

| 通知类型          | 默认消息                            |
| ----------------- | ----------------------------------- |
| permission        | "需要权限: {sessionTitle}"          |
| complete          | "回答完成: {sessionTitle}"          |
| subagent_complete | "Subagent 任务完成: {sessionTitle}" |
| error             | "执行错误: {sessionTitle}"          |
| question          | "需要回答: {sessionTitle}"          |
| user_cancelled    | "会话取消: {sessionTitle}"          |
| plan_exit         | "Plan 制定完成: {sessionTitle}"     |
| session_started   | "新会话: {sessionTitle}"            |
| user_message      | "用户已发送消息"                    |
| client_connected  | "OpenCode 已连接"                   |

#### Scenario: 标题占位符替换

- **WHEN** 消息模板包含 `{sessionTitle}`
- **THEN** 替换为实际的 session 标题（可通过 HTTP API 获取）
