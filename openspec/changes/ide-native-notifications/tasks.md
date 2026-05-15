## Tasks

### Wave 1: SSE 事件监听扩展

- [ ] 1.1 SSEEventParser 增加 SyncEvent 解析支持
      **What to do**: - 在 SSEEventParser.kt 中增加对 `payload.type == "sync"` 格式的解析 - 提取 `syncEvent.type` 作为实际事件类型（去掉 ".1" 版本后缀）- 提取 `syncEvent.data` 作为有效负载 - 确保与现有 Direct BusEvent 解析兼容

      **Must NOT do**:
      - 不要破坏现有文件事件的解析逻辑
      - 不要引入新的 Gson 序列化类，使用现有 Map 解析方式

      **Recommended Agent Profile**: unspecified-high

      **References**:
      - Pattern: [SSEEventParser.kt](src/main/kotlin/com/github/xausky/opencodewebui/listeners/SSEEventParser.kt)
      - API: SSE 事件格式 `{ directory, project, payload: { type: "sync", syncEvent: { type, data } } }`

      **Acceptance Criteria**:
      SyncEvent 事件能正确解析出 `eventType`（无 ".1" 后缀）和 `properties`

      **QA Scenarios**:
      Scenario: SyncEvent 解析
        Steps: SSE 收到 `session.created.1` 事件
        Expected: payloadType = "session.created"，properties = session info 对象
        Evidence: .sisyphus/evidence/task-1.1-sync-event-parsing.md

      > See OpenSpec context: [spec.md#added-requirements](openspec/changes/ide-native-notifications/specs/notification-events/spec.md)

- [ ] 1.2 OpenCodeSSEConsumer 增加通知事件分发
      **What to do**: - 在 `onMessage` 方法中，在现有 `if (!isSessionDiff && ...) return` 之前增加通知事件判断分支 - 实现事件映射表：SSE payload.type → 内部通知事件类型 - 识别以下事件类型并分发给 OpenCodeNotificationService: - `server.connected` → client_connected - `session.status` (type=idle) → complete/subagent_complete (需延迟 350ms 去抖) - `session.error` → error/user_cancelled (需检查 error.name) - `permission.asked` → permission - `session.next.tool.called` (tool=question/plan_exit) → question/plan_exit - `message.updated` (role=user) → user_message - `session.created` (通过 sync 解析, 无 parentID) → session_started - 保持现有 `isFileEdited`/`isFileWatcherUpdated` 等文件事件逻辑不变

      **Must NOT do**:
      - 不要移除或修改已有的文件刷新逻辑
      - 不要对非通知事件类型产生额外开销

      **Recommended Agent Profile**: unspecified-high

      **References**:
      - Pattern: [OpenCodeSSEConsumer.kt](src/main/kotlin/com/github/xausky/opencodewebui/listeners/OpenCodeSSEConsumer.kt:60-103)
      - Design: `design.md` 中的 SSE 事件映射表

      **Acceptance Criteria**:
      收到 `server.connected` 事件后回调 OpenCodeNotificationService

      **QA Scenarios**:
      Scenario: server.connected 事件分发
        Steps: SSE 收到 `server.connected`
        Expected: OpenCodeNotificationService.notify("client_connected", ...) 被调用
        Evidence: .sisyphus/evidence/task-1.2-server-connected-dispatch.md

      > See OpenSpec context: [spec.md#added-requirements](openspec/changes/ide-native-notifications/specs/notification-events/spec.md)

### Wave 2: 通知发送核心服务

- [ ] 2.1 创建 OpenCodeNotificationService
      **What to do**: - 在 `listeners/` 或 `utils/` 下创建 `OpenCodeNotificationService.kt` - 构造函数接收 `Project` 参数 - 提供 `notify(eventType: String, properties: Map<*, *>?)` 方法 - 内部维护 sessionID → 去抖计时器的映射（用于 session.status idle 的 350ms 延迟）- 使用 `NotificationGroupManager.getInstance().getNotificationGroup("OpenCodeWeb.notifications")` 创建通知 - 根据事件类型选择 `NotificationType`（ERROR/WARNING/INFORMATION）- 设置通知点击行为：激活 OpenCodeWeb 工具窗口 - 自动读取 `OpenCodeNotifierConfig` 判断是否允许通知 - 支持通过 HTTP API 查询 session 标题（`GET /session/:id`）

      **Must NOT do**:
      - 不要使用 `@ts-ignore` 或 `as any` 等效的 Kotlin 类型逃逸
      - 不要静态全局状态（使用类实例而非 object）
      - 不要在通知线程执行网络请求——使用协程

      **Recommended Agent Profile**: unspecified-high

      **References**:
      - API: NotificationGroupManager, Notification, NotificationType
      - Pattern: [HealthMonitor.kt](src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/HealthMonitor.kt)
      - Config: [OpenCodeApi.kt](src/main/kotlin/com/github/xausky/opencodewebui/utils/OpenCodeApi.kt)

      **Acceptance Criteria**:
      调用 `notify("complete", mapOf("sessionTitle" to "test"))` 时弹出 IDE 原生通知

      **QA Scenarios**:
      Scenario: 基本通知发送
        Steps: 调用 notify("complete", properties)
        Expected: 弹出 BALLOON 类型通知，内容为配置中的默认消息
        Evidence: .sisyphus/evidence/task-2.1-notification-basic.md

      Scenario: 通知点击跳转
        Steps: 点击通知
        Expected: OpenCodeWeb 工具窗口被激活
        Evidence: .sisyphus/evidence/task-2.1-notification-click.md

      > See OpenSpec context: [spec.md#added-requirements](openspec/changes/ide-native-notifications/specs/notification-service/spec.md)

- [ ] 2.2 创建 OpenCodeNotifierConfig 配置管理
      **What to do**: - 在 `utils/` 下创建 `OpenCodeNotifierConfig.kt` - 定义 `NotifierConfig` 数据类（notification 全局开关、events 各事件开关、messages 模板）- 定义 `NotificationEventConfig` 数据类（notification 布尔值）- 定义默认事件配置（permission/complete/error/client_connected=true，其余= false）- 定义默认消息模板 - 实现从 `~/.config/opencode/opencode-ide.json` 加载配置 - 实现文件不存在时使用默认配置 - 支持 `{sessionTitle}` 占位符替换 - 提供 `isNotificationEnabled(eventType: String): Boolean` 查询方法

      **Must NOT do**:
      - 不要使用正则解析 JSON 配置——必须用 Gson
      - 不要阻塞启动线程，配置文件加载在后台进行

      **Recommended Agent Profile**: unspecified-high

      **References**:
      - Pattern: [OpenCodeConstants.kt](src/main/kotlin/com/github/xausky/opencodewebui/OpenCodeConstants.kt)
      - External: opencode-notifier config 结构 (`opencode-notifier.json`)

      **Acceptance Criteria**:
      解析示例 JSON 配置文件后事件开关状态正确

      **QA Scenarios**:
      Scenario: 默认配置
        Steps: 不存在配置文件
        Expected: permission/complete/error/client_connected=true，其余=false
        Evidence: .sisyphus/evidence/task-2.2-default-config.md

      Scenario: JSON 配置加载
        Steps: ~/.config/opencode/opencode-ide.json 存在
        Expected: 正确读取配置覆盖默认值
        Evidence: .sisyphus/evidence/task-2.2-config-loading.md

      > See OpenSpec context: [spec.md#added-requirements](openspec/changes/ide-native-notifications/specs/notification-settings/spec.md)

### Wave 3: Settings UI 配置页面

- [ ] 3.1 创建 Settings UI 配置页面
      **What to do**: - 在现有工程结构下创建 settings 包（如 `settings/`）- 创建 `OpenCodeSettingsPage.kt` 实现 `Configurable` - 在 plugin.xml 中注册 `applicationConfigurable`（extensions 中增加条目）- 页面放置在 Settings → Tools → OpenCode 下 - 通知配置作为第一个 Section：- 全局通知开关（Checkbox）- 每事件类型的独立开关（Checkbox）共 10 个 - 所有开关都有对应的中文标签 - "显示项目名称" 开关 - "显示 Session 标题" 开关 - 预留额外 Section 空间（placeholder comments）- 实现 `isModified()`、`apply()`、`reset()` 方法 - 配置变更时通知 OpenCodeNotificationService 刷新配置

      **Must NOT do**:
      - 不要引入外部 UI 框架，使用 IntelliJ 原生 UI 组件
      - 不要硬编码字符串——通过 `@Nls` 或 MyBundle resource bundle
      - 不要在 apply() 中做耗时操作

      **Recommended Agent Profile**: visual-engineering

      **References**:
      - API: IntelliJ Configurable interface
      - API: JBCefSettingsPanel / JBUI / FormBuilder
      - Config: IntelliJ Platform SDK 的 settings 指南

      **Acceptance Criteria**:
      Settings → Tools 下出现 "OpenCode" 配置项，所有事件开关可正常切换

      **QA Scenarios**:
      Scenario: 页面可见
        Steps: 打开 Settings → Tools
        Expected: "OpenCode" 配置项可见
        Evidence: .sisyphus/evidence/task-3.1-settings-visible.png

      Scenario: 配置保存
        Steps: 关闭某项通知 → Apply → 重新打开
        Expected: 关闭状态保持
        Evidence: .sisyphus/evidence/task-3.1-settings-persist.md

      > See OpenSpec context: [spec.md#added-requirements](openspec/changes/ide-native-notifications/specs/notification-settings/spec.md)

- [ ] 3.2 更新 plugin.xml 声明
      **What to do**: - 在 plugin.xml 的 `<extensions>` 中添加: - `<applicationConfigurable instance="..." />` 引用 OpenCodeSettingsPage - 如果需要非 BALLOON 通知组，调整 notificationGroup 配置

      **Must NOT do**:
      - 不要移除现有的 `notificationGroup` 声明
      - 不要改变其他扩展点的注册

      **Recommended Agent Profile**: quick

      **References**:
      - Plugin: [plugin.xml](src/main/resources/META-INF/plugin.xml)
      - Config: IntelliJ Platform plugin.xml reference

      **Acceptance Criteria**:
      IDE 插件加载时不报 plugin.xml 验证错误

      **QA Scenarios**:
      Scenario: 插件启动
        Steps: 使用 runIde 启动 IDE
        Expected: 无 plugin.xml 相关错误
        Evidence: .sisyphus/evidence/task-3.2-plugin-xml-valid.md

      > See OpenSpec context: [spec.md#added-requirements](openspec/changes/ide-native-notifications/specs/notification-settings/spec.md)

### Wave 4: 集成与测试

- [ ] 4.1 通知服务与 SSE Consumer 集成
      **What to do**: - 在 `OpenCodeSSEConsumer` 中集成 `OpenCodeNotificationService` - SSE Consumer 收到通知事件后调用 `openCodeNotificationService.notify(parsedEvent)` - 确保 SSE Consumer 的 start/stop 生命周期同步管理通知服务的创建/销毁 - 验证事件去重逻辑正常工作（BusEvent 优先，SyncEvent 跳过）

      **Must NOT do**:
      - 不要重复初始化通知服务

      **Recommended Agent Profile**: unspecified-high

      **References**:
      - Integration: [MyToolWindowFactory.kt](src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt)
      - Pattern: OpenCodeSSEConsumer 使用 project 持有服务

      **QA Scenarios**:
      Scenario: 完整事件链路
        Steps: 启动 IDE → 插件初始化 → SSE 连接建立 → server.connected 事件到达
        Expected: 弹出 "OpenCode 已连接" 通知
        Evidence: .sisyphus/evidence/task-4.1-full-integration.md

      > See OpenSpec context: [spec.md#added-requirements](openspec/changes/ide-native-notifications/specs/notification-events/spec.md)

- [ ] 4.2 运行测试和诊断
      **What to do**: - 运行 `./gradlew check` 确认项目构建通过 - 运行 `./gradlew :runIde` 手动验证通知功能 - 验证 SSE 连接正常且所有事件类型可被识别 - 用 curl 测试 SSE 事件端点，确认解析正确

      **Must NOT do**:
      - 不要提交未通过 build 的代码

      **Recommended Agent Profile**: unspecified-low

      **Acceptance Criteria**:
      `./gradlew check` 通过

      **QA Scenarios**:
      Scenario: 构建验证
        Steps: ./gradlew check
        Expected: BUILD SUCCESSFUL
        Evidence: .sisyphus/evidence/task-4.2-build-success.md

      > See OpenSpec context: [spec.md](openspec/changes/ide-native-notifications/specs/)
