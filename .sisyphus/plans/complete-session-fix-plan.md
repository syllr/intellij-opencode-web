# Session 下拉框完整修复计划

## 问题列表
1. 下拉框是空的！没有任何 Session！
2. 下拉框太丑了！没有合适的尺寸！
3. 没有自动创建 Session 的逻辑！

## 修复方案

### 1. 改进 Session 的 toString() 显示效果
让 Session 在下拉框里显示得更好看！
```kotlin
override fun toString(): String {
    return if (createdAt != null) {
        "Session ${id.take(8)} (${createdAt.take(16)})"
    } else {
        "Session ${id.take(8)}"
    }
}
```

### 2. 给 sessionComboBox 设置合适尺寸
```kotlin
private val sessionComboBox = ComboBox&lt;Session&gt;().apply {
    preferredSize = Dimension(300, 30)
    maximumRowCount = 10
    // ... 原有代码
}
```

### 3. 自动创建 Session 的逻辑
在 init 块里，如果 sessions 为空，并且 currentState == RUNNING，就调用 createSession() 创建新的！
```kotlin
if (sessions.isEmpty() &amp;&amp; currentState == ServerState.RUNNING) {
    val newSession = PromptEditorService.createSession(projectPath)
    if (newSession != null) {
        sessionComboBox.addItem(newSession)
        sessionComboBox.selectedItem = newSession
    }
}
```

### 4. doRefreshSessions 也处理自动创建
当下拉框打开时，如果 sessions 为空，也尝试创建！

## 修改文件
1. src/main/kotlin/com/github/xausky/opencodewebui/utils/PromptEditorService.kt
2. src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt
