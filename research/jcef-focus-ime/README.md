# JCEF 失焦与输入法问题调研

**日期**: 2026-05-30
**状态**: 调研完成，准备实施
**核心问题**: JCEF 页面失焦、输入法卡顿（刷新可临时修复）

---

## 一、根因确认

### 1.1 项目缺少焦点同步机制

| 缺失组件                  | 功能                                            | 影响             | 修复优先级 |
| ------------------------- | ----------------------------------------------- | ---------------- | ---------- |
| **FocusAdapter**          | Swing ↔ Chromium 焦点同步（OSR 模式的真正机制） | 页面失焦         | 🔴 高      |
| **CefFocusHandler**       | 焦点事件回调（仅用于日志/监控）                 | 无法监控焦点状态 | 🟢 可选    |
| **CefCompositionHandler** | IME 组合事件处理                                | 输入法卡顿       | 🟡 中      |

### 1.2 关键发现：OSR 模式焦点同步的真正机制

```java
// JBCefBrowser.java 第 78-92 行
if (isOffScreenRendering()) {
    myCefBrowser.getUIComponent().addFocusListener(new FocusAdapter() {
        public void focusGained(FocusEvent e) {
            myCefBrowser.setFocus(true);  // Swing 焦点 → Chromium
        }
        public void focusLost(FocusEvent e) {
            if (!contextMenuRunner.isShowing()) {
                myCefBrowser.setFocus(false);  // Swing 焦点丢失 → Chromium
            }
        }
    });
}
```

**核心结论**：

- OSR 模式下，`CefFocusHandler.onSetFocus()` **直接返回 `false`**，不做任何 Swing 焦点处理
- 真正的焦点同步由 **FocusAdapter** 负责
- 当前项目 `requestBrowserFocus()` 只调用 Swing `requestFocus()`，**从未调用 `browser.cefBrowser.setFocus(true)` 通知 Chromium**

### 1.3 焦点协作流程

```
Swing FocusEvent → FocusAdapter.focusGained() → browser.setFocus(true)
                                                  → Chromium onGotFocus
                                                  → CefFocusHandler.onGotFocus() (仅日志)
```

### 1.4 FocusSource 枚举

**CEF C++ API** 定义了 5 个值（NAVIGATION, ACTIVATION, PROGRAMMATIC, BY_SYSTEM, OTHER）。

**JetBrains Java 绑定**只映射了 2 个值：

| 枚举值                    | 说明                       |
| ------------------------- | -------------------------- |
| `FOCUS_SOURCE_NAVIGATION` | 键盘导航触发（Tab 键）     |
| `FOCUS_SOURCE_SYSTEM`     | 系统事件触发（窗口激活等） |

> ⚠️ 文档 07 引用的是 CEF C++ 定义而非 Java 绑定，以 Java 绑定为准。

### 1.5 刷新页面能修复的原因

1. Chromium 渲染状态重置（重建渲染管道）
2. IME 上下文重建
3. 焦点状态重新同步

### 1.6 GPU 渲染参数对 IME 的影响

当前配置（`MyToolWindowFactory.kt:47-48`）：

```kotlin
Registry.get("ide.browser.jcef.gpu.disable").setValue(false)
System.setProperty("ide.browser.jcef.extra.args", "--enable-gpu-compositing,--use-gl=angle,--use-angle=metal")
```

- `--enable-gpu-compositing` 可能引入 IME 事件处理延迟
- JetBrains 有 `FORCE_USE_SOFTWARE_RENDERING` workaround（IJPL-161293, IJPL-182455）
- **GPU 保持启用**，仅在 IME 有问题时考虑 fallback

### 1.7 已知 Bug

| Bug                                | 文件                               | Workaround                     |
| ---------------------------------- | ---------------------------------- | ------------------------------ |
| CEF #1437: 浏览器矩形 0x0 崩溃     | `CefBrowserOsr.java:44`            | 初始化为 1x1 矩形              |
| macOS Java 8 Retina 缩放因子错误   | `CefBrowserOsr.java:92-114`        | 反射获取真实缩放因子           |
| macOS 不在显示时自动请求焦点       | `CefBrowserWr.java:294`            | 注释掉 `setFocus(true)`        |
| IJPL-161293/182455: GPU 渲染问题   | `JBCefNativeOsrHandler.java:24-26` | `FORCE_USE_SOFTWARE_RENDERING` |
| **JBR-7335**: 设备配置变更画面冻结 | `JBCefOsrComponent.java:56-70`     | graphicsConfiguration Listener |

---

## 二、实施计划

### 阶段一：FocusAdapter 注册（核心修复）

**文件**：`BrowserPanel.kt` 的 `createMainTab`

```kotlin
val osrComponent = createdBrowser.cefBrowser.uiComponent
osrComponent?.addFocusListener(object : java.awt.event.FocusAdapter() {
    override fun focusGained(e: java.awt.event.FocusEvent) {
        createdBrowser.cefBrowser.setFocus(true)
        thisLogger().debug("[JCEF Focus] focusGained → setFocus(true)")
    }
    override fun focusLost(e: java.awt.event.FocusEvent) {
        createdBrowser.cefBrowser.setFocus(false)
        thisLogger().debug("[JCEF Focus] focusLost → setFocus(false)")
    }
})
```

**验证**：日志出现 `[JCEF Focus] focusGained` / `[JCEF Focus] focusLost`

### 阶段二：requestBrowserFocus 修改

**文件**：`MyToolWindow.kt`

```kotlin
fun requestBrowserFocus() {
    ApplicationManager.getApplication().invokeLater {
        val browser = browserPanel.getBrowser() ?: return@invokeLater
        browser.cefBrowser.uiComponent?.let { comp ->
            if (comp.isFocusable) { comp.requestFocus() }
        }
        browser.cefBrowser.setFocus(true)  // 新增：通知 Chromium
    }
}
```

### 阶段三：GPU 渲染优化（保持 GPU 启用）

验证 FocusAdapter 注册后 IME 在 GPU 模式下正常工作。如仍有问题，调整 GPU 参数。

---

## 三、文档索引

| 文件                                      | 内容                                                         |
| ----------------------------------------- | ------------------------------------------------------------ |
| `01-jcef-focus-ime-issues.md`             | 问题描述、现有实现分析、根因分析                             |
| `03-jcef-deep-dive-solutions.md`          | JetBrains 源码分析、技术实现方案                             |
| `06-testing-strategies.md`                | 单元测试、集成测试、手动测试、性能测试                       |
| `07-ceffocushandler-focus-source-enum.md` | FocusSource 枚举（⚠️ 引用 CEF C++ 定义，Java 绑定只有 2 值） |
| `18-performance-advanced-features.md`     | 性能优化和高级功能                                           |
| `21-jcef-osr-focus-deep-dive.md`          | **OSR 焦点同步机制（关键文档）**                             |
| `23-jcef-keyboard-ime-deep-dive.md`       | 键盘事件生命周期、IME 处理                                   |
| `30-jcef-source-code-deep-dive.md`        | **JCEF GitHub 源码分析（关键文档）**                         |

---

## 四、参考资源

| 资源                    | URL                                                                                                 |
| ----------------------- | --------------------------------------------------------------------------------------------------- |
| JetBrains JCEF 官方文档 | https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html                              |
| JBCefBrowser 源码       | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefBrowser.java |
| JCEF GitHub 仓库        | https://github.com/JetBrains/jcef                                                                   |
| CEF Focus Handler 文档  | https://bitbucket.org/chromiumembedded/cef/wiki/GeneralUsage.md                                     |
| Apple NSTextInputClient | https://developer.apple.com/documentation/appkit/nstextinputclient                                  |
