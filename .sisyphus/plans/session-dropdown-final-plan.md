# Session 下拉框最终实现（基于 Oracle 审查）

## TL;DR
- 替换为 IntelliJ native `ComboBox`
- Add `AnimatedIcon.Default()` loading indicator
- Fix service state machine (NOT_STARTED/STARTING/RUNNING)
- Auto-start service when opening dropdown
- Clean UI state management

---

## Issues to Fix (from Oracle review)
1. **Duplicate start prevention** - Prevent multiple start clicks
2. **UI-state coupling** - Button text not as source of truth
3. **Missing edge cases** - STARTING state UI feedback
4. **Complicated actions** - Simplify to actual triggers

---

## Core Implementation (Simplified)

### Files to Modify:
- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`

### State Machine:

```
┌─────────────┐ START     ┌─────────────┐ START_SUCCESS ┌───────────┐
│ NOT_STARTED │ ────────► │  STARTING   │ ────────────► │  RUNNING  │
└──────┬──────┘           └─────────────┘               └─────┬─────┘
       │                         │                            │
       │                         │ START_FAILED               │ CRASH
       │                         ▼                            ▼
       │                    ┌───────────┐              ┌─────────────┐
       └────────────────────┤  FAILED   │◄─────────────│ NOT_STARTED │
                            └───────────┘
```

### State Enum:
```kotlin
private enum class ServerState {
    NOT_STARTED,  // Service not running
    STARTING,     // Service is starting
    RUNNING       // Service running normally
}
```

### Actions (Simplified, only actual triggers):
| # | Action |
|---|--------|
| 1 | START (点击启动按钮 / 发送时服务未运行 / 打开下拉框时) |
| 2 | START_SUCCESS (服务启动成功) |
| 3 | START_FAILED (启动失败或超时) |
| 4 | CRASH (服务崩溃检测) |

---

## Success Criteria
- [x] Compiles OK
- [x] Uses `com.intellij.openapi.ui.ComboBox`
- [x] Shows `AnimatedIcon.Default()` loading indicator
- [x] Auto-starts service when dropdown is opened
- [x] Fixed button state management (no text checks!)
- [x] Prevents duplicate start clicks
- [ ] Tested in `runIde`

---

## Plan for Execution
Now implement this simplified version first (prioritize user 3 issues)!
