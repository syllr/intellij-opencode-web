## Tasks

> 本文件由 `.omo/plans/<change-name>.md` 镜像生成。
> 修改 plan 后重新运行同步 tool 即可更新。
> **不要手动编辑**——下次同步会被覆盖。

---

### Wave 1: 依赖升级 + 架构重构（并行 2 task）

- [x] 1.1 升级 okhttp-eventsource 4.1.0 → 4.3.0

      **What to do**: 1. 改 `gradle/libs.versions.toml` 第 6 行 `okhttpEventsource = "4.1.0"` → `"4.3.0"`
      2. 跑 `./gradlew build` 验证编译通过

      **Must NOT do**: - 不改任何源码（4.1.0 → 4.3.0 零 breaking change，详见 `research/okhttp-eventsource-compat/notes.md`）
      - 不删 OkHttp 依赖

      **Recommended Agent Profile**: `category="quick"`

      **References**: - `gradle/libs.versions.toml` (L6)
      - research: `research/okhttp-eventsource-compat/notes.md`

      **Acceptance Criteria**: ```bash
      grep -q 'okhttpEventsource = "4.3.0"' gradle/libs.versions.toml && ./gradlew build
      ```

      **QA Scenarios**: - **Happy**: `./gradlew build` 通过
      - **Exception**: 编译失败 → revert

      **Parallelization**: 可与 Task 2 并发

      **Evidence**: `.omo/evidence/task-1-build.txt`

      **Commit**: YES

- [x] 1.2 OpenCodeServerManager 单例 → ConcurrentHashMap

      **What to do**: 1. 改 `OpenCodeServerManager.kt`：
      - `object` 单例保留
      - `sseConsumer` 字段 → `consumers: ConcurrentHashMap<Project, OpenCodeSSEConsumer>`
      - `ensureSSEConsumer(project)` → `getOrCreateConsumer(project)`（用 `computeIfAbsent` 保证线程安全）
      - `gracefulShutdown()` 遍历 `consumers.values().forEach { it.stop() }`
      2. 删除 line 38-43 "consumer 切换时 stop 旧" 逻辑

      **Must NOT do**: - 不改 OpenCodeSSEConsumer 内部
      - 不动 watchdog 线程

      **Recommended Agent Profile**: `category="quick"`

      **References**: - `toolWindow/OpenCodeServerManager.kt` (L20-50)
      - spec: `sse-per-project-endpoint/spec.md` "Per-Project SSE Connection"

      **Acceptance Criteria**: ```bash
      grep -q "ConcurrentHashMap<Project, OpenCodeSSEConsumer>" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/toolWindow/OpenCodeServerManager.kt && ./gradlew compileKotlin
      ```

      **QA Scenarios**: - **Happy**: 2 个 project 同时调用 `getOrCreateConsumer` → 返回不同实例
      - **Exception**: 同一 project 并发调用 → `computeIfAbsent` 保证只创建 1 个

      **Parallelization**: 可与 Task 1 并发

      **Evidence**: `.omo/evidence/task-2-map.txt`

      **Commit**: YES

- [x] 1.3 OpenCodeSSEConsumer URL 改 + streamEventData

      **What to do**: 1. 改 `startSseConnection()`:
      - URL 改 `/global/event` → `/event?directory=<URLEncoder.encode(projectBasePath, "UTF-8")>`
      - `.errorStrategy(...)` 后追加 `.streamEventData(true)`
      2. 删 `onMessage()` 中 eventDir 比较逻辑（line 236-253）

      **Must NOT do**: - 不改 `onMessage()` 函数体（L129-150）—— 由 Task 4 改
      - 不动 `when (eventType)` case（L181-225）—— 由 Task 5 改
      - 不动 `handleSessionIdle` + state machine（L268-300）—— 由 Task 5 改
      - 不动 `companion object`（L54-78）—— 由 Task 5 改

      **Recommended Agent Profile**: `category="deep"`

      **References**: - `listeners/OpenCodeSSEConsumer.kt` (L82-95, L236-253)
      - design: `design.md` Decision 1
      - spec: `sse-per-project-endpoint/spec.md` "SSE Endpoint Switch"

      **Acceptance Criteria**: ```bash
      grep -q "streamEventData(true)" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt && grep -q "/event?directory=" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt && ! grep -q "/global/event" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt
      ```

      **QA Scenarios**: - **Happy**: `./gradlew runIde` 打开工具窗口，URL 为 `/event?directory=...`
      - **Exception**: 旧版本 opencode server 不支持 `/event?directory=` → 通知丢失（**当前未实现** fallback，spec 阶段决定是否需要回退 `/global/event`）

      **Parallelization**: 必须在 Task 4 之前（Task 4 改 onMessage 函数体）

      **Evidence**: `.omo/evidence/task-3-url.txt`

      **Commit**: YES

- [x] 1.4 OpenCodeSSEConsumer 改用 getDataReader + 适配新 wire 格式（onMessage）

      **What to do**: 1. 改 `onMessage()` (L129-150):
      - `messageEvent.data` → `messageEvent.getDataReader().use { reader -> ... }`
      - `parsedMap["payload"]` 嵌套访问 → `parsedMap["properties"]`
      2. 改 `eventType = parsed.syncEventType ?: payloadType ?: return` → `eventType = payloadType ?: return`（syncEvent 已删）

      **Must NOT do**: - 不改 URL / streamEventData（Task 3 改）
      - 不改 `when (eventType)` 内容（Task 5 改）
      - 不动其他 case

      **Recommended Agent Profile**: `category="deep"`

      **References**: - `listeners/OpenCodeSSEConsumer.kt` (L129-150)
      - research: `research/okhttp-eventsource-compat/notes.md`（streaming mode 安全）
      - design: `design.md` Decision 3

      **Acceptance Criteria**: ```bash
      grep -q "getDataReader" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt && ! grep -q "messageEvent.data" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt
      ```

      **QA Scenarios**: - **Happy**: 高频 `message.part.delta` 事件下不构造 String，CPU/内存降低
      - **Exception**: streaming 模式下未消费 Reader → 流线程读下个事件时关闭 → 通知丢失（**必须**在 onMessage 内 `use { }` 消费）

      **Parallelization**: 必须在 Task 3 之后，Task 5 之前

      **Evidence**: `.omo/evidence/task-4-reader.txt`

      **Commit**: YES

- [x] 1.5 OpenCodeSSEConsumer 砍 4 case + 删 state machine + 简化 handleSessionIdle

      **What to do**: 1. `when (eventType)` 砍 4 case：`server.connected` / `session.error` / `session.next.tool.called` / `message.updated`
      2. 删 `session.deleted` 处理（L169-178）
      3. `session.created` 简化：只追踪 subagent（`subagentSessionIds.add`），**不** dispatch `session_started` 通知
      4. 保留 `session.status` case + `status.type == "idle"` 检查（**非 idle 静默**：`session.status(type=busy/retry)` 解析但**不**触发通知）
      5. 删 `sessionIdleFired` 集合 + `onClosed` 清空（L57, L311-318）
      6. 删 `idleLastFired` 2s 窗口（L271-275, L291-296 in `handleSessionIdle`）
      7. 简化 `handleSessionIdle`（仅 subagent 检查 + `dispatchNotification`）
      8. `companion object` 中 `idleLastFired` 字段删除

      **Must NOT do**: - 不改 `onMessage()` 函数体（Task 4 改）
      - 不改 URL / streamEventData（Task 3 改）
      - 不改 `dispatchNotification` 逻辑
      - 不删 `subagentSessionIds` 追踪

      **Recommended Agent Profile**: `category="deep"`

      **References**: - `listeners/OpenCodeSSEConsumer.kt` (L54-78, L157-178, L181-225, L268-300, L302-318)
      - design: `design.md` Decision 7
      - spec: `sse-notification-dedup/spec.md` "Replace 2s idleLastFired"
      - spec: `sse-stream-filter/spec.md` "session.status with non-idle status is parsed but no notification dispatched"

      **Acceptance Criteria**: ```bash
      ! grep -q "sessionIdleFired" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt && ! grep -q "idleLastFired" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt && ! grep -q '"server.connected"' src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt && ! grep -q '"session.error"' src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt && grep -q 'session.status' src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt
      ```

      **QA Scenarios**: - **Happy**: 父 session.idle 触发 complete 通知；subagent session.idle 不通知；session.status(idle) 触发 complete 通知
      - **Exception**: 多次 session.idle 同 session（间隔 > 1s）→ 都通知（无 `sessionIdleFired` 抑制）；session.status(busy/retry) 到达时**不**通知

      **Parallelization**: 必须在 Task 4 之后

      **Evidence**: `.omo/evidence/task-5-cases.txt`

      **Commit**: YES

- [x] 1.6 SSEEventParser 适配新 wire 格式 + 9 个白名单

      **What to do**: 1. 删外层 `directory` 字段提取（L99）
      2. 删外层 `payload` 嵌套访问（L101, L116, L123, L130）
      3. 删 syncEvent 解析（L122-128）—— SyncEvent 包装格式**不**在 target opencode server 版本（>= 1.0.18）推送
      4. 新增 `ALLOW_PARSE_EVENT_TYPES` 集合（9 个：`session.idle` / `session.status` / `permission.asked` / `question.asked` / `session.created` / `message.part.updated` / `file.edited` / `file.watcher.updated` / `session.diff`）
      5. Reader 阶段 type peek → 查白名单 → 不在白名单 → `close()` 早退
      6. 删 `SKIP_PARSE_EVENT_TYPES`
      7. `extractSessionID()` 改为 **3 级 fallback**（`properties.sessionID` / `properties.info.sessionID` / `properties.info.id`）
      8. `extractParentID()` 改 `properties.parentID` / `properties.info.parentID`
      9. 删 `SYNC_EVENT_TYPE_VERSION_REGEX`（SyncEvent 已删，无需剥离版本后缀 `.N`）

      **Must NOT do**: - 不改 `ParsedSSEEvent` data class 字段名
      - 不改 `clearCache()` / `isEventProcessed()`
      - 不保留 syncEvent 解析（旧版本 server 兼容性回退，target 版本是 1.0.18+，plan 接受此简化）

      **Recommended Agent Profile**: `category="deep"`

      **References**: - `listeners/SSEEventParser.kt` (L1-154)
      - research: `research/event-endpoint-schema/notes.md`（wire 格式）+ `research/sse-event-types/notes.md`（9 type 确认）
      - design: `design.md` Decision 1, 3, 4
      - spec: `sse-stream-filter/spec.md` "Wire Format Without Outer Wrapper"

      **Acceptance Criteria**: ```bash
      grep -q "ALLOW_PARSE_EVENT_TYPES" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/SSEEventParser.kt && grep -q '"session.idle"' src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/SSEEventParser.kt && grep -q '"question.asked"' src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/SSEEventParser.kt && ! grep -q "syncEvent" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/SSEEventParser.kt
      ```

      **QA Scenarios**: - **Happy**: 9 个白名单 type 完整解析 + 进入 when 分支；25+ 噪音 type `close()` 早退
      - **Exception**: `message.part.delta` 100Hz 到达 → `close()` 早退，无 Gson 调用

      **Parallelization**: 可与 Task 7, 8, 10 并发

      **Evidence**: `.omo/evidence/task-6-whitelist.txt`

      **Commit**: YES

- [x] 1.7 OpenCodeNotificationRouter 简化

      **What to do**: 1. 删 `projectRegistry` 字段
      2. 删 `register()` / `unregister()` 方法
      3. 改 `notify()`：
      - 删除 `normalize()` 路径规范化
      - 接收 `project: Project` 参数（**不是** directory string）
      - 直接调 `OpenCodeNotificationService.send()`

      **Must NOT do**: - 不删 `OpenCodeNotificationService`
      - 不改 `OpenCodeConfig` getter

      **Recommended Agent Profile**: `category="quick"`

      **References**: - `utils/OpenCodeNotificationRouter.kt` (L1-46)
      - design: `design.md` Decision 6
      - spec: `sse-per-project-endpoint/spec.md` "Notification Router Simplified"

      **Acceptance Criteria**: ```bash
      ! grep -q "projectRegistry" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationRouter.kt && grep -q "project: Project" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationRouter.kt
      ```

      **QA Scenarios**: - **Happy**: SSE consumer 调 `notify(eventType, parsedMap, project)` → 直接 dispatch 给该 project
      - **Exception**: N/A（per-project consumer 模型已保证 project 正确）

      **Parallelization**: 可与 Task 6, 8, 10 并发

      **Evidence**: `.omo/evidence/task-7-router.txt`

      **Commit**: YES

- [x] 1.8 BashCommandHandler 适配新 wire 格式

      **What to do**: 1. `handleBashEvent` 中 `parsedMap["payload"]["properties"]` 嵌套 → `parsedMap["properties"]`

      **Must NOT do**: - 不改 `READ_ONLY_COMMANDS` 集合
      - 不改 `BASH_SPLIT_REGEX` / `WHITESPACE_REGEX`
      - 不改 `FullRefreshCoordinator.request()` 调用

      **Recommended Agent Profile**: `category="quick"`

      **References**: - `listeners/BashCommandHandler.kt` (L8-28)
      - design: `design.md` Decision 4

      **Acceptance Criteria**: ```bash
      grep -q 'parsedMap\["properties"\]' src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/BashCommandHandler.kt && ! grep -q 'parsedMap\["payload"\]\["properties"\]' src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/BashCommandHandler.kt
      ```

      **QA Scenarios**: - **Happy**: bash 写命令触发 VFS 刷新
      - **Exception**: 非 bash 工具类型 → handler 内部 return false

      **Parallelization**: 可与 Task 6, 7, 10 并发

      **Evidence**: `.omo/evidence/task-8-bash.txt`

      **Commit**: YES

- [x] 1.9 OpenCodeNotificationService 改 hard-code + 加 1s Session 维度防抖

      **What to do**: 1. 删 `if (!OpenCodeConfig.isEventEnabled(eventType)) return`（L49）
      2. 改 `if ((eventType == "complete" || eventType == "subagent_complete") && ...)` → `if (eventType == "complete" && ...)`（L55）
      3. 改 `formatMessage`：
      - `OpenCodeConfig.getMessageTemplate(eventType)` → `when (eventType) { "permission" -> "权限申请: {sessionTitle}"; "complete" -> "回答完成: {sessionTitle}"; "question" -> "询问用户: {sessionTitle}"; else -> eventType }`
      4. 加 `lastNotificationFired: ConcurrentHashMap<Pair<String, String>, Long>`（LRU 1000，synchronizedMap 包装）
      5. 加 `NOTIFICATION_DEDUP_WINDOW_MS = 1000L`
      6. `send()` 入口加 dedup 检查：`last != null && now - last < 1000 → return`
      7. 改 `extractSessionID`：去掉 `payload` 嵌套（L136-139），直接从 `properties.sessionID` 取

      **Must NOT do**: - 不改 `SessionInfoCache`
      - 不改 `addClickAction` / `resolveType` / `lookupSessionTitle` / `lookupAgentName`

      **Recommended Agent Profile**: `category="deep"`

      **References**: - `utils/OpenCodeNotificationService.kt` (L47-93, L112-133, L135-139)
      - design: `design.md` Decision 5
      - spec: `sse-notification-dedup/spec.md` "1s Session-Dimension Notification Deduplication"

      **Acceptance Criteria**: ```bash
      ! grep -q "isEventEnabled" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationService.kt && ! grep -q "subagent_complete" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationService.kt && grep -q "lastNotificationFired" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationService.kt && grep -q "1000" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationService.kt
      ```

      **QA Scenarios**: - **Happy**: 同 session 同事件 1s 内重复 → 第二次抑制
      - **Exception**: 1s 后同 session 同事件 → 通知正常；不同 session 同事件 → 都通知；同 session 不同事件 → 都通知

      **Parallelization**: 可与 Task 6, 7, 8 并发（**Task 9 必须在此之后**——见 Task 9 顺序约束）

      **Evidence**: `.omo/evidence/task-10-dedup.txt`

      **Commit**: YES

- [x] 1.10 OpenCodeConfig 简化

      **What to do**: 1. 删 `isEventEnabled` / `setEventEnabled` / `getMessageTemplate` / `setMessageTemplate` 4 函数
      2. 删 `ALL_EVENT_TYPES` / `defaultEvents()` / `defaultMessages()` 3 顶层
      3. **保留** 4 个 var getter：`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`

      **Must NOT do**: - 不删 4 个 var getter
      - 不改 PropertiesComponent key 名

      **Recommended Agent Profile**: `category="quick"`

      **References**: - `utils/OpenCodeConfig.kt` (L1-74)
      - design: `design.md` Decision 8

      **Acceptance Criteria**: ```bash
      ! grep -q "isEventEnabled" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeConfig.kt && ! grep -q "ALL_EVENT_TYPES" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeConfig.kt && grep -q "notificationEnabled" src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeConfig.kt
      ```

      **QA Scenarios**: - **Happy**: 4 个 var getter 仍工作；PropertiesComponent 持久化保留
      - **Exception**: 老 user 已存 `opencode.event.error.enabled=true` → 不被任何代码读取，无副作用

      **Parallelization**: **必须在 Task 10 之后**（Task 9 删 `isEventEnabled` / `getMessageTemplate` 函数，Task 10 删 `OpenCodeNotificationService` 中对这些函数的调用；Task 9 先做则 `OpenCodeNotificationService` 编译失败）

      **Evidence**: `.omo/evidence/task-9-config.txt`

      **Commit**: YES

### Wave 3: Settings UI 删除（依赖 Task 9）

- [x] 2.1 删除 OpenCodeConfigurable.kt + plugin.xml 清理

      **What to do**: 1. 删 `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/settings/OpenCodeConfigurable.kt`（-114 行）
      2. `plugin.xml` 移除 `<applicationConfigurable parentId="tools" instance="...OpenCodeConfigurable" .../>` 4 行
      3. 删 `src/main/kotlin/.../settings/` 空目录

      **Must NOT do**: - 不改 OpenCodeConfig.kt（Task 9 已做）
      - 不删 ResourceBundle messages

      **Recommended Agent Profile**: `category="quick"`

      **References**: - `settings/OpenCodeConfigurable.kt` (L1-114)
      - `resources/META-INF/plugin.xml` (L52-55)
      - design: `design.md` Decision 8

      **Acceptance Criteria**: ```bash
      ! test -f src/main/kotlin/com/shenyuanlaolarou/opencodewebui/settings/OpenCodeConfigurable.kt && ! grep -q "OpenCodeConfigurable" src/main/resources/META-INF/plugin.xml && ./gradlew build
      ```

      **QA Scenarios**: - **Happy**: `./gradlew build` 通过；plugin 启动无 `OpenCodeConfigurable` 类引用错误
      - **Exception**: 其他代码引用 `OpenCodeConfigurable` → 编译失败 → grep 全文确认（已验证 0 引用）

      **Parallelization**: 必须在 Task 9 完成后

      **Evidence**: `.omo/evidence/task-11-ui.txt`

      **Commit**: YES

### Final Verification Wave

- 3.1 单元测试通过

      **Acceptance Criteria**: - `./gradlew check` 跑全部单测（OpenCodeSSEConsumer / SSEEventParser / ExtractSessionIdTest）
      - 新增 SSEEventParser 新 wire 格式单测（9 个白名单 type + 25+ 噪音 close 早退 + extractSessionID 3 级 fallback）
      - 新增 1s Session 维度防抖单测（mock OpenCodeNotificationService.send）：
      - 同 session 同事件 1s 内重复 → 抑制
      - **不同事件类型 1s 内不互相抑制**（permission 后立即 question 都通知）
      - **不同 session 1s 内独立**（同事件不同 session 都通知）
      - 1s 后同 key → 通知正常
      - LRU 容量 1000 限制（注入 1001 个 entry → 验证 evict）
      - LRU 多线程并发安全（10 线程并发 put/get）

- 3.2 集成测试多项目并发

      **Acceptance Criteria**: - 模拟同时打开 2 个 project
      - 验证两个 consumer 都收到事件（不互相干扰）
      - 验证 subagent 完成不通知父 session（OpenCodeSSEConsumer.subagentSessionIds 追踪）
      - 验证 1s 防抖跨 session 独立（同事件不同 session 都通知）
      - 验证 per-project watchdog 线程独立（10 project 不互相影响）
      - **验证 `disposeForProject(specificProject)` 只移除目标 consumer**，不影响其他 project 的 consumer
      - **验证 `gracefulShutdown()` 停止所有 consumer**（10 project 全部 stop）

- 3.3 手动验证

      **Acceptance Criteria**: - `./gradlew runIde` 触发实际流式生成消息
      - 确认无解析错误、无事件丢失、无 consumer 泄漏
      - 确认 3 个真通知类型（permission / complete / question）正确送达
      - 确认 Settings UI **不**在 IDE Settings → Tools → OpenCode 下显示
      - 确认 4 个 var getter 仍工作（如 `notificationEnabled=false` 关掉所有通知）
      - 确认 `session.status(busy/retry)` 到达时**不**触发 complete 通知
      - 确认 `session.idle` 100ms 后又来 `session.idle` → **不**重复通知（1s 防抖）
      - 确认 `permission.asked` / `question.asked` 实际触发通知（这两个 type 不在 SDK types.gen.ts，wire 字段基于推测，手动验证 properties.sessionID 提取正确）

---

## Plan Reference

> 以下章节从 OMO plan 镜像，仅供人类阅读。OpenSpec CLI 不解析这些字段。

### 1. TL;DR


intellij-opencode-web 插件 SSE 处理架构重构：per-project endpoint + 流式 reader + 9 个白名单 + 1s Session 维度防抖 + Settings UI 完全删除，**修复 3 个根本 bug**（所有通知丢失 / 多项目 SSE 不可用 / 高频噪音）。

### 2. Context


插件当前用 `/global/event` 端点 + 单例 `OpenCodeServerManager`，导致**所有通知丢失**（`OpenCodeNotificationRouter.notify` 第一个 `?: return` 因 `eventDir=null` 永远触发）+ **多项目 SSE 不可用**（切换项目时 stop 旧 consumer）+ 高频 `message.part.delta` 事件走完整 Gson 解析浪费 50-60 KB/s 临时对象。User 决定通知范围**仅**"不介入就 block"的事件（session.idle / session.status(idle) / permission.asked / question.asked），**subagent 完全静默**，**Settings UI 完全删除**改用 `OpenCodeConfig.kt` 作为配置载体。

### 3. Work Objectives


### Must Have:

- **修复所有通知丢失 bug**（`OpenCodeNotificationRouter.notify` 不再因 `eventDir=null` 丢通知）
- **多项目 SSE 真正可用**（`ConcurrentHashMap<Project, OpenCodeSSEConsumer>`）
- **per-project endpoint**：改用 `/event?directory=<url-encoded-path>`（server 端按 directory 过滤）
- **流式 reader**：升级 okhttp-eventsource 4.1.0 → 4.3.0，启用 `streamEventData(true)` + `MessageEvent.getDataReader()`
- **9 个白名单 type 过滤**（4 通知 + 5 业务）：Reader 阶段 type peek → 白名单外 `close()` 早退
- **3 个真通知类型**：`permission` / `complete` / `question`（user 拍板）
- **subagent 完全静默**：subagent 完成**不** dispatch 任何通知
- **Settings UI 完全删除**：`OpenCodeConfigurable.kt` + `plugin.xml` `<applicationConfigurable>` 移除
- **`OpenCodeConfig.kt` 简化**：删 4 个事件类型 specific 函数 + 3 顶层；保留 4 个通用 var getter（PropertiesComponent 持久化）
- **1s Session 维度防抖**：`(sessionID, eventType)` Key，1s 内同 session + 同事件类型抑制
- **删除 2s `idleLastFired` 窗口**（集中防抖到 `OpenCodeNotificationService.send()`）

### Must NOT Have:

- 改 opencode server（不在本仓库）
- 替换 SSE 客户端（继续用 LaunchDarkly EventSource，仅升级版本）
- 迁移到 Ktor Client SSE
- 实现 server-side event filter（需要 opencode server 改动）
- **不解决**多 IDE 打开同 project 的"重复通知"问题（user 已确认这是合理行为）
- 1s 防抖 user 可配（hard-code 1s window）
- 1s 防抖**仅** `sessionID` 维度（会丢真实通知，不合理）

### 4. Verification Strategy


- **Test Decision**: 单元测试（`OpenCodeSSEConsumer` / `SSEEventParser` / `ExtractSessionIdTest`）+ 集成测试（多项目并发 + per-project watchdog）+ 手动验证
- **Coverage Target**: 修改文件 ≥ 80%
- **Manual**：`./gradlew runIde` 触发实际流式生成消息 + `./gradlew check` 跑全部单测
- **Performance benchmark**: 临时对象分配 50-60 KB/s → ~5-10 KB/s（理论估算，spec 阶段跑 JFR micro-benchmark 验证）

### 5. Execution Strategy


- **Critical Path**: Task 1（依赖升级）→ Task 2（架构重构）→ Task 3→4→5（`OpenCodeSSEConsumer.kt` 串行 3 改）→ Task 6/7/8/10（独立文件并行）→ Task 9（OpenCodeConfig 简化，**必须在 Task 10 之后**）→ Task 11（Settings UI 删除）
- **Max Concurrent**: 4（Wave 2b）
- **依赖关系与 Wave 划分**:
  - **Wave 1（并行 2 task）**: Task 1（改 `gradle/libs.versions.toml`）+ Task 2（改 `OpenCodeServerManager.kt`）—— 文件独立，真并行
  - **Wave 2a（串行 3 task）**: Task 3 → Task 4 → Task 5（**全部改 `OpenCodeSSEConsumer.kt` 不同区域，**严格串行避免 git merge 冲突）
  - **Wave 2b（并行 4 task）**: Task 6（`SSEEventParser.kt`）+ Task 7（`OpenCodeNotificationRouter.kt`）+ Task 8（`BashCommandHandler.kt`）+ Task 10（`OpenCodeNotificationService.kt`）—— 4 个文件独立
  - **Wave 2c（顺序 1 task）**: Task 9（`OpenCodeConfig.kt`）—— **必须在 Task 10 之后**（Task 9 删 `isEventEnabled` 函数，Task 10 删 `isEventEnabled` 调用；Task 9 先做则 `OpenCodeNotificationService` 编译失败）
  - **Wave 3（1 task）**: Task 11（删 `OpenCodeConfigurable.kt` + 改 `plugin.xml`）—— 依赖 Task 9 完成
  - **Wave 4（FVW）**: F1 单测 + F2 集成 + F3 手动

### 8. Commit Strategy


- **每个 task 一个 commit**（11 个 task → 11 个 commit）
- 使用 **Conventional Commits** 格式：

1. `build: upgrade okhttp-eventsource 4.1.0 → 4.3.0`
2. `refactor(sse): per-project consumer + URL switch`
3. `feat(sse): stream reader via getDataReader + new wire format`
4. `refactor(sse-consumer): remove legacy notification cases + state machine`
5. `feat(sse-parser): 9 eventType whitelist + new wire format`
6. `refactor(notify): router simplified (per-project direct dispatch)`
7. `refactor(bash): adapt to new wire format (no payload nesting)`
8. `feat(notify): 1s session-dimension dedup + hard-coded templates`
9. `refactor(config): remove 4 event-type functions + 3 top-level`
10. `chore(ui): remove OpenCodeConfigurable + applicationConfigurable`
