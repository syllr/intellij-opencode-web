# Loop 1: WindowFocusListener 泄漏机制深度分析

**日期**: 2026-05-30
**Oracle 审核**: 已完成
**结论**: 确认是真实泄漏，触发频率被初步高估但足以导致可感知卡顿

---

## 核心结论

### 确认的事实

1. **HierarchyEvent 会向父容器冒泡**：`Container.remove(int)` 调用 `fireHierarchyChanged`，事件沿父容器链向上冒泡，触发 `browserPanel` 上的 HierarchyListener
2. **Window.addWindowFocusListener 没有去重机制**：每次调用都添加新 entry 到 `WindowFocusListener[]` 数组
3. **HierarchyListener 本身不会累积**：`setupWindowFocusListener` 只在 `init{}` 调用一次，只有一个 HierarchyListener
4. **`revalidate()` 和 `repaint()` 不触发 HierarchyEvent**：它们只影响布局和绘制，不改变组件树

### 累积量化

| 场景                                     | HierarchyEvent 次数 |
| ---------------------------------------- | ------------------- |
| 首次加载 `checkAndLoadContent()`         | 2-4                 |
| 服务器重启（start button → load page）   | 4-6                 |
| Health 状态切换（unhealthy ↔ recovered） | 2-4                 |
| 手动 Restart Server                      | 6-10                |

**1 小时典型使用估算**：

- 正常使用（服务器稳定）：~10-20 个 listener
- 服务器不稳定：~40-80 个 listener
- 极端场景：~100+ 个 listener

### 影响评估

**单独不足以致命，但作为主要贡献者与其他泄漏叠加后足以解释"IDE 变卡"**：

- 窗口切换时短暂卡顿（EDT 任务风暴：N 个 `invokeLater` 排队）
- 长时间使用后越来越卡（listener 持续累积，永不释放）
- 重启后恢复（所有内存状态重置）

---

## 最佳修复方案

### 方案（Window 变化检查 + 引用保存）

```kotlin
// MyToolWindow.kt 新增字段
private var currentWindow: Window? = null
private var currentWindowFocusListener: WindowAdapter? = null
private var hierarchyListener: HierarchyListener? = null

private fun setupWindowFocusListener(toolWindow: ToolWindow) {
    hierarchyListener = HierarchyListener {
        val newWindow = SwingUtilities.getWindowAncestor(browserPanel)

        // 仅在 window 实际变化时才操作
        if (newWindow === currentWindow) return@HierarchyListener

        // 从旧 window 移除
        currentWindowFocusListener?.let { listener ->
            currentWindow?.removeWindowFocusListener(listener)
        }
        currentWindow = null
        currentWindowFocusListener = null

        // 添加到新 window
        if (newWindow != null) {
            val listener = object : WindowAdapter() {
                override fun windowGainedFocus(e: WindowEvent?) {
                    if (toolWindow.isVisible) requestBrowserFocus()
                }
            }
            newWindow.addWindowFocusListener(listener)
            currentWindow = newWindow
            currentWindowFocusListener = listener
        }
    }
    browserPanel.addHierarchyListener(hierarchyListener)
}
```

### Disposer 清理（关键：顺序很重要）

```kotlin
Disposer.register(project) {
    // 1. 先移除 HierarchyListener（防止后续 removeAll 触发）
    hierarchyListener?.let { browserPanel.removeHierarchyListener(it) }
    // 2. 再移除 WindowFocusListener
    currentWindowFocusListener?.let { listener ->
        currentWindow?.removeWindowFocusListener(listener)
    }
    currentWindow = null
    currentWindowFocusListener = null
    // 3. 最后才 disposeBrowser
    MyToolWindowFactory.myToolWindowInstances.remove(project)
    healthMonitor.stop()
    browserPanel.disposeBrowser()
}
```

### 关键注意事项

1. **Disposer 清理顺序**：HierarchyListener → WindowFocusListener → disposeBrowser。否则 `disposeBrowser()` 的 `removeAll()` 触发 HierarchyListener 又会添加新 listener
2. **HierarchyListener 必须保存为变量引用**：lambda 语法无法直接 `removeHierarchyListener`
3. **Window 变化检查 `newWindow === currentWindow`**：避免同一 window 上的无意义 remove+add
4. **可选：`requestBrowserFocus()` debounce**：泄漏修复后非必须，但可作为防御性编程

---

## 与 jcef-focus-ime 研究的关联

- 修复 WindowFocusListener 泄漏是 FocusAdapter 修复的**前置条件**
- 两者不冲突：WindowFocusListener 管理 Swing 窗口层面的焦点，FocusAdapter 管理 JCEF OSR 组件 ↔ Chromium 的焦点同步
- 建议实施顺序：先修复 WindowFocusListener 泄漏 → 再实施 FocusAdapter 注册
