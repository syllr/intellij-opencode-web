# 重命名 RestartServerAction 为 ShutdownServerAction

## TL;DR
将 `RestartServerAction` 改名为 `ShutdownServerAction`，功能从"重启"改为"关闭服务器"

## Context
用户认为不需要重启服务器的功能，只需要关闭服务器。关闭后每5秒的健康检查会自动显示启动按钮。

## Work Objectives

### 必须修改

1. **重命名 Action 类**
   - `RestartServerAction.kt` → `ShutdownServerAction.kt`
   - 类名：`RestartServerAction` → `ShutdownServerAction`
   - 功能：调用 `MyToolWindowFactory.stopServer()`

2. **更新 plugin.xml**
   - 注册 ID：`RestartServer` → `ShutdownServer`
   - 类名引用：`RestartServerAction` → `ShutdownServerAction`
   - 文本：`Restart OpenCode Server` → `Shutdown OpenCode Server`

3. **简化 stopServer() 逻辑（可选）**
   - 当前 `stopServer()` 逻辑已经完整
   - 可以保持不变

### 应该保持不变
- 健康检查逻辑（每5秒）
- 启动按钮显示逻辑
- 其他 Action

---

## 文件修改清单

| 文件 | 修改 |
|------|------|
| `actions/RestartServerAction.kt` | 重命名为 `ShutdownServerAction.kt`，内容改为调用 `stopServer()` |
| `plugin.xml` | 更新 action 注册 ID 和类引用 |

## TODO

- [x] 1. 重命名 `RestartServerAction.kt` → `ShutdownServerAction.kt`
- [x] 2. 修改类名为 `ShutdownServerAction`
- [x] 3. 修改功能为调用 `MyToolWindowFactory.stopServer()`
- [x] 4. 更新 `plugin.xml` 中的 action 注册
- [x] 5. 删除旧的 `RestartServerAction.kt` 文件
- [x] 6. 构建并验证

## Commit Strategy
- Commit: YES
- Message: `refactor: Rename RestartServerAction to ShutdownServerAction`
