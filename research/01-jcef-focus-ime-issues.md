# JCEF 失焦与输入法卡顿问题调研

**调研时间**: 2026-05-30
**调研目标**: 定位 JCEF 页面失焦和输入法卡顿的根因，提出解决方案

---

## 1. 问题描述

在 IntelliJ IDEA 的 OpenCodeWeb 工具窗口中，基于 JCEF 的 Web 页面存在以下问题：

- **失焦**: 页面偶尔会失去焦点，导致键盘输入无法到达 JCEF 浏览器
- **输入法卡顿**: 中文输入法（IME）在 JCEF 中会出现卡顿或无响应
- **临时修复**: 刷新页面后问题会暂时消失

---

## 2. 当前实现分析

### 2.1 JCEF 初始化配置

**文件**: `MyToolWindowFactory.kt:46-50`

```kotlin
internal val sharedJBCefClient by lazy {
    Registry.get("ide.browser.jcef.gpu.disable").setValue(false)
    System.setProperty("ide.browser.jcef.extra.args",
        "--enable-gpu-compositing,--use-gl=angle,--use-angle=metal")
    JBCefApp.getInstance().createClient()
}
```

**关键发现**:

- 启用了 GPU 加速 (`--enable-gpu-compositing`)
- 使用 ANGLE/Metal 渲染后端 (`--use-gl=angle,--use-angle=metal`)
- 这些参数可能与某些 macOS 版本的 IME 实现冲突

### 2.2 浏览器创建方式

**文件**: `BrowserPanel.kt:103-108`

```kotlin
val createdBrowser = JBCefBrowserBuilder()
    .setClient(sharedClient)
    .setUrl(url)
    .build()
add(createdBrowser.component, BorderLayout.CENTER)
```

**关键点**:

- 使用 `JBCefBrowserBuilder` 创建浏览器（已修复弃用 API 问题）
- 使用 `browser.component`（JCEF 的 Swing 封装组件）而非 `browser.cefBrowser.uiComponent`（OSR 渲染组件）

### 2.3 焦点管理机制

**文件**: `MyToolWindow.kt:69-81, 96-116`

```kotlin
private fun setupWindowFocusListener(toolWindow: ToolWindow) {
    browserPanel.addHierarchyListener {
        SwingUtilities.getWindowAncestor(browserPanel)?.let { window ->
            window.addWindowFocusListener(object : WindowAdapter() {
                override fun windowGainedFocus(e: WindowEvent?) {
                    if (toolWindow.isVisible) {
                        requestBrowserFocus()
                    }
                }
            })
        }
    }
}

fun requestBrowserFocus() {
    ApplicationManager.getApplication().invokeLater {
        val browser = browserPanel.getBrowser() ?: return@invokeLater
        val osrComponent = browser.cefBrowser.uiComponent
        val browserComponent = browser.component

        osrComponent?.let { comp ->
            if (comp.isFocusable) {
                comp.requestFocus()
            }
        }
        if (browserComponent.isFocusable) {
            browserComponent.requestFocus()
        }
    }
}
```

**问题分析**:

1. 焦点请求使用 `invokeLater` 异步执行，可能导致焦点状态不同步
2. 分别对 `osrComponent` 和 `browserComponent` 请求焦点，但 Chromium 内部焦点管理可能未同步
3. 没有处理 `CefFocusHandler` 的焦点事件回调

### 2.4 键盘拦截机制

**文件**: `JcefKeyboardInterceptor.kt:31-41`

```kotlin
fun interceptKeys(component: Component) {
    if (component !is JComponent) return
    component.focusTraversalKeysEnabled = false
    val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
    val actionMap = component.actionMap
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "block")
    actionMap.put("block", emptyAction)
}
```

**问题分析**:

- 仅拦截 ESC 键，未处理其他可能影响焦点的按键
- `WHEN_IN_FOCUSED_WINDOW` 可能与其他快捷键冲突

### 2.5 IME 处理

**文件**: `AddToPromptAction.kt:23-39`

```kotlin
companion object {
    private val IM_SELECT_PATH = "/Users/yutao/Desktop/software/bin/im-select"
    private val IM_SELECT_ARG_EN = "com.apple.keylayout.ABC"
    private val imSelectAvailable by lazy { File(IM_SELECT_PATH).exists() }
}

private fun switchInputMethod(arg: String) {
    if (imSelectAvailable) {
        try {
            Runtime.getRuntime().exec(arrayOf(IM_SELECT_PATH, arg))
        } catch (e: Exception) {
            thisLogger().debug("[AddToPromptAction] Failed to switch input method: ${e.message}")
        }
    }
}
```

**问题分析**:

- 使用外部工具 `im-select` 切换输入法，这是临时方案
- 硬编码路径，需要改为可配置
- 在 JCEF 和编辑器之间切换时强制切换输入法，可能干扰用户输入习惯

---

## 3. 根因分析

### 3.1 JCEF 焦点管理架构

JCEF 使用 **OSR (Off-Screen Rendering)** 模式，焦点管理涉及三层：

```
┌─────────────────────────────────────┐
│  IntelliJ IDEA (Swing)              │
│  ┌─────────────────────────────┐   │
│  │  JBCefBrowser.component     │   │
│  │  (JCEF Swing Wrapper)       │   │
│  │  ┌─────────────────────┐   │   │
│  │  │  CefBrowser         │   │   │
│  │  │  (Chromium 渲染)    │   │   │
│  │  │  ┌─────────────┐   │   │   │
│  │  │  │  uiComponent │   │   │   │
│  │  │  │  (OSR Canvas)│   │   │   │
│  │  │  └─────────────┘   │   │   │
│  │  └─────────────────────┘   │   │
│  └─────────────────────────────┘   │
└─────────────────────────────────────┘
```

**焦点同步问题**:

1. Swing 焦点系统和 Chromium 焦点系统是**独立的**
2. 当 Swing 焦点转移到 JCEF 组件时，Chromium 内部焦点可能未同步
3. `requestFocus()` 只请求 Swing 焦点，不触发 Chromium 的 `CefFocusHandler.onSetFocus()`

### 3.2 IME 卡顿根因

**Chromium IME 处理流程**:

```
键盘事件 → Chromium 输入事件队列 → IME 处理 → 渲染更新
                ↑
        可能被阻塞的地方
```

**可能的阻塞点**:

1. **GPU 渲染管道**: `--enable-gpu-compositing` 可能导致渲染延迟
2. **IME Composition 事件**: Chromium 的 `CefCompositionHandler` 可能未正确处理 macOS 的 NSTextInputClient 协议
3. **焦点丢失**: 当焦点在 Swing 和 Chromium 之间切换时，IME 上下文可能丢失

### 3.3 刷新修复问题的解释

刷新页面后问题消失的原因：

1. **Chromium 渲染状态重置**: 刷新重建了整个渲染管道
2. **IME 上下文重建**: 重新创建了 IME 处理上下文
3. **焦点状态同步**: 刷新后焦点状态重新同步

---

## 4. 解决方案

### 4.1 方案一：实现 CefFocusHandler（推荐）

**原理**: 通过 CEF 的焦点回调机制，确保 Swing 焦点和 Chromium 焦点同步。

**实现思路**:

```kotlin
// 在 BrowserPanel.kt 中添加
sharedClient.addFocusHandler(object : CefFocusHandlerAdapter() {
    override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource) {
        // Chromium 获得焦点时，同步 Swing 焦点
        ApplicationManager.getApplication().invokeLater {
            browser?.let { b ->
                val component = (b as JBCefBrowserBase).component
                if (component.isFocusable) {
                    component.requestFocusInWindow()
                }
            }
        }
    }

    override fun onGotFocus(browser: CefBrowser?) {
        // Chromium 获得焦点的确认
        thisLogger().debug("[JCEF] Chromium got focus")
    }
}, createdBrowser.cefBrowser)
```

**优点**:

- 使用 CEF 原生焦点回调机制
- 确保焦点状态完全同步

**缺点**:

- 需要处理 `CefFocusHandler` 的各种焦点来源
- 可能引入新的焦点循环

### 4.2 方案二：优化 IME Composition 处理

**原理**: 通过 `CefCompositionHandler` 正确处理 macOS 的输入法组合事件。

**实现思路**:

```kotlin
// 在 BrowserPanel.kt 中添加
sharedClient.addCompositionHandler(object : CefCompositionHandlerAdapter() {
    override fun onSetComposition(
        browser: CefBrowser?,
        text: String?,
        attributes: MutableMap<CefCompositionUnderline, Long>?,
        cursorPosition: Int,
        selectionRangeStart: Int,
        selectionRangeEnd: Int
    ) {
        // 处理输入法组合状态
        thisLogger().debug("[IME] Composition: $text, cursor=$cursorPosition")
    }

    override fun onResetComposition(browser: CefBrowser?) {
        // 输入法组合重置
        thisLogger().debug("[IME] Composition reset")
    }
}, createdBrowser.cefBrowser)
```

**优点**:

- 正确处理输入法组合事件
- 可以监控 IME 状态变化

**缺点**:

- 需要深入了解 macOS 输入法协议
- 可能无法完全解决卡顿问题

### 4.3 方案三：禁用 GPU 加速测试

**原理**: 测试 GPU 渲染是否是卡顿的原因。

**实现思路**:

```kotlin
// 修改 MyToolWindowFactory.kt
internal val sharedJBCefClient by lazy {
    // 禁用 GPU 加速进行测试
    Registry.get("ide.browser.jcef.gpu.disable").setValue(true)
    // 移除 GPU 相关参数
    // System.setProperty("ide.browser.jcef.extra.args",
    //     "--enable-gpu-compositing,--use-gl=angle,--use-angle=metal")
    JBCefApp.getInstance().createClient()
}
```

**优点**:

- 简单快速，可以快速验证假设
- 如果有效，说明是 GPU 渲染问题

**缺点**:

- 可能影响渲染性能
- 不是根本解决方案

### 4.4 方案四：实现焦点状态监控和自动恢复

**原理**: 监控焦点状态，在检测到失焦时自动恢复。

**实现思路**:

```kotlin
// 在 MyToolWindow.kt 中添加
private var focusMonitorThread: Thread? = null
private var lastFocusTime = System.currentTimeMillis()

private fun startFocusMonitor() {
    focusMonitorThread = Thread {
        while (running) {
            Thread.sleep(1000)
            val browser = browserPanel.getBrowser() ?: continue
            val component = browser.component

            // 检查是否在期望的焦点状态
            val currentFocusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val isFocusInJCEF = SwingUtilities.isDescendingFrom(currentFocusOwner, component)

            if (toolWindow.isVisible && !isFocusInJCEF) {
                // 检查是否应该有焦点（窗口可见但焦点不在 JCEF）
                val timeSinceLastFocus = System.currentTimeMillis() - lastFocusTime
                if (timeSinceLastFocus > 5000) { // 5 秒无焦点活动
                    thisLogger().warn("[Focus] Auto-restoring focus to JCEF")
                    requestBrowserFocus()
                }
            }
        }
    }.apply {
        isDaemon = true
        name = "FocusMonitor"
        start()
    }
}
```

**优点**:

- 自动检测和恢复失焦状态
- 不依赖 CEF 原生 API

**缺点**:

- 可能干扰用户主动切换焦点
- 需要合理的超时设置

---

## 5. 推荐实施路径

### 阶段一：快速验证（1-2 天）

1. **禁用 GPU 加速测试**
   - 修改 `MyToolWindowFactory.kt` 禁用 GPU 参数
   - 观察 IME 卡顿是否改善
   - 记录测试结果

2. **添加焦点状态日志**
   - 在 `requestBrowserFocus()` 添加详细日志
   - 记录 `isFocusable` 状态和实际焦点状态
   - 分析失焦发生的时间模式

### 阶段二：核心修复（3-5 天）

1. **实现 CefFocusHandler**
   - 添加 `CefFocusHandlerAdapter` 处理焦点同步
   - 测试各种焦点切换场景
   - 确保不引入焦点循环

2. **优化 IME Composition 处理**
   - 添加 `CefCompositionHandlerAdapter`
   - 监控输入法组合状态
   - 测试中文输入法场景

### 阶段三：增强稳定性（2-3 天）

1. **实现焦点状态监控**
   - 添加焦点监控线程
   - 自动检测和恢复失焦状态
   - 添加用户可配置选项

2. **配置化 IME 处理**
   - 将 `im-select` 路径改为可配置
   - 支持多种输入法切换方式
   - 添加输入法状态指示器

---

## 6. 测试场景

### 6.1 焦点测试

| 场景            | 操作                            | 预期结果                   |
| --------------- | ------------------------------- | -------------------------- |
| 工具窗口切换    | 在编辑器和 OpenCodeWeb 之间切换 | 焦点正确跟随，无失焦       |
| 快捷键使用      | 按 Cmd+K、Cmd+,                 | 快捷键正确传递给网页       |
| ESC 按键        | 在 JCEF 中按 ESC                | ESC 传递给网页，焦点不丢失 |
| 窗口最小化/恢复 | 最小化 IDE 后恢复               | 焦点状态正确恢复           |

### 6.2 IME 测试

| 场景         | 操作                     | 预期结果             |
| ------------ | ------------------------ | -------------------- |
| 中文输入     | 在 JCEF 输入框中输入中文 | 无卡顿，输入流畅     |
| 输入法切换   | 切换中英文输入法         | 切换正常，无残留状态 |
| 长文本输入   | 输入长段中文文本         | 无延迟，响应及时     |
| 输入法候选词 | 显示候选词列表           | 候选词正确显示和选择 |

### 6.3 稳定性测试

| 场景       | 操作                    | 预期结果         |
| ---------- | ----------------------- | ---------------- |
| 长时间使用 | 连续使用 2 小时         | 无性能下降       |
| 多会话切换 | 在多个 session 之间切换 | 焦点状态正确     |
| 服务器重启 | 重启 opencode 服务器    | 焦点状态正确恢复 |

---

## 7. 相关代码文件

| 文件                         | 行号          | 说明                 |
| ---------------------------- | ------------- | -------------------- |
| `MyToolWindowFactory.kt`     | 46-50         | JCEF GPU 配置        |
| `BrowserPanel.kt`            | 103-152       | 浏览器创建和 JS 注入 |
| `MyToolWindow.kt`            | 69-81, 96-116 | 焦点管理             |
| `JcefKeyboardInterceptor.kt` | 31-41         | ESC 键拦截           |
| `AddToPromptAction.kt`       | 23-39         | IME 切换             |
| `EmacsKeyHandler.kt`         | 20-37         | Emacs 按键映射       |

---

## 8. 参考资源

| 资源                      | URL                                                                                   |
| ------------------------- | ------------------------------------------------------------------------------------- |
| JCEF 官方文档             | https://chromiumembedded.github.io/java-cef/                                          |
| CefFocusHandler API       | https://chromiumembedded.github.io/java-cef/ Reference/cef/CefFocusHandler.html       |
| CefCompositionHandler API | https://chromiumembedded.github.io/java-cef/ Reference/cef/CefCompositionHandler.html |
| IntelliJ JCEF 示例        | https://github.com/JetBrains/intellij-sdk-code-samples/tree/master/jcef               |
| macOS 输入法协议          | Apple NSTextInputClient 协议文档                                                      |

---

## 9. 待深入调研

- [ ] CefFocusHandler 的 `FocusSource` 枚举值含义
- [ ] macOS 输入法与 Chromium 的集成机制
- [ ] IntelliJ 平台的 JCEF 扩展点
- [ ] 其他 JCEF 插件的焦点处理最佳实践
- [ ] Chromium 的 IME Composition 事件处理细节
