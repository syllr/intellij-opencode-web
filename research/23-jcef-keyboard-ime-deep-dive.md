# JCEF 键盘事件和 IME 输入法深度分析

**调研时间**: 2026-05-30
**调研目标**: 深入分析 JCEF 键盘事件处理和 IME 输入法支持

---

## 1. 键盘事件生命周期

### 1.1 按键从 Swing 到 Chromium 的完整路径

```
用户按键
    ↓
Swing KeyEvent (JBCefOsrComponent)
    ↓ CefKeyboardHandler.onPreKeyEvent()
JCEF 预处理（可拦截/修改）
    ↓ CefKeyboardHandler.onKeyEvent()
Chromium 渲染进程
    ↓ DOM 事件分发
JavaScript 处理
```

### 1.2 JCEF OSR 模式的键盘事件路由

**关键文件**: `JBCefOsrComponent.java`

```java
// 鼠标按下时请求焦点
@Override
protected void processMouseEvent(MouseEvent e) {
    super.processMouseEvent(e);
    if (e.getID() == MouseEvent.MOUSE_PRESSED) {
        requestFocusInWindow();  // OSR 组件获取 Swing 焦点
    }
}

// 键盘事件直接传递给 Chromium
@Override
protected void processKeyEvent(KeyEvent e) {
    super.processKeyEvent(e);
    // CEF 通过 InputMethod 接口接收键盘事件
}
```

### 1.3 CefKeyboardHandler 回调

```java
// JBCefClient.java 中的键盘处理器注册
public JBCefClient addKeyboardHandler(@NotNull CefKeyboardHandler handler, @NotNull CefBrowser browser) {
    return myKeyboardHandler.add(handler, browser, () -> {
        myCefClient.addKeyboardHandler(new CefKeyboardHandler() {
            @Override
            public boolean onPreKeyEvent(CefBrowser browser, KeyEvent event, boolean isKeyboardEvent) {
                // 预处理：可拦截或修改键盘事件
                // 返回 true 表示事件已处理，不再传递
                return myKeyboardHandler.handleBooleanReturnAnyOf(browser, handler -> {
                    return handler.onPreKeyEvent(browser, event, isKeyboardEvent);
                });
            }

            @Override
            public boolean onKeyEvent(CefBrowser browser, KeyEvent event) {
                // 后处理：事件已传递给 Chromium
                return myKeyboardHandler.handleBooleanReturnAnyOf(browser, handler -> {
                    return handler.onKeyEvent(browser, event);
                });
            }
        });
    });
}
```

---

## 2. IME (输入法编辑器) 处理

### 2.1 macOS 上的 NSTextInputClient 协议

macOS 输入法通过 `NSTextInputClient` 协议工作：

| 方法                            | 说明                   | JCEF 映射           |
| ------------------------------- | ---------------------- | ------------------- |
| `setMarkedText()`               | 设置组合文本（输入中） | `ImeSetComposition` |
| `unmarkText()`                  | 取消组合文本           | `ImeCommitText`     |
| `hasMarkedText()`               | 是否有组合文本         | 检查状态            |
| `selectedRange()`               | 获取选区范围           | `CefRange`          |
| `firstRect(forCharacterRange:)` | 获取字符位置           | 候选词窗口定位      |

### 2.2 JBCefInputMethodAdapter 实现

**关键文件**: `JBCefInputMethodAdapter.java`

```java
class JBCefInputMethodAdapter implements InputMethodRequests, InputMethodListener, JBCefCaretListener {

    @Override
    public void inputMethodTextChanged(InputMethodEvent event) {
        int committedCharacterCount = event.getCommittedCharacterCount();

        // 1. 处理已提交文本（输入法的最终结果）
        if (committedCharacterCount > 0) {
            String committedText = extractCommittedText(event);
            CefRange replacementRange = calculateReplacementRange();
            myBrowser.ImeCommitText(committedText, replacementRange, relativeCursorPos);
        }

        // 2. 处理组合文本（输入法候选词）
        String composedText = extractComposedText(event);
        if (!composedText.isEmpty()) {
            CefCompositionUnderline underline = new CefCompositionUnderline(
                0, composedText.length(),
                CefCompositionUnderline.LineStyle.SPELLCHECK, 0, 0
            );
            myBrowser.ImeSetComposition(
                composedText,
                List.of(underline),
                replacementRange,
                selectionRange
            );
        }

        event.consume();  // 关键：消费事件防止 Swing 再次处理
    }

    @Override
    public void caretPositionChanged(InputMethodEvent event) {
        // 光标位置变化时更新
        updateCaretPosition();
    }
}
```

### 2.3 韩语输入的特殊处理

```java
// JBCefInputMethodAdapter.java 中的韩语输入修复
CefRange replacementRange = mySelectionRange;
if (replacementRange == null || replacementRange.from == replacementRange.to) {
    // 实验发现：只有需要替换文本时才传有效 replacementRange
    // 传零点范围的指针位置会破坏韩语输入字符顺序
    replacementRange = DEFAULT_RANGE;  // CefRange(-1, -1)
}
```

---

## 3. 项目当前键盘处理分析

### 3.1 ESC 键拦截

**文件**: `JcefKeyboardInterceptor.kt:31-41`

```kotlin
fun interceptKeys(component: Component) {
    if (component !is JComponent) return
    component.focusTraversalKeysEnabled = false

    val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
    val actionMap = component.actionMap

    // 拦截 ESC，防止 IntelliJ 框架处理
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "block")
    actionMap.put("block", emptyAction)
}
```

**分析**: 只拦截 ESC，未处理其他可能影响焦点的按键。

### 3.2 Cmd+K/Cmd+, JS 注入拦截

**文件**: `BrowserPanel.kt:117-126`

```kotlin
cefBrowser?.executeJavaScript("""
    (function() {
        document.addEventListener('keydown', function(e) {
            if (e.metaKey && (e.key === ',' || e.key === 'k' || e.key === 'K')) {
                e.preventDefault();
                e.stopImmediatePropagation();
            }
        }, true);  // capture phase
    })();
""", cefBrowser.url, 0)
```

**分析**: 通过 JS 在 DOM 层面拦截，但无法拦截 Java 层面的键盘事件。

### 3.3 Emacs 按键映射

**文件**: `EmacsKeyHandler.kt:10-36`

```kotlin
val emacsMappings = mapOf(
    KeyEvent.VK_N to KeyEvent.VK_DOWN,
    KeyEvent.VK_P to KeyEvent.VK_UP,
    KeyEvent.VK_E to KeyEvent.VK_END,
    KeyEvent.VK_A to KeyEvent.VK_HOME,
    KeyEvent.VK_B to KeyEvent.VK_LEFT,
    KeyEvent.VK_F to KeyEvent.VK_RIGHT,
    KeyEvent.VK_W to -1  // Ctrl+W → Option+Backspace
)

private fun handleEmacsKey(e: KeyEvent, mappings: Map<Int, Int>, target: Component) {
    if ((e.modifiersEx and KeyEvent.CTRL_DOWN_MASK) == 0) return
    val targetKeyCode = mappings[e.keyCode] ?: return

    if (targetKeyCode == -1) {
        sendKeyEvent(target, e.id, KeyEvent.VK_BACK_SPACE, KeyEvent.ALT_DOWN_MASK)
    } else {
        sendKeyEvent(target, e.id, targetKeyCode, 0)
    }
    e.consume()
}
```

**分析**: 消费了原始事件，可能导致 IME 组合文本丢失。

---

## 4. 问题诊断

### 4.1 失焦问题的键盘相关原因

1. **ESC 键处理不当**
   - 当前只拦截 ESC，但 ESC 可能触发 Chromium 的焦点切换
   - 需要在 `CefKeyboardHandler.onPreKeyEvent` 中统一处理

2. **焦点遍历键未完全禁用**
   - `focusTraversalKeysEnabled = false` 只禁用了 Tab 键
   - 其他焦点遍历键（如 Shift+Tab）可能仍然生效

3. **键盘事件被 IntelliJ 拦截**
   - 部分快捷键在 IntelliJ 层面被消费
   - 需要更全面的快捷键拦截策略

### 4.2 输入法卡顿的键盘相关原因

1. **IME 组合事件未正确处理**
   - 没有实现 `CefKeyboardHandler` 处理键盘事件
   - 组合文本可能被错误地提交

2. **Emacs 按键干扰 IME**
   - `e.consume()` 消费了原始事件
   - 可能中断 IME 的组合过程

3. **JS 注入与 IME 冲突**
   - DOM 层面的按键拦截可能影响 IME 候选词

---

## 5. 解决方案

### 5.1 完整的键盘事件处理

```kotlin
// 在 BrowserPanel.kt 中添加
sharedClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
    override fun onPreKeyEvent(
        browser: CefBrowser?,
        event: KeyEvent?,
        isKeyboardEvent: Boolean
    ): Boolean {
        if (event == null) return false

        // 1. ESC 键：不拦截，让 Chromium 处理
        // （当前实现的 ESC 拦截可能有问题）

        // 2. Tab 键：根据上下文决定是否拦截
        if (event.keyCode == KeyEvent.VK_TAB) {
            // 在输入框中 Tab 应该插入制表符
            return false
        }

        // 3. 快捷键：检查是否应该传递给 IDE
        if (event.modifiersEx and (KeyEvent.META_DOWN_MASK or KeyEvent.CTRL_DOWN_MASK) != 0) {
            // 修饰键组合，检查是否是 IDE 快捷键
            if (isIdeShortcut(event)) {
                return false  // 让 IDE 处理
            }
        }

        return false
    }

    override fun onKeyEvent(browser: CefBrowser?, event: KeyEvent?): Boolean {
        // 后处理：可以在这里添加日志
        return false
    }
}, createdBrowser.cefBrowser)
```

### 5.2 IME 焦点处理

```kotlin
// 在 BrowserPanel.kt 中添加
sharedClient.addFocusHandler(object : CefFocusHandlerAdapter() {
    override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource): Boolean {
        // OSR 模式下返回 false，由 FocusAdapter 处理
        return false
    }

    override fun onGotFocus(browser: CefBrowser?) {
        thisLogger().debug("[JCEF Focus] Chromium got focus - IME context restored")
    }

    override fun onTakeFocus(browser: CefBrowser?, next: Boolean) {
        thisLogger().debug("[JCEF Focus] Chromium lost focus - IME context may be lost")
    }
}, createdBrowser.cefBrowser)
```

### 5.3 焦点恢复时的 IME 处理

```kotlin
// 在 MyToolWindow.kt 中修改 requestBrowserFocus
fun requestBrowserFocus() {
    ApplicationManager.getApplication().invokeLater {
        try {
            val browser = browserPanel.getBrowser() ?: return@invokeLater
            val osrComponent = browser.cefBrowser.uiComponent

            // 1. 请求 Swing 焦点
            osrComponent?.requestFocusInWindow()

            // 2. 通知 Chromium 恢复焦点
            browser.cefBrowser.setFocus(true)

            // 3. 触发 IME 上下文恢复
            // （通过 FocusAdapter 自动处理）

            thisLogger().debug("[JCEF Focus] Focus restored with IME context")
        } catch (e: Exception) {
            thisLogger().warn("Failed to restore focus: ${e.message}")
        }
    }
}
```

---

## 6. 测试场景

### 6.1 键盘事件测试

| 场景   | 操作             | 预期结果               |
| ------ | ---------------- | ---------------------- |
| ESC 键 | 在输入框中按 ESC | 正确传递给网页         |
| Tab 键 | 在输入框中按 Tab | 插入制表符或切换焦点   |
| 快捷键 | 按 Cmd+K         | IDE 处理，不传递给网页 |
| 修饰键 | 按 Ctrl+A        | 正确选择所有文本       |

### 6.2 IME 测试

| 场景       | 操作       | 预期结果         |
| ---------- | ---------- | ---------------- |
| 中文输入   | 输入拼音   | 候选词正确显示   |
| 组合文本   | 输入过程中 | 组合文本正确显示 |
| 提交文本   | 选择候选词 | 文本正确提交     |
| 输入法切换 | 切换中英文 | 切换正常         |

### 6.3 焦点恢复测试

| 场景         | 操作               | 预期结果     |
| ------------ | ------------------ | ------------ |
| ESC 失焦     | 按 ESC 后重新点击  | 焦点正确恢复 |
| 窗口切换     | Alt+Tab 切换       | 焦点正确恢复 |
| 工具窗口切换 | 切换到其他工具窗口 | 焦点正确恢复 |

---

## 7. 参考资源

| 资源                    | URL                                                                                                            |
| ----------------------- | -------------------------------------------------------------------------------------------------------------- |
| JBCefInputMethodAdapter | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefInputMethodAdapter.java |
| JBCefKeyboardHandler    | https://chromiumembedded.github.io/java-cef/ Reference/cef/CefKeyboardHandler.html                             |
| macOS NSTextInputClient | https://developer.apple.com/documentation/appkit/nstextinputclient                                             |

---

## 8. 下一步行动

1. **实现完整的 CefKeyboardHandler** - 统一处理所有键盘事件
2. **添加 IME 状态监控** - 调试输入法问题
3. **测试韩语输入** - 验证韩语输入修复
4. **优化焦点恢复** - 确保 IME 上下文正确恢复
