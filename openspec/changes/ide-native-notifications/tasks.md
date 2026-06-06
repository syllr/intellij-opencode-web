## Tasks(代码同步版)

> **状态说明**:原 plan 列出 11 个 task,实际代码**只实施了部分**。本文件按"已实施/未实施"重新组织,保留原 task 标题作为历史回溯锚点。

### Wave 0: 前置验证

- [x] 0.1 SSE 事件 + HTTP API 实测
      **What to do**:
  - curl 连接 SSE 端点,触发 question/permission 场景验证 `session.next.tool.called` 是否在 SSE 流中
  - 验证 `GET /session/:sessionID` API 是否存在及返回格式(title、parentID、time.created)
  - 记录 `session.next.tool.called` 的字段路径(Direct: properties.tool, Sync: data.tool)
  - 如果不可用:确认 `question.asked` BusEvent 作为 question fallback

    **Acceptance Criteria**: session.next.tool.called 格式确认 + GET /session/:id 可用
    **实际状态**:**部分完成**。SSE 事件格式已实测(见 `research/sse-event-format-verification/research.md`),`GET /session/:id` 已实现为 `OpenCodeApi.getSession()`。**`session.next.tool.called` 是否在 SSE 流未实测**,但实际**未采用该事件**,改用 `question.asked` BusEvent 作为唯一 question 来源;`plan_exit` 工具调用**完全不通知**。

- [x] 0.2 扩展 OpenCodeApi
      **What to do**: 新增 `getSession(sessionID: String): SessionInfo?`,返回 title/parentID/time
      **Acceptance Criteria**: `OpenCodeApi.getSession("ses_xxx")` 返回 session 标题
      **实际状态**:**已完成**。`utils/OpenCodeApi.kt` 中实现 `getSession()` API,被 `OpenCodeNotificationService.SessionInfoCache` 消费(title + timeCreated)。

### Wave 1: SSE 事件监听扩展

- [x] 1.1 SSEEventParser 增加 SyncEvent 解析 + 去重
      **What to do**:
  - `ParsedSSEEvent` 新增 `syncEventType`、`syncEventData` 字段
  - `parse()` 中检测 `payload.type == "sync"` → 提取 `syncEvent.type`(去掉 `.N` 后缀)和 `syncEvent.data`
  - 下游消费者判断:`payloadType == "sync"` 时用 `syncEventType` 路由
  - 实现 eventID 去重:`LinkedHashMap(accessOrder=true) + removeEldestEntry` + `synchronizedMap`,最大 1000 条
  - 无 `payload.id` 的事件用 `eventType + sessionID` 复合键去重
  - 确保现有 Direct BusEvent 解析不受影响

    **Must NOT do**: 不破坏文件事件解析
    **实际状态**:**部分完成**。
    - ✅ eventID 去重:已实现(LRU 1000, `SSEEventParser.dedupCache`)
    - ✅ Direct BusEvent 解析:已实现
    - ❌ SyncEvent V2 解析(**`syncEventType` / `syncEventData` 字段未添加**)
    - ❌ `eventType + sessionID` 复合键去重(只用 event id 去重)
    - 实际 `ParsedSSEEvent` 只有 `eventType: String` 和 `parsedMap: Map<*, *>?` 两个字段,无 SyncEvent 字段

- [x] 1.2 OpenCodeSSEConsumer 通知事件分发
      **What to do**:
  - 在 `onMessage()` 第 80 行 early return **之前**插入通知事件分发
  - 实现 SSE 事件到 11 种通知类型的映射
  - subagent 本地追踪:`session.created(有parentID)` 加入 Set,`session.deleted` 移除
  - `session.status(idle)` 或 `session.idle` → 350ms debounce,查 subagent 集合区分 complete/subagent_complete
  - `session.error` → error/user_cancelled(检查 error.name)
  - `session.next.tool.called(tool=question/plan_exit)` → question/plan_exit(含 SyncEvent 兼容)
  - `question.asked` → question(fallback 路径)
  - `message.updated(role=user)` → user_message
  - `session.created(无parentID,Sync)` → session_started

    **Must NOT do**: 不修改已有文件刷新逻辑
    **实际状态**:**部分完成**。
    - ✅ 4 类通知事件分发(已实现):
      - `permission.asked` → `permission`
      - `session.status(idle)` → `handleSessionIdle()` → `complete`
      - `session.idle` → `handleSessionIdle()` → `complete`
      - `question.asked` → `question`
    - ❌ `session.error` → `error` / `user_cancelled`(**未实现**)
    - ❌ `session.next.tool.called` 检测(**未实现**)
    - ❌ `session.error.name == "MessageAbortedError"` 区分(**未实现**)
    - ❌ `session_started` / `user_message` 通知(**未实现**)
    - ❌ `subagentSessionIds` Set 追踪(**未实现**,改用 title 正则)
    - ❌ 350ms debounce + subagent 集合区分(**未实现**,改用 1s LRU + per-session Set)
    - ✅ `message.updated(role=user)` → **仅用于重置 idle 抑制**,**未发 user_message 通知**

### Wave 2: 通知核心服务

- [x] 2.1 创建 OpenCodeNotificationRouter
      **What to do**:
  - `object` 单例,维护 `ConcurrentHashMap<String, Project>`
  - `register(project)`: 通过 `ProjectManagerListener`(项目加载时)和 `createToolWindowContent`(工具窗口创建时)双保险注册
  - `unregister(project)`: 项目关闭时注销
  - `notify(eventType, properties, eventDir)`: 用 `File.canonicalPath` 规范化路径后查找
  - null directory 时静默返回
  - 最外层 try/catch 兜底 `ProcessCanceledException`

    **实际状态**:**大幅简化,未达到原 plan 设计**。
    - ❌ `ConcurrentHashMap` 路由表(**未实现**)
    - ❌ `ProjectManagerListener` 双保险注册(**未实现**)
    - ❌ `unregister()`(**未实现**)
    - ❌ `eventDir` 参数和 `File.canonicalPath` 规范化(**未实现**)
    - 实际:10 行委托,`notify(eventType, properties, project)`,project 由 SSE Consumer 直接传入
    - 简化理由:OpenCodeSSEConsumer 是 per-project 实例,`project` 闭包已正确,无需路由表

- [x] 2.2 创建 OpenCodeNotificationService
      **What to do**:
  - 双模式通知:先 `Notification(...).notify(project)`(BALLOON),后 `SystemNotifications.getInstance().notify()`(后台)
  - `WindowManager.getFrame(project).isActive` 判断项目窗口焦点(多显示器场景)
  - 工具窗口聚焦抑制:`ToolWindowManager.getToolWindow("OpenCodeWeb")?.isActive == true` 时跳过
  - 差异化点击:permission/question/error 激活工具窗口,其余仅聚焦 IDE
  - `minDuration` 过滤:complete/subagent_complete 时计算会话耗时
  - `project.isDisposed` 守卫 + try/catch 兜底
  - HTTP API `GET /session/:id` 查询 session 标题(超时复用 `HTTP_TIMEOUT_MS=8000`,失败重试 1 次)
  - 支持 `{sessionTitle}/{projectName}/{timestamp}/{agentName}` 占位符替换,未识别保留原样

    **实际状态**:**大部分完成,有偏离**。
    - ✅ 双模式发送:**实现为互斥分支**(if-else),不是同时调
    - ✅ `WindowManager.getFrame(project).isActive` 多显示器检测
    - ✅ 工具窗口聚焦抑制
    - ✅ 点击动作:仅 `permission` / `question` 激活工具窗口(`error` **未实现**差异化点击)
    - ✅ `minDuration` 过滤(仅 `complete` 事件,`subagent_complete` **未独立处理**)
    - ✅ `project.isDisposed` 守卫 + try/catch 兜底
    - ✅ HTTP API 查询 session 标题(经 `SessionInfoCache` 30s TTL 包装)
    - ✅ 4 个占位符 `{sessionTitle}/{projectName}/{timestamp}/{agentName}` 支持
    - ❌ 失败重试 1 次(未实现;由 `OpenCodeApiResult` sealed class 统一错误处理)

- [x] 2.3 创建 OpenCodeConfig
      **What to do**:
  - 基于 `PropertiesComponent.getInstance()` (IDE 级别)
  - Key 命名:`opencode.settings.{name}` / `opencode.event.{type}.enabled` / `opencode.message.{type}`
  - 11 种事件开关读写(含 interrupted,默认 false)
  - 通用设置:notificationEnabled、showProjectName、showSessionTitle、minDuration
  - 默认消息模板(与 opencode-notifier 配置一致)
  - `{agentName}` 从 session title 的 `(@name subagent)` 格式提取,不存在时填空字符串

    **实际状态**:**部分完成**。
    - ✅ PropertiesComponent 包装
    - ✅ 4 个通用设置:`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`
    - ❌ 11 事件开关 `opencode.event.{type}.enabled`(**未实现**)
    - ❌ 11 消息模板 `opencode.message.{type}`(**未实现**;模板硬编码在 `OpenCodeNotificationService.formatMessage()`)
    - ❌ interrupted 单独处理(**未实现**)
    - ✅ `{agentName}` 占位符实现(在 `OpenCodeNotificationService.lookupAgentName()`,从 session title 提取)
    - 实际只有 4 个 key,无 PropertiesComponent 完整配置层

### Wave 3: Settings UI

- [ ] 3.1 创建 OpenCodeConfigurable
      **What to do**:
  - `settings/` 包下实现 `Configurable`,注册在 Settings → Tools → OpenCode
  - 通知 Section:全局开关、11 个事件独立开关(interrupted 灰色)、消息模板文本框
  - 通用设置:minDuration、showProjectName、showSessionTitle
  - `isModified()/apply()/reset()`,apply 写入 PropertiesComponent
  - 校验:minDuration 不能为负数

    **实际状态**:**未实施**。
    - ❌ `settings/OpenCodeConfigurable.kt` 不存在
    - ❌ 4 个通用设置无 UI 配置入口(用户只能手动改 PropertiesComponent 配置文件)
    - ❌ 11 事件独立开关未实现
    - ❌ 消息模板自定义未实现

- [ ] 3.2 更新 plugin.xml
      **What to do**: 添加 `<applicationConfigurable parentId="tools" .../>`
      **实际状态**:**未实施**。- `plugin.xml` 中**无** `<applicationConfigurable>` 注册 - `plugin.xml:51` 实际为:`<notificationGroup id="OpenCodeWeb.notifications" displayType="TOOL_WINDOW" toolWindowId="OpenCodeWeb"/>`(**displayType 偏离原 plan**) - commit `60ebd1f` 把 displayType 从 `BALLOON` 改为 `TOOL_WINDOW`

### Wave 4: 集成与验证

- [ ] 4.1 集成测试
      **What to do**: Router ↔ Service ↔ Consumer 全链路对接;验证去重、subagent 追踪、session.idle 兼容
      **实际状态**:**未实施**。- 现有单测只覆盖纯函数(`resolveType` / `extractSessionID` / `SessionInfo` 构造 / `AllowParseEventTypes`) - 无 end-to-end 集成测试

- [ ] 4.2 构建验证
      **What to do**: `./gradlew check`
      **实际状态**:**待运行**。未在本 change 提交时跑过。

## 已实施 vs 未实施 汇总

| 类别              | 已实施                                                                       | 未实施                                                                                                     |
| ----------------- | ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| **事件分发**      | permission / complete / question + handleSessionIdle 三层去重                | error / user_cancelled / subagent_complete / plan_exit / session_started / user_message / client_connected |
| **SSE 解析**      | Direct BusEvent 12 白名单 + LRU 1000 去重                                    | SyncEvent V2 (`syncEventType` / `syncEventData`)                                                           |
| **通知核心**      | OpenCodeNotificationService 双模式 + minDuration + 占位符 + SessionInfoCache | setImportant / setSuggestionType                                                                           |
| **配置层**        | OpenCodeConfig 4 个通用 setting                                              | OpenCodeConfigurable Settings UI / 11 事件开关 / 11 消息模板                                               |
| **多项目路由**    | 简化委托(Router 10 行)                                                       | projectRegistry / ProjectManagerListener 双保险                                                            |
| **subagent 检测** | title 正则 `SUBAGENT_TITLE_REGEX`                                            | subagentSessionIds Set(基于 parentID / session.deleted)                                                    |
| **idle 去重**     | 1s LRU dedup + idleNotifiedSessions Set + message.updated(user) 重置         | 350ms debounce + 2s idleLastFired window                                                                   |
| **plugin.xml**    | `<notificationGroup>` 注册(但 displayType 偏离)                              | `<applicationConfigurable>` 注册                                                                           |
| **集成测试**      | 单元测试 4 文件 / 29 用例                                                    | end-to-end 集成测试                                                                                        |

## 后续未实施项的处理建议(非本 change 范围)

1. **Settings UI 实施** → 独立 change,在已落地的 `OpenCodeConfig` 上构建 `OpenCodeConfigurable`
2. **11 事件开关 / 消息模板** → 与 Settings UI 一同实施
3. **SyncEvent V2 解析** → 单独 change,先实测 `session.next.tool.called` 是否在 SSE 流
4. **`plan_exit` 通知** → 跟随 SyncEvent V2 change
5. **多项目路由** → 当前简化方案已满足实际场景,无迫切需求
6. **`TOOL_WINDOW` → `STICKY_BALLOON` 切换** → 独立 change,解决"通知停留短"UX 问题
