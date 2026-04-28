# 修复 PromptEditor 发送问题

## TL;DR
根据用户反馈，当前实现有问题。需要：
1. 面板打开时检测服务状态，未启动时按钮显示"启动服务"
2. 获取会话失败时用 IDEA 自带的提示功能，不要改按钮文本
3. 发送失败且是服务未启动原因时，按钮也应该改成"启动服务"

## Context
用户测试后反馈：
- "获取会话失败了" - 当前代码把按钮文本改成"获取会话失败"了
- 应该用 IDEA 自带提示功能显示错误，不要改按钮
- 面板打开时就应该检测服务状态

## 修改内容

### PromptToolWindowPanel.kt

1. **初始化检测服务状态**
   - 在 init 块里检测 `OpenCodeServerManager.isServerRunning()`
   - 如果未运行，sendButton 显示"启动服务"
   - 如果已运行，sendButton 保持正常显示

2. **sendPrompt() 重写**
   - 如果按钮是"启动服务"状态，点击应该调用 `OpenCodeServerManager.startServer()`
   - 如果是发送状态，正常发送流程
   - 获取 session 失败：用 IDEA Notification 显示错误，不要改按钮文本

3. **doSend() 更新**
   - 如果检测到服务不健康（崩溃），按钮改成"启动服务"
   - 不要递归调用，直接让用户点击"启动服务"

### 关键点
- 用 `OpenCodeServerManager.isServerRunning()` 而不是 `OpenCodeApi.isServerHealthySync()` 检测
- 服务未启动时，按钮是"启动服务"而不是禁用状态
- 错误提示用 Notification，不是按钮文本

## Files to Modify
- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`

## Success Criteria
- [x] 面板打开时检测服务状态，未启动时按钮显示"启动服务"
- [x] 获取会话失败用 Notification 提示，不是改按钮文本
- [x] 发送失败（服务崩溃）时，按钮改成"启动服务"
- [x] 编译通过

## 实施完成！

