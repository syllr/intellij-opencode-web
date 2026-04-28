# 优化"发送到 OpenCode"按钮逻辑

## TL;DR
优化 PromptEditor 的发送按钮逻辑，支持服务未启动时自动启动服务，支持发送时服务崩溃后自动重连。

## Context
当前 `sendPrompt` 方法在服务不健康时只是禁用按钮并返回，不会启动服务。需要修改为：服务未启动时先启动服务，启动成功后再发送。

## Work Objectives

### Must Have
- [x] 使用 `OpenCodeServerManager.isServerRunning()` 判断服务状态
- [x] 服务未运行时先启动服务，等待启动成功后再发送
- [x] `doSend` 开头再次确认服务健康状态，防止状态陈旧
- [x] 服务崩溃时自动重新触发启动流程

### Must NOT Have
- [x] 不要每次发送前都调用 `isServerHealthySync()` 网络检查（用本地状态判断）

> `sendPrompt()` 使用 `isServerRunning()` 本地状态判断，`isServerHealthySync()` 仅在 `doSend()` 中作为二次确认

## Implementation

### 修改 PromptToolWindowPanel.kt

**新增 `sendPrompt` 方法**：
```kotlin
private fun sendPrompt() {
    val text = textArea.text.trim()
    if (text.isEmpty()) return

    val projectPath = project.basePath ?: return

    // 使用本地状态判断，服务未运行时先启动
    if (!OpenCodeServerManager.isServerRunning()) {
        sendButton.isEnabled = false
        sendButton.text = "启动服务..."
        OpenCodeServerManager.startServer(
            project = project,
            onStarted = {
                SwingUtilities.invokeLater {
                    doSend(projectPath, text)
                }
            },
            onFailed = { e ->
                SwingUtilities.invokeLater {
                    onSendFailed(e.message ?: "启动失败")
                }
            }
        )
        return
    }

    // 服务已运行，直接发送
    doSend(projectPath, text)
}
```

**新增 `doSend` 方法**：
```kotlin
private fun doSend(projectPath: String, text: String) {
    // 再次确认服务健康，防止状态陈旧
    if (!OpenCodeApi.isServerHealthySync()) {
        sendButton.text = "启动服务..."
        OpenCodeServerManager.startServer(
            project = project,
            onStarted = {
                SwingUtilities.invokeLater {
                    doSend(projectPath, text)
                }
            },
            onFailed = { e ->
                SwingUtilities.invokeLater {
                    onSendFailed(e.message ?: "启动失败")
                }
            }
        )
        return
    }

    sendButton.isEnabled = false
    sendButton.text = "发送中..."

    val sessionId = PromptEditorService.getOrCreateSession(projectPath) ?: return

    Thread {
        val success = PromptEditorService.sendMessage(sessionId, text)
        SwingUtilities.invokeLater {
            if (success) {
                textArea.text = ""
                sendButton.text = "已发送!"
                onSendSuccess()
                Thread {
                    Thread.sleep(1500)
                    SwingUtilities.invokeLater {
                        sendButton.text = "发送到 OpenCode"
                        sendButton.isEnabled = textArea.text.isNotBlank()
                    }
                }.start()
            } else {
                onSendFailed("发送失败")
            }
        }
    }.start()
}
```

**新增 `onSendFailed` 方法**：
```kotlin
private fun onSendFailed(message: String) {
    sendButton.text = message
    sendButton.isEnabled = true
}
```

### 流程图

```
点击"发送到 OpenCode"
        ↓
┌─────────────────────────┐
│  isServerRunning()?     │
└─────────────────────────┘
    ↓是              ↓否
  doSend()        显示"启动服务..."
    ↓            startServer()
    │               ↓
    │         ┌─────────────┐
    │         │ 启动成功？    │
    │         └─────────────┘
    │           ↓是      ↓否
    │         doSend()  onSendFailed()
    ↓               ↓
    │          ┌───────────┐
    │          │ doSend 中 │
    │          │ 再次检查   │
    │          │ 健康状态   │
    │          └───────────┘
    │            ↓否
    │      startServer()
    ↓
  发送成功
    ↓
  清空内容
```

## Verification Strategy

### QA Scenarios

**Scenario: 服务未启动时点击发送**
- Preconditions: 服务未运行，`isServerRunning()` 返回 false
- Steps: 点击"发送到 OpenCode"
- Expected Result: 显示"启动服务..."，服务启动后自动发送，发送成功后清空内容
- Evidence: .sisyphus/evidence/send-with-startup.md

**Scenario: 服务已启动时点击发送**
- Preconditions: 服务正常运行
- Steps: 点击"发送到 OpenCode"
- Expected Result: 直接显示"发送中..."，发送成功后清空内容
- Evidence: .sisyphus/evidence/send-when-running.md

**Scenario: 发送时服务崩溃**
- Preconditions: 服务运行中突然崩溃
- Steps: 点击发送
- Expected Result: `doSend` 检测到服务不健康，重新启动服务，然后发送
- Evidence: .sisyphus/evidence/send-reconnect.md

## Files Modified
- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`
- `AGENTS.md` (更新文档)

## Success Criteria
- [x] 使用 `OpenCodeServerManager.isServerRunning()` 和 `OpenCodeApi.isServerHealthySync()` 实现服务状态判断
- [x] `sendPrompt` 和 `doSend` 方法实现完整的启动-发送流程
- [x] `onSendFailed` 统一处理失败状态
- [x] 编译通过，代码无 error
- [x] AGENTS.md 已更新，添加 AddToPrompt 和 PromptEditor 自动启动说明
- [x] ⚠️ 已被新的 fix-prompt-editor-sending.md 计划替代

### 替代方案已实现！
请见 fix-prompt-editor-sending.md 计划，该计划实现了用户反馈的需求。
