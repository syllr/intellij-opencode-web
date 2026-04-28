# Session 下拉框显示优化计划

## 目标
1. 显示 session 的 name/title 而不是 session id
2. 不显示时间戳
3. 排序规则：
   - 第一优先级：当前选中的 session
   - 第二优先级：按时间戳排序（从新到旧）

## 需要修改的文件
src/main/kotlin/com/github/xausky/opencodewebui/utils/PromptEditorService.kt
src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt

## 修改 1: PromptEditorService.kt
修改 SessionResponse 和 Session 的 toString()：
```kotlin
private data class SessionResponse(
    val id: String,
    val directory: String,
    val title: String? = null,
    val name: String? = null,
    val time: TimeData
) {
    data class TimeData(
        val created: Long,
        val archived: Long? = null
    )

    fun toSession() = Session(
        id = id,
        directory = directory,
        name = title ?: name ?: "Session ${id.take(8)}",
        createdAt = time.created.toString(),
        archivedAt = time.archived?.toString()
    )
}

data class Session(
    val id: String,
    val directory: String,
    val name: String,
    val createdAt: String? = null,
    val archivedAt: String? = null
) {
    val isArchived: Boolean = archivedAt != null
    override fun toString() = name
}
```

## 修改 2: PromptToolWindowPanel.kt
修改 refreshSessions() 中的排序逻辑：
```kotlin
private fun doRefreshSessions(projectPath: String, currentSelected: Session? = null) {
    thisLogger().info("[PromptEditorService] loading sessions...")
    Thread {
        val sessions = PromptEditorService.getSessions(projectPath)
            .sortedWith(compareBy<Session> {
                // 第一优先级：当前选中的 session 在最前面
                currentSelected != null && it.id == currentSelected.id
            }.thenByDescending {
                // 第二优先级：按时间戳从新到旧排序
                it.createdAt?.toLongOrNull() ?: 0
            })
        
        thisLogger().info("[PromptEditorService] loaded ${sessions.size} sessions")
        
        SwingUtilities.invokeLater {
            val previousSelected = sessionComboBox.selectedItem
            sessionComboBox.removeAllItems()
            sessions.forEach {
                sessionComboBox.addItem(it)
            }
            if (previousSelected != null && sessions.contains(previousSelected)) {
                sessionComboBox.selectedItem = previousSelected
            } else if (sessions.isNotEmpty()) {
                sessionComboBox.selectedItem = sessions.first()
            }
            sendButton.isEnabled = sessionComboBox.selectedItem != null
        }
    }.start()
}
```

需要保存当前选中的 session，每次 refresh 时传入。

## 验证
./gradlew compileKotlin
