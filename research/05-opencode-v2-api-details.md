# OpenCode V2 API 详解

**调研时间**: 2026-05-30
**调研目标**: 深入分析 opencode v2 API，为 IDEA 插件集成提供详细技术指导

---

## 1. V2 API 概述

### 1.1 V2 vs V1 差异

| 特性     | V1 API                 | V2 API                   |
| -------- | ---------------------- | ------------------------ |
| 路径前缀 | `/session/*`           | `/api/session/*`         |
| 分页     | 无                     | 支持 cursor 分页         |
| 消息结构 | `MessageV2.WithParts`  | `SessionMessage.Message` |
| 认证     | `x-opencode-directory` | `V2Authorization`        |
| 状态     | 稳定                   | 实验性                   |

### 1.2 V2 API 端点

| 方法 | 路径                              | 功能                 |
| ---- | --------------------------------- | -------------------- |
| GET  | `/api/session`                    | 列出会话（分页）     |
| POST | `/api/session/:sessionID/prompt`  | 发送消息             |
| POST | `/api/session/:sessionID/compact` | 压缩会话             |
| POST | `/api/session/:sessionID/wait`    | 等待会话空闲         |
| GET  | `/api/session/:sessionID/context` | 获取会话上下文       |
| GET  | `/api/session/:sessionID/message` | 获取消息列表（分页） |
| GET  | `/api/model`                      | 列出可用模型         |
| GET  | `/api/provider`                   | 列出 Provider        |
| GET  | `/api/provider/:providerID`       | 获取 Provider 详情   |

---

## 2. V2 Session API

### 2.1 列出会话（分页）

**请求**:

```http
GET /api/session?limit=50&order=desc
```

**查询参数**:

| 参数     | 类型    | 说明                            |
| -------- | ------- | ------------------------------- |
| `limit`  | number  | 最大返回数量（1-200，默认 50）  |
| `order`  | string  | 排序方式：`asc` 或 `desc`       |
| `cursor` | string  | 分页游标（不与 order 组合使用） |
| `search` | string  | 搜索关键词                      |
| `path`   | string  | 路径过滤                        |
| `roots`  | boolean | 是否只返回根会话                |

**响应**:

```json
{
  "items": [
    {
      "id": "ses_xxx",
      "title": "My Session",
      "agent": "build",
      "model": {
        "providerID": "anthropic",
        "modelID": "claude-3-5-sonnet"
      },
      "cost": 0.05,
      "tokens": {
        "input": 1000,
        "output": 500,
        "reasoning": 200
      },
      "time": {
        "created": 1234567890,
        "updated": 1234567900
      }
    }
  ],
  "cursor": {
    "previous": "opaque_cursor_string",
    "next": "opaque_cursor_string"
  }
}
```

### 2.2 发送消息（V2）

**请求**:

```http
POST /api/session/:sessionID/prompt
Content-Type: application/json

{
  "prompt": {
    "parts": [
      {
        "type": "text",
        "text": "请帮我分析这段代码"
      }
    ]
  },
  "delivery": {
    "mode": "async"
  }
}
```

**Prompt 结构**:

```typescript
interface Prompt {
  parts: PromptPart[];
  // 可选字段
  model?: {
    providerID: string;
    modelID: string;
  };
  agent?: string;
  system?: string;
  noReply?: boolean;
}

interface PromptPart {
  type: "text" | "file" | "agent" | "subtask";
  // 根据 type 不同，有不同字段
}
```

**响应**:

```json
{
  "id": "msg_xxx",
  "sessionID": "ses_xxx",
  "role": "user",
  "time": {
    "created": 1234567890
  }
}
```

### 2.3 等待会话空闲

**请求**:

```http
POST /api/session/:sessionID/wait
```

**响应**: `204 No Content`

**用途**: 等待 AI 处理完成，用于同步调用场景。

### 2.4 获取会话上下文

**请求**:

```http
GET /api/session/:sessionID/context
```

**响应**:

```json
[
  {
    "id": "msg_xxx",
    "sessionID": "ses_xxx",
    "role": "user",
    "parts": [
      {
        "type": "text",
        "text": "用户消息"
      }
    ]
  },
  {
    "id": "msg_yyy",
    "sessionID": "ses_xxx",
    "role": "assistant",
    "parts": [
      {
        "type": "text",
        "text": "AI 响应"
      }
    ]
  }
]
```

**说明**: 返回最后一次压缩后的上下文消息。

### 2.5 获取消息列表（分页）

**请求**:

```http
GET /api/session/:sessionID/message?limit=50&order=asc
```

**查询参数**:

| 参数     | 类型   | 说明                  |
| -------- | ------ | --------------------- |
| `limit`  | number | 最大返回数量（1-200） |
| `order`  | string | 排序方式              |
| `cursor` | string | 分页游标              |

**响应**:

```json
{
  "items": [
    {
      "id": "msg_xxx",
      "sessionID": "ses_xxx",
      "role": "user",
      "parts": [
        {
          "id": "prt_xxx",
          "type": "text",
          "text": "消息内容"
        }
      ],
      "time": {
        "created": 1234567890
      }
    }
  ],
  "cursor": {
    "previous": "opaque_cursor",
    "next": "opaque_cursor"
  }
}
```

---

## 3. V2 Model API

### 3.1 列出可用模型

**请求**:

```http
GET /api/model
```

**响应**:

```json
[
  {
    "id": "claude-3-5-sonnet",
    "providerID": "anthropic",
    "name": "Claude 3.5 Sonnet",
    "description": "Anthropic's most capable model",
    "contextWindow": 200000,
    "maxOutput": 8192,
    "capabilities": {
      "vision": true,
      "toolUse": true
    }
  }
]
```

---

## 4. V2 Provider API

### 4.1 列出 Provider

**请求**:

```http
GET /api/provider
```

**响应**:

```json
[
  {
    "id": "anthropic",
    "name": "Anthropic",
    "status": "active",
    "models": ["claude-3-5-sonnet", "claude-3-opus"]
  }
]
```

### 4.2 获取 Provider 详情

**请求**:

```http
GET /api/provider/:providerID
```

**响应**:

```json
{
  "id": "anthropic",
  "name": "Anthropic",
  "status": "active",
  "auth": {
    "type": "api_key",
    "configured": true
  },
  "models": [
    {
      "id": "claude-3-5-sonnet",
      "name": "Claude 3.5 Sonnet"
    }
  ]
}
```

---

## 5. V2 消息结构

### 5.1 SessionMessage.Message

```typescript
interface Message {
  id: string; // "msg_xxx"
  sessionID: string; // "ses_xxx"
  role: "user" | "assistant" | "system";
  parentID?: string; // 父消息 ID
  agent?: string; // Agent 名称
  model?: {
    providerID: string;
    modelID: string;
    variant?: string;
  };
  finish?: "stop" | "tool-calls" | "error";
  error?: NamedError;
  cost: number;
  tokens: {
    input: number;
    output: number;
    reasoning: number;
    cache: { read: number; write: number };
  };
  time: {
    created: number;
    completed?: number;
  };
  parts: Part[];
}
```

### 5.2 Part 类型

| 类型          | 结构                                                 | 说明     |
| ------------- | ---------------------------------------------------- | -------- |
| `text`        | `{ text: string }`                                   | 文本内容 |
| `tool`        | `{ tool: string; callID: string; state: ToolState }` | 工具调用 |
| `file`        | `{ mime: string; filename?: string; url: string }`   | 文件附件 |
| `reasoning`   | `{ text: string }`                                   | 推理过程 |
| `subtask`     | `{ prompt: string; agent: string }`                  | 子任务   |
| `patch`       | `{ hash: string; files: string[] }`                  | 代码补丁 |
| `snapshot`    | `{ snapshot: string }`                               | 快照     |
| `compaction`  | `{ auto: boolean }`                                  | 压缩标记 |
| `step-start`  | `{}`                                                 | 步骤开始 |
| `step-finish` | `{ reason: string; cost: number }`                   | 步骤结束 |

### 5.3 ToolState 状态机

```typescript
type ToolState =
  | { status: "pending"; input: Record<string, any> }
  | { status: "running"; input: Record<string, any>; time: { start: number } }
  | {
      status: "completed";
      input: Record<string, any>;
      output: string;
      title: string;
      time: { start: number; end: number };
    }
  | {
      status: "error";
      input: Record<string, any>;
      error: string;
      time: { start: number; end: number };
    };
```

---

## 6. IDEA 插件集成示例

### 6.1 V2 API 客户端

```kotlin
class OpenCodeV2Client(
    private val host: String = "127.0.0.1",
    private val port: Int = 12396
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun listSessions(
        limit: Int = 50,
        order: String = "desc",
        directory: String
    ): List<SessionInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://$host:$port/api/session?limit=$limit&order=$order")
            .addHeader("X-Opencode-Directory", directory)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = gson.fromJson(response.body?.string(), JsonObject::class.java)
            gson.fromJson(body.getAsJsonArray("items"),
                object : TypeToken<List<SessionInfo>>() {}.type)
        }
    }

    suspend fun sendMessage(
        sessionID: String,
        text: String,
        directory: String
    ): MessageResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://$host:$port/api/session/$sessionID/prompt")
            .addHeader("X-Opencode-Directory", directory)
            .post(gson.toJson(mapOf(
                "prompt" to mapOf(
                    "parts" to listOf(mapOf("type" to "text", "text" to text))
                )
            )).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            gson.fromJson(response.body?.string(), MessageResponse::class.java)
        }
    }

    suspend fun waitForIdle(
        sessionID: String,
        directory: String,
        timeoutMs: Long = 60000
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://$host:$port/api/session/$sessionID/wait")
            .addHeader("X-Opencode-Directory", directory)
            .post("".toRequestBody(null))
            .build()

        client.newCall(request).execute().use { response ->
            response.code == 204
        }
    }

    suspend fun getMessages(
        sessionID: String,
        limit: Int = 50,
        order: String = "asc",
        directory: String
    ): List<MessageResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://$host:$port/api/session/$sessionID/message?limit=$limit&order=$order")
            .addHeader("X-Opencode-Directory", directory)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = gson.fromJson(response.body?.string(), JsonObject::class.java)
            gson.fromJson(body.getAsJsonArray("items"),
                object : TypeToken<List<MessageResponse>>() {}.type)
        }
    }
}
```

### 6.2 使用 V2 API 发送消息并等待响应

```kotlin
class OpenCodeIntegration(private val client: OpenCodeV2Client) {

    suspend fun sendAndWaitForResponse(
        sessionID: String,
        message: String,
        directory: String
    ): String? {
        // 1. 发送消息
        val userMessage = client.sendMessage(sessionID, message, directory)

        // 2. 等待 AI 完成
        val idle = client.waitForIdle(sessionID, directory, timeoutMs = 120000)
        if (!idle) return null

        // 3. 获取最新消息
        val messages = client.getMessages(sessionID, limit = 1, order = "desc", directory)

        // 4. 提取 AI 响应
        return messages.firstOrNull { it.role == "assistant" }
            ?.parts
            ?.filterIsInstance<TextPart>()
            ?.joinToString("\n") { it.text }
    }
}
```

### 6.3 代码上下文注入（V2）

```kotlin
class CodeContextV2Injector(private val client: OpenCodeV2Client) {

    suspend fun injectContext(
        project: Project,
        editor: Editor,
        sessionID: String
    ) {
        val psiFile = PsiDocumentManager.getInstance(project)
            .getPsiFile(editor.document) ?: return

        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return

        val context = buildString {
            appendLine("当前代码上下文:")
            appendLine("文件: ${psiFile.virtualFile?.path}")
            appendLine("行号: ${editor.document.getLineNumber(offset) + 1}")
            appendLine("符号: ${element.text}")
            appendLine("类型: ${element.javaClass.simpleName}")
        }

        // 使用 V2 API 发送
        client.sendMessage(sessionID, context, project.basePath!!)
    }
}
```

---

## 7. 错误处理

### 7.1 V2 API 错误码

| 错误码 | 说明           | 处理方式       |
| ------ | -------------- | -------------- |
| 400    | 请求格式错误   | 检查请求参数   |
| 403    | 禁止访问       | 检查认证信息   |
| 404    | 资源不存在     | 检查 sessionID |
| 500    | 服务器内部错误 | 重试或报告用户 |
| 503    | 服务不可用     | 检查服务器状态 |

### 7.2 错误响应格式

```json
{
  "error": {
    "name": "SessionNotFoundError",
    "message": "Session not found: ses_xxx"
  }
}
```

---

## 8. 性能优化

### 8.1 分页策略

```kotlin
// 使用 cursor 分页获取所有消息
suspend fun getAllMessages(sessionID: String, directory: String): List<MessageResponse> {
    val allMessages = mutableListOf<MessageResponse>()
    var cursor: String? = null

    do {
        val url = buildString {
            append("http://$host:$port/api/session/$sessionID/message?limit=50&order=asc")
            cursor?.let { append("&cursor=$it") }
        }

        val response = client.fetchMessages(url, directory)
        allMessages.addAll(response.items)
        cursor = response.cursor?.next
    } while (cursor != null)

    return allMessages
}
```

### 8.2 并发请求

```kotlin
// 并发获取会话和消息
coroutineScope {
    val sessionsDeferred = async { client.listSessions(directory = directory) }
    val modelsDeferred = async { client.listModels(directory = directory) }

    val sessions = sessionsDeferred.await()
    val models = modelsDeferred.await()
}
```

---

## 9. 测试场景

### 9.1 V2 API 集成测试

| 场景       | 操作                         | 预期结果         |
| ---------- | ---------------------------- | ---------------- |
| 列出会话   | GET /api/session             | 返回分页会话列表 |
| 发送消息   | POST /api/session/:id/prompt | 返回用户消息     |
| 等待空闲   | POST /api/session/:id/wait   | 等待 AI 完成     |
| 获取上下文 | GET /api/session/:id/context | 返回上下文消息   |
| 获取消息   | GET /api/session/:id/message | 返回分页消息列表 |

### 9.2 分页测试

| 场景   | 操作                        | 预期结果                 |
| ------ | --------------------------- | ------------------------ |
| 首页   | GET /api/session?limit=10   | 返回 10 条 + next cursor |
| 下一页 | GET /api/session?cursor=xxx | 返回后续数据             |
| 上一页 | GET /api/session?cursor=yyy | 返回前序数据             |

---

## 10. 参考资源

| 资源                  | URL                                                                                                      |
| --------------------- | -------------------------------------------------------------------------------------------------------- |
| OpenCode API 文档     | 运行 opencode 后访问 GET /doc                                                                            |
| V2 Session API        | /Users/yutao/Projects/opencode/packages/opencode/src/server/routes/instance/httpapi/groups/v2/session.ts |
| V2 Message API        | /Users/yutao/Projects/opencode/packages/opencode/src/server/routes/instance/httpapi/groups/v2/message.ts |
| V2 Model API          | /Users/yutao/Projects/opencode/packages/opencode/src/server/routes/instance/httpapi/groups/v2/model.ts   |
| SessionMessage Schema | @opencode-ai/core/session-message                                                                        |

---

## 11. 待深入调研

- [ ] V2 API 的认证机制细节
- [ ] 流式响应的完整处理逻辑
- [ ] WebSocket 连接的实现细节
- [ ] 多项目支持的实现方式
- [ ] 性能监控和指标收集
