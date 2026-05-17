## Tasks

### Wave 0: 前置验证

- [ ] 0.1 SSE 事件 + HTTP API 实测
      **What to do**:
  - curl 连接 SSE 端点，触发 question/permission 场景验证 `session.next.tool.called` 是否在 SSE 流中
  - 验证 `GET /session/:sessionID` API 是否存在及返回格式（title、parentID、time.created）
  - 记录 `session.next.tool.called` 的字段路径（Direct: properties.tool, Sync: data.tool）
  - 如果不可用：确认 `question.asked` BusEvent 作为 question fallback

  **Acceptance Criteria**: session.next.tool.called 格式确认 + GET /session/:id 可用

- [ ] 0.2 扩展 OpenCodeApi
      **What to do**: 新增 `getSession(sessionID: String): SessionInfo?`，返回 title/parentID/time

  **Acceptance Criteria**: `OpenCodeApi.getSession("ses_xxx")` 返回 session 标题

### Wave 1: SSE 事件监听扩展

- [ ] 1.1 SSEEventParser 增加 SyncEvent 解析 + 去重
      **What to do**:
  - `ParsedSSEEvent` 新增 `syncEventType`、`syncEventData` 字段
  - `parse()` 中检测 `payload.type == "sync"` → 提取 `syncEvent.type`（去掉 `.N` 后缀）和 `syncEvent.data`
  - 下游消费者判断：`payloadType == "sync"` 时用 `syncEventType` 路由
  - 实现 eventID 去重：`LinkedHashMap(accessOrder=true) + removeEldestEntry` + `synchronizedMap`，最大 1000 条
  - 无 `payload.id` 的事件用 `eventType + sessionID` 复合键去重
  - 确保现有 Direct BusEvent 解析不受影响

  **Must NOT do**: 不破坏文件事件解析

- [ ] 1.2 OpenCodeSSEConsumer 通知事件分发
      **What to do**:
  - 在 `onMessage()` 第 80 行 early return **之前**插入通知事件分发
  - 实现 SSE 事件到 11 种通知类型的映射
  - subagent 本地追踪：`session.created(有parentID)` 加入 Set，`session.deleted` 移除
  - `session.status(idle)` 或 `session.idle` → 350ms debounce，查 subagent 集合区分 complete/subagent_complete
  - `session.error` → error/user_cancelled（检查 error.name）
  - `session.next.tool.called(tool=question/plan_exit)` → question/plan_exit（含 SyncEvent 兼容）
  - `question.asked` → question（fallback 路径）
  - `message.updated(role=user)` → user_message
  - `session.created(无parentID,Sync)` → session_started

  **Must NOT do**: 不修改已有文件刷新逻辑

### Wave 2: 通知核心服务

- [ ] 2.1 创建 OpenCodeNotificationRouter
      **What to do**:
  - `object` 单例，维护 `ConcurrentHashMap<String, Project>`
  - `register(project)`: 通过 `ProjectManagerListener`（项目加载时）和 `createToolWindowContent`（工具窗口创建时）双保险注册
  - `unregister(project)`: 项目关闭时注销
  - `notify(eventType, properties, eventDir)`: 用 `File.canonicalPath` 规范化路径后查找
  - null directory 时静默返回
  - 最外层 try/catch 兜底 `ProcessCanceledException`

- [ ] 2.2 创建 OpenCodeNotificationService
      **What to do**:
  - 双模式通知：先 `Notification(...).notify(project)`（BALLOON），后 `SystemNotifications.getInstance().notify()`（后台）
  - `WindowManager.getFrame(project).isActive` 判断项目窗口焦点（多显示器场景）
  - 工具窗口聚焦抑制：`ToolWindowManager.getToolWindow("OpenCodeWeb")?.isActive == true` 时跳过
  - 差异化点击：permission/question/error 激活工具窗口，其余仅聚焦 IDE
  - `minDuration` 过滤：complete/subagent_complete 时计算会话耗时
  - `project.isDisposed` 守卫 + try/catch 兜底
  - HTTP API `GET /session/:id` 查询 session 标题（超时复用 `HTTP_TIMEOUT_MS=8000`，失败重试 1 次）
  - 支持 `{sessionTitle}/{projectName}/{timestamp}/{agentName}` 占位符替换，未识别保留原样

- [ ] 2.3 创建 OpenCodeConfig
      **What to do**:
  - 基于 `PropertiesComponent.getInstance()` (IDE 级别)
  - Key 命名：`opencode.settings.{name}` / `opencode.event.{type}.enabled` / `opencode.message.{type}`
  - 11 种事件开关读写（含 interrupted，默认 false）
  - 通用设置：notificationEnabled、showProjectName、showSessionTitle、minDuration
  - 默认消息模板（与 opencode-notifier 配置一致）
  - `{agentName}` 从 session title 的 `(@name subagent)` 格式提取，不存在时填空字符串

### Wave 3: Settings UI

- [ ] 3.1 创建 OpenCodeConfigurable
      **What to do**:
  - `settings/` 包下实现 `Configurable`，注册在 Settings → Tools → OpenCode
  - 通知 Section：全局开关、11 个事件独立开关（interrupted 灰色）、消息模板文本框
  - 通用设置：minDuration、showProjectName、showSessionTitle
  - `isModified()/apply()/reset()`，apply 写入 PropertiesComponent
  - 校验：minDuration 不能为负数

- [ ] 3.2 更新 plugin.xml
      **What to do**: 添加 `<applicationConfigurable parentId="tools" .../>`

### Wave 4: 集成与验证

- [ ] 4.1 集成测试
      **What to do**: Router ↔ Service ↔ Consumer 全链路对接；验证去重、subagent 追踪、session.idle 兼容

- [ ] 4.2 构建验证
      **What to do**: `./gradlew check`
