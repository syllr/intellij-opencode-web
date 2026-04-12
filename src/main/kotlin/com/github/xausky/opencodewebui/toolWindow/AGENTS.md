# 工具窗口领域

**Parent:** ../../AGENTS.md

## 概述
核心 JCEF 浏览器 + OpenCode 服务器管理（507行，单文件）。

## 结构
```
toolWindow/
└── MyToolWindowFactory.kt    # 所有核心逻辑在此文件
```

## 关键位置
| 任务 | 位置 | 备注 |
|------|------|------|
| 服务器启动/停止 | MyToolWindowFactory.kt | startServer(), stopServer() |
| 健康检查 | MyToolWindowFactory.kt | 30秒间隔，自动重启 |
| JCEF 设置 | MyToolWindowFactory.kt | JBCefBrowser 初始化 |
| 键盘处理 | MyToolWindowFactory.kt | Emacs 快捷键 + ESC 修复 |
| 状态 | companion object | AtomicReference/AtomicBoolean 线程安全 |

## 关键常量
- `PORT = 12396`
- `HOST = "127.0.0.1"`

## 已修复的反模式
- ✅ 静态变量改为 AtomicReference/AtomicBoolean（线程安全）
- ✅ JBCefBrowser 通过 Disposer 管理生命周期
- ⚠️ 单个 507 行文件处理所有逻辑（仍可拆分）
- ⚠️ 端口硬编码（不可配置）

## JCEF 参考资料
| 主题 | URL |
|------|-----|
| **JCEF in IntelliJ** | https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html |
| **JBCefBrowser 源码** | https://github.com/JetBrains/intellij-community/tree/master/platform/ui.jcef/jcef |
| **CefKeyboardHandler** | https://github.com/chromiumembedded/java-cef/blob/master/java/org/cef/handler/CefKeyboardHandler.java |
| **JCEF API 文档** | https://chromiumembedded.github.io/java-cef/ |
| **JCEF 示例插件** | https://github.com/ilkeratik/sample-intellij-idea-plugin |

## JCEF 最佳实践
- 创建浏览器前先检查 `JBCefApp.isSupported()`
- 复杂配置使用 `JBCefBrowserBuilder`
- 注册 `CefMessageRouter` 时使用自定义路由名称（不要用 "cefQuery"）
- 通过 `JBCefDisposable` 正确释放浏览器资源
- 键盘事件通过 `CefKeyboardHandler.onPreKeyEvent()` 处理
