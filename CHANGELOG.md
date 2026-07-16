<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# opencode-web-ui Changelog

## [Unreleased]

### Changed

- **浏览器切换为 Microsoft Edge**:`OpenCodeBrowserLauncher` 移除 Google Chrome / Brave Browser fallback,只支持 Edge。`pickBrowser()` 检查 `/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge`;`launch()` 用 `open -na "Microsoft Edge" --args --app=<url> [--load-extension=<extDir>]`。**原因**:Chrome 150+ stable 静默忽略 `--load-extension=` flag(实测 `WARNING:chrome/browser/extensions/extension_service.cc:420] --load-extension is not allowed in Google Chrome, ignoring`),导致 plugin 端 Chrome extension 注入失效;Edge 150+ 接受该 flag 且对 unpacked extension 兼容性更好
- **Edge 未安装处理**:新增 `OpenCodeBrowserLauncher.checkEdgeInstalled()` — 在 `MyToolWindow.launchEdgeForSession()` 点击 sessions 列表行时调用;未安装时弹 `Messages.showErrorDialog` 提示下载 `https://www.microsoft.com/edge`,不 throw 不 crash
- **Dashboard UI 微调**:移除"Open in Edge"按钮(v2.0.1 有),改为"点击 sessions 列表行直接 Edge 打开"——更符合 web app 原生交互模式

## [2.0.0] - 2026-07-14

### Changed

- **架构大改:丢掉 JCEF 浏览器**(`BrowserPanel` / `JcefJsInjector` / `ResizeObserverThrottler` / `LinkContextMenuHandler` / `JcefKeyboardInterceptor` / `EmacsKeyHandler` 整删)。OpenCode Web UI 改在外部 **Chrome --app 模式 + 日常 profile 复用**(无 `--user-data-dir`,防止 localStorage 缓存丢失),通过 `OpenCodeBrowserLauncher` 启动
- **Dashboard 模式**:`MyToolWindow` 重写为纯 Swing 6 区面板(server 状态 / 项目路径 / 端口+SSE 健康 / 4 控制按钮 / sessions 列表 / +New Session),不再持有 JCEF 控件
- **in-IDE 通知 UI 砍掉**:`OpenCodeNotificationService.send()` 改为 no-op(Chrome --app 由 OpenCode Web UI 自带通知接管)
- **Plugin 自启 opencode server**(M0-T1 修 cwd = `project.basePath`;M2-T1 health gate 验证 `directory` 字段;端口冲突自动升级 12397-12400)
- **合并 `AddToPromptAction` 进 `CopyAsPromptAction`**(M1-T5),共享 `Toolkit.getDefaultToolkit().systemClipboard` 路径

### Added

- `OpenCodeBrowserLauncher`(`toolWindow/`):Chrome --app + 日常 profile 启动器。macOS-only。探测 Google Chrome > Edge > Brave,回退到 `ProcessBuilder --app` 路径
- 6 个新单测:`MyToolWindowTest` / `MyToolWindowFactoryTest` / `OpenCodeBrowserLauncherTest` / `ServerProcessLauncherTest` / `OpenCodeApiHealthDirectoryTest` / `CopyAsPromptMergeTest`

### Removed

- 6 个 JCEF 源文件 + 6 个相关测试 + 2 个偏离方案文件(`OpenInBrowserAction` / `CleanBrowserLauncher`,违反 `--user-data-dir` 硬约束)

## [1.0.0] - 2026-02-26

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

[Unreleased]: https://github.com/syllr/intellij-opencode-web/compare/2.0.0...HEAD
[2.0.0]: https://github.com/syllr/intellij-opencode-web/compare/1.0.0...2.0.0
[1.0.0]: https://github.com/syllr/intellij-opencode-web/commits/1.0.0
