# IDEA 插件功能联动调研

**调研时间**: 2026-05-30
**调研目标**: 探索 IDEA 插件可与 opencode 后端联动的功能点

---

## 1. 调研背景

当前 OpenCodeWeb 插件与 IDEA 的联动主要限于：

- **Copy as Prompt**: 选中代码复制为 Prompt 格式
- **Add to Prompt**: 选中代码添加到 Prompt 编辑器
- **工具窗口**: 在侧边栏显示 OpenCode Web UI

**需要扩展的方向**: 利用 IDEA 的代码智能、调试、VCS 等能力，与 opencode 后端深度集成。

---

## 2. OpenCode 后端 API 能力总览

### 2.1 API 架构

| 特性     | 说明                                        |
| -------- | ------------------------------------------- |
| 协议     | REST + SSE + WebSocket                      |
| 框架     | Effect-IO HTTP API (TypeScript)             |
| 默认端口 | 12396                                       |
| 认证     | `x-opencode-directory` header 或 query 参数 |

### 2.2 核心 API 分组

| API 组           | 路径前缀             | 功能               |
| ---------------- | -------------------- | ------------------ |
| **Session API**  | `/session/*`         | 会话管理、消息收发 |
| **Event API**    | `/event`             | SSE 实时事件       |
| **File API**     | `/find/*`, `/file/*` | 文件搜索、读取     |
| **V2 API**       | `/api/*`             | 新版会话、消息 API |
| **Instance API** | `/instance/*`        | VCS、命令、Agent   |
| **PTY API**      | `/pty/*`             | 伪终端会话         |
| **MCP API**      | `/mcp/*`             | MCP 服务器管理     |
| **Global API**   | `/global/*`          | 健康检查、配置     |

### 2.3 可用操作

**会话管理**:

- `POST /session/:sessionID/message` - 发送消息（流式响应）
- `POST /session/:sessionID/prompt_async` - 异步发送消息
- `POST /session/:sessionID/command` - 发送命令
- `POST /session/:sessionID/shell` - 执行 Shell 命令
- `POST /session/:sessionID/revert` - 回退消息
- `POST /session/:sessionID/abort` - 中止会话

**文件操作**:

- `GET /find` - 文本搜索 (ripgrep)
- `GET /find/file` - 文件名搜索
- `GET /find/symbol` - 符号搜索 (LSP)
- `GET /file/content` - 读取文件内容
- `GET /file/status` - Git 状态

**VCS 操作**:

- `GET /vcs/diff` - 获取差异
- `POST /vcs/apply` - 应用 patch
- `GET /vcs/status` - VCS 状态

**实时事件**:

- `GET /global/event` - 全局 SSE 事件
- `GET /event` - 实例级 SSE 事件

---

## 3. IDEA 插件能力与 opencode 联动机会

### 3.1 代码上下文自动注入

**能力**: 将当前编辑器的代码上下文自动注入到 opencode 会话

**实现方式**:

```kotlin
// 利用 IDEA 的 PsiElement 获取代码上下文
class CodeContextAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = PsiDocumentManager.getInstance(project)
            .getPsiFile(editor.document) ?: return

        // 获取当前光标位置的符号信息
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)

        // 构建上下文信息
        val context = buildMap {
            put("file", psiFile.virtualFile.path)
            put("line", editor.document.getLineNumber(offset) + 1)
            put("symbol", element?.text ?: "")
            put("scope", getSymbolScope(element))  // 方法/类/文件级别
        }

        // 通过 API 发送到 opencode
        sendContextToOpencode(project, context)
    }
}
```

**联动 API**:

- `POST /session/:sessionID/prompt_async` - 异步发送上下文
- `GET /find/symbol` - 获取符号信息

**用户价值**: AI 自动知道当前代码位置和上下文，无需手动复制

---

### 3.2 代码导航集成

**能力**: 将 opencode 的代码建议导航到 IDEA 中的具体位置

**实现方式**:

```kotlin
// 监听 SSE 事件，解析代码位置
class CodeNavigationHandler {
    fun handleCodeReference(event: SSEEvent) {
        val filePath = event.properties["file"] as? String ?: return
        val line = event.properties["line"] as? Int ?: return

        // 在 IDEA 中打开文件并导航到指定行
        ApplicationManager.getApplication().invokeLater {
            val virtualFile = LocalFileSystem.getInstance()
                .findFileByPath(filePath) ?: return@invokeLater

            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, virtualFile, line - 1), true)
        }
    }
}
```

**联动 API**:

- `GET /session/:sessionID/diff` - 获取代码变更
- `GET /file/content` - 读取文件内容

**用户价值**: AI 生成的代码位置可以直接点击跳转

---

### 3.3 自动化重构集成

**能力**: AI 建议的重构可以直接应用到 IDEA 中

**实现方式**:

```kotlin
class RefactorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        // 发送重构请求到 opencode
        val sessionID = getCurrentSession(project)
        val response = OpenCodeApi.sendCommand(sessionID, "refactor", selectedText)

        // 解析 AI 返回的 patch
        val patch = parsePatch(response)

        // 应用 patch 到 IDEA
        applyPatch(project, patch)
    }
}

private fun applyPatch(project: Project, patch: String) {
    // 使用 IDEA 的 DiffUtil 或 WriteCommandAction 应用变更
    WriteCommandAction.runWriteCommandAction(project) {
        val document = editor.document
        // 应用 patch 到 document
    }
}
```

**联动 API**:

- `POST /session/:sessionID/message` - 发送重构请求
- `POST /vcs/apply` - 应用 patch
- `GET /session/:sessionID/diff` - 获取变更

**用户价值**: AI 重构建议一键应用，无需手动复制粘贴

---

### 3.4 调试信息集成

**能力**: 将调试器的堆栈信息、变量状态发送给 AI 分析

**实现方式**:

```kotlin
class DebugInfoAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // 获取当前调试会话
        val debugSession = XDebuggerManager.getInstance(project)
            .currentDebugSession ?: return

        // 收集调试信息
        val debugInfo = buildMap {
            put("stackTrace", getStackTrace(debugSession))
            put("variables", getVariables(debugSession))
            put("breakpoints", getBreakpoints(debugSession))
        }

        // 发送到 opencode 分析
        val sessionID = getCurrentSession(project)
        OpenCodeApi.sendMessage(sessionID,
            "请分析这个调试信息并给出修复建议：\n${debugInfo.toJson()}")
    }
}
```

**联动 API**:

- `POST /session/:sessionID/message` - 发送调试信息
- `GET /session/:sessionID/message` - 获取 AI 分析结果

**用户价值**: AI 可以分析运行时状态，给出更准确的修复建议

---

### 3.5 Git 工作流集成

**能力**: AI 辅助 Git 操作（commit message、PR 描述、代码审查）

**实现方式**:

```kotlin
class GitAssistAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val git = Git.getInstance()
        val repository = git.getRepository(project) ?: return

        // 获取当前变更
        val diff = git.collectUncommittedChanges(repository)

        // 发送到 opencode 生成 commit message
        val sessionID = getCurrentSession(project)
        val response = OpenCodeApi.sendMessage(sessionID,
            "根据以下代码变更生成 commit message：\n${formatDiff(diff)}")

        // 应用到 IDEA 的 commit 对话框
        applyCommitMessage(response)
    }
}
```

**联动 API**:

- `GET /vcs/diff` - 获取 VCS 差异
- `POST /session/:sessionID/message` - 生成 commit message

**用户价值**: AI 生成高质量的 commit message 和 PR 描述

---

### 3.6 代码审查集成

**能力**: AI 自动审查代码变更，提供改进建议

**实现方式**:

```kotlin
class CodeReviewAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // 获取当前变更的文件
        val changedFiles = getChangedFiles()

        // 发送到 opencode 审查
        val sessionID = getCurrentSession(project)
        val review = OpenCodeApi.sendMessage(sessionID,
            "请审查以下代码变更并提供改进建议：\n${formatChanges(changedFiles)}")

        // 在 IDEA 中显示审查结果
        showReviewResults(review)
    }
}
```

**联动 API**:

- `GET /file/status` - 获取文件状态
- `GET /file/content` - 读取文件内容
- `POST /session/:sessionID/message` - 代码审查

**用户价值**: 即时获得专业的代码审查反馈

---

### 3.7 终端命令集成

**能力**: 在 IDEA 终端中执行 AI 生成的命令

**实现方式**:

```kotlin
class TerminalCommandAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // 获取用户选中的文本（可能是命令）
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText ?: ""

        // 发送到 opencode 解析命令
        val sessionID = getCurrentSession(project)
        val command = OpenCodeApi.sendMessage(sessionID,
            "请将以下需求转换为 shell 命令：$selectedText")

        // 在 IDEA 终端中执行
        val terminal = TerminalWidgetManager.getInstance(project)
            .getWidget(project)?.currentTerminal
        terminal?.sendCommand(command)
    }
}
```

**联动 API**:

- `POST /session/:sessionID/message` - 解析命令
- `POST /pty` - 创建终端会话
- `POST /pty/:ptyID/connect-token` - WebSocket 连接

**用户价值**: AI 生成的命令直接在 IDEA 终端中执行

---

### 3.8 LSP 诊断集成

**能力**: 将 IDEA 的 LSP 诊断信息发送给 AI 分析

**实现方式**:

```kotlin
class LspDiagnosticAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        // 获取当前文件的 LSP 诊断
        val diagnostics = DiagnosticProgressRenderer.getExistingDiagnostics(
            editor.document)

        // 发送到 opencode 分析
        val sessionID = getCurrentSession(project)
        OpenCodeApi.sendMessage(sessionID,
            "请分析这些 LSP 诊断并给出修复建议：\n${formatDiagnostics(diagnostics)}")
    }
}
```

**联动 API**:

- `GET /instance/lsp` - 获取 LSP 状态
- `POST /session/:sessionID/message` - 分析诊断

**用户价值**: AI 可以理解 IDE 的诊断信息，给出更精准的修复

---

## 4. 实施优先级

### 高优先级（立即可做）

| 功能                   | 复杂度 | 价值 | 依赖              |
| ---------------------- | ------ | ---- | ----------------- |
| **代码上下文自动注入** | 低     | 高   | PsiElement API    |
| **代码导航集成**       | 中     | 高   | FileEditorManager |
| **Git 工作流集成**     | 中     | 高   | Git4Idea          |

### 中优先级（需要更多设计）

| 功能               | 复杂度 | 价值 | 依赖            |
| ------------------ | ------ | ---- | --------------- |
| **自动化重构集成** | 高     | 高   | Refactoring API |
| **调试信息集成**   | 高     | 中   | XDebugger API   |
| **代码审查集成**   | 中     | 中   | File API        |

### 低优先级（长期规划）

| 功能             | 复杂度 | 价值 | 依赖         |
| ---------------- | ------ | ---- | ------------ |
| **终端命令集成** | 中     | 中   | Terminal API |
| **LSP 诊断集成** | 低     | 低   | LSP API      |

---

## 5. 技术实现建议

### 5.1 HTTP 客户端升级

当前使用 `HttpURLConnection`（同步），建议升级为异步客户端：

```kotlin
// 推荐使用 OkHttp + Kotlin Coroutines
class OpenCodeHttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(sessionID: String, message: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("http://$HOST:$PORT/session/$sessionID/message")
                .post(message.toJson().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                response.body?.string() ?: ""
            }
        }
    }
}
```

### 5.2 SSE 事件处理优化

当前的 `OpenCodeSSEConsumer` 可以扩展支持更多事件类型：

```kotlin
// 扩展 SSE 事件处理器
class ExtendedSSEConsumer(project: Project) : OpenCodeSSEConsumer(project) {
    override fun onMessage(event: String, messageEvent: MessageEvent) {
        super.onMessage(event, messageEvent)

        // 处理代码导航事件
        when (event) {
            "code.reference" -> handleCodeReference(messageEvent)
            "refactor.suggestion" -> handleRefactorSuggestion(messageEvent)
            "review.comment" -> handleReviewComment(messageEvent)
        }
    }
}
```

### 5.3 新增 Action 注册

在 `plugin.xml` 中注册新 Action：

```xml
<actions>
    <!-- 现有 Action -->
    <action id="com.shenyuanlaolarou.opencodewebui.CopyAsPrompt" .../>
    <action id="com.shenyuanlaolarou.opencodewebui.AddToPrompt" .../>

    <!-- 新增 Action -->
    <action id="com.shenyuanlaolarou.opencodewebui.CodeContext"
            class="com.github.xausky.opencodewebui.actions.CodeContextAction"
            text="Send Code Context to OpenCode"
            description="Send current code context to OpenCode for analysis">
        <add-to-group group-id="EditorPopupMenu" anchor="after"
                      relative-to-action="com.shenyuanlaolarou.opencodewebui.AddToPrompt"/>
    </action>

    <action id="com.shenyuanlaolarou.opencodewebui.GitAssist"
            class="com.github.xausky.opencodewebui.actions.GitAssistAction"
            text="Git Assist with OpenCode"
            description="Use OpenCode to generate commit messages and review changes">
        <add-to-group group-id="VcsGroup" anchor="after"/>
    </action>
</actions>
```

---

## 6. 测试场景

### 6.1 代码上下文测试

| 场景     | 操作                            | 预期结果              |
| -------- | ------------------------------- | --------------------- |
| 光标位置 | 在方法内点击，执行 Code Context | AI 知道当前方法和类   |
| 选中文本 | 选中变量名，执行 Code Context   | AI 知道变量类型和使用 |
| 文件级别 | 在文件顶部执行 Code Context     | AI 知道整个文件结构   |

### 6.2 代码导航测试

| 场景       | 操作                 | 预期结果           |
| ---------- | -------------------- | ------------------ |
| 单文件引用 | AI 返回 `file.kt:42` | 点击跳转到第 42 行 |
| 跨文件引用 | AI 返回多个文件位置  | 可以导航到所有位置 |
| 无效路径   | AI 返回不存在的路径  | 显示友好错误提示   |

### 6.3 Git 集成测试

| 场景       | 操作                              | 预期结果       |
| ---------- | --------------------------------- | -------------- |
| 单文件变更 | 修改一个文件，生成 commit message | 准确描述变更   |
| 多文件变更 | 修改多个文件，生成 commit message | 概括所有变更   |
| 新文件     | 新建文件，生成 commit message     | 包含"添加"描述 |

---

## 7. 相关代码文件

| 文件                                | 用途             |
| ----------------------------------- | ---------------- |
| `actions/CopyAsPromptAction.kt`     | 现有 Action 参考 |
| `actions/AddToPromptAction.kt`      | 现有 Action 参考 |
| `utils/OpenCodeApi.kt`              | HTTP API 调用    |
| `utils/JcefJsInjector.kt`           | JS 注入参考      |
| `listeners/OpenCodeSSEConsumer.kt`  | SSE 事件处理     |
| `toolWindow/MyToolWindowFactory.kt` | 工具窗口集成     |

---

## 8. 参考资源

| 资源                      | URL                                                            |
| ------------------------- | -------------------------------------------------------------- |
| IntelliJ SDK Code Samples | https://github.com/JetBrains/intellij-sdk-code-samples         |
| PsiElement 文档           | https://plugins.jetbrains.com/docs/intellij/psi-elements.html  |
| Action System             | https://plugins.jetbrains.com/docs/intellij/action-system.html |
| OpenCode API 文档         | 运行 opencode 后访问 `GET /doc`                                |

---

## 9. 待深入调研

- [ ] IDEA 的 `InlayProvider` API 用于显示 AI 建议
- [ ] `RefactoringSupportProvider` 用于集成重构
- [ ] `XDebuggerExtension` 用于调试集成
- [ ] `TerminalRunner` 用于终端集成
- [ ] OkHttp 在 IntelliJ 插件中的最佳实践
- [ ] Kotlin Coroutines 在 IntelliJ 插件中的使用
