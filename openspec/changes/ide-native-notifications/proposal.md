## Why

当前 IntelliJ 插件依赖服务端 `@mohak34/opencode-notifier` 插件发送系统通知，导致 macOS 上点击通知无法跳转到对应的 IDE（IDEA/GoLand/WebStorm）。改用 IDEA 原生通知机制后，通知将通过 IDE 自身发送，macOS 通知中心会自动关联到对应 IDE，点击即可跳转。

## What Changes

- 新增 SSE 通知事件处理器：监听 SSE 流中的 `permission.asked`、`session.status`、`session.idle`、`session.error`、`session.next.tool.called`、`server.connected`、`message.updated`、`session.created` 等事件（包括 11 种通知类型：permission/complete/subagent_complete/error/question/interrupted/user_cancelled/plan_exit/session_started/user_message/client_connected）
- 新增 `OpenCodeNotificationService`：将 SSE 事件映射为 IDEA 原生通知（`NotificationGroupManager`）
- 新增插件设置页面（Settings UI）：在 IntelliJ Settings → Tools 下增加 "OpenCode" 配置页，通知配置作为其中一个 Section
- 新增通知事件配置模型：支持用户按事件类型独立开关通知，同时支持 `minDuration`（会话最短耗时过滤，来自 opencode-notifier 的同名配置，避免短促交互时触发 complete 通知造成骚扰）、消息模板占位符（`{sessionTitle}`/`{projectName}`/`{timestamp}`/`{agentName}`）
- 修改 `OpenCodeSSEConsumer`：扩展事件处理逻辑，不再忽略生命周期/权限/工具调用事件；同时兼容 `session.status` 和 `session.idle` 两种事件格式
- (本次不涉及) 移除 `@mohak34/opencode-notifier` 依赖 — 待用户测试完成后手动处理

## Capabilities

### New Capabilities

- `notification-events`: SSE 通知事件监听与解析，覆盖 permission.asked、session.status、session.error、session.next.tool.called、server.connected、message.updated 等 11 种事件类型
- `notification-service`: IDEA 原生通知发送，事件→通知标题/内容/行为的映射逻辑，通知点击行为（跳转到工具窗口/聚焦 IDE）
- `notification-settings`: 插件设置页面，事件类型开关、消息模板自定义等配置能力

### Modified Capabilities

无

## Impact

- **OpenCodeSSEConsumer.kt** — 扩展 `onMessage` 方法，增加对新事件类型的处理和分发
- **SSEEventParser.kt** — 扩展解析逻辑，支持 SyncEvent 格式（`payload.type: "sync"`）
- 新增 **OpenCodeNotificationService**、**OpenCodeNotificationConfig** 等文件
- 新增 **Settings UI** 配置页面（IntelliJ 原生配置框架，数据通过 PropertiesComponent 持久化）
- `plugin.xml` — 只需在 `<extensions>` 中增加一行 `applicationConfigurable` 注册 OpenCodeConfigurable（parentId="tools"），其他均无需改动
