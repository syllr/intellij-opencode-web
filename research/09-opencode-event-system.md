# OpenCode 事件系统详解

**调研时间**: 2026-05-30
**调研目标**: 深入分析 opencode 事件系统，为 IDEA 插件实时集成提供技术指导

---

## 1. 事件系统架构概览

### 1.1 双层事件架构

OpenCode 采用双层事件架构：

```
┌─────────────────────────────────────────────┐
│  SyncEvent (事件源)                          │
│  - 事件溯源                                  │
│  - 持久化存储                                │
│  - 支持重放                                  │
└─────────────────┬───────────────────────────┘
                  │ 自动重新发布
                  ▼
┌─────────────────────────────────────────────┐
│  BusEvent (总线事件)                         │
│  - 实时通知                                  │
│  - 发布/订阅模式                             │
│  - SSE 传输                                 │
└─────────────────────────────────────────────┘
```

### 1.2 核心组件

| 组件        | 文件               | 功能             |
| ----------- | ------------------ | ---------------- |
| `BusEvent`  | `bus/bus-event.ts` | 事件定义和注册   |
| `Bus`       | `bus/index.ts`     | 发布/订阅服务    |
| `SyncEvent` | `sync/index.ts`    | 事件溯源和持久化 |
| `GlobalBus` | `bus/global.ts`    | 全局事件总线     |
| `Event`     | `server/event.ts`  | 服务器事件定义   |

---

## 2. 事件定义

### 2.1 BusEvent 定义

```typescript
// bus/bus-event.ts
export function define<Type extends string, Properties extends Schema.Top>(
  type: Type,
  properties: Properties,
): Definition<Type, Properties> {
  const result = { type, properties };
  registry.set(type, result);
  return result;
}
```

**使用示例**:

```typescript
// 定义一个事件
const SessionCreated = BusEvent.define(
  "session.created",
  Schema.Struct({
    sessionID: SessionID,
    info: Session.Info,
  }),
);

// 发布事件
yield * bus.publish(SessionCreated, { sessionID: "ses_xxx", info: sessionInfo });

// 订阅事件
yield *
  bus.subscribe(SessionCreated, (event) => {
    console.log("Session created:", event.properties.sessionID);
  });
```

### 2.2 SyncEvent 定义

```typescript
// sync/index.ts
export function define<
  Type extends string,
  Agg extends string,
  Schema extends EffectSchema.Top,
  BusSchema extends EffectSchema.Top = Schema,
>(input: {
  type: Type;
  version: number;
  aggregate: Agg;
  schema: Schema;
  busSchema?: BusSchema;
}): Definition<Type, Schema, BusSchema> {
  // ...
}
```

**使用示例**:

```typescript
// 定义一个同步事件
const SessionUpdated = SyncEvent.define({
  type: "session.updated",
  version: 1,
  aggregate: "sessionID",
  schema: Schema.Struct({
    sessionID: SessionID,
    info: partialSchema(Info),
  }),
  busSchema: Schema.Struct({
    sessionID: SessionID,
    info: Info,
  }),
});

// 运行事件（自动持久化 + 发布到 Bus）
yield * syncEvent.run(SessionUpdated, { sessionID: "ses_xxx", info: { title: "New Title" } });
```

---

## 3. 事件类型

### 3.1 核心事件类型

| 事件类型               | 说明           | 触发时机        |
| ---------------------- | -------------- | --------------- |
| `server.connected`     | 服务器连接     | SSE 连接建立    |
| `global.disposed`      | 全局实例释放   | 服务器关闭      |
| `session.created`      | 会话创建       | 创建新会话      |
| `session.deleted`      | 会话删除       | 删除会话        |
| `session.updated`      | 会话更新       | 更新会话属性    |
| `session.status`       | 会话状态变化   | idle/busy/retry |
| `session.idle`         | 会话空闲       | AI 处理完成     |
| `session.error`        | 会话错误       | 发生错误        |
| `message.updated`      | 消息更新       | 消息内容变化    |
| `permission.asked`     | 权限请求       | AI 请求权限     |
| `question.asked`       | 问题询问       | AI 提问         |
| `file.edited`          | 文件编辑       | 文件被修改      |
| `file.watcher.updated` | 文件监视器更新 | 文件系统变化    |

### 3.2 session.next.\* 流式事件

这些事件用于 AI 响应的流式传输：

| 事件                             | 触发时机     |
| -------------------------------- | ------------ |
| `session.next.text.started`      | 文本块开始   |
| `session.next.text.delta`        | 文本增量     |
| `session.next.text.ended`        | 文本块结束   |
| `session.next.tool.called`       | 工具调用开始 |
| `session.next.tool.success`      | 工具调用成功 |
| `session.next.tool.failed`       | 工具调用失败 |
| `session.next.reasoning.started` | 思考开始     |
| `session.next.reasoning.delta`   | 思考增量     |
| `session.next.reasoning.ended`   | 思考结束     |
| `session.next.step.started`      | 步骤开始     |
| `session.next.step.ended`        | 步骤结束     |

---

## 4. 事件订阅

### 4.1 SSE 订阅

```http
GET /global/event
Accept: text/event-stream
```

**事件格式**:

```
event: message
data: {"id":"evt_xxx","type":"session.status","properties":{"sessionID":"ses_xxx","status":{"type":"busy"}}}

event: message
data: {"id":"evt_yyy","type":"session.next.text.delta","properties":{"delta":"Hello"}}
```

### 4.2 Bus 订阅（内部使用）

```typescript
// 订阅特定事件
yield *
  bus.subscribe(SessionCreated, (event) => {
    console.log("Session created:", event.properties.sessionID);
  });

// 订阅所有事件
yield *
  bus.subscribeAll((event) => {
    console.log("Event:", event.type, event.properties);
  });
```

### 4.3 SyncEvent 订阅

```typescript
// 订阅同步事件
yield *
  syncEvent.subscribeAll((event) => {
    console.log("Sync event:", event.type, event.data);
  });
```

---

## 5. 事件处理

### 5.1 IDEA 插件事件处理

```kotlin
class OpenCodeEventConsumer(
    private val project: Project
) : BackgroundEventHandler {

    private val eventSource = AtomicReference<EventSource?>(null)
    private val logger = thisLogger()

    fun start() {
        val uri = URI.create("http://$HOST:$PORT/global/event")
        val connectStrategy = ConnectStrategy.http(uri)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)

        val esBuilder = EventSource.Builder(connectStrategy)
            .errorStrategy(ErrorStrategy.alwaysContinue())

        val bgBuilder = BackgroundEventSource.Builder(this, esBuilder)
        val bgEventSource = bgBuilder.build()

        eventSource.set(bgEventSource)
        bgEventSource.start()
    }

    override fun onMessage(event: String, messageEvent: MessageEvent) {
        val eventData = gson.fromJson(messageEvent.data, OpenCodeEvent::class.java)

        when (eventData.type) {
            "session.status" -> handleSessionStatus(eventData)
            "session.next.text.delta" -> handleTextDelta(eventData)
            "session.next.tool.called" -> handleToolCalled(eventData)
            "file.edited" -> handleFileEdited(eventData)
            "permission.asked" -> handlePermissionAsked(eventData)
            // ... 其他事件处理
        }
    }

    private fun handleSessionStatus(event: OpenCodeEvent) {
        val sessionID = event.properties["sessionID"] as? String ?: return
        val status = event.properties["status"] as? Map<*, *> ?: return
        val type = status["type"] as? String ?: return

        ApplicationManager.getApplication().invokeLater {
            when (type) {
                "idle" -> {
                    // AI 处理完成
                    showNotification("AI 处理完成", sessionID)
                }
                "busy" -> {
                    // AI 正在处理
                    updateProgressBar("AI 正在处理...")
                }
                "retry" -> {
                    // 重试中
                    val attempt = status["attempt"] as? Int ?: 0
                    updateProgressBar("重试中 ($attempt)...")
                }
            }
        }
    }

    private fun handleTextDelta(event: OpenCodeEvent) {
        val delta = event.properties["delta"] as? String ?: return
        ApplicationManager.getApplication().invokeLater {
            appendToResponse(delta)
        }
    }
}
```

### 5.2 事件重连处理

```kotlin
class OpenCodeEventConsumer : BackgroundEventHandler {

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private val reconnectDelayMs = 1000L

    override fun onOpen() {
        logger.info("[SSE] Connection opened")
        reconnectAttempts = 0
    }

    override fun onClosed() {
        logger.info("[SSE] Connection closed")
        attemptReconnect()
    }

    override fun onError(error: Throwable) {
        logger.error("[SSE] Error: ${error.message}", error)
        attemptReconnect()
    }

    private fun attemptReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            logger.error("[SSE] Max reconnect attempts reached")
            return
        }

        reconnectAttempts++
        val delay = reconnectDelayMs * reconnectAttempts

        logger.info("[SSE] Attempting reconnect in ${delay}ms (attempt $reconnectAttempts)")

        Thread {
            Thread.sleep(delay)
            start()
        }.start()
    }
}
```

---

## 6. 事件格式

### 6.1 SSE 事件格式

```
event: message
data: {"id":"evt_xxx","type":"session.status","properties":{"sessionID":"ses_xxx","status":{"type":"busy"}}}

event: message
data: {"id":"evt_yyy","type":"session.next.text.delta","properties":{"delta":"Hello, "}}
```

### 6.2 事件 JSON 结构

```json
{
  "id": "evt_xxx",
  "type": "session.status",
  "properties": {
    "sessionID": "ses_xxx",
    "status": {
      "type": "busy"
    }
  }
}
```

### 6.3 SyncEvent 格式

```json
{
  "type": "sync",
  "name": "session.updated/1",
  "id": "evt_xxx",
  "seq": 42,
  "aggregateID": "ses_xxx",
  "data": {
    "sessionID": "ses_xxx",
    "info": {
      "title": "New Title"
    }
  }
}
```

---

## 7. 事件处理最佳实践

### 7.1 事件去重

```kotlin
class EventDeduplicator {
    private val processedEvents = ConcurrentHashMap.newKeySet<String>()
    private val deduplicationWindowMs = 5000L

    fun shouldProcess(eventId: String): Boolean {
        val now = System.currentTimeMillis()

        // 清理过期事件
        processedEvents.removeIf { it.startsWith("evt_") &&
            it.substring(4).toLongOrNull()?.let { time -> now - time > deduplicationWindowMs } == true
        }

        // 检查是否已处理
        if (processedEvents.contains(eventId)) {
            return false
        }

        processedEvents.add(eventId)
        return true
    }
}
```

### 7.2 事件批处理

```kotlin
class EventBatchProcessor {
    private val eventBuffer = mutableListOf<OpenCodeEvent>()
    private val batchIntervalMs = 100L
    private var lastProcessTime = System.currentTimeMillis()

    fun addEvent(event: OpenCodeEvent) {
        synchronized(eventBuffer) {
            eventBuffer.add(event)

            val now = System.currentTimeMillis()
            if (now - lastProcessTime >= batchIntervalMs) {
                processBatch()
                lastProcessTime = now
            }
        }
    }

    private fun processBatch() {
        val batch: List<OpenCodeEvent>
        synchronized(eventBuffer) {
            batch = eventBuffer.toList()
            eventBuffer.clear()
        }

        // 批量处理事件
        batch.groupBy { it.type }.forEach { (type, events) ->
            when (type) {
                "session.next.text.delta" -> processTextDeltas(events)
                "session.next.tool.called" -> processToolCalls(events)
                // ...
            }
        }
    }
}
```

### 7.3 事件过滤

```kotlin
class EventFilter {
    private val projectDirectory: String

    fun shouldProcess(event: OpenCodeEvent): Boolean {
        // 过滤非本项目的事件
        val directory = event.properties["directory"] as? String
        if (directory != projectDirectory) {
            return false
        }

        // 过滤不需要的事件类型
        val ignoredTypes = setOf("server.heartbeat", "message.part.delta")
        if (event.type in ignoredTypes) {
            return false
        }

        return true
    }
}
```

---

## 8. 性能优化

### 8.1 事件压缩

```kotlin
class EventCompressor {
    private val textDeltas = mutableListOf<String>()
    private var lastCompressTime = System.currentTimeMillis()
    private val compressIntervalMs = 50L

    fun addTextDelta(delta: String) {
        textDeltas.add(delta)

        val now = System.currentTimeMillis()
        if (now - lastCompressTime >= compressIntervalMs) {
            compressAndProcess()
            lastCompressTime = now
        }
    }

    private fun compressAndProcess() {
        if (textDeltas.isEmpty()) return

        val compressed = textDeltas.joinToString("")
        textDeltas.clear()

        // 处理压缩后的文本
        processCompressedText(compressed)
    }
}
```

### 8.2 事件缓存

```kotlin
class EventCache(private val maxSize: Int = 1000) {
    private val cache = LinkedHashMap<String, OpenCodeEvent>(maxSize, 0.75f, true)

    fun getEvent(eventId: String): OpenCodeEvent? {
        return synchronized(cache) {
            cache[eventId]
        }
    }

    fun addEvent(event: OpenCodeEvent) {
        synchronized(cache) {
            if (cache.size >= maxSize) {
                val eldest = cache.entries.iterator().next()
                cache.remove(eldest.key)
            }
            cache[event.id] = event
        }
    }
}
```

---

## 9. 错误处理

### 9.1 事件解析错误

```kotlin
class EventHandler {
    fun handleEvent(rawData: String) {
        try {
            val event = gson.fromJson(rawData, OpenCodeEvent::class.java)
            processEvent(event)
        } catch (e: JsonSyntaxException) {
            logger.error("[Event] Failed to parse event: ${e.message}")
            logger.debug("[Event] Raw data: $rawData")
        } catch (e: Exception) {
            logger.error("[Event] Unexpected error: ${e.message}", e)
        }
    }
}
```

### 9.2 事件处理错误

```kotlin
class EventHandler {
    fun processEvent(event: OpenCodeEvent) {
        try {
            when (event.type) {
                "session.status" -> handleSessionStatus(event)
                "session.next.text.delta" -> handleTextDelta(event)
                // ...
            }
        } catch (e: Exception) {
            logger.error("[Event] Error processing ${event.type}: ${e.message}", e)
            // 不要让错误传播，避免影响其他事件处理
        }
    }
}
```

---

## 10. 测试场景

### 10.1 事件订阅测试

| 场景         | 操作                                | 预期结果                  |
| ------------ | ----------------------------------- | ------------------------- |
| 订阅会话状态 | 订阅 `session.status` 事件          | 收到 idle/busy/retry 事件 |
| 订阅文本增量 | 订阅 `session.next.text.delta` 事件 | 收到 AI 响应文本          |
| 订阅文件编辑 | 订阅 `file.edited` 事件             | 收到文件变更通知          |
| 订阅权限请求 | 订阅 `permission.asked` 事件        | 收到权限请求通知          |

### 10.2 事件重连测试

| 场景     | 操作               | 预期结果               |
| -------- | ------------------ | ---------------------- |
| 正常重连 | 关闭 SSE 连接      | 自动重连成功           |
| 延迟重连 | 服务器暂时不可用   | 延迟后重连成功         |
| 最大重试 | 服务器长时间不可用 | 达到最大重试次数后停止 |

### 10.3 事件处理测试

| 场景       | 操作             | 预期结果   |
| ---------- | ---------------- | ---------- |
| 事件去重   | 发送重复事件     | 只处理一次 |
| 事件批处理 | 快速发送多个事件 | 批量处理   |
| 事件过滤   | 发送非本项目事件 | 过滤掉     |

---

## 11. 参考资源

| 资源                 | URL                                                                                                  |
| -------------------- | ---------------------------------------------------------------------------------------------------- |
| BusEvent 源码        | /Users/yutao/Projects/opencode/packages/opencode/src/bus/bus-event.ts                                |
| Bus 源码             | /Users/yutao/Projects/opencode/packages/opencode/src/bus/index.ts                                    |
| SyncEvent 源码       | /Users/yutao/Projects/opencode/packages/opencode/src/sync/index.ts                                   |
| SSE 事件处理         | /Users/yutao/Projects/opencode/packages/opencode/src/server/event.ts                                 |
| OpenCode SSEConsumer | /Users/yutao/IdeaProjects/intellij-opencode-web/src/main/kotlin/.../listeners/OpenCodeSSEConsumer.kt |

---

## 12. 待深入调研

- [ ] 事件版本管理机制
- [ ] 事件重放的具体实现
- [ ] 多设备同步的实现细节
- [ ] 事件持久化存储格式
- [ ] 事件压缩和优化策略
