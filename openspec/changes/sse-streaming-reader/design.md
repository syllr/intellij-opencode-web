## Context

### 背景

intellij-opencode-web 插件是 JetBrains IDE 平台插件，为 opencode CLI（一个 AI 编程助手）提供 Web UI 集成。当前插件在 macOS / Windows / Linux 上运行，通过 12396 端口与 opencode server 通信。插件通过 SSE（Server-Sent Events）长连接实时接收 server 推送的事件，驱动 IDE 内嵌的 JCEF 浏览器、文件 VFS 刷新和桌面通知。

### 当前状态

- **SSE 端点**：使用 `/global/event`（全 instance 广播，无任何过滤）
- **客户端**：`com.launchdarkly:okhttp-eventsource` 4.1.0，`BackgroundEventSource` 异步模式
- **解析器**：`SSEEventParser` 适配外层 `{directory, project, workspace, payload}` 包装，提取 `payload.syncEvent.type` 等字段
- **路由**：`OpenCodeNotificationRouter` 用 `projectRegistry: ConcurrentHashMap<String, Project>` 缓存，**当前依赖 `eventDir` 匹配 project**——但 `/global/event` 端点 `eventDir` 永远是 `null`
- **服务器进程**：`OpenCodeServerManager` 是 `object` 单例，维护唯一一个 `sseConsumer: OpenCodeSSEConsumer?`
- **Settings UI**：`OpenCodeConfigurable` 注册到 IDE Settings → Tools → OpenCode 面板
- **配置**：`OpenCodeConfig` 4 个通用 var + 4 个事件类型 specific 函数 + `ALL_EVENT_TYPES` / `defaultEvents` / `defaultMessages`
- **通知路由**：`OpenCodeNotificationService.send()` 处理 `permission` / `complete` / `subagent_complete` / `error` / `question` 等通知类型，含 minDuration 过滤和 `isEventEnabled` 检查

### 三个根本问题（proposal 中已详细分析）

1. **多项目 SSE 不可用**——`OpenCodeServerManager` 单例在 project 切换时 `stop()` 旧 consumer
2. **所有通知丢失**——`/global/event` 端点 wire JSON 没有 `directory` 字段（`InstanceRef` 默认 `undefined` + RootHttpApi 不注册 `InstanceContextMiddleware`），`OpenCodeNotificationRouter.notify` 第一行 `?: return` 永远触发
3. **高频噪音 + 临时对象浪费**——100+ Hz `message.part.delta` + 25+ 个不关心事件全部走完整 Gson 解析，~50-60 KB/s 临时对象

### 约束

- **包名**：`com.shenyuanlaolarou.opencodewebui`（AGENTS.md 强制）
- **禁止静态全局可变状态**：用 `AtomicReference` / `AtomicBoolean` / `ConcurrentHashMap`（`OpenCodeSSEConsumer` companion object 中 `subagentSessionIds` / `sessionIdleFired` 是已知例外，标记后保留）
- **禁止 Regex 解析 HTTP/JSON**：HTTP 响应体走 Gson
- **Git 提交/发布**由用户显式触发
- **Type 抑制禁用**：`as Any` / `@ts-ignore` / `@ts-expect-error`
- **AGENTS.md "通知降噪三层去重"**：必须保留 subagent 追踪（即使通知层砍了）
- **多 IDE 打开同 project 的"重复通知"**：user 已确认为合理行为
- **不解决 opencode server 改动**（不在本仓库）

### 干系人

- **user**（多项目 IDE 用户）：决定通知范围（仅"不介入就 block"事件）；决定 Settings UI 走代码常量而非 UI 暴露；决定 1s 防抖为核心功能
- **插件维护者**：需要清晰可回归的实现
- **JUnit 4 + opentest4j** 测试：保护 OpenCodeSSEConsumer + SSEEventParser + ExtractSessionIdTest 不回归

## Goals / Non-Goals

**Goals:**

- **修复三个根本 bug**（多项目 SSE 不可用 / 所有通知丢失 / 高频噪音）
- **per-project SSE 真正可用**：每个 IDE project 独立 SSE connection + 独立 30s watchdog + 独立 reconnect
- **通知精准化**：仅 3 个真通知类型（`permission` / `complete` / `question`），按"不介入就 block"标准
- **subagent 完全静默**：subagent 完成**不**通知 user（user 决定）
- **Settings UI 完全删除**：4 个通用 var getter 继续工作（PropertiesComponent 持久化，老 user 配置不丢）
- **1s Session 维度防抖**：1s 内同 session + 同事件类型重复触发 → 抑制；替换现有 2s `idleLastFired` 窗口
- **白名单早退 + Gson 缓存优化**：升级 okhttp-eventsource 4.1.0 → 4.3.0，**保留** `JsonParser.parseReader` 流式 + Gson 缓存复用；通过 9 个 wire-level 白名单过滤让 80%+ 业务事件**不走**完整 Gson 反序列化，省去 50-60 KB/s 临时对象分配
- **白名单 type 过滤**：9 个 wire-level eventType（4 通知 + 5 业务），Reader 阶段 type peek → 白名单外 `close()` Reader 早退

**Non-Goals:**

- 不改 opencode server（不在本仓库）
- 不替换 SSE 客户端（继续用 LaunchDarkly EventSource，仅升级版本）
- 不迁移到 Ktor Client SSE
- 不实现 server-side event filter（需要 opencode server 改动）
- **不解决**多 IDE 打开同 project 的"重复通知"问题（user 已确认这是合理行为）
- 不实现 1s 防抖的 user 可配置（hard-code 1s window）
- **不重命名** `OpenCodeSSEConsumer` / `SSEEventParser` 等类（保留现有命名）

## Decisions

### Decision 1: SSE 端点改用 `/event?directory=<url-encoded-path>`

**Context**: 当前 `/global/event` 端点 `eventDir` 永远是 `null`（调研详见 `research/event-endpoint-schema/notes.md`），导致所有通知丢失。opencode server 提供 per-instance 端点 `/event?directory=...`，server 端按 `event.location.directory === instance.directory` 过滤。

**Rationale**:

- **直接命中**问题 2（所有通知丢失）：server 端注入 `directory` 字段，client 端 `parsed.directory` 非空
- **同时解决**问题 1（多项目 SSE 不可用）：每个 client connection 独立 directory 过滤
- **wire 格式简化**：根据 `research/event-endpoint-schema/notes.md`，`/event` 端点直出 `{id, type, properties}`，**无**外层 `{directory, project, workspace, payload}` 包装；`/global/event` 端点**有**外层包装。新端点 wire 格式**更简洁**，解析路径**更短**
- **plugin 端可删除** eventDir 比较逻辑（`OpenCodeSSEConsumer.kt:236-253`）——server 已过滤

**Alternatives Considered**:

- **继续用 `/global/event` + client 端自行过滤**：需保留 `parsed.directory` 字段解析 + eventDir 比较逻辑；wire 格式更复杂（外层包装）；性能开销更高
- **改用 WebSocket**：完全换 SSE 客户端；改动巨大；不解决根本问题
- **实现 server-side event filter**：需要改 opencode server（不在本仓库）

**Wire 格式差异**（`research/event-endpoint-schema/notes.md`）:

```
/event?directory=/path              → data: {"id":"evt_xxx","type":"session.idle","properties":{...}}
/global/event                       → data: {"directory":"/path","project":"...","workspace":"...","payload":{"id":"...","type":"...","properties":{...}}}
```

`directory` query param 是 **URL-encoded 普通路径**（`encodeURIComponent`），不是 base64。

### Decision 2: 升级 okhttp-eventsource 4.1.0 → 4.3.0（**0 源码修改**）

**Context**: 调研详见 `research/okhttp-eventsource-compat/notes.md`。

**Rationale**:

- 4.2.0/4.3.0 之间**无 breaking change**：仅 OkHttp 4.12.0 依赖 bump
- 4.1.0 → 4.2.0 唯一变化：新增 `MessageEvent.getHeaders()`（纯加法）
- 当前项目使用的所有 API（`BackgroundEventHandler` / `BackgroundEventSource.Builder` / `EventSource.Builder` / `ConnectStrategy.http()` / `ErrorStrategy`）在 4.3.0 签名完全一致
- **升级只需改 `gradle/libs.versions.toml` 一行**（`4.1.0` → `4.3.0`），**0 源码修改**

**Alternatives Considered**:

- **不升级**：无法用 `MessageEvent.getDataReader()` 流式优化
- **升级到 4.0.0**：4.0.0 是破坏性重写（同步 I/O 模型、`background` 包、Builder API 重大变化），改动巨大
- **替换为 Ktor Client SSE**：完全换客户端，重写所有 SSE 处理；不推荐

**为什么不直接 4.1.0 用 `getDataReader()`**：`getDataReader()` 4.1.0 已存在，但 `gradle/libs.versions.toml` 升级是项目规范要求（4.3.0 是最新稳定版）。

### Decision 3: `streamEventData(false)` + `JsonParser.parseReader` 内部流式 + 白名单早退

**Context**: 当前 `OpenCodeSSEConsumer.onMessage` 需要解析 SSE 事件。100Hz `message.part.delta` 累计 50-60 KB/s 临时对象分配（~300 字节 JSON String + ~200-300 字节 JsonObject 树）。

**F2/F3 实际验证结论**（2026-06-04 实测）:

- okhttp-eventsource 4.3.0 `streamEventData(true)` **不**是"真**正**流**式** socket 读取——**只**是 API **标**记**：`MessageEvent` **接**收**时**已**把**整**个** JSON **全**读**到** `data` 字段；`streamEventData(true)` **只**是把 `data` **设**置**为** null，`getDataReader()` **懒**加**载** `new StringReader(data)`（**当** data **是** null **时** wrap StringReader("")—— **空** reader，readText() **永**远** length=0，**这**是** F2/F3 **实**际**验**证**得**出**的** bug**）
- 反**编**译\*\* `MessageEvent.class` （bytecode）：`getDataReader()` 内部 `if (dataReader == null) dataReader = new StringReader(data)`，`isStreamingData()` = `data == null`
- 真**正**减** GC** **不**靠** `streamEventData(true)`，**靠**白**名**单**早**退** + Gson **缓**存**复**用

**Rationale**:

- **`streamEventData(false)`**：直接用 `messageEvent.data` String，**避**免** `streamEventData(true)` 模式**下** reader **空**的** bug**（**实**测**有**问**题\*\*）
- **`JsonParser.parseReader(reader)`** 内**部**真**正**流**式**读**取** JSON（**不**是**全**读**到** String **再** parse）—— `byteInputStream.bufferedReader()` 创**建**一个** reader，JsonParser **按**需**读**取**
- **白**名**单**早**退**：9 个 wire-level eventType 白**名**单**外**的**事件**走** `early-return`，**不**创**建** Gson `Map<String, Any>`（**省** 50-60 KB/s 临**时**对**象\*\*）
- **handler 线程安全**：`streamEventData(false)` + `messageEvent.data` String 在 `BackgroundEventSource` **异**步**派**发**中**完**全**安**全**（String **不**会**被**流**线**程**取**消\*\*）

**实现细节**:

```kotlin
EventSource.Builder(connectStrategy)
    .errorStrategy(ErrorStrategy.alwaysContinue())
    .streamEventData(false)  // 关键:F2/F3 实测 streamEventData(true) 模式下 reader 永远空
    .build()
```

```kotlin
override fun onMessage(event: String, messageEvent: MessageEvent) {
    val data = messageEvent.data ?: return  // String, 已读完的 JSON
    val parsed = SSEEventParser.parse(event, data)  // 内部用 JsonParser.parseReader 真正流式解析
    ...
}
```

```kotlin
// SSEEventParser.parse(String) 重载 - 内部转 Reader 调 parse(Reader)
fun parse(eventType: String, text: String): ParsedSSEEvent =
    parse(eventType, text.byteInputStream(Charsets.UTF_8).bufferedReader())

// SSEEventParser.parse(Reader) - 真正流式 + 白名单早退
fun parse(eventType: String, reader: Reader): ParsedSSEEvent {
    val jsonElement = JsonParser.parseReader(reader)  // 流式按需读
    if (jsonElement !is JsonObject) return ...
    val type = jsonElement.get("type")?.asString
    if (type == null || type !in ALLOW_PARSE_EVENT_TYPES) {
        return ParsedSSEEvent(eventType = eventType, parsedMap = null)  // 早退,不创建 Map
    }
    val parsedMap: Map<*, *> = gson.fromJson(jsonElement, Map::class.java)  // 仅白名单内
    return ParsedSSEEvent(eventType = eventType, parsedMap = parsedMap)
}
```

**Alternatives Considered**:

- **`streamEventData(true)` + `getDataReader()`**：F2/F3 **实**测**有** bug（reader **永**远** length=0）—— **不**采**用\*\*
- **用 `MessageEvent.getData()` InputStream**：API 4.3.0 **不**暴**露** getData()，**只**有 getDataReader() —— **不**可**用**
- **`streamEventData(false)` + `JsonParser.parseString(text)`**：**完**全**失**去**流**式**优**势**（text 已 String 化）—— 改用 `parseReader` 内**部**转 reader **流**式**
- **白名单早退优化**：**采**用**—— 100+ Hz `message.part.delta` 跳过 Gson，**省\*\* 50-60 KB/s

### Decision 4: 9 个 wire-level eventType 白名单

**Context**: opencode server 通过 SSE 推送 ≥37 个 BusEvent + 26 个 SyncEvent + 4 个内部事件（TUI）。plugin 实际只关心 9 个（4 通知 + 5 业务）。

**Rationale**（基于 `research/sse-event-types/notes.md`）:

- 9 个 type 全部在 SDK `types.gen.ts` Event 联合类型中**确认存在**（`permission.asked` / `question.asked` 不在 SDK 但在 binary 中**确认存在**）
- 9 个 type 全部以 **Direct BusEvent 格式**到达（`{type, properties}`），**无** SyncEvent 包装
- 100+ Hz `message.part.delta` 在白名单外 → Reader 阶段 `close()` 早退
- 25+ 个不关心事件（`server.heartbeat` / `session.next.*` 子事件 / `project.updated` 等）→ 全部跳过完整 Gson 解析

**白名单（9 个）**:

| 类别   | eventType                    | 用途                                           | SDK 定义                     |
| ------ | ---------------------------- | ---------------------------------------------- | ---------------------------- |
| 通知 4 | `session.idle`               | 通知 `complete`                                | `EventSessionIdle` ✅        |
| 通知 4 | `session.status` (type=idle) | 通知 `complete` (fallback)                     | `EventSessionStatus` ✅      |
| 通知 4 | `permission.asked`           | 通知 `permission`                              | ❌ 不在 SDK（binary 确认）   |
| 通知 4 | `question.asked`             | 通知 `question`                                | ❌ 不在 SDK（binary 确认）   |
| 业务 5 | `session.created`            | `subagentSessionIds.add`（仅追踪，**不**通知） | `EventSessionCreated` ✅     |
| 业务 5 | `message.part.updated`       | `BashCommandHandler`（bash 写文件 VFS 刷新）   | `EventMessagePartUpdated` ✅ |
| 业务 5 | `file.edited`                | `FullRefreshCoordinator.request()`             | `EventFileEdited` ✅         |
| 业务 5 | `file.watcher.updated`       | `FullRefreshCoordinator.request()`             | `EventFileWatcherUpdated` ✅ |
| 业务 5 | `session.diff`               | `FullRefreshCoordinator.request()`             | `EventSessionDiff` ✅        |

**降级路径**（白名单外）:

- **Reader 阶段 type peek** → 拿到 `type` → 查白名单 → 不在白名单 → `close()` Reader 早退
- 旧 `SKIP_PARSE_EVENT_TYPES = setOf("message.part.delta")` **删除**（被白名单机制吸收）

**Alternatives Considered**:

- **黑名单方案**（仅跳过高频噪音）：只能跳过 5 个 type + 2 个泛匹配，节省 50%；白名单方案**全覆盖** 25+ 个不关心事件，节省 80-90%
- **解析所有事件再过滤**：临时对象浪费严重
- **用 server 端过滤器**：需要 opencode server 改动

### Decision 5: `(sessionID, eventType)` 维度 1s 防抖

**Context**: opencode server 在某些边缘情况下**可能**在 1s 内**多次**发同一类型事件（如 `session.status(idle)` 100ms 后又来 `session.idle`；LLM 一次发多个子权限请求触发多次 `permission.asked`）。

**Rationale**:

- 现有 `OpenCodeSSEConsumer.idleLastFired` 2s 窗口**只**对 idle 生效，**不**通用
- **位置**：放在 `OpenCodeNotificationService.send()` 中（**通用**防抖，所有通知事件生效），**不**分散在每个 consumer
- **Key 维度**：`(sessionID, eventType)`（user 拍板 A 方案）——permission 后立即 question **都**通知 ✅
- **替换**现有 2s `idleLastFired` 窗口，统一为 1s 通用防抖
- **LRU 容量 1000**（user 拍板）：复用现有 `MAX_TRACKED_SESSIONS=1000` 模式；1000 sessions × 4 event types = 4000 entries，估算 ~200KB 内存，**不会**有 1000+ session 同时活跃

**实现细节**:

```kotlin
object OpenCodeNotificationService {
    private val lastNotificationFired = Collections.synchronizedMap(
        object : LinkedHashMap<Pair<String, String>, Long>(1000, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Pair<String, String>, Long>) = size > 1000
        }
    )
    private const val NOTIFICATION_DEDUP_WINDOW_MS = 1000L

    fun send(eventType: String, properties: Map<*, *>?, project: Project) {
        val sessionID = extractSessionID(properties) ?: return
        val key = sessionID to eventType
        val now = System.currentTimeMillis()
        val last = lastNotificationFired.put(key, now)
        if (last != null && now - last < NOTIFICATION_DEDUP_WINDOW_MS) {
            return  // 1s 内同 session 同事件抑制
        }
        // ... 实际发送通知
    }
}
```

**删除** `OpenCodeSSEConsumer.handleSessionIdle` 中的 `idleLastFired` 2s 窗口（`OpenCodeSSEConsumer.kt:271-275`）——集中防抖逻辑到一处。

**Alternatives Considered**:

- **仅 `sessionID` 维度**（B 方案）：permission 后立即 question **会**抑制 question 通知，**丢真实通知**——不推荐
- **per-event-type 维度**（不带 sessionID）：不同 session 同一事件会互相抑制——不推荐
- **保留 2s 窗口**（不改为 1s）：现有 2s 是 idle 专用窗口，不通用；改 1s 更符合防抖的"短重复合并"语义

### Decision 6: `OpenCodeServerManager` 单例 → `ConcurrentHashMap<Project, OpenCodeSSEConsumer>`

**Context**: `OpenCodeServerManager` 是 `object` 单例（`OpenCodeServerManager.kt:20`），维护唯一一个 `sseConsumer: OpenCodeSSEConsumer?`。`ensureSSEConsumer(project)` 在 project 切换时**会 stop 旧 consumer**。

**Rationale**:

- **多项目并发场景**：每个 IDE project 独立 SSE connection
- **`computeIfAbsent` 线程安全**：JDK 标准 `ConcurrentHashMap.computeIfAbsent` 保证多线程同时调用时**只有一个** consumer 被创建
- **`gracefulShutdown` 改为停止所有 consumer**：遍历 `Map.values()` 逐个 stop
- **保留** `disposeForProject(project)` 单项目清理逻辑

**实现细节**:

```kotlin
object OpenCodeServerManager {
    private val consumers = ConcurrentHashMap<Project, OpenCodeSSEConsumer>()

    fun getOrCreateConsumer(project: Project): OpenCodeSSEConsumer {
        return consumers.computeIfAbsent(project) { p ->
            OpenCodeSSEConsumer(p).also { it.start() }
        }
    }

    fun disposeForProject(project: Project) {
        consumers.remove(project)?.stop()
    }

    fun gracefulShutdown() {
        consumers.values.forEach { it.stop() }
        consumers.clear()
    }
}
```

**Alternatives Considered**:

- **`synchronized` 块 + 普通 Map**：性能差；多线程阻塞
- **`CopyOnWriteArrayList<OpenCodeSSEConsumer>`**：按 project 索引需要遍历；不适合
- **每个 project 独立 service**：拆分太细；增加管理复杂度

### Decision 7: `OpenCodeSSEConsumer.handleSessionIdle` 改为 subagent 完全跳过

**Context**: 原设计 `subagent` 走 `dispatchNotification("subagent_complete")`（默认 OFF）。user 不想要 subagent 通知。

**Rationale**:

- `subagentSessionIds` 追踪**保留**（用于 `handleSessionIdle` 区分 subagent/parent）
- subagent 完成时 `handleSessionIdle` **直接 return**，**不 dispatch 任何通知**
- **`subagent_complete` 通知类型完全砍**：从 `OpenCodeConfig.ALL_EVENT_TYPES` / `defaultEvents` / `defaultMessages` 移除；Settings UI（已删）不显示

**删除** `sessionIdleFired` 集合（原 `message.updated(role=user)` 重置信号）：

- **原因**：user 决定砍 `message.updated` 通知 + `message.updated` 通知默认 OFF
- **后果**：同 session 多次发消息 → 每次 session.idle（间隔 > 2s）**都**通知（更符合"user 发新消息 = 应该通知"直觉）
- **替代方案** `session.status(busy)` 做重置信号：**不采纳**——会引入新事件 type 到白名单，违反简化原则；2s 窗口足够覆盖高频重复场景

**实现细节**:

```kotlin
private fun handleSessionIdle(parsed: ParsedSSEEvent, eventDir: String?) {
    val sessionID = parsed.extractSessionID() ?: return
    if (sessionID in subagentSessionIds) {
        return  // subagent 完全跳过，不 dispatch 任何通知
    }
    // 1s Session 维度防抖在 OpenCodeNotificationService.send() 集中处理
    // 此处不再保留 idleLastFired 2s 窗口（已删除，见 Decision 5）
    dispatchNotification("complete", parsedMap, eventDir)
}
```

**关键变更**：原 `OpenCodeSSEConsumer.idleLastFired` 2s 窗口 + `idleDedupWindowMs` 常量**完全删除**（`OpenCodeSSEConsumer.kt:271-275`）——1s 防抖**集中**到 `OpenCodeNotificationService.send()` 一处（`(sessionID, eventType)` 维度），避免重复防抖逻辑分散。

**Alternatives Considered**:

- **保留 subagent_complete 通知类型**：user 不需要 subagent 通知
- **删除 subagentSessionIds 追踪**：subagent 完成会**误触发** complete 通知——违反 user 需求
- **`session.status(busy)` 重置信号**：增加白名单条目 + 复杂度

### Decision 8: Settings UI 完全删除 + `OpenCodeConfig.kt` 简化

**Context**: user 决定事件类型已 hard-code 为 3 种，**不再让 user 配置单个事件开关 / 模板**。4 个通用 var getter（`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`）功能保留，但**改用** `OpenCodeConfig.kt`（专门的配置文件的代码）承载。

**Rationale**:

- **事件类型 specific 配置（`isEventEnabled` / `getMessageTemplate` 等）已无意义**——事件已 hard-code 在代码里
- **4 个通用 var getter 保留**（PropertiesComponent 持久化）——老 user 之前在 UI 设置的值**继续生效**
- **`OpenCodeConfigurable.kt` 整个文件删除**（-114 行）
- **`plugin.xml` `<applicationConfigurable>` 注册移除**（-4 行）
- **`OpenCodeNotificationService.formatMessage` 改 `when` 分支 hard-code 3 个模板**（"权限申请: {sessionTitle}" / "回答完成: {sessionTitle}" / "询问用户: {sessionTitle}"）

**Alternatives Considered**:

- **保留 Settings UI 砍开关**：user 已决定砍整个 UI
- **用 PersistentStateComponent 替代 PropertiesComponent**：复杂度增加；现有 PropertiesComponent 工作正常
- **专门 properties 文件**（`opencode.properties` + `Properties` 类加载）：增加配置文件 + 加载代码；Kotlin object 已是"专门的配置文件的代码"

## Risks / Trade-offs

| 风险                                                                                                                                                                                                                                                                                                                                       | 缓解措施                                                                                                                                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **API 兼容性问题**：okhttp-eventsource 4.1.0→4.3.0 升级可能引入未察觉的行为变化                                                                                                                                                                                                                                                            | spec 阶段跑 `./gradlew check` 跑全部单测 + 集成测试；4.1.0/4.2.0/4.3.0 之间无 breaking change（已验证）                                                                |
| **`streamEventData(true)` 模式下 reader 永远空**（F2/F3 实测，266 次 onMessage 全部 readText() length=0）：okhttp-eventsource 4.3.0 `MessageEvent.getDataReader()` 内部 `if (dataReader == null) dataReader = new StringReader(data)`，当 `data` 字段被 `streamEventData(true)` 置 null 时，wrap 的是空 StringReader，**不是**真 socket 流 | **改用** `streamEventData(false)` + `messageEvent.data` String，**用** `JsonParser.parseReader(byteInputStream.bufferedReader())` **内**部**真**正**流**式**读**取\*\* |
| **`/event?directory=...` 端点跨版本兼容**：老版本 opencode server（< 1.0.18）可能不支持此端点                                                                                                                                                                                                                                              | spec 阶段验证；plugin 启动时检测 server 版本，fallback 到 `/global/event`（**可选**，当前未实现）                                                                      |
| **`computeIfAbsent` 多线程竞态**：多个 project 同时创建 consumer 时可能 race                                                                                                                                                                                                                                                               | JDK `ConcurrentHashMap.computeIfAbsent` 保证原子性；spec 阶段跑多项目并发测试                                                                                          |
| **per-project watchdog 线程开销**：每个 consumer 1 个 daemon thread，10 个 project = 10 个线程                                                                                                                                                                                                                                             | daemon 线程不阻塞 IDE 关闭；线程数可控（10 个 < IDE 线程池）；spec 阶段实测                                                                                            |
| **9 个白名单 type 漏过未来新增事件**：opencode server 端可能新增 plugin 关心的 type                                                                                                                                                                                                                                                        | 白名单机制**不**影响新 type 到达——只是**默认**不解析；如发现新 type 需 plugin 处理，spec 阶段更新白名单                                                                |
| **`permission.asked` / `question.asked` 不在 SDK 类型**：binary 确认存在但 SDK 未导出                                                                                                                                                                                                                                                      | 解析时按现有 `properties` 字段处理；如未来 SDK 添加类型定义，更新 fallback                                                                                             |
| **老 user PropertiesComponent 已存配置不丢**：`opencode.event.error.enabled=true` 等已删除事件 key 仍存在                                                                                                                                                                                                                                  | stored value **不影响**任何代码逻辑（`isEventEnabled` 函数已删）；spec 阶段确认无其他隐藏引用                                                                          |
| **`OpenCodeConfigurable` 类删除后被其他代码引用**                                                                                                                                                                                                                                                                                          | 已 grep 确认 0 引用；spec 阶段 `./gradlew build` 全量编译验证                                                                                                          |
| **State machine 重构后 user 多次发消息通知更频繁**：从"不通知"变为"每次通知"                                                                                                                                                                                                                                                               | user 决定接受；2s 窗口仍防高频重复                                                                                                                                     |
| **多个 IDE 进程打开同 project 的"重复通知"**                                                                                                                                                                                                                                                                                               | user 已确认这是合理行为；不动                                                                                                                                          |

## Migration Plan

### 部署步骤

**顺序改动**（每个步骤**独立可测试**）:

1. **层 4：1s Session 维度防抖**（**最简单**先行）
   - `OpenCodeNotificationService.send()` 加 `lastNotificationFired` + dedup 检查
   - `OpenCodeSSEConsumer` 删 `idleLastFired` 2s 窗口
   - 测试：同 session 同事件 1s 内触发 → 仅首次通知

2. **层 1：Per-Project SSE Connection + Endpoint 切换**
   - `OpenCodeServerManager` 单例 → `ConcurrentHashMap<Project, OpenCodeSSEConsumer>`
   - `OpenCodeSSEConsumer.startSseConnection` 改 URL + 启用 `streamEventData(true)`
   - `OpenCodeSSEConsumer.onMessage` 删 eventDir 比较逻辑
   - `OpenCodeNotificationRouter.notify` 简化（直接用 `OpenCodeSSEConsumer` 构造时传入的 `project`）
   - 测试：2 个 project 同时打开，验证两个 consumer 都收到事件

3. **层 2：流式 Reader + 4.3.0 升级**
   - `gradle/libs.versions.toml` `okhttpEventsource` 4.1.0 → 4.3.0
   - `OpenCodeSSEConsumer.onMessage` 改用 `messageEvent.getDataReader().use { }`
   - 测试：高频 `message.part.delta` 事件下 CPU/内存占用降低

4. **层 3：Type 白名单**（**最后**，因为依赖前 3 层的 SSE 解析）
   - `SSEEventParser` 重写适配新 wire 格式（无外层包装）
   - `BashCommandHandler` / `OpenCodeNotificationService` / `OpenCodeSSEConsumer` 适配 `parsedMap` 无嵌套访问
   - 新增 `ALLOW_PARSE_EVENT_TYPES` 集合
   - Reader 阶段 type peek → 白名单外 `close()` 早退
   - 测试：高频 `message.part.delta` 走白名单外快速路径（debug log 验证）

5. **白名单事件处理重构**
   - `OpenCodeSSEConsumer.kt` `when (eventType)` 砍 4 个 case + `session.deleted` 删除 + `session.created` 简化为只追踪 subagent
   - `handleSessionIdle` 简化（subagent 改为直接 return）
   - `OpenCodeConfig.kt` 删 4 个事件类型 specific 函数 + `ALL_EVENT_TYPES` / `defaultEvents` / `defaultMessages`
   - `OpenCodeNotificationService.kt` 改 `formatMessage` 模板 hard-code + 删 `isEventEnabled` 检查 + 删 `subagent_complete` minDuration 检查
   - 测试：3 个真通知类型 + 5 个业务事件正确处理

6. **Settings UI 完全删除**（**最后**）
   - 删 `OpenCodeConfigurable.kt`（-114 行）
   - `plugin.xml` 移除 `<applicationConfigurable>` 注册（-4 行）
   - 测试：plugin 启动无 `OpenCodeConfigurable` 引用错误；4 个 var getter 仍工作

### 回滚策略

- **单步回滚**：每个层（层 1 / 层 2 / 层 3 / 层 4）独立提交；如某层失败可 revert 该层
- **完整回滚**：revert 整个 branch（plugin 没有发布版本，回滚成本低）
- **配置迁移**：无（PropertiesComponent 持久化，4 个 var getter 兼容老 user 配置）

### 不需要迁移的项

- PropertiesComponent 中已存配置（4 个通用 var getter 继续工作）
- 外部 `SSEEventParser` 公开 API（签名不变）
- 外部 `OpenCodeServerManager` 公开 API（签名不变）

## Open Questions

**`sse-event-types` 调研已解决**：

- ✅ 9 个白名单 type 全部确认存在（`permission.asked` / `question.asked` 在 binary 确认）
- ✅ Wire 格式 Direct BusEvent（无 SyncEvent 包装）
- ✅ 版本后缀仅在 SyncEvent database 中存在，SSE 直发无后缀

**`okhttp-eventsource-compat` 调研已解决**：

- ✅ 4.1.0 → 4.3.0 无 breaking change
- ✅ `getDataReader()` 完全可用
- ✅ Streaming 模式在 `BackgroundEventSource` 异步派发中**必须**在 `onMessage()` 内消费

**`event-endpoint-schema` 调研已解决**：

- ✅ `/event?directory=...` 端点直出 `{id, type, properties}`，无外层包装
- ✅ `directory` query param 是 URL-encoded 普通路径
- ✅ 1.0.18+ 版本支持此端点

**仍需 spec 阶段验证**：

- `[NEEDS INVESTIGATION]` 多项目并发 `computeIfAbsent` 线程安全（spec 阶段用 `CountDownLatch` 模拟 10 project 同时创建）
- `[NEEDS INVESTIGATION]` per-project watchdog 线程在 10 project 并发下 CPU 占用（spec 阶段用 JFR 实测）
- `[NEEDS INVESTIGATION]` `/event?directory=...` 端点在所有目标 opencode server 版本（特别是 1.0.18 老版本）下都接受 `directory` query param（spec 阶段构造老版本 server 集成测试）
- `[NEEDS INVESTIGATION]` 性能估算 benchmark 验证（理论 50-60 KB/s → ~5-10 KB/s，spec 阶段用 JFR 实测）
- `[NEEDS INVESTIGATION]` `OpenCodeConfigurable` 删除后无其他引用（spec 阶段 `grep` 全文确认）
- `[NEEDS INVESTIGATION]` 老 user `PropertiesComponent` 中 `opencode.event.error.enabled=true` 等已删除事件 key 不被任何代码读取（spec 阶段 `grep` 全文确认）

## Post-Implementation Findings

### omo 1.16.0+ SSE 端行为变化（2026-06-05 实测）

**Context**: 11 个 task 已实现并 commit（`a5eafc4`）后，在 omo 1.16.0 环境下实测 SSE 端点行为，**发现 omo 升级改变了 SSE 流的事件投递策略**。

**关键发现**:

- **omo 1.16.0 SSE 端** `/event?directory=...` **只**下发 `server.connected` 事件；业务事件（`session.idle` / `session.status` / `permission.asked` / `question.asked` / `session.created` / `message.part.updated` / `file.edited` / `file.watcher.updated` / `session.diff`）**全部不通过 SSE 流投递**
- `curl http://127.0.0.1:12396/event?directory=helloworld` 3 秒内**仅**收到 1 条 `server.connected`，无其他事件（即使 helloworld 主 session 在跑 + 派了 explore subagent）
- omo 内部 bus 仍**正常 publish** `session.idle` 等事件（opencode log `service=bus type=session.idle publishing` 大量出现），但**仅用于 omo Sisyphus 内部 system-reminder 通知**，**不**通过 SSE 暴露给客户端
- 6 小时前（omo 1.15.13 时期）IDEA 插件 SSE 端**确实**收到过 `session.idle` 事件（1 条历史证据），所以**这是 omo 1.16.0 升级后引入的行为变化**

**对本 change 11 个 task 的影响**:

| 实现                                                                                                  | 影响                                      |
| ----------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| `OpenCodeSSEConsumer.handleSessionIdle`                                                               | 永远不被调用（`session.idle` 不通过 SSE） |
| `OpenCodeSSEConsumer.onMessage` 的 `when (eventType)` 分支                                            | 9 个白名单 type 全部无事件可路由          |
| `OpenCodeNotificationService.send()`                                                                  | 0 次调用（`NOTIFY-PROBE` 实测 0 条）      |
| `subagentSessionIds` 追踪                                                                             | 永远为空（无 `session.created` 事件）     |
| 1s Session 维度防抖                                                                                   | 永远不触发                                |
| 4 个通用 var getter（`notificationEnabled` / `showProjectName` / `showSessionTitle` / `minDuration`） | 仍正确工作（代码层防御）                  |
| 多项目 SSE 隔离（`ConcurrentHashMap<Project, OpenCodeSSEConsumer>`）                                  | 仍正确工作（多 IDE 场景下真用得到）       |
| Settings UI 删除 + PropertiesComponent 兼容                                                           | 仍正确工作                                |

**结论**: 11 个 task 的**实现正确性不变**（防御性代码对老 omo 版本仍有效），但**实际生效路径在 omo 1.16.0+ 上是空的**。omo 1.16.0 自身已经"天然 always silent"（不通过 SSE 发业务事件）→ user 感知到的"subagent 不通知"实际由 omo 1.16.0 保证，**不**依赖本插件的 11 个 task。

**对老 omo 版本（< 1.16.0）的价值**: 如果 user 降级 omo 到 1.15.13 或更早，11 个 task 仍按设计生效（`session.idle` 通过 SSE 到达 → `handleSessionIdle` 区分 subagent/parent → subagent 直接 return → parent 派发 `complete` 通知 + 1s 防抖）。

**对 omo 未来版本的建议**: 如果 omo 后续**恢复**通过 SSE 发业务事件，本插件无需任何代码改动即可正常工作（防御性代码自动激活）。如果 omo **改用其他机制**（如 WebSocket），需要新增对应 transport adapter。

**临时诊断埋点清理**（本 change 期间添加，已全部移除）:

- `OpenCodeNotificationService.send()` 中 9 个 `[NOTIFY-PROBE]` 日志 + 死变量（`appActive` / `bodyHasSubagent` / `titleHasSubagent` / `entrySessionID`）
- `SSEEventParser.parse()` 中 2 个 `[F2/F3]` 日志 + `parse(eventType, text)` 诊断重载
- `OpenCodeServerManager.getOrCreateConsumer` / `disposeForProject` 中 3 个 `[F2/F3]` 日志
- 编译验证：`./gradlew compileKotlin` BUILD SUCCESSFUL + `./gradlew test` BUILD SUCCESSFUL
