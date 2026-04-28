# Session 下拉框优化和加载状态修复

## TL;DR
- Fix service start state logic issues
- Use `com.intellij.openapi.ui.ComboBox` (IntelliJ native) instead of `javax.swing.JComboBox`
- Add loading indicator with `AnimatedIcon.Default()` when loading sessions
- Automatically start service if needed when opening dropdown

---

## Issues Found
### 1. 启动服务的状态问题
Current code in `PromptToolWindowPanel.kt` has incorrect state management:
- Button logic checks `sendButton.text == "启动服务"` instead of using `OpenCodeServerManager.isServerRunning()`
- State changes button text too many places
- Doesn't reliably check server health

### 2. 下拉框 UI 丑
- Current using `javax.swing.JComboBox` (plain Swing)
- Should use IntelliJ native `com.intellij.openapi.ui.ComboBox`
- No loading indicator missing

### 3. 下拉框打开时服务未启动
- No loading indicator
- Auto-start service when needed

---

## What We Found (from librarian)
- ✅ `com.intellij.openapi.ui.ComboBox` for native IntelliJ component
- ✅ `AnimatedIcon.Default()` for loading spinner
- ✅ `ComboBoxWithLoadSpinner` (Git4Idea pattern)

---

## Implementation Plan

### Files to Modify:
1. `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`

### Changes:

1. **Replace JComboBox with IntelliJ ComboBox:**
   - `import com.intellij.openapi.ui.ComboBox`
   - Replace `javax.swing.JComboBox` → `ComboBox<Session>`

2. **Add AnimatedIcon import:**
   - `import com.intellij.ui.AnimatedIcon`
   - `import javax.swing.JLabel`
   - `import javax.swing.SwingConstants`
   - `import java.awt.FlowLayout`

3. **Add loading indicator next to combo box:
   - Wrap combo box + animated label in a JPanel
   - Hide/show loading indicator when needed

4. **Fix refreshSessions():**
   - Check if server is running before loading sessions
   - If not running, refresh normally
   - If not running, first start server, show loading indicator first

5. **Fix sendPrompt() state management:**
   - Remove text state checks
   - Clean up button state management

---

## Success Criteria
- [ ] Compiles OK
- [ ] Uses native IntelliJ ComboBox instead of JComboBox
- [ ] Shows AnimatedIcon loading indicator
- [ ] Auto-starts service when dropdown if needed
- [ ] Correct send button state management fixed

---

## Related Files
- `.sisyphus/plans/refresh-session-on-dropdown.md` (current plan)
