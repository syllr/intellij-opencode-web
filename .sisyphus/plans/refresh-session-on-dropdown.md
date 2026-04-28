# Session 下拉框优化

## TL;DR
1. 下拉框打开时，重新查询并更新 session 列表
2. 添加向下箭头按钮，类似 Commit 工具的样式

## Context
用户希望 PromptEditor 的 session 下拉框有更好的用户体验：
- 下拉时刷新列表
- 有向下箭头图标

## 修改内容

### PromptToolWindowPanel.kt

1. **添加 PopupMenuListener**
监听下拉框的弹出事件，当打开时重新加载 sessions

```kotlin
sessionComboBox.addPopupMenuListener(object : PopupMenuListener {
    override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
        refreshSessions()
    }
    override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
    override fun popupMenuCanceled(e: PopupMenuEvent?) {}
})
```

2. **添加 refreshSessions() 方法**
```kotlin
private fun refreshSessions() {
    val projectPath = project.basePath ?: return
    Thread {
        val sessions = PromptEditorService.getSessions(projectPath)
            .filter { !it.isArchived }
            .sortedByDescending { it.createdAt }
        SwingUtilities.invokeLater {
            sessionComboBox.removeAllItems()
            sessions.forEach {
                sessionComboBox.addItem(it)
            }
            if (sessions.isNotEmpty()) {
                sessionComboBox.selectedItem = sessions.first()
            }
        }
    }.start()
}
```

3. **添加向下箭头图标**
可以用 `JButton` 显示箭头图标，或者找 IntelliJ 内置的图标
比如用 `UIManager.getIcon("Tree.expandedIcon")`

## Files to Modify
- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`

## Success Criteria
- [x] 下拉框打开时，刷新 session 列表
- [ ] 添加向下箭头图标
- [x] 编译通过

## 备注
箭头图标需要查看 Commit 工具窗口的实现方式，目前下拉框默认就有箭头
