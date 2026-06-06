# opencode-web-ui

![Build](https://github.com/syllr/intellij-opencode-web/workflows/Build/badge.svg)

> **Fork of [xausky/intellij-opencode-web-ui](https://github.com/xausky/intellij-opencode-web-ui)**

## 简介

OpenCodeWeb 是一款 JetBrains IDE（IntelliJ IDEA、PyCharm、WebStorm 等）插件，为 OpenCode 提供便捷的 Web UI 集成。

> **Fork 说明**：本项目 fork 自 [xausky/intellij-opencode-web-ui](https://github.com/xausky/intellij-opencode-web-ui)，以下是相比原版的优化：

### 优化内容

1. **端口修改** - 将默认端口从 `4096` 改为 `12396`，避免与常见服务端口冲突

2. **手动启动 + Singleflight 防抖** - 工具窗口打开时不再自动探测/启动 server,改为始终显示 "Start OpenCode Server" 按钮,由用户手动点击启动;5 次连续点击通过 Go-style singleflight 合并为单次进程启动

3. **ESC 快捷键修复** - 解决了在 JCEF 浏览器中按 ESC 键导致焦点从 Web 页面丢失的问题。原问题：在 Web 页面的输入框中按 ESC，焦点会从 JCEF 浏览器移走，导致后续按键无法被网页接收；现已修复，ESC 键能正确传递给网页处理

4. **Emacs 风格按键映射** - 支持 Emacs 风格的导航快捷键：
   - `Ctrl+N` → 下移
   - `Ctrl+P` → 上移
   - `Ctrl+E` → 行尾
   - `Ctrl+A` → 行首
   - `Ctrl+B` → 左移
   - `Ctrl+F` → 右移

5. **Copy as Prompt** - 在编辑器中选中代码后，右键菜单选择 "Copy as Prompt"，自动复制为带文件路径和行号的 Prompt 格式，方便粘贴到 OpenCode

<!-- Plugin description -->

## Plugin Description

> **Note:** This is an unofficial plugin for OpenCode, maintained by the community and not affiliated with OpenCode.

### Features

- **Manual Start** - Click the sidebar icon to open the tool window, then click the "Start OpenCode Server" button to launch the server (no automatic startup)
- **Singleflight Debounce** - Multiple rapid clicks on the Start button are merged into a single server launch via Go-style singleflight pattern (5 clicks → 1 process)
- **Crash Detection** - Server health is monitored; if the server crashes, the tool window shows a reconnection button (no automatic restart)
- **Auto Cleanup** - Automatically stop OpenCode service when IDE exits to release resources
- **Sidebar Integration** - Display plugin icon in the right sidebar, click to access OpenCode Web UI
- **Project Path** - After server starts, loads the OpenCode Web interface for the current project
- **Keyboard Shortcuts** - Emacs-style navigation and properly forwarded shortcuts in JCEF browser
- **Copy as Prompt** - Right-click selected code in editor and choose "Copy as Prompt" to copy as formatted prompt with file path and line numbers

### Use Cases

- Developers who need to use OpenCode Web UI in JetBrains IDE
- Developers who prefer explicit control over the OpenCode server lifecycle (no auto-start, no auto-restart)
- Users who want to quickly view the AI assistant interface during coding
<!-- Plugin description end -->

## 安装

- 使用 IDE 内置插件系统：

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>搜索 "open code web"</kbd> >
  <kbd>Install</kbd>

- 使用 JetBrains Marketplace：

  访问 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30364-opencode-web-ui) 进行安装（原始插件）

  也可以从 JetBrains Marketplace 下载[最新版本](https://plugins.jetbrains.com/plugin/30364-opencode-web-ui/versions)，然后使用
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>从磁盘安装插件...</kbd>

- 手动安装：

  下载[最新版本](https://github.com/syllr/intellij-opencode-web/releases/latest)，然后使用
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>从磁盘安装插件...</kbd>

## 使用说明

1. **确保已安装 OpenCode CLI 工具**  
   插件需要在系统 PATH 中找到 `opencode` 命令。如未安装，请访问 OpenCode 官方文档进行安装。

2. **打开插件侧边栏**  
   在 IDE 右侧边栏找到 "OpenCodeWeb" 图标并点击。

3. **手动启动服务**  
   打开工具窗口后,会看到一个深色背景的 "Start OpenCode Server" 按钮。点击后,插件在后台执行:

   ```
   opencode serve --hostname 127.0.0.1 --port 12396
   ```

   启动成功后,自动加载当前项目的 OpenCode Web 界面。  
   如果你连点多次按钮,只会启动一次进程(Go-style singleflight 防抖),不会浪费资源。

4. **Server 崩溃恢复**  
   当 server 异常退出时,HealthMonitor 会在 15s 内检测到,并在工具窗口显示 "Start OpenCode Server" 按钮供你手动重启连接。  
   插件**不会**自动重启 server——一切由你决定。

5. **Copy as Prompt**  
   在编辑器中选中代码 → 右键 → "Copy as Prompt"，选中的代码会被复制为以下格式，可直接粘贴到 OpenCode：

   ```
   location:/path/to/file.kt:10-20
   content:
   ```

   选中的代码

   ```

   ```

## 配置

插件默认使用以下配置：

- 主机: 127.0.0.1
- 端口: 12396

如需修改，请确保 OpenCode CLI 使用相同的端口和主机启动。

## 故障排除

**问题：插件无法找到 opencode 命令**

- 确保 OpenCode CLI 已正确安装
- 确保 opencode 可执行文件在系统的 PATH 环境变量中
- 在终端中运行 `opencode --version` 验证安装

**问题：服务启动失败**

- 检查端口 12396 是否已被其他程序占用
- 查看 IDE 日志窗口中的错误信息
- 手动运行启动命令排查问题

**问题：快捷键不生效**

- 确保 OpenCodeWeb 工具窗口已激活（点击侧边栏图标）
- `Cmd+K` 和 `Cmd+,` 在 JCEF 焦点时会传递给网页而非 IDEA

## 开发

项目基于 [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) 开发。

## 许可证

本项目采用 MIT 许可证。

## 免责声明

本插件是 OpenCode 的非官方社区插件，与 OpenCode 官方没有任何关联。使用本插件产生的任何问题，请通过 GitHub Issues 反馈。

---

> 💡 如果你觉得这个插件有帮助，欢迎给个 Star 🌟
