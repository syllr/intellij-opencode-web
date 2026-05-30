# OpenCode MCP 和 LSP 集成详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供 MCP 和 LSP 集成技术指导

---

## 1. MCP (Model Context Protocol) 概述

### 1.1 MCP 是什么

MCP 是一个开放协议，用于标准化 AI 模型与外部工具和数据源的通信方式。它允许 AI 模型调用外部工具、访问数据源，并与各种服务进行交互。

### 1.2 MCP 核心组件

| 组件        | 说明       | 用途                       |
| ----------- | ---------- | -------------------------- |
| `Client`    | MCP 客户端 | 连接 MCP 服务器            |
| `Server`    | MCP 服务器 | 提供工具和资源             |
| `Tool`      | MCP 工具   | 可调用的功能               |
| `Resource`  | MCP 资源   | 可访问的数据               |
| `Transport` | 传输层     | 通信方式（HTTP/STDIO/SSE） |

### 1.3 MCP 在 opencode 中的实现

```typescript
// /packages/opencode/src/mcp/index.ts
export const Resource = Schema.Struct({
  name: Schema.String,
  uri: Schema.String,
  description: Schema.optional(Schema.String),
  mimeType: Schema.optional(Schema.String),
  client: Schema.String,
}).annotate({ identifier: "McpResource" });

export const Status = Schema.Union([
  StatusConnected,
  StatusDisabled,
  StatusFailed,
  StatusNeedsAuth,
  StatusNeedsClientRegistration,
]).annotate({ identifier: "MCPStatus", discriminator: "status" });
```

---

## 2. LSP (Language Server Protocol) 概述

### 2.1 LSP 是什么

LSP 是一个开放协议，用于标准化编辑器/IDE 与语言服务器之间的通信。它提供了代码补全、跳转到定义、查找引用、诊断等代码智能功能。

### 2.2 LSP 核心组件

| 组件         | 说明       | 用途                   |
| ------------ | ---------- | ---------------------- |
| `Client`     | LSP 客户端 | 连接语言服务器         |
| `Server`     | LSP 服务器 | 提供代码智能功能       |
| `Document`   | 文档模型   | 表示打开的文件         |
| `Symbol`     | 符号       | 代码元素（类、方法等） |
| `Diagnostic` | 诊断       | 代码问题（错误、警告） |

### 2.3 LSP 在 opencode 中的实现

```typescript
// /packages/opencode/src/lsp/lsp.ts
export const Symbol = Schema.Struct({
  name: Schema.String,
  kind: NonNegativeInt,
  location: Schema.Struct({
    uri: Schema.String,
    range: Range,
  }),
}).annotate({ identifier: "Symbol" });

export const DocumentSymbol = Schema.Struct({
  name: Schema.String,
  detail: Schema.optional(Schema.String),
  kind: NonNegativeInt,
  range: Range,
  selectionRange: Range,
}).annotate({ identifier: "DocumentSymbol" });

export const Status = Schema.Struct({
  id: Schema.String,
  name: Schema.String,
  root: Schema.String,
  status: Schema.Literals(["connected", "error"]),
}).annotate({ identifier: "LSPStatus" });
```

---

## 3. MCP 集成示例

### 3.1 连接 MCP 服务器

```kotlin
class McpClient(private val serverUrl: String) {

    private var client: Client? = null

    suspend fun connect(): Boolean {
        return try {
            val transport = StreamableHTTPClientTransport(
                URI(serverUrl)
            )

            client = Client(
                ClientInfo(
                    name = "intellij-opencode-web",
                    version = "1.0.0"
                )
            )

            client?.connect(transport)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun listTools(): List<McpTool> {
        val response = client?.listTools() ?: return emptyList()

        return response.tools.map { tool ->
            McpTool(
                name = tool.name,
                description = tool.description ?: "",
                inputSchema = tool.inputSchema
            )
        }
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>): McpToolResult {
        val response = client?.callTool(
            CallToolRequest(
                name = name,
                arguments = arguments
            )
        ) ?: throw Exception("No response")

        return McpToolResult(
            content = response.content.map { content ->
                when (content) {
                    is TextContent -> content.text
                    is ImageContent -> "[Image: ${content.mimeType}]"
                    else -> content.toString()
                }
            },
            isError = response.isError ?: false
        )
    }

    fun disconnect() {
        client?.close()
        client = null
    }
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)

data class McpToolResult(
    val content: List<String>,
    val isError: Boolean
)
```

### 3.2 使用 MCP 工具

```kotlin
class McpToolIntegration(private val mcpClient: McpClient) {

    suspend fun searchCode(query: String): List<SearchResult> {
        val result = mcpClient.callTool(
            "search_code",
            mapOf("query" to query)
        )

        if (result.isError) {
            throw Exception("Search failed: ${result.content.joinToString()}")
        }

        return parseSearchResults(result.content.first())
    }

    suspend fun readFile(path: String): String {
        val result = mcpClient.callTool(
            "read_file",
            mapOf("path" to path)
        )

        if (result.isError) {
            throw Exception("Read failed: ${result.content.joinToString()}")
        }

        return result.content.first()
    }

    suspend fun executeCommand(command: String): String {
        val result = mcpClient.callTool(
            "execute_command",
            mapOf("command" to command)
        )

        if (result.isError) {
            throw Exception("Command failed: ${result.content.joinToString()}")
        }

        return result.content.first()
    }
}
```

---

## 4. LSP 集成示例

### 4.1 连接语言服务器

```kotlin
class LspClient(private val serverCommand: List<String>) {

    private var client: LanguageClient? = null
    private var server: LanguageServer? = null

    suspend fun connect(): Boolean {
        return try {
            val launcher = LSPLauncher.createClientLauncher(
                LanguageClient::class.java,
                LanguageServer::class.java,
                System.`in`,
                System.out,
                null,
                null
            )

            client = launcher.remoteProxy
            server = launcher.startListening()

            // 初始化
            val initResult = client?.initialize(
                InitializeParams().apply {
                    rootUri = File(System.getProperty("user.dir")).toURI().toString()
                    capabilities = ClientCapabilities()
                }
            )

            initResult != null
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getDocumentSymbols(uri: String): List<DocumentSymbol> {
        val response = client?.textDocumentSymbol(
            DocumentSymbolParams().apply {
                textDocument = TextDocumentIdentifier(uri)
            }
        )

        return response?.map { symbol ->
            DocumentSymbol(
                name = symbol.name,
                kind = symbol.kind,
                range = symbol.range,
                selectionRange = symbol.selectionRange
            )
        } ?: emptyList()
    }

    suspend fun getDefinition(uri: String, position: Position): List<Location> {
        val response = client?.textDocumentDefinition(
            TextDocumentPositionParams().apply {
                textDocument = TextDocumentIdentifier(uri)
                this.position = position
            }
        )

        return response?.map { location ->
            Location(
                uri = location.uri,
                range = location.range
            )
        } ?: emptyList()
    }

    suspend fun getReferences(uri: String, position: Position): List<Location> {
        val response = client?.textDocumentReferences(
            ReferenceParams().apply {
                textDocument = TextDocumentIdentifier(uri)
                this.position = position
                context = ReferenceContext(includeDeclaration = true)
            }
        )

        return response?.map { location ->
            Location(
                uri = location.uri,
                range = location.range
            )
        } ?: emptyList()
    }

    fun disconnect() {
        client?.shutdown()
        server?.exit()
    }
}
```

### 4.2 使用 LSP 功能

```kotlin
class LspIntegration(private val lspClient: LspClient) {

    suspend fun getCodeAtLocation(filePath: String, line: Int, column: Int): CodeInfo {
        val uri = File(filePath).toURI().toString()
        val position = Position(line, column)

        // 获取符号信息
        val symbols = lspClient.getDocumentSymbols(uri)
        val symbol = symbols.find { symbol ->
            symbol.range.containsPosition(position)
        }

        // 获取定义
        val definitions = lspClient.getDefinition(uri, position)

        // 获取引用
        val references = lspClient.getReferences(uri, position)

        return CodeInfo(
            symbol = symbol,
            definitions = definitions,
            references = references
        )
    }

    suspend fun navigateToDefinition(filePath: String, line: Int, column: Int): Location? {
        val uri = File(filePath).toURI().toString()
        val position = Position(line, column)

        val definitions = lspClient.getDefinition(uri, position)
        return definitions.firstOrNull()
    }

    suspend fun findUsages(filePath: String, line: Int, column: Int): List<Location> {
        val uri = File(filePath).toURI().toString()
        val position = Position(line, column)

        return lspClient.getReferences(uri, position)
    }
}

data class CodeInfo(
    val symbol: DocumentSymbol?,
    val definitions: List<Location>,
    val references: List<Location>
)
```

---

## 5. 高级功能

### 5.1 MCP 资源访问

```kotlin
class McpResourceIntegration(private val mcpClient: McpClient) {

    suspend fun listResources(): List<McpResource> {
        val response = mcpClient.listResources() ?: return emptyList()

        return response.resources.map { resource ->
            McpResource(
                name = resource.name,
                uri = resource.uri,
                description = resource.description,
                mimeType = resource.mimeType
            )
        }
    }

    suspend fun readResource(uri: String): String {
        val response = mcpClient.readResource(uri) ?: throw Exception("No response")

        return response.contents.joinToString("\n") { content ->
            when (content) {
                is TextResourceContents -> content.text
                is BlobResourceContents -> "[Binary: ${content.mimeType}]"
                else -> content.toString()
            }
        }
    }
}

data class McpResource(
    val name: String,
    val uri: String,
    val description: String?,
    val mimeType: String?
)
```

### 5.2 LSP 诊断集成

```kotlin
class LspDiagnosticIntegration(private val lspClient: LspClient) {

    suspend fun getDiagnostics(filePath: String): List<Diagnostic> {
        val uri = File(filePath).toURI().toString()

        // 等待诊断更新
        delay(500) // 等待 LSP 服务器处理

        val diagnostics = lspClient.getDiagnostics(uri)

        return diagnostics.map { diagnostic ->
            Diagnostic(
                range = diagnostic.range,
                severity = diagnostic.severity,
                message = diagnostic.message,
                source = diagnostic.source
            )
        }
    }

    suspend fun getErrors(filePath: String): List<Diagnostic> {
        return getDiagnostics(filePath).filter { it.severity == DiagnosticSeverity.Error }
    }

    suspend fun getWarnings(filePath: String): List<Diagnostic> {
        return getDiagnostics(filePath).filter { it.severity == DiagnosticSeverity.Warning }
    }
}

data class Diagnostic(
    val range: Range,
    val severity: DiagnosticSeverity,
    val message: String,
    val source: String?
)

enum class DiagnosticSeverity {
    Error, Warning, Information, Hint
}
```

---

## 6. IDEA 插件集成

### 6.1 MCP 集成到 IDEA

```kotlin
class IdeaMcpIntegration(private val project: Project) {

    private val mcpClient = McpClient("http://127.0.0.1:12396/mcp")

    suspend fun initialize() {
        val connected = mcpClient.connect()
        if (connected) {
            // 注册 MCP 工具到 IDEA
            registerMcpTools()
        }
    }

    private fun registerMcpTools() {
        val tools = mcpClient.listTools()

        tools.forEach { tool ->
            // 为每个 MCP 工具创建 IDEA Action
            val action = McpToolAction(tool)
            // 注册到工具栏或菜单
        }
    }

    suspend fun executeMcpTool(toolName: String, arguments: Map<String, Any>): String {
        val result = mcpClient.callTool(toolName, arguments)

        if (result.isError) {
            throw Exception("MCP tool execution failed: ${result.content.joinToString()}")
        }

        return result.content.first()
    }
}
```

### 6.2 LSP 集成到 IDEA

```kotlin
class IdeaLspIntegration(private val project: Project) {

    private val lspClient = LspClient(listOf("kotlin-language-server"))

    suspend fun initialize() {
        val connected = lspClient.connect()
        if (connected) {
            // 监听文件变化
            setupFileListeners()
        }
    }

    private fun setupFileListeners() {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                val file = editor.virtualFile

                if (file != null) {
                    // 通知 LSP 服务器文件打开
                    lspClient.didOpen(file)
                }
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                val editor = event.editor
                val file = editor.virtualFile

                if (file != null) {
                    // 通知 LSP 服务器文件关闭
                    lspClient.didClose(file)
                }
            }
        }, project)
    }

    suspend fun getCodeCompletions(filePath: String, line: Int, column: Int): List<CompletionItem> {
        val uri = File(filePath).toURI().toString()
        val position = Position(line, column)

        val response = lspClient.completion(uri, position)

        return response?.items?.map { item ->
            CompletionItem(
                label = item.label,
                kind = item.kind,
                detail = item.detail,
                documentation = item.documentation
            )
        } ?: emptyList()
    }
}

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind?,
    val detail: String?,
    val documentation: String?
)
```

---

## 7. 测试场景

### 7.1 MCP 集成测试

| 场景            | 操作              | 预期结果     |
| --------------- | ----------------- | ------------ |
| 连接 MCP 服务器 | 连接到 MCP 服务器 | 连接成功     |
| 列出工具        | 获取 MCP 工具列表 | 返回工具列表 |
| 调用工具        | 执行 MCP 工具     | 返回正确结果 |
| 访问资源        | 读取 MCP 资源     | 返回资源内容 |

### 7.2 LSP 集成测试

| 场景           | 操作              | 预期结果     |
| -------------- | ----------------- | ------------ |
| 连接语言服务器 | 连接到 LSP 服务器 | 连接成功     |
| 获取符号       | 获取文件符号      | 返回符号列表 |
| 跳转到定义     | 导航到定义        | 返回定义位置 |
| 查找引用       | 查找符号引用      | 返回引用列表 |
| 获取诊断       | 获取代码诊断      | 返回诊断信息 |

---

## 8. 性能优化

### 8.1 MCP 连接池

```kotlin
class McpConnectionPool(private val maxConnections: Int = 5) {

    private val connections = ConcurrentLinkedQueue<McpClient>()
    private val semaphore = Semaphore(maxConnections)

    suspend fun <T> withConnection(block: suspend (McpClient) -> T): T {
        semaphore.acquire()

        val client = connections.poll() ?: createNewClient()

        return try {
            block(client)
        } finally {
            connections.offer(client)
            semaphore.release()
        }
    }

    private fun createNewClient(): McpClient {
        return McpClient("http://127.0.0.1:12396/mcp")
    }
}
```

### 8.2 LSP 缓存

```kotlin
class LspCache {

    private val symbolCache = ConcurrentHashMap<String, List<DocumentSymbol>>()
    private val cacheTimeout = 5000L // 5秒

    suspend fun getSymbols(filePath: String, lspClient: LspClient): List<DocumentSymbol> {
        val cached = symbolCache[filePath]

        if (cached != null) {
            return cached
        }

        val symbols = lspClient.getDocumentSymbols(File(filePath).toURI().toString())
        symbolCache[filePath] = symbols

        return symbols
    }

    fun invalidate(filePath: String) {
        symbolCache.remove(filePath)
    }

    fun invalidateAll() {
        symbolCache.clear()
    }
}
```

---

## 9. 参考资源

| 资源              | URL                                                               |
| ----------------- | ----------------------------------------------------------------- |
| MCP 官方文档      | https://modelcontextprotocol.io/                                  |
| MCP SDK           | https://github.com/modelcontextprotocol/typescript-sdk            |
| LSP 官方文档      | https://microsoft.github.io/language-server-protocol/             |
| LSP SDK           | https://github.com/eclipse/lsp4j                                  |
| OpenCode MCP 实现 | /Users/yutao/Projects/opencode/packages/opencode/src/mcp/index.ts |
| OpenCode LSP 实现 | /Users/yutao/Projects/opencode/packages/opencode/src/lsp/lsp.ts   |

---

## 10. 待深入调研

- [ ] MCP 认证和授权机制
- [ ] LSP 高级功能（重构、代码操作）
- [ ] 多语言服务器支持
- [ ] MCP 资源缓存策略
- [ ] LSP 诊断实时更新
