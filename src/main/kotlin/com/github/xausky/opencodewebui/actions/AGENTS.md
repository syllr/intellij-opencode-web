# IDE Actions 领域

**Parent:** ../../AGENTS.md

## 概述

通过 Find Action（Cmd/Ctrl+Shift+A）访问的 Actions。

## 结构

```
actions/
├── RestartServerAction.kt      # 重启 opencode serve
├── PassToJcefAction.kt        # 转发快捷键到 JCEF
├── ToggleOpenCodeAction.kt    # 显示/隐藏工具窗口
├── CopyAsPromptAction.kt      # 复制选中文本为 Prompt 格式
├── PromptActions.kt           # Action 基类和工具函数
├── OpenPromptEditorAction.kt  # 打开 Prompt 编辑器
└── AppendToPromptAction.kt   # 追加选中文本到 Prompt
```

## 关键位置

| 任务             | 位置                        | 备注                                         |
|----------------|---------------------------|--------------------------------------------|
| 重启服务器          | RestartServerAction.kt    | 杀死进程，调用 MyToolWindowFactory.startServer()  |
| 快捷键转发          | PassToJcefAction.kt       | ESC, Ctrl+A/E/B/F/N/P 到 JCEF               |
| 切换窗口           | ToggleOpenCodeAction.kt   | 显示/隐藏工具窗口                                  |
| Copy as Prompt | CopyAsPromptAction.kt     | 复制到剪贴板                                     |
| 打开 Prompt 编辑器  | OpenPromptEditorAction.kt | 打开或聚焦 Prompt 编辑器 Tab                       |
| 追加到 Prompt     | AppendToPromptAction.kt   | 将选中文本追加到 Prompt 编辑器                        |
| Action 基类      | PromptActions.kt          | AbstractPromptAction + showSessionSelector |

## 代码规范

- 实现 IntelliJ `AnAction`
- 在 `plugin.xml` 中注册
- 使用 `ActionManager`

## 传递给 JCEF 的快捷键映射

| 快捷键              | 功能                          |
|------------------|-----------------------------|
| ESC              | 将焦点返回到 JCEF（修复 ESC 失去焦点的问题） |
| Ctrl+A/E/B/F/N/P | Emacs 导航                    |

## Copy as Prompt Action

- **菜单位置**: 编辑器右键菜单 Paste 选项之后
- **触发条件**: 有选中文本时启用，无选中文本时隐藏
- **输出格式**: `location:/path/to/file.kt:10-15\ncontent:\n```\n选中的代码\n```\n`
- **实现方式**: 复制到系统剪贴板
- **⚠️ 注意**: IntelliJ `EditorPopupMenu` 中的 Paste action ID 是 `$Paste`，不是 `EditorPaste`

## OpenPromptEditor Action

- **Action ID**: `com.shenyuanlaolarou.opencodewebui.OpenPromptEditor`
- **功能**: 打开或聚焦 Prompt 编辑器 Tab
- **实现**: 调用 `PromptEditorTabFactory.showPromptEditor(toolWindow)`
- **注册**: plugin.xml 中的 `<actions>` 节点

## AppendToPrompt Action

- **Action ID**: `com.shenyuanlaolarou.opencodewebui.AppendToPrompt`
- **功能**: 将选中文本追加到 Prompt 编辑器
- **触发条件**: 有选中文本且 Prompt 编辑器处于可编辑状态
- **输出格式**: `location:/path/to/file.kt:10-15\n```\n选中的代码\n```\n`
- **注册**: plugin.xml 中的 `<actions>` 节点

## PromptActions 工具类

| 函数                                       | 说明                           |
|------------------------------------------|------------------------------|
| `AbstractPromptAction`                   | Action 基类，提供通用功能             |
| `getCurrentProject(event)`               | 从 AnActionEvent 获取当前 Project |
| `showSessionSelector(sessions, project)` | 显示会话选择对话框                    |
| `SessionInfo`                            | 会话信息数据类                      |

## Action 参考资料

| 主题                       | URL                                                                   |
|--------------------------|-----------------------------------------------------------------------|
| **Working with Actions** | https://plugins.jetbrains.com/docs/intellij/working-with-actions.html |
| **Action System**        | https://plugins.jetbrains.com/docs/intellij/action-system.html        |
| **AnAction Class**       | https://plugins.jetbrains.com/docs/intellij/plugin-classes.html       |
| **Keymap Extension**     | https://plugins.jetbrains.com/docs/intellij/keymap.html               |
