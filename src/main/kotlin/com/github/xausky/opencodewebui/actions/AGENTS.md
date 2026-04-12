# IDE Actions 领域

**Parent:** ../../AGENTS.md

## 概述
通过 Find Action（Cmd/Ctrl+Shift+A）访问的 Actions。

## 结构
```
actions/
├── RestartServerAction.kt      # 重启 opencode serve
├── PassToJcefAction.kt        # 转发快捷键到 JCEF
└── ToggleOpenCodeAction.kt    # 显示/隐藏工具窗口
```

## 关键位置
| 任务 | 位置 | 备注 |
|------|------|------|
| 重启服务器 | RestartServerAction.kt | 杀死进程，调用 MyToolWindowFactory.startServer() |
| 快捷键转发 | PassToJcefAction.kt | ESC, Ctrl+A/E/B/F/N/P 到 JCEF |
| 切换窗口 | ToggleOpenCodeAction.kt | 显示/隐藏工具窗口 |

## 代码规范
- 实现 IntelliJ `AnAction`
- 在 `plugin.xml` 中注册
- 使用 `ActionManager`

## 传递给 JCEF 的快捷键映射
| 快捷键 | 功能 |
|--------|------|
| ESC | 将焦点返回到 JCEF（修复 ESC 失去焦点的问题） |
| Ctrl+A/E/B/F/N/P | Emacs 导航 |

## Action 参考资料
| 主题 | URL |
|------|-----|
| **Working with Actions** | https://plugins.jetbrains.com/docs/intellij/working-with-actions.html |
| **Action System** | https://plugins.jetbrains.com/docs/intellij/action-system.html |
| **AnAction Class** | https://plugins.jetbrains.com/docs/intellij/plugin-classes.html |
| **Keymap Extension** | https://plugins.jetbrains.com/docs/intellij/keymap.html |
