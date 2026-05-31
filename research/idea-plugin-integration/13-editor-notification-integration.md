# IntelliJ 编辑器集成和通知系统详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供编辑器集成和通知系统技术指导

---

## 1. 编辑器集成概述

### 1.1 核心组件

| 组件             | 说明       | 用途                |
| ---------------- | ---------- | ------------------- |
| `EditorFactory`  | 编辑器工厂 | 监听编辑器创建/销毁 |
| `Editor`         | 编辑器实例 | 获取当前编辑器状态  |
| `Document`       | 文档模型   | 获取/修改文档内容   |
| `SelectionModel` | 选区模型   | 获取/设置选中文本   |
| `CaretModel`     | 光标模型   | 获取/设置光标位置   |

---

## 2. 监听编辑器变化

### 2.1 EditorFactoryListener

```kotlin
class MyEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project

        // 编辑器创建时的处理
        println("Editor created: ${editor.virtualFile?.name}")

        // 添加文档监听器
        editor.document.addDocumentListener(MyDocumentListener(editor))

        // 添加编辑器监听器
        editor.addEditorMouseListener(MyEditorMouseListener())
        editor.addEditorMotionListener(MyEditorMotionListener())
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor

        // 编辑器释放时的处理
        println("Editor released: ${editor.virtualFile?.name}")
    }
}
```

### 2.2 DocumentListener

```kotlin
class MyDocumentListener(private val editor: Editor) : DocumentListener {

    override fun beforeDocumentChange(event: DocumentEvent) {
        // 文档变化前的处理
        val offset = event.offset
        val oldLength = event.oldLength
        val newLength = event.newLength

        println("Document will change at offset $offset")
    }

    override fun documentChanged(event: DocumentEvent) {
        // 文档变化后的处理
        val offset = event.offset
        val oldText = event.oldFragment.toString()
        val newText = event.newFragment.toString()

        println("Document changed at offset $offset")
        println("Old text: $oldText")
        println("New text: $newText")

        // 更新 AI 上下文
        updateAiContext(editor, offset, newText)
    }

    private fun updateAiContext(editor: Editor, offset: Int, text: String) {
        // 构建 AI 上下文
        val context = buildMap {
            put("file", editor.virtualFile?.path)
            put("line", editor.document.getLineNumber(offset) + 1)
            put("change", text)
            put("timestamp", System.currentTimeMillis())
        }

        // 发送到 opencode
        OpenCodeApiClient.sendContextUpdate(context)
    }
}
```

### 2.3 EditorMouseListener

```kotlin
class MyEditorMouseListener : EditorMouseListener {

    override fun mousePressed(event: EditorMouseEvent) {
        val editor = event.editor
        val offset = event.offset

        // 获取点击位置的元素
        val psiFile = PsiDocumentManager.getInstance(editor.project!!)
            .getPsiFile(editor.document)
        val element = psiFile?.findElementAt(offset)

        if (element != null) {
            println("Clicked on element: ${element.text}")
            println("Element type: ${element.javaClass.simpleName}")

            // 分析元素上下文
            analyzeElementContext(element)
        }
    }

    private fun analyzeElementContext(element: PsiElement) {
        // 获取元素上下文
        val context = buildMap {
            put("text", element.text)
            put("type", element.javaClass.simpleName)
            put("parent", element.parent?.javaClass?.simpleName)
            put("children", element.children.map { it.javaClass.simpleName })
        }

        println("Element context: $context")
    }
}
```

---

## 3. 获取编辑器上下文

### 3.1 获取当前编辑器状态

```kotlin
fun getEditorContext(project: Project): EditorContext? {
    val editorManager = FileEditorManager.getInstance(project)
    val editor = editorManager.selectedTextEditor ?: return null

    val document = editor.document
    val selectionModel = editor.selectionModel
    val caretModel = editor.caretModel

    return EditorContext(
        filePath = editor.virtualFile?.path,
        fileName = editor.virtualFile?.name,
        line = document.getLineNumber(caretModel.offset) + 1,
        column = caretModel.offset - document.getLineStartOffset(document.getLineNumber(caretModel.offset)),
        hasSelection = selectionModel.hasSelection(),
        selectedText = selectionModel.selectedText,
        selectionStart = selectionModel.selectionStart,
        selectionEnd = selectionModel.selectionEnd,
        textLength = document.textLength,
        lineCount = document.lineCount
    )
}

data class EditorContext(
    val filePath: String?,
    val fileName: String?,
    val line: Int,
    val column: Int,
    val hasSelection: Boolean,
    val selectedText: String?,
    val selectionStart: Int,
    val selectionEnd: Int,
    val textLength: Int,
    val lineCount: Int
)
```

### 3.2 获取代码上下文

```kotlin
fun getCodeContext(editor: Editor, project: Project): CodeContext? {
    val psiFile = PsiDocumentManager.getInstance(project)
        .getPsiFile(editor.document) ?: return null

    val offset = editor.caretModel.offset
    val element = psiFile.findElementAt(offset) ?: return null

    return CodeContext(
        file = FileContext(
            filePath = psiFile.virtualFile?.path ?: "",
            fileName = psiFile.virtualFile?.name ?: "",
            packageName = getPackageName(psiFile),
            language = psiFile.language.id
        ),
        cursor = CursorContext(
            line = editor.document.getLineNumber(offset) + 1,
            column = offset - editor.document.getLineStartOffset(editor.document.getLineNumber(offset)),
            offset = offset
        ),
        element = ElementContext(
            text = element.text,
            type = element.javaClass.simpleName,
            parentType = element.parent?.javaClass?.simpleName,
            parentText = element.parent?.text
        ),
        selection = SelectionContext(
            hasSelection = editor.selectionModel.hasSelection(),
            selectedText = editor.selectionModel.selectedText,
            startLine = editor.document.getLineNumber(editor.selectionModel.selectionStart) + 1,
            endLine = editor.document.getLineNumber(editor.selectionModel.selectionEnd) + 1
        )
    )
}
```

---

## 4. 修改编辑器内容

### 4.1 修改文档内容

```kotlin
fun modifyDocumentContent(editor: Editor, startOffset: Int, endOffset: Int, newText: String) {
    val document = editor.document

    // 使用 WriteCommandAction 执行修改
    WriteCommandAction.runWriteCommandAction(editor.project) {
        document.replaceString(startOffset, endOffset, newText)
    }
}

fun insertTextAtCaret(editor: Editor, text: String) {
    val document = editor.document
    val offset = editor.caretModel.offset

    WriteCommandAction.runWriteCommandAction(editor.project) {
        document.insertString(offset, text)
        editor.caretModel.moveToOffset(offset + text.length)
    }
}

fun deleteSelectedText(editor: Editor) {
    val document = editor.document
    val selectionModel = editor.selectionModel

    if (selectionModel.hasSelection()) {
        val start = selectionModel.selectionStart
        val end = selectionModel.selectionEnd

        WriteCommandAction.runWriteCommandAction(editor.project) {
            document.deleteString(start, end)
        }
    }
}
```

### 4.2 替换整个文件内容

```kotlin
fun replaceFileContent(project: Project, filePath: String, newContent: String) {
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
    val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

    WriteCommandAction.runWriteCommandAction(project) {
        document.setText(newContent)
    }
}
```

---

## 5. 通知系统

### 5.1 显示通知

```kotlin
fun showNotification(project: Project, title: String, content: String, type: NotificationType) {
    val notificationGroup = NotificationGroupManager.getInstance()
        .getNotificationGroup("OpenCodeWeb.notifications")

    val notification = notificationGroup.createNotification(
        title,
        content,
        type
    )

    // 添加动作
    notification.addAction(NotificationAction.createSimpleExpiring("查看") {
        // 打开相关文件或工具窗口
        OpenCodeWebToolWindowFactory.openToolWindow(project)
    })

    notification.notify(project)
}
```

### 5.2 通知类型

```kotlin
// 信息通知
showNotification(project, "AI 处理完成", "代码分析已完成", NotificationType.INFORMATION)

// 警告通知
showNotification(project, "代码问题", "发现潜在的代码问题", NotificationType.WARNING)

// 错误通知
showNotification(project, "处理失败", "AI 处理过程中发生错误", NotificationType.ERROR)
```

### 5.3 通知动作

```kotlin
fun showNotificationWithActions(project: Project) {
    val notification = NotificationGroupManager.getInstance()
        .getNotificationGroup("OpenCodeWeb.notifications")
        .createNotification(
            "AI 建议",
            "发现代码改进建议",
            NotificationType.INFORMATION
        )

    // 添加多个动作
    notification.addAction(NotificationAction.createSimpleExpiring("应用建议") {
        // 应用 AI 建议
        applyAiSuggestion(project)
    })

    notification.addAction(NotificationAction.createSimpleExpiring("忽略") {
        // 忽略建议
        ignoreSuggestion()
    })

    notification.addAction(NotificationAction.createSimpleExpiring("查看详情") {
        // 打开详细视图
        showSuggestionDetails(project)
    })

    notification.notify(project)
}
```

---

## 6. 进度指示器

### 6.1 显示进度

```kotlin
fun showProgress(project: Project, title: String, task: (ProgressIndicator) -> Unit) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            indicator.text = "正在处理..."

            task(indicator)
        }
    })
}

// 使用示例
showProgress(project, "AI 代码分析") { indicator ->
    indicator.text = "分析代码结构..."
    // 执行分析

    indicator.text = "生成建议..."
    // 生成建议

    indicator.text = "完成"
}
```

### 6.2 取消进度

```kotlin
fun showCancellableProgress(project: Project) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI 处理", true) {
        override fun run(indicator: ProgressIndicator) {
            var cancelled = false

            while (!cancelled) {
                if (indicator.isCanceled) {
                    cancelled = true
                    break
                }

                // 执行任务
                Thread.sleep(100)
            }
        }

        override fun onCancel() {
            // 处理取消
            println("Task cancelled")
        }
    })
}
```

---

## 7. 工具窗口通知

### 7.1 更新工具窗口内容

```kotlin
fun updateToolWindowContent(project: Project, content: String) {
    val toolWindowManager = ToolWindowManager.getInstance(project)
    val toolWindow = toolWindowManager.getToolWindow("OpenCodeWeb") ?: return

    val contentManager = toolWindow.contentManager
    val contentComponent = contentManager.selectedContent?.component ?: return

    // 更新内容
    if (contentComponent is JTextPane) {
        contentComponent.text = content
    } else if (contentComponent is JPanel) {
        // 更新面板内容
        updatePanelContent(contentComponent, content)
    }
}
```

### 7.2 工具窗口状态指示

```kotlin
fun updateToolWindowStatus(project: Project, status: String) {
    val toolWindowManager = ToolWindowManager.getInstance(project)
    val toolWindow = toolWindowManager.getToolWindow("OpenCodeWeb") ?: return

    // 更新工具窗口标题
    toolWindow.displayName = "OpenCodeWeb - $status"

    // 更新工具窗口图标
    when (status) {
        "connected" -> toolWindow.icon = ConnectedIcon
        "disconnected" -> toolWindow.icon = DisconnectedIcon
        "processing" -> toolWindow.icon = ProcessingIcon
    }
}
```

---

## 8. 性能优化

### 8.1 事件节流

```kotlin
class EventThrottler(private val intervalMs: Long = 100L) {
    private var lastEventTime = 0L
    private val eventQueue = ConcurrentLinkedQueue<Runnable>()
    private val processingThread = Thread {
        while (true) {
            Thread.sleep(intervalMs)

            val event = eventQueue.poll()
            if (event != null) {
                event.run()
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun throttle(event: Runnable) {
        val now = System.currentTimeMillis()
        if (now - lastEventTime >= intervalMs) {
            lastEventTime = now
            event.run()
        } else {
            eventQueue.offer(event)
        }
    }
}
```

### 8.2 异步处理

```kotlin
fun processAsync(project: Project, task: suspend () -> Unit) {
    CoroutineScope(Dispatchers.Default).launch {
        task()
    }
}

// 使用示例
processAsync(project) {
    val context = getCodeContext(editor, project)
    val suggestion = apiClient.getSuggestion(context)

    withContext(Dispatchers.Main) {
        showNotification(project, "AI 建议", suggestion, NotificationType.INFORMATION)
    }
}
```

---

## 9. 测试场景

### 9.1 编辑器集成测试

| 场景       | 操作         | 预期结果                      |
| ---------- | ------------ | ----------------------------- |
| 编辑器创建 | 打开文件     | 正确触发 editorCreated 事件   |
| 文档修改   | 修改文档内容 | 正确触发 documentChanged 事件 |
| 选中文本   | 选中文本     | 正确获取选中文本              |
| 光标移动   | 移动光标     | 正确获取光标位置              |

### 9.2 通知系统测试

| 场景     | 操作                  | 预期结果           |
| -------- | --------------------- | ------------------ |
| 显示通知 | 调用 showNotification | 正确显示通知       |
| 通知动作 | 点击通知动作          | 正确执行动作       |
| 进度显示 | 调用 showProgress     | 正确显示进度指示器 |
| 取消进度 | 取消进度任务          | 正确取消任务       |

---

## 10. 参考资源

| 资源                      | URL                                                            |
| ------------------------- | -------------------------------------------------------------- |
| IntelliJ Editor API       | https://plugins.jetbrains.com/docs/intellij/editor.html        |
| IntelliJ Notification API | https://plugins.jetbrains.com/docs/intellij/notifications.html |
| IntelliJ Progress API     | https://plugins.jetbrains.com/docs/intellij/progress.html      |
| IntelliJ SDK Code Samples | https://github.com/JetBrains/intellij-sdk-code-samples         |

---

## 11. 待深入调研

- [ ] 编辑器装饰器（InlayProvider）的使用
- [ ] 编辑器代码折叠的集成
- [ ] 编辑器书签的集成
- [ ] 编辑器断点的集成
- [ ] 多编辑器同步的实现
