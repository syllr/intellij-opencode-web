# opencode-web-ui

![Build](https://github.com/syllr/intellij-opencode-web/workflows/Build/badge.svg)

> **Fork of [xausky/intellij-opencode-web-ui](https://github.com/xausky/intellij-opencode-web-ui)**

## 简介

OpenCodeWeb 是一款 JetBrains IDE（IntelliJ IDEA、PyCharm、WebStorm 等）插件，为 OpenCode 提供便捷的 Web UI 集成。

> **Fork 说明**：本项目 fork 自 [xausky/intellij-opencode-web-ui](https://github.com/xausky/intellij-opencode-web-ui)，以下是相比原版的优化：

### 优化内容

1. **端口修改** - 将默认端口从 `4096` 改为 `10086`，避免与常见服务端口冲突

2. **重启刷新功能** - 添加 `Restart OpenCode Server` Action，可以通过 `Cmd+Shift+A` 搜索并执行，实现手动重启服务器并刷新页面

3. **ESC 快捷键修复** - 解决了在 JCEF 浏览器中按 ESC 键导致焦点从 Web 页面丢失的问题。原问题：在 Web 页面的输入框中按 ESC，焦点会从 JCEF 浏览器移走，导致后续按键无法被网页接收；现已修复，ESC 键能正确传递给网页处理

4. **Emacs 风格按键映射** - 支持 Emacs 风格的导航快捷键：
   - `Ctrl+N` → 下移
   - `Ctrl+P` → 上移
   - `Ctrl+E` → 行尾
   - `Ctrl+A` → 行首
   - `Ctrl+B` → 左移
   - `Ctrl+F` → 右移

5. **IDE 启动时自动更新** - IDE 每次启动后首次打开工具窗口时，会自动重启服务器以确保使用最新版本的 opencode

<!-- Plugin description -->

## Plugin Description

> **Note:** This is an unofficial plugin for OpenCode, maintained by the community and not affiliated with OpenCode.

### Features

- **Auto-start Service** - Click the sidebar icon to automatically check and start the OpenCode server
- **Smart Monitoring** - Periodically check server status and automatically restart failed services
- **Auto Cleanup** - Automatically stop OpenCode service when IDE exits to release resources
- **Sidebar Integration** - Display plugin icon in the right sidebar, click to access OpenCode Web UI
- **Project Sync** - Automatically load the Web interface for the current project
- **Manual Restart** - Use "Restart OpenCode Server" action to manually restart server and refresh page
- **Keyboard Shortcuts** - Emacs-style navigation and properly forwarded shortcuts in JCEF browser

### Use Cases

- Developers who need to use OpenCode Web UI in JetBrains IDE
- Developers who need to automatically manage OpenCode servers
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

3. **自动启动服务**  
   插件会自动检查 127.0.0.1:10086 端口，如果服务未运行，将自动执行：
   ```
   opencode serve --hostname 127.0.0.1 --port 10086
   ```

4. **访问 Web UI**  
   服务启动成功后，侧边栏将自动加载当前项目的 OpenCode Web 界面。

5. **手动重启服务**  
   使用快捷键 `Cmd+Shift+A` (macOS) 或 `Ctrl+Shift+A` (Windows/Linux)，搜索 "Restart OpenCode Server" 并执行，即可重启服务器并刷新页面。

## 配置

插件默认使用以下配置：
- 主机: 127.0.0.1
- 端口: 10086

如需修改，请确保 OpenCode CLI 使用相同的端口和主机启动。

## 故障排除

**问题：插件无法找到 opencode 命令**

- 确保 OpenCode CLI 已正确安装
- 确保 opencode 可执行文件在系统的 PATH 环境变量中
- 在终端中运行 `opencode --version` 验证安装

**问题：服务启动失败**

- 检查端口 10086 是否已被其他程序占用
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
