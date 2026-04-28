# optimize-send-prompt learnings

## 实施完成

### 实现内容
1. `sendPrompt()` - 使用 `OpenCodeServerManager.isServerRunning()` 判断服务状态
2. `doSend()` - 用 `OpenCodeApi.isServerHealthySync()` 二次确认（防止状态陈旧）
3. `onSendFailed()` - 统一处理失败状态

### 关键设计决策
- 主要判断使用 `isServerRunning()` 本地状态，避免每次发送前都网络检查
- `doSend()` 中的二次确认是为了处理服务器崩溃后状态陈旧的情况
- 服务启动成功后通过回调自动继续发送流程

### 文件修改
- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`

## 待用户测试
运行 `./gradlew runIde` 测试三个场景：
1. 服务未启动时点击发送
2. 服务已启动时点击发送
3. 发送时服务崩溃自动重连
