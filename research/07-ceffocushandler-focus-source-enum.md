# CefFocusHandler.FocusSource 枚举详解

**调研时间**: 2026-05-30
**调研目标**: 完整分析 CefFocusHandler.FocusSource 枚举值及其处理方式

---

## 1. FocusSource 枚举概述

`CefFocusHandler.FocusSource` 是 CEF (Chromium Embedded Framework) 中用于标识焦点请求来源的枚举类型。理解每个枚举值的含义对于正确处理焦点事件至关重要。

---

## 2. FocusSource 枚举值

### 2.1 完整枚举列表

根据 CEF 官方文档和 JetBrains JCEF 源码，`FocusSource` 包含以下枚举值：

| 枚举值                      | 值  | 说明                                      |
| --------------------------- | --- | ----------------------------------------- |
| `FOCUS_SOURCE_NAVIGATION`   | 0   | 导航触发的焦点（URL 加载、页面跳转）      |
| `FOCUS_SOURCE_ACTIVATION`   | 1   | 窗口激活触发的焦点（Alt+Tab、点击任务栏） |
| `FOCUS_SOURCE_PROGRAMMATIC` | 2   | 程序代码触发的焦点（requestFocus 调用）   |
| `FOCUS_SOURCE_BY_SYSTEM`    | 3   | 系统触发的焦点（操作系统级别）            |
| `FOCUS_SOURCE_OTHER`        | 4   | 其他来源                                  |

### 2.2 枚举值详细说明

#### FOCUS_SOURCE_NAVIGATION (0)

**触发时机**:

- URL 加载完成
- 页面跳转（JavaScript `window.location`）
- iframe 加载
- 表单提交

**特点**:

- 最常见的焦点来源
- 可能在页面加载过程中多次触发
- 在 Windows 上可能导致焦点丢失（已知问题）

**处理建议**:

```kotlin
if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_NAVIGATION) {
    // 延迟焦点请求，等待页面加载稳定
    ApplicationManager.getApplication().invokeLater {
        browser?.uiComponent?.requestFocusInWindow()
    }
    return false  // 接受焦点
}
```

#### FOCUS_SOURCE_ACTIVATION (1)

**触发时机**:

- 窗口从后台切换到前台（Alt+Tab）
- 点击任务栏图标
- 点击窗口标题栏
- 通过 API 激活窗口（`Window.toFront()`）

**特点**:

- 用户主动操作触发
- 焦点状态应该保持
- 不需要特殊处理

**处理建议**:

```kotlin
if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_ACTIVATION) {
    // 直接接受焦点
    return false
}
```

#### FOCUS_SOURCE_PROGRAMMATIC (2)

**触发时机**:

- 调用 `Component.requestFocus()`
- 调用 `Component.requestFocusInWindow()`
- 调用 `CefBrowser.setFocus(true)`
- 焦点管理器自动聚焦

**特点**:

- 由代码主动触发
- 应该立即接受焦点
- 不需要延迟处理

**处理建议**:

```kotlin
if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_PROGRAMMATIC) {
    // 立即接受焦点
    return false
}
```

#### FOCUS_SOURCE_BY_SYSTEM (3)

**触发时机**:

- 操作系统级别的焦点管理
- 无障碍工具触发
- 多显示器切换
- 远程桌面连接

**特点**:

- 由操作系统直接控制
- 可能不可预测
- 需要谨慎处理

**处理建议**:

```kotlin
if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_BY_SYSTEM) {
    // 根据当前状态决定是否接受
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    val componentFocused = focusOwner == browser?.uiComponent

    if (!componentFocused) {
        // 系统请求焦点，应该接受
        return false
    }
    // 已经有焦点，不需要处理
    return true
}
```

#### FOCUS_SOURCE_OTHER (4)

**触发时机**:

- 其他未分类的焦点来源
- 第三方库触发
- 插件触发

**特点**:

- 来源不明确
- 需要默认处理

**处理建议**:

```kotlin
if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_OTHER) {
    // 默认接受焦点
    return false
}
```

---

## 3. 平台特定行为

### 3.1 macOS

| FocusSource  | 行为         | 注意事项                     |
| ------------ | ------------ | ---------------------------- |
| NAVIGATION   | 正常接受焦点 | Metal 渲染可能影响焦点同步   |
| ACTIVATION   | 正常接受焦点 | Mission Control 切换可能触发 |
| PROGRAMMATIC | 正常接受焦点 | 无特殊问题                   |
| BY_SYSTEM    | 正常接受焦点 | Spotlight 搜索可能触发       |
| OTHER        | 正常接受焦点 | 无特殊问题                   |

**macOS 特殊处理**:

```kotlin
if (SystemInfo.isMac) {
    // macOS 上 Metal 渲染可能导致焦点同步延迟
    // 需要额外的焦点同步机制
    if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_NAVIGATION) {
        ApplicationManager.getApplication().invokeLater {
            browser?.uiComponent?.requestFocusInWindow()
        }
    }
}
```

### 3.2 Linux

| FocusSource  | 行为             | 注意事项                          |
| ------------ | ---------------- | --------------------------------- |
| NAVIGATION   | 需要手动请求焦点 | X11 焦点管理与 macOS/Windows 不同 |
| ACTIVATION   | 正常接受焦点     | 无特殊问题                        |
| PROGRAMMATIC | 需要手动请求焦点 | `requestFocus()` 可能不生效       |
| BY_SYSTEM    | 需要手动请求焦点 | 窗口管理器可能干预                |
| OTHER        | 需要手动请求焦点 | 无特殊问题                        |

**Linux 特殊处理**:

```kotlin
if (SystemInfo.isLinux) {
    // Linux 上需要手动请求焦点
    if (!browser?.uiComponent?.hasFocus()!!) {
        browser?.uiComponent?.requestFocus()
    }
}
```

### 3.3 Windows

| FocusSource  | 行为             | 注意事项                 |
| ------------ | ---------------- | ------------------------ |
| NAVIGATION   | 可能导致焦点丢失 | 已知问题：导航时焦点丢失 |
| ACTIVATION   | 正常接受焦点     | 无特殊问题               |
| PROGRAMMATIC | 正常接受焦点     | 无特殊问题               |
| BY_SYSTEM    | 正常接受焦点     | UAC 弹窗可能触发         |
| OTHER        | 正常接受焦点     | 无特殊问题               |

**Windows 特殊处理**:

```kotlin
if (SystemInfo.isWindows) {
    // Windows 上导航可能导致焦点丢失
    if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_NAVIGATION) {
        // 检查是否需要禁用导航焦点
        if (!focusOnNavigation) {
            myCefBrowser.setFocus(false)
            return true  // 抑制导航焦点
        }
    }
}
```

---

## 4. 完整实现示例

### 4.1 推荐的 FocusHandler 实现

```kotlin
class OpenCodeFocusHandler(
    private val logger: Logger
) : CefFocusHandlerAdapter() {

    private var hasFocus = false
    private var lastFocusSource: CefFocusHandler.FocusSource? = null

    override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource): Boolean {
        logger.debug("[JCEF Focus] onSetFocus: source=$focusSource")
        lastFocusSource = focusSource

        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val browserComponent = (browser as? JBCefBrowser)?.component
        val osrComponent = browser?.uiComponent
        val componentFocused = focusOwner == browserComponent || focusOwner == osrComponent

        return when (focusSource) {
            CefFocusHandler.FocusSource.FOCUS_SOURCE_NAVIGATION -> {
                // 导航来源：延迟焦点请求
                if (!componentFocused) {
                    ApplicationManager.getApplication().invokeLater {
                        osrComponent?.requestFocusInWindow()
                    }
                }
                false  // 接受焦点
            }

            CefFocusHandler.FocusSource.FOCUS_SOURCE_ACTIVATION -> {
                // 窗口激活：直接接受
                false
            }

            CefFocusHandler.FocusSource.FOCUS_SOURCE_PROGRAMMATIC -> {
                // 程序触发：立即接受
                false
            }

            CefFocusHandler.FocusSource.FOCUS_SOURCE_BY_SYSTEM -> {
                // 系统触发：根据状态决定
                if (!componentFocused) {
                    ApplicationManager.getApplication().invokeLater {
                        osrComponent?.requestFocusInWindow()
                    }
                    false
                } else {
                    true  // 已有焦点，不需要处理
                }
            }

            CefFocusHandler.FocusSource.FOCUS_SOURCE_OTHER -> {
                // 其他来源：默认接受
                false
            }

            else -> false
        }
    }

    override fun onGotFocus(browser: CefBrowser?) {
        logger.debug("[JCEF Focus] onGotFocus")
        hasFocus = true
    }

    override fun onTakeFocus(browser: CefBrowser?, next: Boolean) {
        logger.debug("[JCEF Focus] onTakeFocus: next=$next")
        hasFocus = false
    }

    fun hasFocus(): Boolean = hasFocus

    fun getLastFocusSource(): CefFocusHandler.FocusSource? = lastFocusSource
}
```

### 4.2 在 BrowserPanel.kt 中注册

```kotlin
// 在 createMainTab 方法中添加
val focusHandler = OpenCodeFocusHandler(thisLogger())
sharedClient.addFocusHandler(focusHandler, createdBrowser.cefBrowser)
```

### 4.3 在 MyToolWindow.kt 中使用

```kotlin
fun requestBrowserFocus() {
    ApplicationManager.getApplication().invokeLater {
        try {
            val browser = browserPanel.getBrowser() ?: return@invokeLater

            // 检查当前焦点状态
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val browserComponent = browser.component
            val osrComponent = browser.cefBrowser.uiComponent

            val componentFocused = focusOwner == browserComponent || focusOwner == osrComponent

            if (!componentFocused) {
                // 请求焦点
                osrComponent?.let { comp ->
                    if (comp.isFocusable) {
                        comp.requestFocusInWindow()
                    }
                }

                if (browserComponent.isFocusable) {
                    browserComponent.requestFocusInWindow()
                }

                logger.debug("[Focus] Requested browser focus")
            }
        } catch (e: Exception) {
            logger.warn("Failed to request browser focus: ${e.message}")
        }
    }
}
```

---

## 5. 测试场景

### 5.1 按 FocusSource 分类测试

| FocusSource  | 测试场景          | 预期行为     |
| ------------ | ----------------- | ------------ |
| NAVIGATION   | 加载新 URL        | 焦点正确恢复 |
| NAVIGATION   | JavaScript 跳转   | 焦点正确恢复 |
| ACTIVATION   | Alt+Tab 切换      | 焦点正确恢复 |
| ACTIVATION   | 点击任务栏        | 焦点正确恢复 |
| PROGRAMMATIC | 调用 requestFocus | 立即获得焦点 |
| PROGRAMMATIC | 工具窗口激活      | 焦点正确恢复 |
| BY_SYSTEM    | 多显示器切换      | 焦点正确恢复 |
| BY_SYSTEM    | 远程桌面连接      | 焦点正确恢复 |
| OTHER        | 第三方插件触发    | 焦点正确恢复 |

### 5.2 平台特定测试

| 平台    | 测试场景             | 预期行为         |
| ------- | -------------------- | ---------------- |
| macOS   | Metal 渲染 + 导航    | 焦点正确同步     |
| macOS   | Mission Control 切换 | 焦点正确恢复     |
| Linux   | X11 焦点管理         | 手动请求焦点生效 |
| Linux   | 窗口管理器切换       | 焦点正确恢复     |
| Windows | 导航焦点丢失         | 抑制导航焦点     |
| Windows | UAC 弹窗             | 焦点正确恢复     |

---

## 6. 调试技巧

### 6.1 启用焦点日志

```kotlin
// 在 FocusHandler 中添加详细日志
override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource): Boolean {
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    val browserComponent = (browser as? JBCefBrowser)?.component
    val osrComponent = browser?.uiComponent

    logger.info("[JCEF Focus] onSetFocus:")
    logger.info("  - focusSource: $focusSource")
    logger.info("  - focusOwner: ${focusOwner?.javaClass?.simpleName}")
    logger.info("  - browserComponent focused: ${focusOwner == browserComponent}")
    logger.info("  - osrComponent focused: ${focusOwner == osrComponent}")
    logger.info("  - osrComponent hasFocus: ${osrComponent?.hasFocus()}")
    logger.info("  - browserComponent hasFocus: ${browserComponent?.hasFocus()}")

    // ... 处理逻辑
}
```

### 6.2 焦点状态监控

```kotlin
class FocusStateMonitor(private val logger: Logger) {

    private var lastFocusOwner: Component? = null
    private var monitorThread: Thread? = null

    fun start(intervalMs: Long = 1000) {
        monitorThread = Thread {
            while (true) {
                Thread.sleep(intervalMs)
                val currentFocusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

                if (currentFocusOwner != lastFocusOwner) {
                    logger.info("[Focus Monitor] Focus changed:")
                    logger.info("  - from: ${lastFocusOwner?.javaClass?.simpleName}")
                    logger.info("  - to: ${currentFocusOwner?.javaClass?.simpleName}")
                    lastFocusOwner = currentFocusOwner
                }
            }
        }.apply {
            isDaemon = true
            name = "FocusStateMonitor"
            start()
        }
    }

    fun stop() {
        monitorThread?.interrupt()
        monitorThread = null
    }
}
```

---

## 7. 常见问题排查

### 7.1 焦点丢失问题

**症状**: 页面偶尔失去焦点，键盘输入无法到达 JCEF

**可能原因**:

1. 缺少 `CefFocusHandler` 实现
2. `FocusSource.NAVIGATION` 处理不当
3. 平台特定行为未处理

**排查步骤**:

1. 检查是否注册了 `addFocusHandler`
2. 检查 `onSetFocus` 的返回值
3. 启用焦点日志观察 FocusSource 值
4. 检查平台特定处理

### 7.2 IME 卡顿问题

**症状**: 中文输入法在 JCEF 中卡顿或无响应

**可能原因**:

1. 缺少 `CefCompositionHandler` 实现
2. GPU 渲染问题
3. 输入法候选词窗口定位错误

**排查步骤**:

1. 检查是否注册了 `addCompositionHandler`
2. 测试禁用 GPU 加速
3. 检查输入法候选词窗口位置
4. 启用 IME 日志观察组合事件

### 7.3 快捷键冲突问题

**症状**: ESC/Cmd+K/Cmd+, 无法正确传递给网页

**可能原因**:

1. `JcefKeyboardInterceptor` 未正确拦截
2. JS 注入未生效
3. 快捷键优先级冲突

**排查步骤**:

1. 检查 `JcefKeyboardInterceptor` 是否注册
2. 检查 JS 注入是否执行
3. 检查快捷键优先级
4. 测试不同场景下的快捷键行为

---

## 8. 参考资源

| 资源                   | URL                                                                                                 |
| ---------------------- | --------------------------------------------------------------------------------------------------- |
| CEF Focus Handler 文档 | https://bitbucket.org/chromiumembedded/cef/wiki/GeneralUsage.md                                     |
| JBCefBrowser 源码      | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefBrowser.java |
| CEF Issue Tracker      | https://github.com/chromiumembedded/cef/issues                                                      |
| JetBrains YouTrack     | https://youtrack.jetbrains.com                                                                      |

---

## 9. 待深入调研

- [ ] CEF 官方 FocusSource 文档的完整列表
- [ ] 不同 CEF 版本的 FocusSource 差异
- [ ] JetBrains JCEF 对 FocusSource 的封装
- [ ] 第三方 JCEF 插件的 FocusSource 处理示例
