## ADDED Requirements

### Requirement: Settings UI 页面

系统 MUST 在 IntelliJ Settings → Tools 下注册 "OpenCode" 配置页。

#### Scenario: 页面可见

- **WHEN** 用户打开 Settings → Tools
- **THEN** 可以看到 "OpenCode" 配置项

#### Scenario: 页面结构

- **WHEN** 用户点击 "OpenCode"
- **THEN** 显示通知配置 Section（全局开关 + 12 个事件开关 + minDuration + 显示选项），且预留额外 Section 空间

### Requirement: 事件开关配置

系统 MUST 支持按事件类型独立开关通知。

#### Scenario: 事件开关 UI

- **WHEN** 用户打开通知配置 Section
- **THEN** 每个事件类型都有复选框（permission/complete/subagent_complete/error/question/interrupted/user_cancelled/plan_exit/session_started/user_message/client_connected）

#### Scenario: interrupted 标注

- **WHEN** 显示 `interrupted` 开关
- **THEN** 灰色显示并标注"当前环境不支持"

#### Scenario: 配置持久化

- **WHEN** 用户点击 Apply/OK
- **THEN** 配置写入 `PropertiesComponent.getInstance()`，Key 格式 `opencode.event.{type}.enabled`

#### Scenario: 配置立即生效

- **WHEN** 用户修改配置
- **THEN** 运行中的通知服务立即使用新配置，无需重启 IDE

#### Scenario: plan_exit 不支持标注

- **WHEN** `session.next.tool.called` 经实测不在 SSE 流中
- **THEN** `plan_exit` 开关也灰色显示并标注"当前环境不支持"

### Requirement: 消息模板配置

系统 SHALL 在通知配置 Section 中提供消息模板编辑功能。

#### Scenario: 消息模板文本框

- **WHEN** 用户展开某事件类型的配置
- **THEN** 可以看到该事件的消息模板文本输入框（默认值见默认模板列表）

#### Scenario: 模板持久化

- **WHEN** 用户修改消息模板并点击 Apply
- **THEN** 模板写入 `PropertiesComponent`，Key 格式 `opencode.message.{type}`

### Requirement: 通用配置

系统 SHALL 在 Setting UI 中提供 minDuration、showProjectName、showSessionTitle 配置项。

#### Scenario: minDuration

- **WHEN** 用户打开通知配置 Section
- **THEN** 可以看到 minDuration（秒）数字输入框，默认 0

#### Scenario: showProjectName

- **WHEN** 用户打开通知配置 Section
- **THEN** 可以看到"在通知标题中显示项目名称"复选框

#### Scenario: showSessionTitle

- **WHEN** 用户打开通知配置 Section
- **THEN** 可以看到"在通知内容中显示 Session 标题"复选框

### Requirement: 默认消息模板

系统 MUST 提供默认消息模板，支持 `{sessionTitle}`、`{projectName}`、`{timestamp}`、`{agentName}` 占位符。

#### Scenario: 默认模板

- **WHEN** PropertiesComponent 中无对应 key
- **THEN** 使用以下默认模板：

| 通知类型          | 默认消息                            |
| ----------------- | ----------------------------------- |
| permission        | "权限申请: {sessionTitle}"          |
| complete          | "回答完成: {sessionTitle}"          |
| subagent_complete | "Subagent 任务完成: {sessionTitle}" |
| error             | "执行错误: {sessionTitle}"          |
| question          | "询问用户: {sessionTitle}"          |
| interrupted       | "会话中断: {sessionTitle}"          |
| user_cancelled    | "会话取消: {sessionTitle}"          |
| plan_exit         | "Plan 制定完成: {sessionTitle}"     |
| session_started   | "新会话: {sessionTitle}"            |
| user_message      | "用户已发送消息"                    |
| client_connected  | "OpenCode 已连接"                   |

#### Scenario: 占位符替换

- **WHEN** 消息模板包含 `{sessionTitle}`
- **THEN** 替换为 HTTP API 获取的 session 标题

- **WHEN** 消息模板包含 `{projectName}`
- **THEN** 替换为 `project.basePath` 的 basename

- **WHEN** 消息模板包含 `{timestamp}`
- **THEN** 替换为当前 HH:MM:SS

- **WHEN** 消息模板包含 `{agentName}`
- **THEN** 替换为 session title 中 `(@name subagent)` 格式的 agent 名称

- **WHEN** 消息模板包含不支持的占位符
- **THEN** 保留原样输出
