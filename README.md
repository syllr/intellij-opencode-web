# opencode-web-ui

![Build](https://github.com/syllr/intellij-opencode-web/workflows/Build/badge.svg)

> **Fork of [xausky/intellij-opencode-web-ui](https://github.com/xausky/intellij-opencode-web-ui)**

## 简介

OpenCodeWeb 是一款 JetBrains IDE（IntelliJ IDEA、PyCharm、WebStorm 等）插件，为 OpenCode 提供便捷的 Web UI 集成。

> **Fork 说明**：本项目 fork 自 [xausky/intellij-opencode-web-ui](https://github.com/xausky/intellij-opencode-web-ui)，以下是相比原版的优化：

### 优化内容

1. **端口修改** - 将默认端口从 `4096` 改为 `12396`，避免与常见服务端口冲突

2. **手动启动 + Singleflight 防抖** - 工具窗口打开时不再自动探测/启动 server,改为始终显示 "Start OpenCode Server" 按钮,由用户手动点击启动;并发/重复点击通过 Go-style singleflight(`Singleflight.kt` leader/follower 模式)合并为单次进程启动

3. **健康目录验证 + 端口冲突自动升级 (M2-T1)** - 启动 server 前调 `/global/health` 验证 `directory` 字段是否匹配当前 `project.basePath`,多 IDE 同端口碰撞时自动 port upgrade 12397→12400

4. **Copy as Prompt** - 在编辑器中选中代码后，右键菜单选择 "Copy as Prompt"，自动复制为带文件路径和行号的 Prompt 格式,直接走系统剪贴板

5. **v2.0.0 架构大改 + v2.0.2 浏览器切换** - 砍掉 JCEF 浏览器内嵌(`BrowserPanel` 等 6 个源文件整删),改用 **Microsoft Edge --app 模式 + 日常 profile 复用**展示 OpenCode Web UI;Dashboard 显示 server 状态 + 3 控制按钮(Stop / Restart / Reset)+ sessions 列表(点击 session 行直接 Edge 打开);in-IDE 通知 UI 砍掉,由 Edge 窗口内的 OpenCode Web UI 自身接管;v2.0.2 起只支持 Edge(原 Chrome 路径移除,因 Chrome 150+ stable 禁 `--load-extension=`,Edge 对扩展兼容性更好)

<!-- Plugin description -->

## Plugin Description

> **Note:** This is an unofficial plugin for OpenCode, maintained by the community and not affiliated with OpenCode.

### Features

- **Manual Start** - Click the sidebar icon to open the tool window, then click the "Start OpenCode Server" button to launch the server (no automatic startup)
- **Singleflight Debounce** - Multiple rapid clicks on the Start button are merged into a single server launch via Go-style singleflight pattern (concurrent clicks → 1 process)
- **Crash Detection** - Server health is detected via SSE connection state; on shutdown, the tool window immediately shows the Start button (no automatic restart)
- **Auto Cleanup** - Automatically stop OpenCode service when IDE exits to release resources
- **Dashboard Panel** - Pure Swing 6-area panel showing server status / project path / port + SSE health / 3 control buttons (Stop / Restart / Reset) / sessions list (click row to open in Edge). No browser widget embedded in IDE.
- **Edge --app Launcher** - Clicking a session row spawns external Microsoft Edge process in --app mode via `OpenCodeBrowserLauncher`, reusing user's daily Edge profile (no --user-data-dir), so localStorage / cookies persist across clicks and across IDE restarts. Edge not installed → `Messages.showErrorDialog` prompts user to install from https://www.microsoft.com/edge
- **Copy as Prompt** - Right-click selected code in editor and choose "Copy as Prompt" to copy as formatted prompt with file path and line numbers
- **macOS only** - Hard rule per SPEC §2

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

  本仓库基于 xausky 原始插件 (`plugin id 30364`) fork，发布在自己的 Marketplace 页面（搜索 "opencode web"）。
  xausky 原始插件页面：[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30364-opencode-web-ui)（仅供溯源参考）

  也可以从 JetBrains Marketplace 下载最新版本，然后使用
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>从磁盘安装插件...</kbd>

- 手动安装：

  下载[最新版本](https://github.com/syllr/intellij-opencode-web/releases/latest)，然后使用
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>从磁盘安装插件...</kbd>

## 使用说明

1. **确保已安装 OpenCode CLI 工具**  
   插件需要在系统 PATH 中找到 `opencode` 命令。如未安装，请访问 OpenCode 官方文档进行安装。
   Microsoft Edge 浏览器需要装在 `/Applications/Microsoft Edge.app`(Open session 按钮依赖;未安装时 Dashboard 会弹 dialog 提示下载)。

2. **打开插件侧边栏**  
   在 IDE 右侧边栏找到 "OpenCodeWeb" 图标并点击,显示 **Dashboard** 面板(server 状态 / 项目路径 / 端口 + SSE 健康 / 4 控制按钮 / sessions 列表)。

3. **手动启动服务**  
   点击 "Start OpenCode Server" 按钮,插件在后台执行:

   ```
   opencode serve --hostname 127.0.0.1 --port 12396
   ```

   cwd = `project.basePath`(M0-T1 修),所以 server 启动后能正确识别当前项目。
   如果你连点多次按钮,只会启动一次进程(Go-style singleflight 防抖),不会浪费资源。
   启动成功后,端口冲突时自动 port upgrade 12397→12400。

4. **Open session in Edge**  
   server 启动后,点击 Dashboard 的 session 列表行,plugin 会在外部 Microsoft Edge 窗口以 **--app 模式** 加载 OpenCode Web UI 对应 session URL。Edge 复用你日常的 profile(localStorage / cookies 不丢),关闭 Edge 不影响 IDE。

5. **Server 崩溃恢复**  
   当 server 主动 shutdown 时,SSE 连接被服务端关闭,工具窗口通过 `onConnectionLost` 回调**立即**显示 "Start OpenCode Server" 按钮(早期 HealthMonitor 5s 轮询机制已删除,改由 SSE `onClosed`/`onError` 直接驱动)。
   当 server 临时不可用后恢复时,SSE 重建 1.5s debounce 后自动恢复 UI 显示。
   插件**不会**自动重启 server——一切由你决定。

6. **Copy as Prompt**  
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

**问题：Edge 启动后 Web UI 加载失败 / 找不到 Edge**

- 确保 Microsoft Edge 装在 `/Applications/Microsoft Edge.app`
- 如果没装,点击 session 行时 Dashboard 会弹 `Messages.showErrorDialog` 提示下载 Edge,plugin 不会 throw / crash,server 仍在跑
- 如果 Edge 在但启动失败,看 IDE 日志窗口中的 `[Dashboard] Edge launch failed: ...` 错误信息

## 开发

项目基于 [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) 开发。

## 许可证

本项目采用 MIT 许可证。

## 免责声明

本插件是 OpenCode 的非官方社区插件，与 OpenCode 官方没有任何关联。使用本插件产生的任何问题，请通过 GitHub Issues 反馈。

---

> 💡 如果你觉得这个插件有帮助，欢迎给个 Star 🌟
