# 添加 Session 下拉选择器

## TL;DR
参考 Commit 工具窗口的 UI，在 PromptToolWindowPanel 中添加一个 session 下拉选择器，显示未归档的 session，默认选最新的。

## Context
- Commit 工具窗口中有一个下拉选择器（类似 last commit）
- 我们需要在 PromptToolWindowPanel 中添加类似的组件

## 修改内容

### PromptToolWindowPanel.kt

1. **添加 JComboBox 组件**
   - 显示 session 列表
   - 只显示未归档（isArchived == false）的 session
   - 默认选择最新的（按 createdAt 时间倒序排序）

2. **初始化时加载 session**
   - 在 init 块中调用 `PromptEditorService.getSessions()`
   - 如果有 session，按时间排序，默认选最新的
   - 如果没有 session，下拉框为空或显示提示

3. **doSend() 修改**
   - 使用下拉框选中的 session 发送消息，而不是 `getOrCreateSession()`

### Session 显示格式
在下拉框中显示：
- 显示 session id（截断部分？或者显示创建时间？）
- 可以显示：`Session ${id.substring(0,8)}` 或者类似的

### Session 排序
按 `createdAt` 时间倒序（最新的在上面）

## Files to Modify
- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt`

## Success Criteria
- [x] 添加 JComboBox 组件显示 session 列表
- [x] 只显示未归档的 session（!session.isArchived）
- [x] 默认选最新的 session（按 createdAt 倒序）
- [x] 发送消息时使用选中的 session
- [x] 编译通过
- [x] ✅ Session 数据类添加 toString(): "Session ${id.take(8)}"

## 实施完成！

## UI Layout
```
┌─────────────────────────────────┐
│ Session: [▼ Session 1234abcd]    │ ← 新增下拉框
├─────────────────────────────────┤
│                             │
│  text area                  │
│                             │
├─────────────────────────────────┤
│  [复制] [发送到 OpenCode]  │
└─────────────────────────────────┘
```
