# Session 下拉框 UI 修复计划

## 问题分析
1. 下拉框太丑！使用 IntelliJ 原生 ComboBox 没有样式
2. 下拉框没有默认值！需要在第一次打开时，如果没有 session，就创建一个！
3. UI 应该和 Commit 工具窗口类似，最好带刷新按钮或者更美观的样式！

## 修复方案

### 方案：初始化时自动创建会话（如果没有的话）
1. 在 init 块里，如果服务已启动（currentState == RUNNING），立刻加载 sessions
2. 如果加载到 0 sessions，立刻调用 createSession() 创建一个！
3. 改进下拉框的样式，让它更好看，类似 Commit 工具窗口的下拉框！
4. 改进 loading 指示器！

## 需要修改的文件
- src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/PromptToolWindowPanel.kt
