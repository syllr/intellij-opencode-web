# 添加详细日志计划

## 需求
添加详细的日志到 `PromptToolWindowPanel.kt`，方便调试

## 实现方案

### 添加的 imports
```kotlin
import com.intellij.openapi.diagnostic.thisLogger
```

### 日志添加位置

| 位置 | 日志内容 |
|------|---------|
| init block | 初始化时的状态 |
| refreshSessions() start | "refreshSessions() called, currentState: $currentState" |
| refreshSessions() starting service | "refreshSessions(): service not started, starting..." |
| refreshSessions() started | "refreshSessions(): service started" |
| refreshSessions() loading sessions | "refreshSessions(): loading sessions..." |
| refreshSessions() loaded | "refreshSessions(): loaded ${sessions.size} sessions" |
| sendPrompt() start | "sendPrompt() called, currentState: $currentState" |
| sendPrompt() state change | "sendPrompt(): state: NOT_STARTED → STARTING" |
| sendPrompt() sending | "sendPrompt(): state: RUNNING, doSend()" |
| startServerAndSend() start | "startServerAndSend() called" |
| startServerAndSend() onStarted | "startServerAndSend(): onStarted" |
| startServerAndSend() onFailed | "startServerAndSend(): onFailed: ${e.message}" |
| doSend() start | "doSend() called, session: ${selectedSession?.id}" |
| doSend() success | "doSend(): send success" |
| doSend() failed | "doSend(): send failed" |
| setState() change | "setState(): $previousState → $newState" |

### 修改文件
- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`

---

请 Sisyphus 来实现这个！
