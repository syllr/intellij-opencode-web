# OpenCode Session 和 Message 系统详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供 Session 和 Message 系统技术指导

---

## 1. Session 系统架构

### 1.1 核心组件

| 组件                       | 文件                   | 说明                     |
| -------------------------- | ---------------------- | ------------------------ |
| `Session.Service`          | `session/session.ts`   | Session CRUD + 事件      |
| `SessionStatus.Service`    | `session/status.ts`    | idle/busy/retry 状态管理 |
| `SessionRunState.Service`  | `session/run-state.ts` | Runner 管理              |
| `SessionPrompt.Service`    | `session/prompt.ts`    | 消息构建和 Agent 循环    |
| `SessionProcessor.Service` | `session/processor.ts` | 流式事件处理             |

### 1.2 Session 数据模型

```typescript
// session/session.ts
export const Info = Schema.Struct({
  id: SessionID,                    // 单调递增 ID
  slug: Schema.String,              // URL 友好标识
  projectID: ProjectID,             // 所属项目
  workspaceID: WorkspaceID?,        // 工作空间（可选）
  directory: Schema.String,         // 工作目录
  path: Schema.String?,             // 相对路径
  parentID: SessionID?,             // 父 Session（fork 时）
  title: Schema.String,             // 显示标题
  agent: Schema.String?,            // 当前 Agent
  model: Model?,                    // 当前模型
  summary: Summary?,                // 变更摘要
  cost: Schema.Finite,              // 累计成本
  tokens: Tokens,                   // Token 统计
  time: Time,                        // 时间戳
  permission: Permission.Ruleset?,   // 权限规则
  revert: Revert?,                  // 回滚信息
})
```

---

## 2. Session 生命周期

### 2.1 创建 Session

```typescript
// session.ts
const createNext = Effect.fn("Session.createNext")(function* (input: {
  id?: SessionID;
  title?: string;
  agent?: string;
  model?: Model;
  parentID?: SessionID;
  directory: string;
}) {
  const result: Info = {
    id: SessionID.descending(input.id),
    slug: Slug.create(),
    projectID: ctx.project.id,
    directory: input.directory,
    title: input.title ?? createDefaultTitle(!!input.parentID),
    agent: input.agent,
    model: input.model,
    cost: 0,
    tokens: EmptyTokens,
    time: { created: Date.now(), updated: Date.now() },
  };

  // 发布 sync 事件
  yield* sync.run(Event.Created, { sessionID: result.id, info: result });

  return result;
});
```

### 2.2 Fork Session（分支创建）

```typescript
// session.ts
const fork = Effect.fn("Session.fork")(function* (input: {
  sessionID: SessionID;
  messageID?: MessageID;
}) {
  const original = yield* get(input.sessionID);
  const title = getForkedTitle(original.title);

  // 创建新 Session
  const session = yield* createNext({ title, workspaceID: original.workspaceID });

  // 复制消息直到指定 messageID
  const msgs = yield* messages({ sessionID: input.sessionID });
  const idMap = new Map<string, MessageID>();

  for (const msg of msgs) {
    if (input.messageID && msg.info.id >= input.messageID) break;
    const newID = MessageID.ascending();
    idMap.set(msg.info.id, newID);

    // 克隆消息
    yield* updateMessage({
      ...msg.info,
      sessionID: session.id,
      id: newID,
      parentID:
        msg.info.role === "assistant" && msg.info.parentID
          ? idMap.get(msg.info.parentID)
          : undefined,
    });
  }
  return session;
});
```

### 2.3 删除 Session（级联删除子 Session）

```typescript
// session.ts
const remove = Effect.fnUntraced(function* (sessionID: SessionID) {
  const session = yield* get(sessionID);

  // 取消关联的后台作业
  yield* cancelBackgroundJobs(background, sessionID);

  // 递归删除子 Session
  const kids = yield* children(sessionID);
  for (const child of kids) {
    yield* remove(child.id);
  }

  // 发布删除事件
  yield* sync.run(Event.Deleted, { sessionID, info: session });
  yield* sync.remove(sessionID);
});
```

---

## 3. Message 生命周期

### 3.1 消息类型层次

```
Message
├── User (role: "user")
│   ├── parts: TextPart[]
│   ├── parts: FilePart[]
│   ├── parts: SubtaskPart[]
│   └── parts: CompactionPart[]
│
└── Assistant (role: "assistant")
    ├── parts: TextPart (流式文本)
    ├── parts: ReasoningPart (思考过程)
    ├── parts: ToolPart (工具调用)
    ├── parts: StepStartPart (步骤开始)
    ├── parts: StepFinishPart (步骤完成)
    ├── parts: PatchPart (文件变更)
    └── parts: RetryPart (重试记录)
```

### 3.2 创建 User Message

```typescript
// prompt.ts
const createUserMessage = Effect.fn("SessionPrompt.createUserMessage")(function* (
  input: PromptInput,
) {
  const info: MessageV2.User = {
    id: input.messageID ?? MessageID.ascending(),
    role: "user",
    sessionID: input.sessionID,
    time: { created: Date.now() },
    agent: ag.name,
    model: { providerID, modelID, variant },
  };

  // 解析 parts（文件读取、MCP 资源等）
  const resolvePart = function* (part) {
    if (part.type === "file") {
      // 处理 file:// URL，读取文件内容
    }
    return [{ ...part, messageID: info.id, sessionID: input.sessionID }];
  };

  const resolvedParts = yield* Effect.forEach(input.parts, resolvePart);

  // 保存到数据库
  yield* sessions.updateMessage(info);
  for (const part of resolvedParts) yield* sessions.updatePart(part);

  return { info, parts: resolvedParts };
});
```

### 3.3 消息存储与查询

```typescript
// message-v2.ts
export const page = Effect.fn("MessageV2.page")(function* (input: {
  sessionID: SessionID;
  limit: number;
  before?: string; // cursor
}) {
  const rows = yield* db.query(
    `SELECT * FROM message_table 
     WHERE session_id = ? AND (time_created < ? OR (time_created = ? AND id < ?))
     ORDER BY time_created DESC, id DESC
     LIMIT ?`,
    [input.sessionID, before.time, before.time, before.id, input.limit + 1],
  );

  // 关联查询 parts
  const items = hydrate(rows);

  return {
    items,
    more: rows.length > input.limit,
    cursor: more ? cursor.encode({ id: tail.id, time: tail.time_created }) : undefined,
  };
});
```

---

## 4. 流式响应处理

### 4.1 LLM 流式接口

```typescript
// llm.ts
export type StreamInput = {
  user: MessageV2.User;
  sessionID: string;
  model: Provider.Model;
  agent: Agent.Info;
  system: string[];
  messages: ModelMessage[];
  tools: Record<string, Tool>;
};

export interface Interface {
  readonly stream: (input: StreamInput) => Stream.Stream<LLMEvent, unknown>;
}
```

### 4.2 事件类型（LLMEvent）

```typescript
type StreamEvent =
  | { type: "reasoning-start"; id: string }
  | { type: "reasoning-delta"; id: string; text: string }
  | { type: "reasoning-end"; id: string }
  | { type: "text-start" }
  | { type: "text-delta"; text: string }
  | { type: "text-end" }
  | { type: "tool-input-start"; id: string; name: string }
  | { type: "tool-input-delta"; id: string; delta: string }
  | { type: "tool-input-end"; id: string; name: string }
  | { type: "tool-call"; id: string; name: string; input: unknown }
  | { type: "tool-result"; id: string; result: { type: "string" | "json"; value: unknown } }
  | { type: "tool-error"; id: string; error: string }
  | { type: "step-start" }
  | { type: "step-finish"; reason: string; usage?: Usage }
  | { type: "provider-error"; message: string }
  | { type: "finish" };
```

### 4.3 Processor 事件处理

```typescript
// processor.ts
const handleEvent = Effect.fnUntraced(function* (value: StreamEvent) {
  switch (value.type) {
    case "text-delta":
      ctx.currentText.text += value.text;
      yield* session.updatePartDelta({
        sessionID,
        messageID,
        partID: ctx.currentText.id,
        field: "text",
        delta: value.text,
      });
      return;

    case "tool-call":
      yield* ensureToolCall(value);
      yield* updateToolCall(value.id, (match) => ({
        ...match,
        tool: value.name,
        state: { status: "running", input, time: { start: Date.now() } },
      }));
      return;

    case "step-finish":
      const usage = Session.getUsage({ model: ctx.model, usage: value.usage });
      yield* session.updatePart({ type: "step-finish", reason, cost, tokens });
      yield* session.updateMessage(ctx.assistantMessage);
      return;
  }
});
```

### 4.4 Part Delta 增量更新（实时推送）

```typescript
// session.ts
const updatePartDelta = Effect.fnUntraced(function* (input: {
  sessionID: SessionID;
  messageID: MessageID;
  partID: PartID;
  field: string;
  delta: string;
}) {
  yield* bus.publish(MessageV2.Event.PartDelta, input);
});
```

---

## 5. 状态管理

### 5.1 Session 状态

```typescript
// status.ts
export const Info = Schema.Union([
  Schema.Struct({ type: Schema.Literal("idle") }),
  Schema.Struct({
    type: Schema.Literal("retry"),
    attempt: NonNegativeInt,
    message: Schema.String,
    action: RetryAction?,
    next: NonNegativeInt,
  }),
  Schema.Struct({ type: Schema.Literal("busy") }),
])

const set = Effect.fn("SessionStatus.set")(function* (sessionID: SessionID, status: Info) {
  yield* bus.publish(Event.Status, { sessionID, status })
  if (status.type === "idle") {
    yield* bus.publish(Event.Idle, { sessionID })
  }
})
```

### 5.2 Runner 状态机

```typescript
// run-state.ts
const ensureRunning = Effect.fn("SessionRunState.ensureRunning")(function* (
  sessionID: SessionID,
  onInterrupt: Effect.Effect<MessageV2.WithParts>,
  work: Effect.Effect<MessageV2.WithParts>,
) {
  return yield* (yield* runner(sessionID, onInterrupt)).ensureRunning(work);
});
```

### 5.3 重试策略

```typescript
// retry.ts
export function policy(opts: {
  provider: string;
  parse: (error: unknown) => Err;
  set: (input: {
    attempt: number;
    message: string;
    action?: Retryable["action"];
    next: number;
  }) => Effect.Effect<void>;
}) {
  return Schedule.fromStepWithMetadata(
    Effect.succeed((meta) => {
      const error = opts.parse(meta.input);
      const retry = retryable(error, opts.provider);
      if (!retry) return Cause.done(meta.attempt);
      return Effect.gen(function* () {
        const wait = delay(meta.attempt, error);
        yield* opts.set({
          attempt: meta.attempt,
          message: retry.message,
          action: retry.action,
          next: now + wait,
        });
        return [meta.attempt, Duration.millis(wait)];
      });
    }),
  );
}

// 延迟计算：指数退避
export const RETRY_INITIAL_DELAY = 2000; // 2 秒
export const RETRY_BACKOFF_FACTOR = 2; // 2 倍
export const RETRY_MAX_DELAY = 2_147_483_647; // 约 24.8 天
```

---

## 6. IDE 集成要点

### 6.1 SSE 事件订阅

```kotlin
// IDEA 插件中的 Session 事件监听
class SessionEventListener(private val apiClient: OpenCodeApiClient) {

    fun startListening(project: Project) {
        val uri = URI.create("http://$HOST:$PORT/global/event")

        val connectStrategy = ConnectStrategy.http(uri)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)

        val esBuilder = EventSource.Builder(connectStrategy)
            .errorStrategy(ErrorStrategy.alwaysContinue())

        val bgBuilder = BackgroundEventSource.Builder(object : BackgroundEventHandler {
            override fun onMessage(event: String, messageEvent: MessageEvent) {
                val eventData = gson.fromJson(messageEvent.data, OpenCodeEvent::class.java)

                when (eventData.type) {
                    "session.status" -> handleSessionStatus(eventData)
                    "session.diff" -> handleSessionDiff(eventData)
                    "message.part.delta" -> handlePartDelta(eventData)
                    "session.error" -> handleSessionError(eventData)
                }
            }
        }, esBuilder)

        val bgEventSource = bgBuilder.build()
        bgEventSource.start()
    }

    private fun handlePartDelta(event: OpenCodeEvent) {
        val sessionID = event.properties["sessionID"] as? String ?: return
        val messageID = event.properties["messageID"] as? String ?: return
        val partID = event.properties["partID"] as? String ?: return
        val field = event.properties["field"] as? String ?: return
        val delta = event.properties["delta"] as? String ?: return

        // 更新 UI 中的消息显示
        ApplicationManager.getApplication().invokeLater {
            updateMessageUI(sessionID, messageID, partID, field, delta)
        }
    }
}
```

### 6.2 Session 管理

```kotlin
// IDEA 插件中的 Session 管理
class SessionManager(private val apiClient: OpenCodeApiClient) {

    suspend fun createSession(project: Project): String {
        val response = apiClient.createSession(project.basePath!!)
        return response.id
    }

    suspend fun sendMessage(sessionID: String, message: String): MessageResponse {
        return apiClient.sendMessage(sessionID, message, project.basePath!!)
    }

    suspend fun getMessages(sessionID: String): List<MessageResponse> {
        return apiClient.getMessages(sessionID, project.basePath!!)
    }

    suspend fun abortSession(sessionID: String) {
        apiClient.abortSession(sessionID, project.basePath!!)
    }

    suspend fun revertMessage(sessionID: String, messageID: String) {
        apiClient.revertMessage(sessionID, messageID, project.basePath!!)
    }
}
```

### 6.3 实时消息更新

```kotlin
// IDEA 插件中的实时消息更新
class MessageUpdater {

    private val messageCache = ConcurrentHashMap<String, MutableMap<String, MessagePart>>()

    fun handlePartDelta(sessionID: String, messageID: String, partID: String, field: String, delta: String) {
        val message = messageCache.getOrPut(messageID) { mutableMapOf() }
        val part = message.getOrPut(partID) { MessagePart() }

        when (field) {
            "text" -> part.text += delta
            // 其他字段处理...
        }

        // 触发 UI 更新
        updateMessageUI(sessionID, messageID, partID, part)
    }

    private fun updateMessageUI(sessionID: String, messageID: String, partID: String, part: MessagePart) {
        ApplicationManager.getApplication().invokeLater {
            // 更新 IDEA 中的消息显示
        }
    }
}

data class MessagePart(
    var text: String = "",
    var type: String = "",
    var metadata: Map<String, Any> = emptyMap()
)
```

---

## 7. 参考资源

| 资源         | 文件路径                 |
| ------------ | ------------------------ |
| Session CRUD | `/session/session.ts`    |
| 消息存储     | `/session/message-v2.ts` |
| 流处理器     | `/session/processor.ts`  |
| LLM 流       | `/session/llm.ts`        |
| 状态机       | `/session/status.ts`     |
| 运行控制     | `/session/run-state.ts`  |
| 重试策略     | `/session/retry.ts`      |
| Prompt 构建  | `/session/prompt.ts`     |

---

## 8. 下一步行动

1. **实现 SSE 事件监听** - 订阅 Session 和 Message 事件
2. **实现 Session 管理** - 创建、发送消息、获取历史
3. **实现实时消息更新** - 监听 Part Delta 事件更新 UI
4. **实现取消操作** - 支持中断正在运行的 Session
