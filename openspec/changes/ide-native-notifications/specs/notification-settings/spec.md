## ADDED Requirements(代码同步版)

> **状态说明**:本 spec 原 plan 设计完整的 Settings UI + 11 事件独立开关 + 11 消息模板,**整 Wave 3 未实施**。本文档反映代码现状:仅 4 个 PropertiesComponent 通用设置,**无 UI**。

### Requirement: Settings UI 页面

系统 MUST 在 IntelliJ Settings → Tools 下注册 "OpenCode" 配置页。

#### Scenario: 页面可见

- **WHEN** 用户打开 Settings → Tools
- **THEN** 可以看到 "OpenCode" 配置项

**实际状态**:**未实施**。`settings/OpenCodeConfigurable.kt` **不存在**。`plugin.xml` 中**无** `<applicationConfigurable>` 注册。用户**无法在 IDE 设置中看到 OpenCode 配置页**。

#### Scenario: 页面结构

- **WHEN** 用户点击 "OpenCode"
- **THEN** 显示通知配置 Section(全局开关 + 12 个事件开关 + minDuration + 显示选项),且预留额外 Section 空间

**实际状态**:**未实施**(整 Wave 3 未实施)。

### Requirement: 事件开关配置

系统 MUST 支持按事件类型独立开关通知。

#### Scenario: 事件开关 UI

- **WHEN** 用户打开通知配置 Section
- **THEN** 每个事件类型都有复选框(permission/complete/subagent_complete/error/question/interrupted/user_cancelled/plan_exit/session_started/user_message/client_connected)

**实际状态**:**未实施**。无 UI,也无 PropertiesComponent key(`opencode.event.{type}.enabled` **不存在**)。当前 4 个通知事件(permission / complete / question)的**触发范围由 `OpenCodeSSEConsumer.onMessage()` 的 `when (type)` 硬编码**。

#### Scenario: interrupted 标注

- **WHEN** 显示 `interrupted` 开关
- **THEN** 灰色显示并标注"当前环境不支持"

**实际状态**:**不适用**(整 Wave 3 未实施)。

#### Scenario: 配置持久化

- **WHEN** 用户点击 Apply/OK
- **THEN** 配置写入 `PropertiesComponent.getInstance()`,Key 格式 `opencode.event.{type}.enabled`

**实际状态**:**未实施**。PropertiesComponent 中**无** `opencode.event.*` keys。

#### Scenario: 配置立即生效

- **WHEN** 用户修改配置
- **THEN** 运行中的通知服务立即使用新配置,无需重启 IDE

**实际状态**:**不适用**(配置项未实施)。

#### Scenario: plan_exit 不支持标注

- **WHEN** `session.next.tool.called` 经实测不在 SSE 流中
- **THEN** `plan_exit` 开关也灰色显示并标注"当前环境不支持"

**实际状态**:**不适用**(整 Wave 3 未实施)。

### Requirement: 消息模板配置

系统 SHALL 在通知配置 Section 中提供消息模板编辑功能。

#### Scenario: 消息模板文本框

- **WHEN** 用户展开某事件类型的配置
- **THEN** 可以看到该事件的消息模板文本输入框(默认值见默认模板列表)

**实际状态**:**未实施**。**模板硬编码在 `OpenCodeNotificationService.formatMessage()` 的 `when (eventType)` 中**。

#### Scenario: 模板持久化

- **WHEN** 用户修改消息模板并点击 Apply
- **THEN** 模板写入 `PropertiesComponent`,Key 格式 `opencode.message.{type}`

**实际状态**:**未实施**。PropertiesComponent 中**无** `opencode.message.*` keys。

### Requirement: 通用配置

系统 SHALL 在 Setting UI 中提供 minDuration、showProjectName、showSessionTitle 配置项。

#### Scenario: minDuration

- **WHEN** 用户打开通知配置 Section
- **THEN** 可以看到 minDuration(秒)数字输入框,默认 0

**实际状态**:**PropertiesComponent key 已实施,但无 UI**。

- ✅ `OpenCodeConfig.minDuration: Int` (key: `opencode.settings.minDuration`, 默认 0)
- ✅ 被 `OpenCodeNotificationService.send()` 第 74-86 行消费,过滤 `complete` 通知
- ❌ 无 UI 入口,**用户无法调整**(只能手动改 PropertiesComponent 配置文件)

#### Scenario: showProjectName

- **WHEN** 用户打开通知配置 Section
- **THEN** 可以看到"在通知标题中显示项目名称"复选框

**实际状态**:**PropertiesComponent key 已实施,但无 UI**。

- ✅ `OpenCodeConfig.showProjectName: Boolean` (key: `opencode.settings.showProjectName`, 默认 true)
- ✅ 被 `OpenCodeNotificationService.send()` 第 88-89 行消费,控制 title 拼接项目名
- ❌ 无 UI 入口

#### Scenario: showSessionTitle

- **WHEN** 用户打开通知配置 Section
- **THEN** 可以看到"在通知内容中显示 Session 标题"复选框

**实际状态**:**PropertiesComponent key 已实施,但无 UI**。

- ✅ `OpenCodeConfig.showSessionTitle: Boolean` (key: `opencode.settings.showSessionTitle`, 默认 true)
- ✅ 被 `OpenCodeNotificationService.lookupSessionTitle()` 消费,控制是否查 session title
- ❌ 无 UI 入口

### Requirement: 默认消息模板

系统 MUST 提供默认消息模板,支持 `{sessionTitle}`、`{projectName}`、`{timestamp}`、`{agentName}` 占位符。

#### Scenario: 默认模板

- **WHEN** PropertiesComponent 中无对应 key
- **THEN** 使用以下默认模板:

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

**实际状态**:**部分实施**。`OpenCodeNotificationService.formatMessage()` 硬编码 3 个事件的模板:

```kotlin
internal fun formatMessage(eventType: String, properties: Map<*, *>?, project: Project): String {
    val template = when (eventType) {
        "permission" -> "权限申请: {sessionTitle}"
        "complete" -> "回答完成: {sessionTitle}"
        "question" -> "询问用户: {sessionTitle}"
        else -> eventType  // 未知事件用 type 字符串
    }
    // ...
}
```

**未实现默认模板的事件类型**:`subagent_complete` / `error` / `interrupted` / `user_cancelled` / `plan_exit` / `session_started` / `user_message` / `client_connected`(因为这些事件本身不触发通知)。

#### Scenario: 占位符替换

- **WHEN** 消息模板包含 `{sessionTitle}`
- **THEN** 替换为 HTTP API 获取的 session 标题

**实际状态**:**已实施**。`formatMessage()` 完整实现 4 个占位符替换:`{sessionTitle}` / `{projectName}` / `{timestamp}` / `{agentName}`。未识别的占位符保留原样(原 plan 行为)。

## 已实施 vs 未实施 汇总

| Requirement                                                                  | PropertiesComponent       | UI        |
| ---------------------------------------------------------------------------- | ------------------------- | --------- |
| 4 个通用 setting (notification/showProjectName/showSessionTitle/minDuration) | ✅ 已实施                 | ❌ 未实施 |
| 11 事件独立开关 (opencode.event.\*)                                          | ❌ 未实施                 | ❌ 未实施 |
| 11 消息模板 (opencode.message.\*)                                            | ❌ 未实施                 | ❌ 未实施 |
| 4 占位符替换 ({sessionTitle}/{projectName}/{timestamp}/{agentName})          | ✅ 硬编码在 formatMessage | N/A       |
| 硬编码默认模板 (permission/complete/question)                                | ✅ 硬编码在 formatMessage | N/A       |

## 用户实际可配置项

> **用户须知**:当前唯一可配置途径是手动修改 PropertiesComponent 配置文件
>
> - **位置**: `~/.config/JetBrains/<IDE>/options/workspace.xml`
> - **搜索关键字**: `opencode.settings`
> - **可改 key**:
>   - `opencode.settings.notification` (Boolean, 默认 true)
>   - `opencode.settings.showProjectName` (Boolean, 默认 true)
>   - `opencode.settings.showSessionTitle` (Boolean, 默认 true)
>   - `opencode.settings.minDuration` (Int, 默认 0, 单位秒)

## 后续变更建议(非本 change 范围)

1. **新 change:补全 Settings UI** — 实施 `OpenCodeConfigurable` + `applicationConfigurable` 注册
2. **新 change:11 事件独立开关** — `OpenCodeConfig` 增加 `isEventEnabled(type)` / `setEventEnabled(type, enabled)`,SSE Consumer 消费
3. **新 change:消息模板自定义** — `OpenCodeConfig` 增加 `getMessageTemplate(type)` / `setMessageTemplate(type, template)`,`OpenCodeNotificationService.formatMessage` 改读 PropertiesComponent
