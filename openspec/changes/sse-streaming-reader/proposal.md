## Why

opencode server 提供两个 SSE 端点：

- **`/global/event`**：全 instance 广播，**无任何过滤**
- **`/event?directory=<projectPath>`**：per-connection，server 端按 `directory` query param 路由到对应 instance，handler 内部按 `event.location.directory === instance.directory` 过滤

plugin 当前订阅 `/global/event` 端点（`OpenCodeSSEConsumer.kt:82`），导致**三个根本问题**：

### 问题 1：多项目 SSE 不可用（**多 IDE 用户场景下**）

`OpenCodeServerManager` 是 `object` 单例（`OpenCodeServerManager.kt:20`），维护**唯一一个** `sseConsumer: OpenCodeSSEConsumer?`（`OpenCodeServerManager.kt:26`）。`ensureSSEConsumer(project)` 在 project 切换时**会 stop 旧 consumer**（`OpenCodeServerManager.kt:38-43`）。意味着**多项目同时打开时，只有最后一个 ensureSSEConsumer 调用的项目真正拥有 SSE 连接**——其他项目的事件**全丢失**。

### 问题 2：**所有通知丢失**（**当前 plugin 装上起就存在**——user 报告的"question 通知没收到"是真 bug，但**所有通知都丢**）

调研过程（基于 user 提的"question 通知没收到" bug 报告）：

1. plugin 端 `OpenCodeNotificationRouter.notify(eventType, properties, eventDir)`（`OpenCodeNotificationRouter.kt:31-35`）：
   ```kotlin
   fun notify(eventType: String, properties: Map<*, *>?, eventDir: String?) {
       val dir = normalize(eventDir) ?: return  // ← eventDir=null 时直接 return
       val project = projectRegistry[dir] ?: return
       OpenCodeNotificationService.send(eventType, properties, project)
   }
   ```
2. plugin 端 `eventDir` 来自 `parsed.directory`（`SSEEventParser.kt:99` `root.get("directory")?.asString`）
3. **关键**：`/global/event` 端点处理的 SSE 事件**没有 `directory` 顶层字段**——因为 server 端 `EventV2Bridge`（`event-v2-bridge.ts:38-43`）：
   ```typescript
   const ctx = yield* InstanceRef  // ← undefined（/global/event 不走 InstanceContextMiddleware）
   GlobalBus.emit("event", {
     directory: event.location?.directory ?? ctx?.directory,  // ← undefined ?? undefined
     ...
   })
   ```
4. `InstanceRef` 默认 `undefined`（`instance-ref.ts:5-7` `Context.Reference<..., defaultValue: () => undefined>`）
5. `event.location` 来自 `Location.Service.directory`（`event.ts:263`）—— `Location.Service` 在 `/global/event` 上下文里**未提供**（`api.ts:42-45` RootHttpApi 不注册 `InstanceContextMiddleware`）
6. **结果**：`directory: undefined`，wire JSON 没有 `directory` 字段
7. plugin 端：`parsed.directory` = `null` → `normalize(null) = null` → **`?: return` 直接丢弃**

**所有通知都从 plugin 装上起就从未到达过**（不只是 question——permission / complete 全部同样丢失）。**`OpenCodeSSEConsumer.kt:236-253` 的 eventDir 文件事件比较也是空操作**（永远 `eventDir == null`）。

user 报告的"question 通知没收到"是**真 bug**——但**范围比想的大得多**。

### 问题 3：高频噪音事件 + 临时对象浪费（**所有用户场景**）

`/global/event` 端点**全 instance 广播**——包括 100+ Hz 的 `message.part.delta`（每个 token 一次），以及 25+ 个 plugin 不关心的噪音事件。plugin 当前在 `OpenCodeSSEConsumer.onMessage` 调用 `messageEvent.data`（即 LaunchDarkly `MessageEvent.getData()`）强制构造完整 JSON String（~300 字节），再走 Gson 解析生成 JsonObject 树（~200-300 字节），合计**每个高频事件 500-600 字节临时对象**。100Hz 下累计 **50-60 KB/s** 临时对象分配。

### 目标

**三层叠加优化**：

1. **层 1：per-project endpoint**（解决多项目 SSE 不可用）—— 改用 `/event?directory=<projectPath>`，每个 IDE project 独立 SSE connection，server 端按 directory 过滤。client 端删除 directory 比较逻辑。
2. **层 2：流式 Reader**（解决 String 分配）—— 升级 LaunchDarkly 4.1.0 → 4.3.0，启用 `streamEventData(true)` 模式，用 `MessageEvent.getDataReader()` 流式 Reader 替代强制 String 构造。
3. **层 3：type 白名单**（解决 Gson 解析浪费）—— Reader 阶段 peek `payload.type`，与 `ALLOW_PARSE_EVENT_TYPES` 白名单（9 个 plugin 真正需要的 wire-level eventType）对比：白名单外事件直接 `close()` Reader 早退，**不调 Gson**。

> **注**：4.1.0 内部已经支持 `streamEventData(true)` + `getDataReader()`（已通过反编译 `okhttp-eventsource-4.1.0.jar` 的 `EventSource.java:992-995` 与 `MessageEvent.java` 验证）。**升级 4.3.0 的理由**是拿到最新稳定版的 bug 修复 + 4.2.0/4.3.0 新增的同步 `messages()` / backpressure 等改进（虽然当前 plugin 主要用 `BackgroundEventSource` 异步模式，新 API 暂不直接使用，但升级能减少将来技术债）。

## What Changes

### 层 1：Per-Project SSE Connection + Endpoint 切换

- **改 SSE 端点 URL**：`OpenCodeSSEConsumer.startSseConnection()` 中 `/global/event` 改为 `/event?directory={base64ProjectPath}`（`OpenCodeSSEConsumer.kt:82`）
- **OpenCodeServerManager 重构**：单例 `sseConsumer` 字段改为 `ConcurrentHashMap<Project, OpenCodeSSEConsumer>`，每个 project 独立 consumer
  - `ensureSSEConsumer(project)` 改为 `getOrCreateConsumer(project)`，用 `computeIfAbsent` 保证线程安全
  - `disposeForProject(project)` 删除该项目 consumer（不变逻辑）
  - `gracefulShutdown()` 改为停止**所有** consumer
  - 移除"consumer 切换时 stop 旧" 的逻辑（line 38-43 删除）
- **OpenCodeSSEConsumer.startSseConnection** 改 URL + 启用 `streamEventData(true)`
- **OpenCodeSSEConsumer.onMessage 删除 eventDir 比较逻辑**（`OpenCodeSSEConsumer.kt:236-253`）：server 端已按 directory 过滤，事件一定匹配，无需比较
- **OpenCodeNotificationRouter 简化**：`notify()` 删除 `normalize()` 路径规范化（`OpenCodeNotificationRouter.kt:37-45`）和 `projectRegistry` 查找——**每个 consumer 只属于一个 project**，通知直接用 `OpenCodeSSEConsumer` 构造时传入的 `project` 实例

### 层 2：流式 Reader + 4.3.0 升级

- **依赖升级**：`gradle/libs.versions.toml` 中 `okhttpEventsource = "4.1.0"` → `"4.3.0"`
- **`EventSource.Builder` 链**追加 `.streamEventData(true)`
- **`OpenCodeSSEConsumer.onMessage` 改用 `messageEvent.getDataReader()`** 替代 `messageEvent.data`

### 层 3：Type 白名单

- **`SSEEventParser` 改造**适配 `/event` 端点的 wire 格式（**无**外层 `{directory, project, workspace, payload}` 包装，payload 提升为 root）：
  - 删除外层 `directory` 字段提取（`SSEEventParser.kt:99`）
  - 删除外层 `payload` 嵌套访问（`SSEEventParser.kt:101, 116, 123, 130`）
  - 删除 syncEvent 解析（`/event` 端点 schema 不支持 sync wrapper，**实际不推送**）
  - `extractSessionID()` / `extractParentID()` 适配（去掉 `parsedMap["payload"]` 嵌套）
- **新增** `ALLOW_PARSE_EVENT_TYPES` 集合（plugin 实际处理的 **9 个** wire-level eventType——见白名单表格）
- **Reader 阶段 type peek**：tree-model 解析拿到 `type` → 查白名单 → 不在白名单内 → `close()` Reader 早退
- **删除**现有 `SKIP_PARSE_EVENT_TYPES = setOf("message.part.delta")`（被新白名单机制吸收，**`message.part.delta` 在新设计中归入噪音清单，由 Reader 阶段直接 close 处理，不再走 SKIP_PARSE_EVENT_TYPES 路径**）
- **`BashCommandHandler` 适配**（`BashCommandHandler.kt:12-28`）：去掉 `parsedMap["payload"]["properties"]` 嵌套访问，新格式下直接 `parsedMap["properties"]`
- **`OpenCodeNotificationService` 适配**（`OpenCodeNotificationService.kt:135-139`）：同上去掉嵌套
- **`OpenCodeSSEConsumer.kt` 全部 `parsedMap["payload"]` 嵌套访问**（共 5 处：行 151, 185-186, 193-195, 202-206, 212-214，砍 4 个 case 后剩下极少）：改为 `parsedMap["xxx"]` 直接访问

### 层 4：1s Session 维度防抖（**核心功能**）

**目的**：1s 内同一 session 的同一事件类型**重复**触发 → **抑制**第二次（避免重复打扰 user）。

**典型场景**：

- `session.status(idle)` 100ms 后又来 `session.idle` → 抑制第二次
- `permission.asked` 1s 内多个子权限请求 → 抑制后续
- `question.asked` 1s 内 LLM 重新问 → 抑制后续

**Key 设计**（**user 拍板：A 方案**）：

- **`(sessionID, eventType)` 维度**——同 session + 同事件 1s 内抑制。**不同事件类型不互相干扰**（permission 后立即 question **都**通知）。

**位置**：放在 **`OpenCodeNotificationService.send()`** 中（**通用**防抖，对**所有**通知事件生效），**不**分散在每个 consumer。

**实现要点**：

- 新增 `lastNotificationFired: ConcurrentHashMap<Pair<String, String>, Long>`（sessionID + eventType → timestamp）
- `send()` 入口检查：当前时间 - lastFired < 1000ms → 抑制
- LRU 限制（避免无界增长）—— 复用现有 `LinkedHashMap` LRU 模式

**替换**现有 `OpenCodeSSEConsumer.handleSessionIdle` 的 `idleLastFired` 2s 窗口（`OpenCodeSSEConsumer.kt:271-275`）—— 集中防抖逻辑到 `OpenCodeNotificationService.send()` 一处，**避免重复**。

**改动量**：

- `OpenCodeNotificationService`：+12 行（lastNotificationFired 集合 + dedup 检查 + 清理）
- `OpenCodeSSEConsumer`：-5 行（删 idleLastFired 2s 窗口 + 清理）
- **净 +7 行**

### 白名单（9 个 wire-level eventType）

**白名单设计原则（user 拍板）**：**只处理"不介入就 block"的事件 + IDE 业务集成必须的事件**。

#### 通知白名单（4 个）—— **严格按"不介入就 block"标准**

> 判定标准：事件发生后，session **会等待** user 介入，**否则永远不继续**。

| #   | eventType                    | 格式      | 通知类型     | 为什么符合"不介入就 block"                                |
| --- | ---------------------------- | --------- | ------------ | --------------------------------------------------------- |
| 1   | `session.idle`               | SyncEvent | `complete`   | session 完成，等 user 发新消息才能继续                    |
| 2   | `session.status` (type=idle) | SyncEvent | `complete`   | 等价于 #1，fallback 路径（不同 opencode server 版本兼容） |
| 3   | `permission.asked`           | SyncEvent | `permission` | 权限审批，等 user 批准/拒绝才能继续                       |
| 4   | `question.asked`             | SyncEvent | `question`   | LLM 主动问 user 问题，等 user 回答才能继续                |

#### 业务白名单（5 个）—— **IDE 集成必须，不走通知**

| #   | eventType              | 格式            | 用途                                                                                                                      |
| --- | ---------------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 5   | `session.created`      | Direct BusEvent | **`subagentSessionIds.add(sid)`**——`handleSessionIdle` 据此判断 subagent/parent，防止误把 subagent 完成当 `complete` 通知 |
| 6   | `message.part.updated` | Direct BusEvent | **`BashCommandHandler.handleBashEvent()`**——bash 写文件 VFS 刷新（handler 内部判断 `tool=bash + status=completed`）       |
| 7   | `file.edited`          | Direct BusEvent | **`FullRefreshCoordinator.request()`**——IDE VFS 刷新                                                                      |
| 8   | `file.watcher.updated` | Direct BusEvent | **`FullRefreshCoordinator.request()`**——IDE VFS 刷新（备用）                                                              |
| 9   | `session.diff`         | Direct BusEvent | **`FullRefreshCoordinator.request()`**——IDE VFS 刷新（file.\* 缺失时的兜底）                                              |

### 砍掉的事件（**对照原 plugin 处理逻辑逐项说明**）

| 砍掉的事件                                                | 原 plugin 处理                                                          | 砍掉理由                                                                                                |
| --------------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `server.connected`                                        | `dispatchNotification("client_connected")`                              | `client_connected` 通知默认 OFF + 一次性事件，无业务用途                                                |
| `session.error`                                           | `dispatchNotification("error"/"user_cancelled")`                        | **error 后 session 通常 terminating，不是 waiting**——user 不需要"立即介入"                              |
| `session.next.tool.called` (含 tool=question / plan_exit) | `dispatchNotification("question"/"plan_exit")`                          | **双路径重复 bug 来源**——`question.asked` 已独立处理 question；plan_exit 不算 block                     |
| `message.updated` (role=user)                             | `sessionIdleFired.remove(sid)` + `dispatchNotification("user_message")` | `user_message` 通知默认 OFF；**重置信号消失需要重新设计 state machine**（详见下方"state machine 重构"） |
| `session.created` (parent session)                        | `dispatchNotification("session_started")`                               | `session_started` 通知默认 OFF + 仅追踪 subagent 时需要（白名单 #5 已覆盖）                             |
| `session.deleted`                                         | 仅 log，无业务用途                                                      | 无通知、无业务用途                                                                                      |
| `session.updated` / `session.compacted`                   | 无处理（仅 server 端状态变更）                                          | plugin 不关心                                                                                           |

### state machine 重构（**砍 `message.updated` 后的必然修复**）

**原设计依赖**：`message.updated(role=user)` 作为 `sessionIdleFired` 集合的**重置信号**——user 发新消息后，清除该 session 的"已通知 complete"标记，下次 `session.idle` 时能再次通知。

**砍 `message.updated` 后的影响**：

- `sessionIdleFired` 集合**没有重置信号**
- 同一 session 第一次 idle 通知后，**后续 idle 永久抑制**
- → user 多次发消息**只通知第一次**——**bug**

**新设计**（**简化方案**）：

- **删除** `sessionIdleFired` 集合
- 只保留 2s `idleLastFired` 时间窗口去重
- **接受**"agent 循环中父 session 反复 idle 会在间隔 > 2s 后再通知"作为**边缘 case**
- **2 层去重**（subagent 完全静默 + 2s 窗口）：
  1. **`subagentSessionIds` 完全静默**——subagent 完成时 `handleSessionIdle` **直接 return**，**不 dispatch 任何通知**（`subagent_complete` 通知类型**已砍**——user 不想收 subagent 通知）
  2. **2s `idleLastFired` 时间窗口**——防高频重复（**仅**对父 session 生效）
- **净效果**：
  - 父 session：user 多次发消息 → 每次 session.idle（间隔 > 2s）**都通知** ✅
  - subagent 完成 → **永远不通知** ✅

> **为什么不用 `session.status(type=busy)` 做重置信号？**——mohak34 用了 `markSessionBusy` 模式（`session.status(busy)` 事件触发时设置 busy 标志），但**这会引入新事件 type 到白名单**。**当前设计选择简化优先**：直接删除 `sessionIdleFired`，2s 窗口足够覆盖高频重复场景（agent 循环中 subagent 完成间隔通常 < 1s）。

### 噪音事件清单（plugin 不关心，**全部走白名单外快速路径**）

Reader 阶段 peek 到 `type` 不在白名单内时，直接 `close()` Reader 早退——**不构造完整 String、不调 Gson**：

- **`message.part.delta`**（100+ Hz 高频 token 增量）—— plugin 不渲染 part
- **`server.instance.disposed`**（生命周期）
- **`server.connected`**——一次性事件，无用途
- **`session.error`**——error 不算 block
- **`session.compacted`** / **`session.deleted`**——server 端状态变更，plugin 不关心
- **`session.next.tool.called`**（含 question / plan_exit）——双路径重复 bug 来源，全砍
- **`session.next.*` 子事件**（step / text / reasoning / shell / tool.delta / tool.progress / todo / compaction / prompted / synthetic / retried / agent.switched / model.switched）
- **`message.part.removed`** / **`message.removed`**
- **`project.updated`** / **`mcp.*`** / **`installation.*`** / **`auth.*`** / **`plugin.*`** / **`model.updated`** / **`lsp.*`**
- 任何 opencode server 端未来新增且未在白名单内的事件类型

> **实施时调整（与初版 proposal 的差异）**：以下 3 个事件**未**被移到白名单外，仍在 `SSEEventParser.ALLOW_PARSE_EVENT_TYPES` 白名单内，**不要**按初版 proposal 推断其行为：
>
> - **`server.heartbeat`**（10s 一次 keepalive）—— **必须**留在白名单，因为 `OpenCodeSSEConsumer.onMessage` 依赖解析后的 `parsed.type == "server.heartbeat"` 分支刷新 `lastHeartbeatAt`，供 `HealthMonitor.isHealthy()` 检测 server 静默（SSE 物理连接活但 server 不发事件的边界场景）。若移出白名单，`lastHeartbeatAt` 永远为 `0L`，`isHealthy()` 走 `!connected && lastHeartbeatAt == 0L` 早 return 立即 false，`HealthMonitor` 实际检测时间从 ~40s 退化为 ~15s，语义变化需单独评审。
> - **`session.updated`** —— 保留用于缓存 `sessionTitles: ConcurrentHashMap<String, String>`，供 `handleSessionIdle` 在 `session.idle` 到达时查 title 区分 subagent vs 父 session（不查 HTTP API，避免阻塞 SSE 线程）。若移出白名单，subagent 区分降级为同步 HTTP 调用，8s 超时会阻塞所有后续 SSE 事件，回归到 `sse-streaming-reader` 之前的旧 bug。
> - **`message.updated`** —— 保留用于重置 `idleNotifiedSessions` LRU Set（user 发新消息 = 允许下次 idle 再次通知，实现"user 介入 = 通知"语义）。若移出白名单，同 session 多次 idle 只通知第一次，user 发新消息后无法重新触发，违反直觉。
>
> 实际当前白名单 12 项见 `SSEEventParser.kt:74-90`。本节列出的 3 项差异已在此 note 中说明，后续阅读 proposal 时**不要**再按初版推断 `lastHeartbeatAt` 失效 / `HealthMonitor` 退化。

### 配套修改：**完全删除 Settings UI** + `OpenCodeConfig.kt` 简化

**为什么删 Settings UI**（user 拍板）：通知事件已 hard-code 为 3 种（`permission` / `complete` / `question`），不再让 user 配置单个事件开关 / 模板。`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration` 4 个**通用设置**功能保留，但**改用** `OpenCodeConfig.kt`（**专门的配置文件的代码**）承载，不再通过 IDE Settings 面板暴露。

**改动清单**：

1. **删除** `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/settings/OpenCodeConfigurable.kt`（**-114 行**）—— Settings UI 整个文件
2. **删除** `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/settings/` 目录（空目录）
3. **修改** `src/main/resources/META-INF/plugin.xml` —— 移除 `<applicationConfigurable>` 注册（**-4 行**）
4. **简化** `OpenCodeConfig.kt`：
   - **保留** 4 个 var getter（`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`）—— **功能不变**，老 user 之前在 UI 设置的值**继续生效**（PropertiesComponent 持久化保留）
   - **删除** `isEventEnabled` / `setEventEnabled` / `getMessageTemplate` / `setMessageTemplate` 4 个事件类型 specific 函数（**-8 行**）
   - **删除** `ALL_EVENT_TYPES` / `defaultEvents()` / `defaultMessages()` 3 个（**-20 行**）—— 事件类型已 hard-code 在代码里
5. **简化** `OpenCodeNotificationService.kt`：
   - **删除** `if (!OpenCodeConfig.isEventEnabled(eventType)) return`（line 49，**-1 行**）—— `isEventEnabled` 函数已删
   - **改** `if ((eventType == "complete" || eventType == "subagent_complete") && OpenCodeConfig.minDuration > 0)` → `if (eventType == "complete" && OpenCodeConfig.minDuration > 0)`（line 55，**-1 行**）—— `subagent_complete` 已砍
   - **改** `formatMessage` 的 `OpenCodeConfig.getMessageTemplate(eventType)` → `when (eventType) { "permission" -> "..."; "complete" -> "..."; "question" -> "..."; else -> eventType }` hard-code 3 个模板（**-5 行**）

> **后向兼容性**：4 个 var getter 仍然用 `PropertiesComponent.getInstance()` 持久化，**老 user 之前在 UI 设置的值（如 `notificationEnabled=false`）继续生效**。但**不再有任何 UI 让 user 改这些值**——user 想要"功能不变"指的是**功能继续生效**，不是"继续可配置"。

### 保持现有功能

- 通知分发（`permission.asked` / `session.idle` / `session.status(idle)` / `question.asked`）—— **3 个真通知类型**（`permission` / `complete` / `question`）
- **1s Session 维度防抖**（**(sessionID, eventType) 维度**）—— 1s 内同 session 同事件类型**重复**触发抑制（避免重复打扰 user）；**替换**现有 `OpenCodeSSEConsumer.idleLastFired` 2s 窗口（集中到 `OpenCodeNotificationService.send()` 一处）
- 通知全局开关（`notificationEnabled`）、格式选项（`showProjectName` / `showSessionTitle`）、时长过滤（`minDuration`）—— **功能保留**，实现从 Settings UI 改为 `OpenCodeConfig.kt` 4 个 var getter（PropertiesComponent 持久化；老 user 配置继续生效）
- 文件刷新（`session.diff` / `file.edited` / `file.watcher.updated` / `message.part.updated` bash 联动）
- subagent 追踪 + **完全静默**（`session.created` → `subagentSessionIds.add`；subagent 完成时 `handleSessionIdle` 直接 return，**不 dispatch 任何通知**）
- 30s watchdog 重连机制
- SSE 连接管理（start/stop/reconnect）——**per-project 独立生命周期**
- 30s idle timeout 逻辑
- 多 IDE 打开同 project 时的"重复通知"行为（**user 已确认这是合理的**——每个 IDE 进程独立 SSE connection，server 端按相同 directory 推送相同事件给两个 connection，每个 IDE 独立显示）

### 砍掉的功能

- **Settings UI**（`OpenCodeConfigurable.kt` + `plugin.xml` `<applicationConfigurable>`）—— 4 个 var getter 仍工作，但**不再通过 IDE Settings 面板暴露**（user 拍板）
- **事件类型 specific 配置**（`isEventEnabled` / `setEventEnabled`）—— 事件类型已 hard-code，user 不能 enable/disable 单个事件
- **事件消息模板自定义**（`getMessageTemplate` / `setMessageTemplate`）—— 模板 hard-code 在 `OpenCodeNotificationService.formatMessage` 的 `when` 分支
- **`session.error` 通知**：error 后 user 不需要立即介入（terminating 而非 waiting）
- **`plan_exit` 通知**：plan 审阅不算 block（user 决定砍）
- **`user_message` 通知**：user 自己发消息，不需要通知
- **`session_started` 通知**：默认 OFF 且 user 不关心
- **`client_connected` 通知**：默认 OFF 且一次性事件
- **`subagent_complete` 通知**：user **不**想收 subagent 通知；`handleSessionIdle` 改为 subagent **完全跳过**（不 dispatch 任何通知），`subagent_complete` 通知类型**死代码**清理
- **`sessionIdleFired` state machine**：重置信号消失，改用 2s 时间窗口去重

### 改动量

- **OpenCodeSSEConsumer.kt**：
- URL 改 1 行 + eventDir 比较删除 -20 行 + parsed.directory 删 -5 行 + `getDataReader` 替换 +5 行
- **`when (eventType)` 砍 4 个 case**（`server.connected` / `session.error` / `session.next.tool.called` / `message.updated` 的 idle 抑制重置）共 -25 行。**注**：`message.updated` **仍**在 `SSEEventParser.ALLOW_PARSE_EVENT_TYPES` 白名单内（保留 `parsedMap` 解析），`when` 中只砍了"重置 `idleNotifiedSessions`"那 5 行；详见上文"实施时调整" note。
- **`session.deleted` 处理删除** -10 行
- **`session.created` 简化为只追踪 subagent** -3 行
- **`sessionIdleFired` 集合 + 重置信号删除** -5 行（含 companion object + onClosed 清空）
- **删 `idleLastFired` 2s 窗口** -5 行（**集中防抖**到 `OpenCodeNotificationService.send()` 1s 防抖）
- **加 `session.diff` 处理** +3 行
- **`handleSessionIdle` 简化** -8 行（去 `sessionIdleFired` 检查 + **subagent 改为直接 return**，不 dispatch `subagent_complete`）
- **净 ~-73 行**
- **SSEEventParser.kt**：
  - wire 格式重写 +30-40 行
  - extractSessionID/ParentID 适配 -10 行
  - 现有 SKIP_PARSE_EVENT_TYPES 删除 -1 行
  - **净 +20-30 行**
- **OpenCodeServerManager.kt**：单例 → Map 重构 +50-80 行（核心并发安全逻辑）
- **OpenCodeNotificationRouter.kt**：简化 notify() -10 行
- **OpenCodeConfig.kt**：
  - **删** `isEventEnabled` / `setEventEnabled` / `getMessageTemplate` / `setMessageTemplate` 4 个事件类型 specific 函数，**-8 行**
  - **删** `ALL_EVENT_TYPES` / `defaultEvents()` / `defaultMessages()` 3 个，**-20 行**
  - **保留** 4 个通用 var getter（`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`）
  - **净 -28 行**
- **OpenCodeConfigurable.kt**：**整个文件删除**，**-114 行**
- **plugin.xml**：移除 `<applicationConfigurable>` 注册，**-4 行**
- **OpenCodeNotificationService.kt**：
  - 删 `if (!OpenCodeConfig.isEventEnabled(eventType)) return`，**-1 行**
  - 改 `if ((eventType == "complete" \|\| eventType == "subagent_complete") && ...)` → `if (eventType == "complete" && ...)`，**-1 行**
  - 改 `formatMessage` 的 `getMessageTemplate` → `when` 分支 hard-code 3 个模板，**-5 行**
  - **加 1s Session 维度防抖**：新增 `lastNotificationFired` 集合 + dedup 检查 + 清理，**+12 行**
  - **净 +5 行**
- **总净变化**：**-204 ~ -154 行**（OpenCodeServerManager 仍是唯一增加项，但 Settings UI 全删 + 配置简化 + 1s 防抖小增加导致**总净减**）

### 风险等级

**中**。涉及架构层改动（OpenCodeServerManager 单例 → Map）+ 业务逻辑简化（state machine 重构 + 4 个通知 case 删除 + Settings UI 完全删除 + 配置简化），不是纯实现优化。已有 `OpenCodeSSEConsumer` 单测 + `SSEEventParser` 单测可保护回归。Settings UI 删除是**用户可见行为变更**（user 已拍板接受），但 4 个 var getter 继续生效，老 user 配置不丢。

### API 兼容性

- 内部 API：
  - `SSEEventParser.parse(String)` **重写**适配新 wire 格式（参数和返回类型不变）
  - `OpenCodeServerManager.ensureSSEConsumer(Project)` 签名不变（实现改为 `computeIfAbsent` 模式）
  - `OpenCodeConfigurable` 类**删除**（不再被 `plugin.xml` 引用；删除前确认无其他引用）
- 公共 API（IDE 平台、SSE 客户端）：**Settings UI 不可见是用户可见行为变更**（无 API 破坏——`OpenCodeConfigurable` 是 internal class，不暴露给 plugin 外部）
- 升级 `okhttp-eventsource` 4.1.0 → 4.3.0：4.0.0 是破坏性重写，4.1.0→4.3.0 跨两个小版本，**无已知 breaking change**（待 spec 阶段验证）
- **用户可见行为变更**：
  - **不再通知** session.error / plan_exit / user_message / session_started / client_connected / subagent_complete（**符合 user 拍板**）
  - state machine 行为变更：同 session 多次发消息，**每次** session.idle 都会通知（间隔 > 2s）—— **比原设计通知更频繁**，但更符合"user 发新消息 = 应该通知"直觉
  - **Settings UI 不再显示**：user **不能**通过 IDE Settings → Tools → OpenCode 面板调整配置。`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration` 4 个 var getter 继续生效（PropertiesComponent 持久化），**老 user 之前设置的值继续保留**；新安装 user 用代码默认（`true` / `true` / `true` / `0`）
  - **事件类型 specific 配置移除**：user **不能** enable/disable 单个通知事件（`permission` / `complete` / `question` 全部按代码 hard-code 行为通知）
  - **消息模板不可自定义**：3 个事件类型的消息模板 hard-code 在 `OpenCodeNotificationService.formatMessage` 的 `when` 分支

## Capabilities

### New Capabilities

- `sse-stream-filter`: SSE 事件白名单解析能力。在 LaunchDarkly `streamEventData` 流式模式下，从 SSE message 的 Reader 中提前读取 `type` 字段（payload 直接为 root，无外层包装），与 `ALLOW_PARSE_EVENT_TYPES` 白名单（plugin 实际需要的 **12** 个 wire-level eventType，4 个通知 + 7 个业务 + 1 个健康信号）对比：**在白名单内**才做完整 Gson Map 解析进入 `when (eventType)` 分支，**不在白名单内**则直接 `close()` Reader 并 return，不构造完整 String、不调 Gson。**白名单外事件**包括 `message.part.delta`（100+ Hz 高频 token 增量）、`session.next.*` 子事件（25+ 个）等——全部跳过完整解析，节省 plugin 进程临时对象分配（理论估算 50-60 KB/s → ~5-10 KB/s，节省 80-90%）。**注**：`server.heartbeat` / `session.updated` / `message.updated` **保留**在白名单内（理由见上文"实施时调整" note），实际白名单 12 项见 `SSEEventParser.kt:74-90`。

- `sse-per-project-endpoint`: Per-Project SSE 端点 + 多 consumer 管理能力。plugin 改用 opencode server 的 `/event?directory=<projectPath>` 端点（per-connection directory 过滤）替代 `/global/event` 全局端点；`OpenCodeServerManager` 从单例 `sseConsumer` 重构为 `ConcurrentHashMap<Project, OpenCodeSSEConsumer>`，每个 IDE project 独立 SSE connection、独立 30s watchdog、独立 reconnect；server 端按 `event.location.directory === instance.directory` 过滤，**plugin 端删除** eventDir 比较逻辑和 `OpenCodeNotificationRouter.projectRegistry` 查找。**彻底解决**当前多项目场景下"后打开的项目抢走前一个项目的 SSE consumer"的问题。

- `sse-notification-dedup`: Session 维度 1s 防抖能力。在 `OpenCodeNotificationService.send()` 中新增 `lastNotificationFired: ConcurrentHashMap<Pair<sessionID, eventType>, Long>` 状态：1s 内同 session + 同事件类型**重复**触发 → 抑制第二次（避免重复打扰 user）。**典型场景**：`session.status(idle)` 100ms 后又来 `session.idle` → 抑制第二次；`permission.asked` 1s 内多个子权限请求 → 抑制后续；`question.asked` 1s 内 LLM 重新问 → 抑制后续。**Key 维度**：`(sessionID, eventType)`（**user 拍板**——A 方案；B 方案仅按 sessionID 不合理——permission 后立即 question **会**抑制 question 通知）。**集中**到 `OpenCodeNotificationService.send()` 一处，**替换**现有 `OpenCodeSSEConsumer.idleLastFired` 2s 窗口（**只**对 idle 生效 + 仅 2s），统一为 1s 通用防抖。

### Modified Capabilities

（无 — 本 change 不修改任何已有 spec 的 REQUIREMENTS，仅优化实现路径）

## Impact

- **受影响代码**：
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/toolWindow/OpenCodeServerManager.kt`（**架构重构**：单例 → Map）
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt`（URL 改 + eventDir 删除 + 流式 reader + **砍 4 个 case + 删 state machine** + 加 `session.diff` 处理）
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/SSEEventParser.kt`（**wire 格式重写**适配 `/event` 端点 + 白名单 type 过滤）
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationRouter.kt`（简化 notify()）
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeConfig.kt`（**简化**：删 4 个事件类型 specific 函数 + `ALL_EVENT_TYPES` / `defaultEvents` / `defaultMessages`，**保留** 4 个通用 var getter）
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/settings/OpenCodeConfigurable.kt`（**整个文件删除**——Settings UI 不再暴露）
  - `src/main/resources/META-INF/plugin.xml`（**移除** `<applicationConfigurable>` 注册）
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/BashCommandHandler.kt`（adapt parsedMap 嵌套访问）
  - `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/OpenCodeNotificationService.kt`（adapt parsedMap 嵌套访问 + **移除 `isEventEnabled` 检查** + **改 `formatMessage` 模板 hard-code** + **移除 `subagent_complete` minDuration 检查** + **新增 1s Session 维度防抖**）

- **API 影响**：
  - 内部 API：`SSEEventParser.parse(String)` 重写，签名不变；`OpenCodeSSEConsumer.startSseConnection()` 改 URL + 启用 streamEventData
  - LaunchDarkly 公共 API 使用：`EventSource.Builder.streamEventData(boolean)` + `MessageEvent.getDataReader()`（4.1.0+ 支持）
  - 内部 `OpenCodeServerManager.ensureSSEConsumer(Project)` 签名不变，实现改为 `computeIfAbsent`

- **依赖变更**：**1 项版本升级**。`com.launchdarkly:okhttp-eventsource` 从 `4.1.0` 升级到 `4.3.0`（最新稳定版）。修改文件：`gradle/libs.versions.toml`。**无新增/移除依赖**。

- **测试影响**：
  - `src/test/kotlin/.../listeners/SSEEventParserTest.kt`（如有）需补充新 wire 格式测试
  - 集成测试：需要验证 per-project consumer 独立生命周期（创建/停止/reconnect）
  - 多项目场景测试：模拟同时打开 2 个 project，验证两个 consumer 都正常工作
  - 手动验证：`./gradlew runIde` 触发实际流式生成消息，确认无解析错误、无事件丢失、无 consumer 泄漏
  - **新增**：验证 4 个通知事件（`session.idle` / `session.status(idle)` / `permission.asked` / `question.asked`）能正确触达 `OpenCodeNotificationService.send`（修复"所有通知丢失" bug）

- **性能影响**（**以下为理论估算，待 spec 阶段 benchmark 验证**）：
  - **多项目 SSE 真正可用**（架构修复，**不是性能优化**）
  - **临时对象分配**：50-60 KB/s → ~5-10 KB/s（**估算节省 80-90%**）
  - **白名单比黑名单多省的部分**：25+ 个 plugin 不关心的噪音事件（`session.next.*` 子事件 / `project.updated` / `message.part.delta` 等）**全部**跳过完整 Gson 解析，**而黑名单方案只能跳过 5 个 type + 2 个泛匹配**——白名单多覆盖 20+ 个事件。**注**：`server.heartbeat` 实际**未**移出白名单（见上文"实施时调整" note），性能估算的"噪音事件计数"不含 `server.heartbeat`。
  - **GC 压力**：显著降低（YGC pause time 减少）
  - **CPU**：高频事件从 Gson 解析（量级估算 ~1μs）降到 Reader 阶段 peek（量级估算 ~0.1μs），**估算节省 80%+**
  - **多 SSE 连接资源开销**：每个连接 1 个 socket，N 个 project 不超过 10 个，可控
  - **用户感知**：多项目 SSE 真正生效（之前是 bug）；CPU/内存优化是间接收益；**通知行为**比之前更精准（只通知"不介入就 block"的事件）

- **非影响范围**：
  - 不改 opencode server（不在本仓库）
  - 不替换 SSE 客户端（继续用 LaunchDarkly EventSource，升级版本）
  - 不迁移到 Ktor Client SSE
  - 不实现 server-side event filter（需要 opencode server 改动）
  - **不解决**多 IDE 打开同 project 的"重复通知"问题（user 已确认这是合理行为）

- **待调研项**（在 spec 编写阶段需要验证）：
  - `[NEEDS INVESTIGATION]` `okhttp-eventsource` 从 `4.1.0` 升级到 `4.3.0` 的 API 兼容性（4.0.0 是破坏性重写，4.1.0→4.3.0 跨两个小版本，需确认无 breaking change 影响 `EventSource.Builder` / `BackgroundEventSource` / `BackgroundEventHandler` 的使用）
  - `[NEEDS INVESTIGATION]` LaunchDarkly EventSource 4.3.0 `streamEventData(true)` 模式在所有目标平台（macOS / Windows / Linux）下是否稳定工作
  - `[NEEDS INVESTIGATION]` `MessageEvent.getDataReader()` 在 `BackgroundEventSource` 包装下是否仍可用（源码 `BackgroundEventSource.java:233-236` 表明 handler 拿到的是 `MessageEvent` 实例本身，但需要验证 Reader 状态在异步派发后是否仍然有效）
  - `[NEEDS INVESTIGATION]` 白名单 9 个 wire-level eventType 是否完整覆盖 plugin 当前所有处理路径（通过 spec 阶段对照 `OpenCodeSSEConsumer.kt` `when (eventType)` + `isSessionDiff/isFileEdited/isFileWatcherUpdated` + `handleSessionIdle` + `BashCommandHandler` 调用点逐条验证，**特别确认 4 个砍掉的通知 case 没有遗漏的业务路径**）
  - `[NEEDS INVESTIGATION]` **user 报告的"question 通知没收到" bug 端到端排查**（spec 阶段实测触发一次 question 通知，确认 plugin 端 `OpenCodeNotificationRouter.notify` → `OpenCodeNotificationService.send` 整条链路在 `/event?directory=...` 新端点下能正确送达；新端点解决了"所有通知丢失"问题，但需要端到端验证）
  - `[NEEDS INVESTIGATION]` `OpenCodeServerManager` 从单例 `sseConsumer` 改为 `ConcurrentHashMap<Project, OpenCodeSSEConsumer>` 后，**多项目并发创建 consumer 时的线程安全**（`computeIfAbsent` 在多线程同时调用时的行为、`disposeForProject` 与 `getOrCreateConsumer` 竞态）
  - `[NEEDS INVESTIGATION]` per-project consumer 独立 watchdog 线程的开销（每个 consumer 1 个 daemon 线程，10 个 project = 10 个线程——是否有问题）
  - `[NEEDS INVESTIGATION]` opencode server `/event?directory=<projectPath>` 端点的 `WorkspaceRoutingQuery` schema 在所有目标 opencode server 版本（特别是较老版本）下是否都接受 `directory` query param（虽然 opencode server 端 schema 已声明，但**某些老版本 server 可能 schema 校验更严格**）
  - `[NEEDS INVESTIGATION]` 性能估算 benchmark 验证（proposal 声称"临时对象 50-60 KB/s → ~5-10 KB/s"和"CPU ~1μs → ~0.1μs"为理论估算，需在 spec 阶段跑 micro-benchmark 验证）
  - `[NEEDS INVESTIGATION]` **Settings UI 完全删除后，老 user `PropertiesComponent` 中已存配置的兼容性**（现有用户可能在 `opencode.event.error.enabled=true` / `opencode.event.plan_exit.enabled=true` 等已删除事件 key 上有 stored value；新代码 `OpenCodeConfig.isEventEnabled` 函数已删，这些 stored value **不影响**任何代码逻辑——**无需迁移代码**，但 spec 阶段需确认无其他隐藏引用）
  - ~~`[NEEDS INVESTIGATION]` **1s 防抖 LRU 容量选择**~~ —— **user 拍板**：复用现有 `MAX_TRACKED_SESSIONS=1000` 模式（**不会**有 1000+ session 同时活跃，内存可控 ~200KB）
