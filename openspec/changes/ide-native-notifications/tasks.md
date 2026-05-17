## Tasks

### Wave 0: 前置验证

- [ ] 0.1 验证 SSE 事件和 HTTP API
      **What to do**: - 用 curl 连接 SSE 端点，触发 question 场景验证 `session.next.tool.called` 是否存在 - 验证 `GET /session/:sessionID` API 是否存在及返回格式（session title, parentID, time.created）- 验证 `GET /session?directory=xxx` 的响应格式 - 如果 `session.next.tool.called` 存在，记录其字段路径（properties.tool 还是 data.tool）- 如果不存在，确认 `question.asked` 作为 fallback

      **Must NOT do**:
      - 不要在此步骤修改任何代码

      **Acceptance Criteria**:
      `session.next.tool.called` 的 SSE 格式确认；`GET /session/:id` 可用

- [ ] 0.2 验证 OpenCodeApi 是否需要扩展
      **What to do**: - 检查 `OpenCodeApi.kt` 是否有 `GET /session/:id` 的调用方法 - 如果没有，在 `OpenCodeApi` 中新增 `getSession(sessionID: String): SessionInfo?` - 定义 `SessionInfo` 数据类（title, parentID, time）

      **Must NOT do**:
      - 不要修改现有的 `getLatestSessionId` 方法

      **Acceptance Criteria**:
      `OpenCodeApi.getSession("ses_xxx")` 返回 session 标题

### Wave 1: SSE 事件监听扩展

- [ ] 1.1 SSEEventParser 增加 SyncEvent 解析
      **What to do**: - 在 `SSEEventParser.kt` 中增加对 `payload.type == "sync"` 的解析 - 提取 `syncEvent.type`（去掉 ".1" 版本号后缀）和 `syncEvent.data` - 在 `ParsedSSEEvent` 中新增 `syncEventType` 和 `syncData` 字段（可为 null）- 实现 eventID 去重缓存（LRU 集合，最大 1000 条，以 `payload.id` 为 key）- 确保与现有 Direct BusEvent 解析兼容

      **Must NOT do**:
      - 不要破坏现有文件事件的解析逻辑
      - 不要引入新的 Gson 序列化类

      **Acceptance Criteria**:
      SyncEvent 能正确解析出 eventType（无 ".1" 后缀）和 data

- [ ] 1.2 OpenCodeSSEConsumer 扩展：通知事件分发
      **What to do**: - 在 `onMessage` 中增加通知事件判断分支 - 实现 SSE 事件到 11 种通知类型的映射（查 design.md 映射表）- 处理的事件：- Direct: `server.connected` → client_connected - Direct: `session.status(type=idle)` → complete/subagent_complete（debounce 350ms）- Direct: `session.idle`（兼容旧格式）- Direct: `session.error` → error/user_cancelled（检查 error.name）- Direct: `permission.asked` → permission - Direct/Sync: `session.next.tool.called(tool=question)` → question（fallback: question.asked）- Direct/Sync: `session.next.tool.called(tool=plan_exit)` → plan_exit - Direct: `message.updated(role=user)` → user_message - Sync: `session.created(无 parentID)` → session_started - subagent 追踪：session.created(有 parentID) 加入集合，session.deleted 移除 - idel 事件 debounce 语义：收到 idle → 350ms 定时器 → 同 sessionID 新 idle 重置定时器 → 到期触发 - 未知事件静默忽略

      **Must NOT do**:
      - 不要修改已有的文件刷新逻辑
      - 不要使用 HTTP API 查询 parentID

      **Acceptance Criteria**:
      server.connected → client_connected；subagent idle → subagent_complete

### Wave 2: 通知发送核心服务

- [ ] 2.1 创建 OpenCodeNotificationService
      **What to do**: - 在 `listeners/` 下创建 `OpenCodeNotificationService.kt` - 构造函数接收 `Project`，提供 `notify(eventType, properties)` 方法 - 使用 `NotificationGroupManager` 创建通知，选择 `NotificationType` - 差异化点击行为：- permission/question/error → 激活 OpenCodeWeb 工具窗口 - complete/subagent_complete → 仅聚焦 IDE - 其他 → 无操作 - minDuration 过滤：complet/subagent_complete 时计算会话耗时，小于配置值则丢弃 - 自动读取 `OpenCodeConfig` 判断是否允许通知 - 通过 `OpenCodeApi.getSession()` 查询 session 标题（失败重试 1 次，404 时空字符串）- 支持 `{sessionTitle}/{projectName}/{timestamp}/{agentName}` 占位符替换 - 未识别占位符保留原样 - 增加 `if (project.isDisposed) return` 守卫

      **Must NOT do**:
      - 不要在通知线程执行网络请求
      - 不要静态全局状态

      **Acceptance Criteria**:
      notify("complete", ...) 弹出 BALLOON 通知；project 卸载时不抛异常

- [ ] 2.2 创建 OpenCodeConfig（PropertiesComponent）
      **What to do**: - 在 `utils/` 下创建 `OpenCodeConfig.kt`（object）- 使用 `PropertiesComponent.getInstance()`（IDE 级别）- Key 命名：`opencode.settings.{name}` / `opencode.event.{type}.enabled` / `opencode.message.{type}` - 通用设置：notificationEnabled、showProjectName、showSessionTitle、minDuration - 11 种事件类型：permission/complete/subagent_complete/error/question/interrupted/user_cancelled/plan_exit/session_started/user_message/client_connected - 默认值：permission/complete/error/client_connected=true，其余=false - 默认消息模板含所有 11 种类型 - 提供 `isEventEnabled(eventType)`、`getMessageTemplate(eventType)` 等方法

      **Must NOT do**:
      - 不要读取任何外部配置文件
      - 不要使用 Gson 或正则

      **Acceptance Criteria**:
      配置写入后立即可读，无需重启

### Wave 3: Settings UI 配置页面

- [ ] 3.1 创建 OpenCodeConfigurable
      **What to do**: - 在 `settings/` 包下创建 `OpenCodeConfigurable.kt`，实现 `Configurable` - 通知 Section：- 全局通知开关（Checkbox）- 11 个事件独立开关（interrupted 灰色+标注"当前环境不支持"）- minDuration 数字输入框（秒，默认 0）- "显示项目名称" 复选框 - "显示 Session 标题" 复选框 - 预留额外 Section 空间（placeholder comment）- 实现 isModified()/apply()/reset()，apply() 写入 PropertiesComponent - 配置变更后通知 OpenCodeNotificationService 刷新 - 使用 IntelliJ 原生 UI 组件

      **Must NOT do**:
      - 不要引入外部 UI 框架
      - 不实现消息模板自定义编辑（本次不涉及）

      **Acceptance Criteria**:
      Settings → Tools → OpenCode 可见，所有开关正常切换

- [ ] 3.2 更新 plugin.xml
      **What to do**: - 在 `<extensions>` 中添加 `applicationConfigurable`
      `xml
      <applicationConfigurable parentId="tools"
          instance="com.github.xausky.opencodewebui.config.OpenCodeConfigurable"
          id="com.shenyuanlaolarou.opencodewebui.config"
          displayName="OpenCode"/>
      `

      **Must NOT do**:
      - 不要移除现有声明

      **Acceptance Criteria**:
      插件启动时不报 plugin.xml 错误

### Wave 4: 集成与验证

- [ ] 4.1 通知服务与 SSE Consumer 集成
      **What to do**: - SSE Consumer 收到事件后调用 `OpenCodeNotificationService.notify()` - 生命周期同步：Consumer start → Service 创建，Consumer stop → Service 释放 - 验证 eventID 去重 - 验证 subagent 追踪 - 验证 session.idle + session.status 双格式 - 验证 project.isDisposed 守卫

      **Acceptance Criteria**:
      完整链路：SSE 事件 → 配置检查 → 通知弹出 → 点击跳转

- [ ] 4.2 构建验证
      **What to do**: - `./gradlew check` - `./gradlew :runIde` 手动验证

      **Acceptance Criteria**:
      BUILD SUCCESSFUL
