# OpenCode API 集成技术细节

**调研时间**: 2026-05-30
**调研目标**: 详细分析 opencode 后端 API，为 IDEA 插件集成提供技术指导

---

## 1. API 架构概览

### 1.1 技术栈

| 组件      | 技术                            |
| --------- | ------------------------------- |
| 框架      | Effect-IO HTTP API (TypeScript) |
| 运行时    | Bun                             |
| 数据库    | SQLite (Drizzle ORM)            |
| 实时通信  | SSE (Server-Sent Events)        |
| WebSocket | PTY 终端连接                    |

### 1.2 路由结构

```
Root API (/)
├── Global API (/global/*)
│   ├── GET /global/health
│   ├── GET /global/event (SSE)
│   ├── GET /global/config
│   ├── PATCH /global/config
│   ├── POST /global/dispose
│   └── POST /global/upgrade
│
├── Event API (/event) - SSE
│
└── Instance API (需要 workspace 路由)
    ├── Session API (/session/*)
    ├── File API (/find/*, /file/*)
    ├── Instance API (/instance/*)
    ├── PTY API (/pty/*)
    ├── MCP API (/mcp/*)
    ├── Provider API (/provider/*)
    ├── Permission API (/permission/*)
    ├── Question API (/question/*)
    ├── Config API (/config/*)
    ├── Sync API (/sync/*)
    ├── Project API (/project/*)
    ├── Workspace API (/experimental/workspace/*)
    ├── TUI API (/tui/*)
    └── V2 API (/api/*)
```

---

## 2. 核心 API 详解

### 2.1 Session API

#### 发送消息（流式响应）

**请求**:

```http
POST /session/:sessionID/message
Content-Type: application/json
X-Opencode-Directory: /path/to/project

{
  "parts": [
    {
      "type": "text",
      "text": "你好，请帮我分析这段代码"
    }
  ],
  "model": {
    "providerID": "anthropic",
    "modelID": "claude-3-5-sonnet"
  },
  "agent": "build",
  "noReply": false
}
```

**响应** (流式):

```json
{
  "info": {
    "role": "assistant",
    "id": "msg_xxx",
    "sessionID": "ses_xxx"
  },
  "parts": [
    { "type": "step-start" },
    { "type": "reasoning", "text": "让我分析..." },
    { "type": "text", "text": "分析结果..." },
    { "type": "step-finish" }
  ]
}
```

**Parts 类型**:

| 类型          | 说明         |
| ------------- | ------------ |
| `text`        | 文本内容     |
| `tool`        | 工具调用结果 |
| `reasoning`   | 推理过程     |
| `step-start`  | 步骤开始     |
| `step-finish` | 步骤结束     |
| `file`        | 文件附件     |
| `agent`       | Agent 引用   |
| `subtask`     | 子任务       |

#### 异步发送消息

**请求**:

```http
POST /session/:sessionID/prompt_async
Content-Type: application/json

{
  "parts": [{"type": "text", "text": "异步任务"}]
}
```

**响应**: `204 No Content`

#### 中止会话

```http
POST /session/:sessionID/abort
```

**响应**: `true`

#### 回退消息

```http
POST /session/:sessionID/revert
Content-Type: application/json

{
  "messageID": "msg_xxx"
}
```

### 2.2 Event API (SSE)

#### 全局事件订阅

```http
GET /global/event
Accept: text/event-stream
```

**事件格式**:

```json
{
  "directory": "/path/to/project",
  "payload": {
    "type": "session.status",
    "properties": {
      "sessionID": "ses_xxx",
      "status": { "type": "idle" }
    }
  }
}
```

**事件类型**:

| 事件                       | 说明                           |
| -------------------------- | ------------------------------ |
| `session.created`          | 会话创建                       |
| `session.deleted`          | 会话删除                       |
| `session.status`           | 会话状态变化 (idle/busy/retry) |
| `session.idle`             | 会话空闲                       |
| `session.error`            | 会话错误                       |
| `message.updated`          | 消息更新                       |
| `permission.asked`         | 权限请求                       |
| `question.asked`           | 问题询问                       |
| `session.next.tool.called` | 工具调用                       |
| `file.edited`              | 文件编辑                       |
| `file.watcher.updated`     | 文件监视器更新                 |

### 2.3 File API

#### 文本搜索 (ripgrep)

```http
GET /find?pattern=TODO&directory=/path/to/project
```

**响应**:

```json
[
  {
    "file": "src/main.ts",
    "line": 42,
    "match": "// TODO: implement this"
  }
]
```

#### 文件名搜索

```http
GET /find/file?query=*.kt&directory=/path/to/project&limit=50
```

#### 符号搜索 (LSP)

```http
GET /find/symbol?query=MyClass&directory=/path/to/project
```

#### 读取文件

```http
GET /file/content?path=src/main.kt&directory=/path/to/project
```

#### Git 状态

```http
GET /file/status?directory=/path/to/project
```

### 2.4 VCS API

#### 获取差异

```http
GET /vcs/diff?directory=/path/to/project
```

#### 应用 Patch

```http
POST /vcs/apply
Content-Type: application/json

{
  "patch": "--- a/src/main.kt\n+++ b/src/main.kt\n@@ -10,6 +10,8 @@\n // code here\n+// new line\n"
}
```

---

## 3. IDEA 插件集成模式

### 3.1 HTTP 客户端实现

```kotlin
// 推荐使用 OkHttp + Kotlin Coroutines
class OpenCodeApiClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 12396
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun sendMessage(
        sessionID: String,
        text: String,
        directory: String
    ): MessageResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://$host:$port/session/$sessionID/message")
            .addHeader("X-Opencode-Directory", directory)
            .post(gson.toJson(mapOf(
                "parts" to listOf(mapOf("type" to "text", "text" to text))
            )).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            gson.fromJson(response.body?.string(), MessageResponse::class.java)
        }
    }

    suspend fun sendAsyncMessage(
        sessionID: String,
        text: String,
        directory: String
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://$host:$port/session/$sessionID/prompt_async")
            .addHeader("X-Opencode-Directory", directory)
            .post(gson.toJson(mapOf(
                "parts" to listOf(mapOf("type" to "text", "text" to text))
            )).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            response.code == 204
        }
    }

    suspend fun abortSession(sessionID: String, directory: String): Boolean {
        val request = Request.Builder()
            .url("http://$host:$port/session/$sessionID/abort")
            .addHeader("X-Opencode-Directory", directory)
            .post("".toRequestBody(null))
            .build()

        return client.newCall(request).execute().use { response ->
            response.code == 200
        }
    }
}
```

### 3.2 SSE 事件监听

```kotlin
class OpenCodeSseListener(
    private val host: String = "127.0.0.1",
    private val port: Int = 12396
) {
    private val eventSource = AtomicReference<EventSource?>(null)

    fun start(directory: String, handler: (SseEvent) -> Unit) {
        val uri = URI.create("http://$host:$port/global/event")
        val connectStrategy = ConnectStrategy.http(uri)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)

        val esBuilder = EventSource.Builder(connectStrategy)
            .errorStrategy(ErrorStrategy.alwaysContinue())

        val bgBuilder = BackgroundEventSource.Builder(object : BackgroundEventHandler {
            override fun onOpen() { /* 连接打开 */ }

            override fun onMessage(event: String, messageEvent: MessageEvent) {
                val eventData = gson.fromJson(messageEvent.data, SseEvent::class.java)
                handler(eventData)
            }

            override fun onClosed() { /* 连接关闭 */ }

            override fun onError(error: Throwable) { /* 错误处理 */ }

            override fun onComment(comment: String) { /* 注释处理 */ }
        }, esBuilder)

        val bgEventSource = bgBuilder.build()
        eventSource.set(bgEventSource)
        bgEventSource.start()
    }

    fun stop() {
        eventSource.getAndSet(null)?.close()
    }
}
```

### 3.3 代码上下文注入

```kotlin
class CodeContextInjector(private val apiClient: OpenCodeApiClient) {

    suspend fun injectCodeContext(
        project: Project,
        editor: Editor,
        sessionID: String
    ) {
        val psiFile = PsiDocumentManager.getInstance(project)
            .getPsiFile(editor.document) ?: return

        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return

        // 构建上下文信息
        val context = buildString {
            appendLine("当前代码上下文:")
            appendLine("文件: ${psiFile.virtualFile?.path}")
            appendLine("行号: ${editor.document.getLineNumber(offset) + 1}")
            appendLine("符号: ${element.text}")
            appendLine("类型: ${element.javaClass.simpleName}")
            appendLine("父元素: ${element.parent?.javaClass?.simpleName ?: "无"}")
        }

        // 发送到 opencode
        apiClient.sendMessage(sessionID, context, project.basePath!!)
    }
}
```

### 3.4 Git 集成

```kotlin
class GitIntegration(private val apiClient: OpenCodeApiClient) {

    suspend fun generateCommitMessage(
        project: Project,
        sessionID: String
    ): String? {
        val git = Git.getInstance()
        val repository = git.getRepository(project) ?: return null

        // 获取变更
        val diff = git.collectUncommittedChanges(repository)
        val diffText = buildString {
            diff.allChangedFiles.forEach { change ->
                appendLine("${change.type}: ${change.virtualFile?.name}")
                appendLine(change.diff?.toString() ?: "")
                appendLine()
            }
        }

        // 发送到 opencode 生成 commit message
        val response = apiClient.sendMessage(
            sessionID,
            "根据以下代码变更生成 commit message:\n$diffText",
            project.basePath!!
        )

        return response.parts?.firstOrNull { it.type == "text" }?.text
    }

    suspend fun reviewChanges(
        project: Project,
        sessionID: String
    ): String? {
        val git = Git.getInstance()
        val repository = git.getRepository(project) ?: return null

        val diff = git.collectUncommittedChanges(repository)
        val diffText = // ... 构建 diff 文本

        val response = apiClient.sendMessage(
            sessionID,
            "请审查以下代码变更并提供改进建议:\n$diffText",
            project.basePath!!
        )

        return response.parts?.firstOrNull { it.type == "text" }?.text
    }
}
```

---

## 4. 错误处理

### 4.1 常见错误码

| 错误码 | 说明           | 处理方式            |
| ------ | -------------- | ------------------- |
| 400    | 请求格式错误   | 检查请求参数        |
| 404    | 资源不存在     | 检查 sessionID/路径 |
| 500    | 服务器内部错误 | 重试或报告用户      |
| 503    | 服务不可用     | 检查服务器状态      |

### 4.2 错误响应格式

```json
{
  "error": {
    "name": "SessionNotFoundError",
    "message": "Session not found: ses_xxx"
  }
}
```

### 4.3 重试策略

```kotlin
suspend fun <T> retryWithBackoff(
    maxRetries: Int = 3,
    initialDelay: Long = 1000,
    maxDelay: Long = 10000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(maxRetries - 1) {
        try {
            return block()
        } catch (e: Exception) {
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return block() // 最后一次尝试
}
```

---

## 5. 性能优化

### 5.1 连接池

```kotlin
val client = OkHttpClient.Builder()
    .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
    .build()
```

### 5.2 缓存策略

```kotlin
// 对于不经常变化的数据（如项目信息）使用缓存
val cache = Cache(cacheDir, 10L * 1024 * 1024) // 10MB
val client = OkHttpClient.Builder()
    .cache(cache)
    .build()
```

### 5.3 并发请求

```kotlin
// 使用协程并发执行多个请求
coroutineScope {
    val sessionDeferred = async { apiClient.getSession(sessionID) }
    val messagesDeferred = async { apiClient.getMessages(sessionID) }

    val session = sessionDeferred.await()
    val messages = messagesDeferred.await()
}
```

---

## 6. 测试场景

### 6.1 API 集成测试

| 场景     | 操作                           | 预期结果           |
| -------- | ------------------------------ | ------------------ |
| 健康检查 | GET /global/health             | 返回 200 + healthy |
| 发送消息 | POST /session/:id/message      | 返回 AI 响应       |
| 异步消息 | POST /session/:id/prompt_async | 返回 204           |
| 中止会话 | POST /session/:id/abort        | 返回 true          |
| 文本搜索 | GET /find?pattern=xxx          | 返回匹配结果       |

### 6.2 错误处理测试

| 场景         | 操作                                 | 预期结果         |
| ------------ | ------------------------------------ | ---------------- |
| 无效 session | POST /session/invalid/message        | 返回 404         |
| 空消息       | POST /session/:id/message (空 parts) | 返回 400         |
| 服务器离线   | 所有请求                             | 正确处理连接错误 |

---

## 7. 参考资源

| 资源               | URL                                               |
| ------------------ | ------------------------------------------------- |
| OpenCode API 文档  | 运行 opencode 后访问 GET /doc                     |
| Effect-IO HTTP API | https://effect.website/docs/                      |
| OkHttp 文档        | https://square.github.io/okhttp/                  |
| Kotlin Coroutines  | https://kotlinlang.org/docs/coroutines-guide.html |

---

## 8. 待深入调研

- [ ] 流式响应的完整处理逻辑
- [ ] WebSocket 连接的实现细节
- [ ] 认证机制的具体实现
- [ ] 多项目支持的实现方式
- [ ] 性能监控和指标收集
