# JCEF 失焦与 IME 问题深入技术方案

**调研时间**: 2026-05-30
**调研目标**: 基于深入分析，给出具体的技术解决方案

---

## 1. 根因确认

### 1.1 核心发现

通过代码分析，确认项目**缺少以下关键 JCEF 处理器**：

| 缺失组件                | 功能                      | 影响       |
| ----------------------- | ------------------------- | ---------- |
| `CefFocusHandler`       | Swing ↔ Chromium 焦点同步 | 页面失焦   |
| `CefCompositionHandler` | IME 输入法组合事件处理    | 输入法卡顿 |
| `CefKeyboardHandler`    | 键盘事件拦截和路由        | 快捷键冲突 |

### 1.2 当前实现的不足

**焦点管理** (`MyToolWindow.kt:96-116`):

```kotlin
fun requestBrowserFocus() {
    ApplicationManager.getApplication().invokeLater {
        val browser = browserPanel.getBrowser() ?: return@invokeLater
        val osrComponent = browser.cefBrowser.uiComponent
        val browserComponent = browser.component
        // 只请求 Swing 焦点，未同步 Chromium 焦点
        osrComponent?.let { comp ->
            if (comp.isFocusable) {
                comp.requestFocus()  // ⚠️ 不触发 Chromium 的 onSetFocus
            }
        }
    }
}
```

**问题**: `requestFocus()` 只更新 Swing 焦点状态，Chromium 内部焦点可能不同步。

**IME 处理** (`AddToPromptAction.kt:23-39`):

```kotlin
// 使用外部工具切换输入法，不是真正的 IME 处理
private fun switchInputMethod(arg: String) {
    Runtime.getRuntime().exec(arrayOf(IM_SELECT_PATH, arg))
}
```

**问题**: 这是 workaround，不是根本解决方案。真正的 IME 问题在于 Chromium 的 `CefCompositionHandler` 未正确实现。

---

## 2. JetBrains JCEF 源码分析

### 2.1 JBCefBrowser.java 中的焦点处理

从 JetBrains 官方源码可以看到：

```java
// JBCefBrowser.java 中的焦点处理
myCefClient.addFocusHandler(myCefFocusHandler = new CefFocusHandlerAdapter() {
    @Override
    public void onTakeFocus(CefBrowser browser, boolean next) {
        focusedBrowser = null;  // 失去焦点时清除追踪
    }

    @Override
    public void onGotFocus(CefBrowser browser) {
        focusedBrowser = new WeakReference<>(JBCefBrowser.this);  // 获得焦点时记录
    }

    @Override
    public boolean onSetFocus(CefBrowser browser, FocusSource source) {
        // 关键：根据焦点来源决定是否接受焦点
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean componentFocused = focusOwner == getComponent() ||
                                  focusOwner == getCefBrowser().getUIComponent();

        // 导航来源时在 Windows 上禁用浏览器焦点
        if (source == FocusSource.FOCUS_SOURCE_NAVIGATION && !focusOnNavigation) {
            if (SystemInfo.isWindows) {
                myCefBrowser.setFocus(false);
            }
            return true; // 抑制导航导致的焦点获取
        }

        // Linux 需要手动请求焦点
        if (!browser.getUIComponent().hasFocus()) {
            if (SystemInfo.isLinux) {
                browser.getUIComponent().requestFocus();
            } else {
                browser.getUIComponent().requestFocusInWindow();
            }
        }
        return false;
    }
}, myCefBrowser);
```

### 2.2 JBCefInputMethodAdapter.java 中的 IME 处理

```java
class JBCefInputMethodAdapter implements InputMethodRequests, InputMethodListener {

    @Override
    public void inputMethodTextChanged(InputMethodEvent event) {
        int committedCharacterCount = event.getCommittedCharacterCount();

        // 1. 处理已提交文本
        if (committedCharacterCount > 0) {
            String committedText = ...;
            myBrowser.ImeCommitText(committedText, replacementRange, relativeCursorPos);
        }

        // 2. 处理组合文本（输入法候选词）
        String composedText = ...;
        if (!composedText.isEmpty()) {
            CefCompositionUnderline underline = new CefCompositionUnderline(...);
            myBrowser.ImeSetComposition(composedText, List.of(underline),
                                         replacementRange, selectionRange);
        }
        event.consume();  // 关键：消费事件防止 Swing 再次处理
    }
}
```

### 2.3 关键差异：本项目 vs JetBrains 官方实现

| 功能                    | JetBrains 官方         | 本项目  | 差异         |
| ----------------------- | ---------------------- | ------- | ------------ |
| `addFocusHandler`       | ✅ 实现                | ❌ 缺失 | 焦点同步缺失 |
| `addCompositionHandler` | ✅ 实现                | ❌ 缺失 | IME 处理缺失 |
| `FocusSource` 处理      | ✅ 区分来源            | ❌ 无   | 导航焦点丢失 |
| 平台特定处理            | ✅ Linux/Windows/macOS | ❌ 无   | 平台兼容性差 |
| `event.consume()`       | ✅ 消费事件            | ❌ 无   | 事件重复处理 |

---

## 3. 技术解决方案

### 3.1 方案一：实现 CefFocusHandler（核心修复）

**目标**: 确保 Swing 焦点和 Chromium 焦点完全同步

**实现位置**: `BrowserPanel.kt` 的 `createMainTab` 方法

```kotlin
// 在 BrowserPanel.kt 中添加
sharedClient.addFocusHandler(object : CefFocusHandlerAdapter() {
    override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource) {
        thisLogger().debug("[JCEF Focus] onSetFocus: source=$focusSource")

        // 根据焦点来源决定是否接受
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val componentFocused = focusOwner == browser?.uiComponent ||
                              focusOwner == (browser as? JBCefBrowser)?.component

        // 导航来源时延迟焦点请求
        if (focusSource == CefFocusHandler.FocusSource.FOCUS_SOURCE_NAVIGATION) {
            if (!componentFocused) {
                ApplicationManager.getApplication().invokeLater {
                    browser?.uiComponent?.requestFocusInWindow()
                }
            }
            return false  // 接受焦点
        }

        // 其他来源：确保 Swing 焦点同步
        if (!componentFocused) {
            ApplicationManager.getApplication().invokeLater {
                browser?.uiComponent?.requestFocusInWindow()
            }
        }
        return false
    }

    override fun onGotFocus(browser: CefBrowser?) {
        thisLogger().debug("[JCEF Focus] onGotFocus")
    }

    override fun onTakeFocus(browser: CefBrowser?, next: Boolean) {
        thisLogger().debug("[JCEF Focus] onTakeFocus: next=$next")
    }
}, createdBrowser.cefBrowser)
```

### 3.2 方案二：实现 CefCompositionHandler（IME 修复）

**目标**: 正确处理 macOS 输入法组合事件

**实现位置**: `BrowserPanel.kt` 的 `createMainTab` 方法

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
        thisLogger().debug("[IME] onSetComposition: text=$text, cursor=$cursorPosition")
        // Chromium 会自动处理组合文本的渲染
        // 这里主要用于调试和监控
    }

    override fun onResetComposition(browser: CefBrowser?) {
        thisLogger().debug("[IME] onResetComposition")
    }

    override fun onTextCompositionChanged(
        browser: CefBrowser?,
        type: CefCompositionHandler.TextCompositionType?,
        compositionRangeStart: Int,
        compositionRangeEnd: Int
    ) {
        thisLogger().debug("[IME] onTextCompositionChanged: type=$type, range=[$compositionRangeStart, $compositionRangeEnd]")
    }
}, createdBrowser.cefBrowser)
```

### 3.3 方案三：优化键盘事件处理

**目标**: 统一处理 Swing 和 Chromium 的键盘事件路由

**实现位置**: `JcefKeyboardInterceptor.kt`

```kotlin
// 扩展 JcefKeyboardInterceptor
object JcefKeyboardInterceptor {

    fun interceptKeys(component: Component) {
        if (component !is JComponent) return
        component.focusTraversalKeysEnabled = false

        val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = component.actionMap

        // 拦截 ESC
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "block-esc")
        actionMap.put("block-esc", emptyAction)

        // 拦截 Tab（防止焦点遍历）
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "block-tab")
        actionMap.put("block-tab", emptyAction)

        // 添加键盘监听器处理修饰键
        component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // 确保修饰键状态正确传递给 Chromium
                if (e.modifiersEx and (KeyEvent.META_DOWN_MASK or KeyEvent.CTRL_DOWN_MASK) != 0) {
                    // 修饰键组合，让 Chromium 处理
                    return
                }
            }
        })
    }
}
```

### 3.4 方案四：GPU 渲染优化（可选）

**目标**: 测试 GPU 渲染是否影响 IME 性能

**实现位置**: `MyToolWindowFactory.kt`

```kotlin
internal val sharedJBCefClient by lazy {
    // 方案 A：禁用 GPU 加速（测试用）
    // Registry.get("ide.browser.jcef.gpu.disable").setValue(true)

    // 方案 B：保留 GPU 加速但调整参数
    Registry.get("ide.browser.jcef.gpu.disable").setValue(false)
    System.setProperty("ide.browser.jcef.extra.args",
        "--enable-gpu-compositing," +
        "--use-gl=angle," +
        "--use-angle=metal," +
        "--disable-gpu-driver-bug-workarounds")  // 新增：禁用 GPU 驱动变通

    JBCefApp.getInstance().createClient()
}
```

---

## 4. 实施步骤

### 阶段一：核心焦点修复（1-2 天）

1. **添加 CefFocusHandler**
   - 在 `BrowserPanel.kt` 中注册 `addFocusHandler`
   - 处理 `onSetFocus`、`onGotFocus`、`onTakeFocus`
   - 测试焦点切换场景

2. **测试焦点同步**
   - 编辑器 ↔ JCEF 切换
   - 工具窗口最小化/恢复
   - 快捷键使用（Cmd+K、Cmd+,）

### 阶段二：IME 修复（2-3 天）

1. **添加 CefCompositionHandler**
   - 在 `BrowserPanel.kt` 中注册 `addCompositionHandler`
   - 监控输入法组合状态
   - 测试中文输入法场景

2. **测试 IME 稳定性**
   - 中文输入
   - 输入法切换
   - 长文本输入
   - 候选词显示

### 阶段三：稳定性增强（1-2 天）

1. **添加焦点状态监控**
   - 监控焦点状态变化
   - 自动检测和恢复失焦状态
   - 添加日志记录

2. **平台特定优化**
   - macOS：测试 Metal 渲染
   - Linux：测试 X11 IME
   - Windows：测试导航焦点

---

## 5. 测试矩阵

### 5.1 焦点测试

| 场景          | 操作                               | 预期             | 验证方法            |
| ------------- | ---------------------------------- | ---------------- | ------------------- |
| 编辑器 → JCEF | 在编辑器中点击，切换到 OpenCodeWeb | 焦点跟随         | 日志记录 + 手动验证 |
| JCEF → 编辑器 | 在 JCEF 中按 ESC，切换到编辑器     | 焦点跟随         | 日志记录 + 手动验证 |
| 快捷键        | 按 Cmd+K                           | 快捷键传递给网页 | 网页响应            |
| 窗口最小化    | 最小化 IDE 后恢复                  | 焦点状态正确     | 手动验证            |

### 5.2 IME 测试

| 场景       | 操作           | 预期     | 验证方法 |
| ---------- | -------------- | -------- | -------- |
| 中文输入   | 输入中文       | 无卡顿   | 手动测试 |
| 输入法切换 | 切换中英文     | 切换正常 | 手动测试 |
| 长文本     | 输入长段中文   | 无延迟   | 手动测试 |
| 候选词     | 显示候选词列表 | 正确显示 | 手动测试 |

### 5.3 稳定性测试

| 场景       | 操作             | 预期       | 验证方法 |
| ---------- | ---------------- | ---------- | -------- |
| 长时间使用 | 连续使用 2 小时  | 无性能下降 | 手动测试 |
| 多会话切换 | 切换多个 session | 焦点正确   | 手动测试 |
| 服务器重启 | 重启 opencode    | 焦点恢复   | 手动测试 |

---

## 6. 相关代码文件

| 文件                         | 修改内容                                     | 行号    |
| ---------------------------- | -------------------------------------------- | ------- |
| `BrowserPanel.kt`            | 添加 CefFocusHandler + CefCompositionHandler | 103-152 |
| `MyToolWindow.kt`            | 优化 requestBrowserFocus()                   | 96-116  |
| `JcefKeyboardInterceptor.kt` | 扩展键盘拦截                                 | 31-41   |
| `MyToolWindowFactory.kt`     | 调整 GPU 参数                                | 46-50   |

---

## 7. 参考资源

| 资源                         | URL                                                                                                            |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------- |
| JBCefBrowser 源码            | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefBrowser.java            |
| JBCefInputMethodAdapter 源码 | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefInputMethodAdapter.java |
| CefFocusHandler 文档         | https://bitbucket.org/chromiumembedded/cef/wiki/GeneralUsage.md                                                |
| macOS NSTextInputClient      | Apple 官方文档                                                                                                 |

---

## 8. 风险评估

| 风险       | 影响               | 缓解措施               |
| ---------- | ------------------ | ---------------------- |
| 焦点循环   | 用户无法切换焦点   | 添加焦点请求频率限制   |
| IME 兼容性 | 某些输入法不工作   | 支持多种输入法切换方式 |
| 平台差异   | 不同 OS 行为不一致 | 平台特定处理           |
| 性能影响   | 焦点监控消耗资源   | 合理的监控间隔         |

---

## 9. 待深入调研

- [ ] JetBrains 官方 JCEF 示例代码
- [ ] Chromium 的 NSTextInputClient 协议实现细节
- [ ] 其他 JCEF 插件的焦点处理最佳实践
- [ ] macOS Metal 渲染与 IME 的兼容性
- [ ] CefFocusHandler 的 `FocusSource` 枚举值完整列表
