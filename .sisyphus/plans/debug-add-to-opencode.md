# Add to OpenCode 功能排查计划

## 问题描述
点击 "Add to OpenCode" 后，OpenCode 输入框没有收到追加的内容。需要排查是 Action 获取内容的问题还是 API 调用的问题。

## 排查步骤

### 步骤 1: 在 AddToOpenCodeAction 添加调试日志

**文件**: `src/main/kotlin/com/github/xausky/opencodewebui/actions/AddToOpenCodeAction.kt`

**修改**: 在 `actionPerformed` 方法中添加调试输出

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    val project: Project? = e.project
    if (project == null) {
        println("=== AddToOpenCode: project is null")
        return
    }

    val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
    if (editor == null) {
        println("=== AddToOpenCode: editor is null")
        return
    }

    val selectionModel = editor.selectionModel
    val selectedText = selectionModel.selectedText
    if (selectedText.isNullOrBlank()) {
        println("=== AddToOpenCode: selectedText is null or blank")
        return
    }

    println("=== AddToOpenCode: selectedText = $selectedText")

    val psiFile: PsiFile? = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
    if (psiFile == null) {
        println("=== AddToOpenCode: psiFile is null")
        return
    }

    val filePath = psiFile.virtualFile?.path ?: run {
        println("=== AddToOpenCode: virtualFile is null")
        return
    }
    println("=== AddToOpenCode: filePath = $filePath")

    val document = editor.document
    val startOffset = selectionModel.selectionStart
    val endOffset = selectionModel.selectionEnd
    
    val startLine = document.getLineNumber(startOffset) + 1
    val endLine = document.getLineNumber(endOffset) + 1
    println("=== AddToOpenCode: startLine = $startLine, endLine = $endLine")

    val lineRange = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
    val formattedContent = "$filePath:$lineRange\n$selectedText"
    println("=== AddToOpenCode: formattedContent = $formattedContent")

    appendToOpenCode(formattedContent)
}
```

**同时在 `appendToOpenCode` 方法中也添加日志**:

```kotlin
private fun appendToOpenCode(content: String) {
    try {
        println("=== AddToOpenCode: calling API with content = $content")
        val client = HttpClient.newHttpClient()
        val requestBody = """{"text": "${escapeJson(content)}"}"""
        println("=== AddToOpenCode: requestBody = $requestBody")
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://$SERVER_HOST:$SERVER_PORT$API_ENDPOINT"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        println("=== AddToOpenCode: response status = ${response.statusCode()}")
        println("=== AddToOpenCode: response body = ${response.body()}")
        
        if (response.statusCode() != 200) {
            println("=== AddToOpenCode: API returned status ${response.statusCode()}")
        }
    } catch (ex: Exception) {
        println("=== AddToOpenCode: Failed to append - ${ex.message}")
        ex.printStackTrace()
    }
}
```

### 步骤 2: 重新构建插件

```bash
./gradlew buildPlugin
```

### 步骤 3: 运行 IDE 并测试

1. 运行 `./gradlew runIde` 启动带插件的 IDE
2. 打开一个文件，选中一些代码
3. 右键 → "Add to OpenCode"
4. 查看 IDEA 日志窗口或控制台输出

### 步骤 4: 如果步骤 1-3 确认内容获取正常但 API 调用失败，测试 API

**在终端直接测试 API**:

```bash
curl -X POST http://127.0.0.1:12396/tui/append-prompt \
  -H "Content-Type: application/json" \
  -d '{"text": "/test/file.kt:10\nconsole.log(\"hello\")"}'
```

如果 API 返回错误或超时，说明 OpenCode 服务可能没运行或端口不对。

---

## 执行代理

委托 `quick` 类型代理执行此排查计划。

**需要用户配合**:
1. 确认是否运行 `./gradlew buildPlugin` 后重新安装插件
2. 确认 OpenCode 服务是否在 `127.0.0.1:12396` 运行
3. 查看日志输出并反馈结果
