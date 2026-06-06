## Why

> **状态说明**:本 change 的 plan/critic 在 2026-05-31 批准,但**实际实施偏离了原计划**(见各文件「Status」段)。本文档已与代码同步,以代码为唯一事实来源。本文件保留以供回溯。

## What Changes (实际落地)

- 新增 `OpenCodeNotificationService`:双模式通知(BALLOON + `SystemNotifications`),支持工具窗口聚焦抑制、`minDuration` 过滤、模板占位符、permission/question 点击动作
- 新增 `OpenCodeNotificationRouter`:简化版委托(无多项目路由表,project 直接由 SSE Consumer 传入)
- 新增 `OpenCodeConfig`:PropertiesComponent 包装,4 个通用设置(`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`)
- 修改 `OpenCodeSSEConsumer`:`onMessage()` 中扩展对 `permission.asked` / `session.status` / `session.idle` / `question.asked` 的分发;`session.idle` 走 `handleSessionIdle()` 三层去重
- 修改 `plugin.xml`:`<notificationGroup>` 注册保留,但 **`displayType` 从原计划的 `BALLOON` 改为 `TOOL_WINDOW`**(实际代码见 `META-INF/plugin.xml:51`)

## What Changes (未实施,仅保留在原 plan)

> **未实施项**:以下条目**没有代码对应**,保留在此处仅用于历史回溯

- ~~`OpenCodeConfigurable` Settings UI~~ — `settings/OpenCodeConfigurable.kt` 不存在
- ~~`<applicationConfigurable>` 注册~~ — `plugin.xml` 中无此条目
- ~~11 个事件独立开关 (`opencode.event.{type}.enabled`)~~ — PropertiesComponent 中**未实现**;通知事件范围**硬编码在 `OpenCodeSSEConsumer.onMessage()` 的 `when (type)` 分支中**
- ~~11 个消息模板 (`opencode.message.{type}`)~~ — **硬编码在 `OpenCodeNotificationService.formatMessage()` 的 `when (eventType)` 分支中**
- ~~SyncEvent V2 解析 (`payload.syncEvent.*`)~~ — `SSEEventParser` **未实现** SyncEvent V2 字段提取,只解析 Direct BusEvent 的 `payload.properties.*`
- ~~`session.next.tool.called` 检测 (question / plan_exit)~~ — 仅 `question.asked` BusEvent 在用;`plan_exit` **未支持**
- ~~`session_started` / `user_message` / `client_connected` / `subagent_complete` / `error` / `user_cancelled` 通知~~ — **均未触发**;`OpenCodeSSEConsumer` 只分发 4 类事件:`permission.asked` / `session.status(idle)` / `session.idle` / `question.asked`
- ~~多项目路由 (`projectRegistry`)~~ — `OpenCodeNotificationRouter` 是 10 行委托,**没有** `ConcurrentHashMap` 路由表
- ~~`subagentSessionIds` Set 追踪 (基于 `session.created.parentID` / `session.deleted`)~~ — 改用 `SUBAGENT_TITLE_REGEX = Regex("""@\w+ subagent""")` 匹配 session title
- ~~350ms idle debounce + 2s idleLastFired window~~ — 改用 `NOTIFICATION_DEDUP_WINDOW_MS = 1000L` LRU(基于 `(sessionID, eventType)` 1s 去重)+ `idleNotifiedSessions` Set(per-session 首次 idle 触发,`message.updated(role=user)` 重置)
- ~~`osascript` 移除~~ — `(本次不涉及)` 原文如此;`@mohak34/opencode-notifier` 实际是否还在运行,本仓库不感知

## Capabilities

### Modified Capabilities

- `notification-events`(delta):从原计划的 11 事件 → **实际 4 事件**(`permission` / `complete` / `question` / `error` 预备)。详见 `specs/notification-events/spec.md`
- `notification-service`(delta):从原计划的 28 keys → **实际 4 keys**;`displayType` 从 `BALLOON` → **`TOOL_WINDOW`**(见 `plugin.xml:51`)。详见 `specs/notification-service/spec.md`
- `notification-settings`(delta):**Settings UI 未实施**;当前仅 4 个通用设置,无 per-event 开关/模板。详见 `specs/notification-settings/spec.md`

## Impact (实际改动文件)

| 文件                                                       | 改动                                                                                                                                           |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main/resources/META-INF/plugin.xml`                   | 第 51 行 `<notificationGroup id="OpenCodeWeb.notifications" displayType="TOOL_WINDOW" toolWindowId="OpenCodeWeb"/>`(`displayType` 偏离原 plan) |
| `src/main/kotlin/.../utils/OpenCodeNotificationService.kt` | **新建**                                                                                                                                       |
| `src/main/kotlin/.../utils/OpenCodeNotificationRouter.kt`  | **新建**                                                                                                                                       |
| `src/main/kotlin/.../utils/OpenCodeConfig.kt`              | **新建**(只 4 个 setting)                                                                                                                      |
| `src/main/kotlin/.../listeners/OpenCodeSSEConsumer.kt`     | `onMessage()` 中扩展 4 类通知事件分发;新增 `handleSessionIdle()` / `dispatchNotification()`                                                    |
| `src/main/kotlin/.../listeners/SSEEventParser.kt`          | 白名单加入 4 个通知事件 `session.idle` / `session.status` / `permission.asked` / `question.asked`                                              |
| `src/main/kotlin/.../utils/OpenCodeApi.kt`                 | 新增 `getSession(sessionID)`(被 `SessionInfoCache` 消费)                                                                                       |

## Impact (未实际改动)

- `src/main/kotlin/.../settings/OpenCodeConfigurable.kt` — **不存在**
- `src/main/kotlin/.../toolWindow/OpenCodeProjectActivity.kt` — **未注册** `OpenCodeNotificationRouter`(Router 是 10 行委托,无需注册)

## 回溯说明

- 本 change 计划于 2026-05-15 创建,2026-05-31 critic 通过
- 实际代码落地时间无明确 commit 标记,但根据 `git log` 中 commit `3df951c` (feat: implement ide-native-notifications core) 可推断
- **显示类型变更** (BALLOON → TOOL_WINDOW) 的 commit 是 `60ebd1f` (fix: change notification display type to tool window)
- 后续 commit `1a7a03b` (fix: replace idle session debounce timer with dedup window) 把原计划的 350ms debounce + 2s window 改成了 1s LRU
- SPEC.md 中明确记录此状态为 "GAP-8: ide-native-notifications 0/11 tasks 待实施" — **此 GAP 描述不再准确**,实际有部分实施,只是偏离了原 plan
