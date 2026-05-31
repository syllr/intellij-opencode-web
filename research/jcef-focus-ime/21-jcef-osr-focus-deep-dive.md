# JCEF OSR 焦点管理深度分析

**调研时间**: 2026-05-30
**调研目标**: 基于 JetBrains 源码深度分析 JCEF OSR 焦点管理机制

---

## 1. 核心发现

### 1.1 OSR 模式下的焦点处理关键差异

**最重要的发现**: 在 OSR 模式下，`CefFocusHandler.onSetFocus()` **直接返回 `false`**，不进行任何 Swing 焦点处理！

```java
// JBCefBrowser.java 第 56-92 行
@Override
public boolean onSetFocus(CefBrowser browser, FocusSource source) {
    // ... 计算 focusOnNavigation ...

    // 导航来源且不需要焦点时，Windows 上显式设置 false
    if (source == FocusSource.FOCUS_SOURCE_NAVIGATION && !focusOnNavigation) {
        if (SystemInfo.isWindows) {
            myCefBrowser.setFocus(false);
        }
        return true; // 抑制浏览器获得焦点
    }

    // ⚠️ 关键：OSR 模式下直接返回，不处理！
    if (isOffScreenRendering()) {
        return false;  // 不做任何 Swing 焦点请求
    }

    // 非 OSR 模式下才请求 Swing 焦点
    if (!browser.getUIComponent().hasFocus()) {
        if (SystemInfo.isLinux) {
            browser.getUIComponent().requestFocus();
        } else {
            browser.getUIComponent().requestFocusInWindow();
        }
    }
    return false;
}
```

### 1.2 OSR 模式的焦点同步机制

OSR 模式的焦点同步由 **FocusAdapter** 而非 `CefFocusHandler` 负责：

```java
// JBCefBrowser.java 第 78-92 行
if (isOffScreenRendering()) {
    myCefBrowser.getUIComponent().addFocusListener(new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
            myCefBrowser.setFocus(true);  // Swing 焦点获得 → 通知 Chromium
        }
        @Override
        public void focusLost(FocusEvent e) {
            if (!contextMenuRunner.isShowing()) {
                myCefBrowser.setFocus(false);  // Swing 焦点失去 → 通知 Chromium
            }
        }
    });
}
```

### 1.3 焦点同步的完整流程

```
┌─────────────────────────────────────────────────────────────┐
│                    Swing Focus System                        │
│  KeyboardFocusManager → JPanel → JBCefOsrComponent           │
└────────────────────────┬────────────────────────────────────┘
                         │ FocusAdapter.focusGained()
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              myCefBrowser.setFocus(true)                     │
│  通知 Chromium 内部焦点系统                                    │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│               Chromium Internal Focus                        │
│  - onGotFocus: 更新 focusedBrowser 静态引用                 │
│  - onTakeFocus: 清除 focusedBrowser 引用                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 项目当前实现的问题分析

### 2.1 当前实现

**文件**: `MyToolWindow.kt:96-116`

```kotlin
fun requestBrowserFocus() {
    ApplicationManager.getApplication().invokeLater {
        val browser = browserPanel.getBrowser() ?: return@invokeLater
        val osrComponent = browser.cefBrowser.uiComponent
        val browserComponent = browser.component

        osrComponent?.let { comp ->
            if (comp.isFocusable) {
                comp.requestFocus()  // ⚠️ 只请求 Swing 焦点
            }
        }
        if (browserComponent.isFocusable) {
            browserComponent.requestFocus()
        }
    }
}
```

### 2.2 问题分析

1. **只请求 Swing 焦点，不同步 Chromium 焦点**
   - `requestFocus()` 只更新 Swing 焦点状态
   - 没有调用 `myCefBrowser.setFocus(true)` 通知 Chromium

2. **缺少 FocusAdapter 注册**
   - 没有在 `osrComponent` 上注册 `FocusListener`
   - Swing 焦点变化不会自动同步到 Chromium

3. **缺少 CefFocusHandler 实现**
   - 没有注册 `addFocusHandler`
   - 无法响应 Chromium 的焦点事件

---

## 3. 正确的实现方案

### 3.1 方案一：注册 FocusAdapter（推荐）

```kotlin
// 在 BrowserPanel.kt 的 createMainTab 方法中
private fun setupOsrFocusSync(browser: JBCefBrowser) {
    val osrComponent = browser.cefBrowser.uiComponent ?: return

    osrComponent.addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) {
            // Swing 焦点获得 → 通知 Chromium
            browser.cefBrowser.setFocus(true)
            thisLogger().debug("[JCEF Focus] Swing focus gained, notifying Chromium")
        }

        override fun focusLost(e: FocusEvent) {
            // Swing 焦点失去 → 通知 Chromium
            browser.cefBrowser.setFocus(false)
            thisLogger().debug("[JCEF Focus] Swing focus lost, notifying Chromium")
        }
    })
}
```

### 3.2 方案二：同时注册 CefFocusHandler

```kotlin
// 在 BrowserPanel.kt 中添加
sharedClient.addFocusHandler(object : CefFocusHandlerAdapter() {
    override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource): Boolean {
        // OSR 模式下返回 false，让 FocusAdapter 处理
        thisLogger().debug("[JCEF Focus] onSetFocus: source=$focusSource, isOSR=${isOffScreenRendering()}")
        return false
    }

    override fun onGotFocus(browser: CefBrowser?) {
        thisLogger().debug("[JCEF Focus] Chromium got focus")
    }

    override fun onTakeFocus(browser: CefBrowser?, next: Boolean) {
        thisLogger().debug("[JCEF Focus] Chromium lost focus, next=$next")
    }
}, createdBrowser.cefBrowser)
```

### 3.3 完整实现

```kotlin
// BrowserPanel.kt 中的完整焦点管理
private fun setupBrowserFocusManagement(browser: JBCefBrowser) {
    val osrComponent = browser.cefBrowser.uiComponent ?: return

    // 1. 注册 FocusAdapter 同步 Swing ↔ Chromium 焦点
    osrComponent.addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) {
            browser.cefBrowser.setFocus(true)
            thisLogger().debug("[JCEF Focus] Swing→Chromium: focus gained")
        }

        override fun focusLost(e: FocusEvent) {
            browser.cefBrowser.setFocus(false)
            thisLogger().debug("[JCEF Focus] Swing→Chromium: focus lost")
        }
    })

    // 2. 注册 CefFocusHandler 监控焦点事件
    sharedClient.addFocusHandler(object : CefFocusHandlerAdapter() {
        override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource): Boolean {
            thisLogger().debug("[JCEF Focus] onSetFocus: source=$focusSource")
            return false  // OSR 模式下由 FocusAdapter 处理
        }

        override fun onGotFocus(browser: CefBrowser?) {
            thisLogger().debug("[JCEF Focus] Chromium got focus")
        }

        override fun onTakeFocus(browser: CefBrowser?, next: Boolean) {
            thisLogger().debug("[JCEF Focus] Chromium lost focus, next=$next")
        }
    }, browser.cefBrowser)

    // 3. 注册 CefKeyboardHandler 处理键盘事件
    sharedClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
        override fun onPreKeyEvent(browser: CefBrowser?, event: KeyEvent?, isKeyboardEvent: Boolean): Boolean {
            // 处理键盘事件
            return false
        }

        override fun onKeyEvent(browser: CefBrowser?, event: KeyEvent?): Boolean {
            // 处理键盘事件
            return false
        }
    }, browser.cefBrowser)
}
```

---

## 4. 关键源码文件

| 文件                         | 行号    | 内容                  |
| ---------------------------- | ------- | --------------------- |
| `JBCefBrowser.java`          | 46-90   | CefFocusHandler 实现  |
| `JBCefBrowser.java`          | 78-92   | OSR FocusAdapter 注册 |
| `JBCefBrowser.java`          | 182-203 | FocusTraversalPolicy  |
| `JBCefBrowser.java`          | 213-220 | removeNotify 焦点处理 |
| `JBCefBrowser.java`          | 228-233 | Panel FocusEvent 处理 |
| `JBCefOsrComponent.java`     | 56-70   | JBR-7335 workaround   |
| `JBCefNativeOsrHandler.java` | 24-26   | GPU 渲染 workaround   |

---

## 5. 已知 Bug 和 Workaround

### 5.1 JBR-7335：设备配置变更导致画面冻结

```java
// JBCefOsrComponent.java 第 56-70 行
// This delay is a workaround for JBR-7335.
addPropertyChangeListener("graphicsConfiguration", e -> {
    myGraphicsConfigurationAlarm.cancelAllRequests();
    if (myScaleInitialized.get()) {
        myGraphicsConfigurationAlarm.addRequest(this::onGraphicsConfigurationChanged, 1000);
    } else {
        onGraphicsConfigurationChanged();
        myScaleInitialized.set(true);
    }
});
```

### 5.2 GPU 硬件渲染问题

```java
// JBCefNativeOsrHandler.java 第 24-26 行
static {
    // NOTE: temporary enabled until fixed IJPL-161293, IJPL-182455
    FORCE_USE_SOFTWARE_RENDERING = !Boolean.getBoolean("jcef.remote.enable_hardware_rendering");
}
```

### 5.3 Linux 焦点请求差异

```java
// JBCefBrowser.java 第 68-72 行
if (!browser.getUIComponent().hasFocus()) {
    if (SystemInfo.isLinux) {
        browser.getUIComponent().requestFocus();  // Linux 用 requestFocus
    } else {
        browser.getUIComponent().requestFocusInWindow();  // 其他平台用 requestFocusInWindow
    }
}
```

### 5.4 Windows 移除前焦点处理

```java
// JBCefBrowser.java 第 213-220 行
@Override
public void removeNotify() {
    if (SystemInfo.isWindows) {
        if (myCefBrowser.getUIComponent().hasFocus()) {
            myCefBrowser.setFocus(false);  // Windows: 移除前显式清除焦点
        }
    }
    myFirstShow = true;
    super.removeNotify();
}
```

---

## 6. 测试场景

### 6.1 OSR 焦点同步测试

| 场景           | 操作           | 预期结果              |
| -------------- | -------------- | --------------------- |
| Swing→Chromium | 点击 JCEF 组件 | Chromium 收到焦点通知 |
| Chromium→Swing | 在网页中点击   | Swing 焦点同步        |
| 窗口切换       | Alt+Tab 切换   | 焦点正确恢复          |
| 上下文菜单     | 右键打开菜单   | 焦点不丢失            |

### 6.2 平台特定测试

| 平台    | 测试场景     | 预期行为                                    |
| ------- | ------------ | ------------------------------------------- |
| Windows | removeNotify | 焦点正确清除                                |
| Linux   | requestFocus | 使用 requestFocus 而非 requestFocusInWindow |
| macOS   | Metal 渲染   | 焦点同步正常                                |

---

## 7. 参考资源

| 资源                       | URL                                                                                                          |
| -------------------------- | ------------------------------------------------------------------------------------------------------------ |
| JBCefBrowser 源码          | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefBrowser.java          |
| JBCefOsrComponent 源码     | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefOsrComponent.java     |
| JBCefNativeOsrHandler 源码 | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefNativeOsrHandler.java |
| JCEF 官方文档              | https://chromiumembedded.github.io/java-cef/                                                                 |

---

## 8. 下一步行动

1. **立即实施 FocusAdapter 注册** - 解决 OSR 焦点同步问题
2. **添加 CefFocusHandler 日志** - 监控焦点事件
3. **测试平台特定行为** - 确保跨平台兼容
