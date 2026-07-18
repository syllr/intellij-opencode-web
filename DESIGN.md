# DESIGN.md — IntelliJ OpenCode Web 插件设计文档

> **本文档定位**:项目级**架构设计**文档,描述当前生产系统的设计决策、关键机制与组件关系。
>
> **三元组关系**:
>
> - `AGENTS.md` — **How to develop**(开发宪法、流程规则、踩坑经验)
> - `DESIGN.md` — **How it's built**(架构设计、组件关系、关键决策)—— 本文档
> - `SPEC.md` — **What it must do**(系统级行为规范、SLA、安全契约、数据不变量)
>
> **范围**:聚焦**插件整体架构**,包括 SSE 消费、通知路由、subagent 追踪、进程管理。

---

## 0. 项目背景与子系统地图

### 0.1 项目概述

**IntelliJ OpenCode Web 插件**是 JetBrains IDE(2026.1+)的插件,**自启** opencode 子进程并通过外部 **Microsoft Edge --app 模式**展示 OpenCode Web UI。Dashboard 显示 server 状态 + 3 控制按钮(Stop / Restart / Reset)+ sessions 列表(点击行直接 Edge 打开),所有通知由 OpenCode Web UI 自身接管。v2.0.2 起只支持 Edge(原 Chrome 路径移除)。

核心能力(v2.0.0 起):

- 工具窗口(右侧边栏)显示 **Swing Dashboard**(server 状态 / 项目路径 / 端口+SSE 健康 / 4 控制按钮 / sessions 列表 / +New Session)
- 点击 Dashboard session 列表行调 `OpenCodeBrowserLauncher`(M1-T2)在外部 Microsoft Edge 窗口展示 OpenCode Web UI(v2.0.2 起只支持 Edge)
- Plugin **自启** opencode 子进程(M0-T1 cwd 热修复:`ProcessBuilder.directory = project.basePath`)
- 多 IDE 同端口冲突时 health gate 验证 `directory` 字段(M2-T1),**冲突时直接 onFailed**(端口固定 12396 不再升级 — Edge extension `background.js` / `manifest.json` + `MyToolWindow.buildOpenUrl()` 硬编码 12396,升级会引入 notification 预置 allow 错位 + Edge 连不上 server 的新失败模式)
- SSE 消费 opencode server 事件,驱动 file/refresh + bash 工具检测
- 文件变更检测同步 IDE VFS 刷新(`FullRefreshCoordinator`)
- Bash 工具执行检测,决定是否触发 VFS 刷新
- IdeaVim visual 模式选区支持(`IdeaVimIntegration`)
- Copy as Prompt(选中代码 → 系统剪贴板)
- Go-style Singleflight 防抖,合并重复点击的服务器启动请求
- **v2.0.0+ 完全拆除**:JCEF 浏览器(`BrowserPanel` 等 6 文件)、in-IDE 通知 UI(`OpenCodeNotificationService.kt` + `OpenCodeNotificationRouter.kt` 整删,SSE 通知事件从白名单移除)、JCEF 键盘拦截、AddToPrompt action

### 0.2 子系统清单

| 子系统         | 路径                                 | 角色                                                                                                |
| -------------- | ------------------------------------ | --------------------------------------------------------------------------------------------------- |
| **toolWindow** | `toolWindow/`                        | 工具窗口(Dashboard) + 服务器进程启停 + Singleflight + Edge --app 启动器(6 文件)                     |
| **listeners**  | `listeners/`                         | SSE 事件消费 + 文件刷新协调(4 文件)                                                                 |
| **actions**    | `actions/`                           | IDE Actions:Copy as Prompt(1 文件)                                                                  |
| **utils**      | `utils/`                             | HTTP API、IdeaVim 集成、SSE consumer 工厂(4 文件;v2.0.0+ 通知 service/router/OpenCodeConfig 已整删) |
| **根**         | `OpenCodeConstants.kt` `MyBundle.kt` | 端口/超时/间隔常量 + i18n                                                                           |

> **历史**: v2.0.0 起 6 个 JCEF 源文件整删(`BrowserPanel` / `JcefKeyboardInterceptor` / `EmacsKeyHandler` / `LinkContextMenuHandler` / `JcefJsInjector` / `ResizeObserverThrottler`)+ 1 个 action(`AddToPromptAction`)+ 1 个偏离 launcher(`CleanBrowserLauncher`)+ 1 个偏离 action(`OpenInBrowserAction`)。`HealthMonitor.kt` 已在 commit `2e7b302`(Part D)整删;`settings/OpenCodeConfigurable.kt` + `OpenCodeProjectActivity.kt` 分别在 commit `a5eafc4`(1.0.20)和 `95d7faf`(HEAD 之前)整删。

### 0.3 部署拓扑

```
┌────────────────────────────────────────────────────────────────────┐
│                      JetBrains IDE(单进程)                          │
│                                                                    │
│  ┌──────────────┐    ┌──────────────────┐                            │
│  │  ToolWindow  │───▶│ OpenCodeSSECons- │──┬─▶ FullRefreshCoord(文件) │
│  │  (Dashboard, │    │ umer(每 Project   │  └─▶ BashCommandHandler   │
│  │   纯 Swing)  │    │  一个实例)        │                            │
│  │              │    └──────────────────┘                            │
│  │  ┌────────┐  │              │                                      │
│  │  │Open   │  │              │ (dashboard 状态由 SSE 回调驱动)     │
│  │  │in Edge│  │              ▼                                      │
│  │  │(sessions 列表行)│  ┌──────────────────┐                        │
│  │  └────────┘  │    │ OpenCodeNotifi-  │ (no-op 桩)                  │
│  └──────────────┘    │ cationRouter →  │                              │
│         │             │ Service.send() │                              │
│         ▼             └──────────────────┘                              │
│  OpenCodeBrowserLauncher                                                 │
│         │                                                                 │
│         │  spawn 外部 Edge 进程                                          │
│         ▼                                                                 │
│  ┌──────────────────┐                                                    │
│  │  Edge --app=URL │ (复用用户日常 profile,无 --user-data-dir)            │
│  │  - OpenCode Web UI │                                                  │
│  │  - 浏览器原生通知  │                                                    │
│  └──────────────────┘                                                    │
│         │                                                                 │
│         ▼                                                                 │
│         用户                                                              │
│                                                                    │
│         IDE 进程内(单 JBR 运行时)                                   │
└──────────────┬─────────────────────────────────────────────────────┘
                │ HTTP + SSE  (127.0.0.1:12396/event?directory=<path>)
                ▼
┌────────────────────────────────────────┐
│      OpenCode CLI 进程(PLUGIN 自启)    │
│      - cwd = project.basePath (M0-T1)  │
│      - HTTP /global/health             │
│      - SSE  /event?directory=...        │
│      - POST /global/dispose            │
│      - GET  /session/:id               │
└────────────────────────────────────────┘
```

**关键事实**:

- IDE 进程、OpenCode CLI 进程、Edge 浏览器进程是**三个独立进程**
- IDE ↔ CLI 通过 localhost:12396 通信(端口固定,冲突时直接报错 — 见 §1.2 决策表)
- CLI ↔ Edge 通过 `http://localhost:12396/<base64path>/session/...` URL
- Edge 进程与 IDE 进程**生命周期独立** — 关闭 IDE **不**自动 kill Edge 窗口
- 每 Project 一个 `OpenCodeSSEConsumer` 实例(`SSEConsumerFactory.create(project)`),v2.0.0+ in-IDE 通知拆除后,per-instance 状态仅余 `eventSourceRef` / `connectionGen` / `lastEventAt` / `lastHeartbeatAt`
- OpenCode CLI 启动方式: `/bin/zsh -l -c "source ~/.zshrc && opencode serve --hostname 127.0.0.1 --port 12396"`(M0-T1 修 cwd = `project.basePath`,为加载用户 `.zshrc` 中的 PATH)

---

## 1. 架构核心决策

### 1.1 核心矛盾(已解决)

| 矛盾                                        | 解决方案                                                                                |
| ------------------------------------------- | --------------------------------------------------------------------------------------- |
| 单 IDE 实例多 Project 打开,SSE 事件如何隔离 | 每 Project 独立 `OpenCodeSSEConsumer` 实例,经 `SSEConsumerFactory.create(project)` 获取 |
| OpenCode 进程可能崩溃,IDE 必须能清理        | `OpenCodeServerManager` 4 阶段关闭策略(§3.2)                                            |
| HTTP 调用阻塞 SSE 事件分发                  | 1h TTL `SessionInfoCache` + 3 级 fallback 字段提取                                      |

### 1.2 关键决策

| 决策                        | 方案                                                                                                                                                                                                                                                                                                                                                                       | 收益                                                                |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| **三进程隔离**              | IDE / OpenCode CLI / Edge 三个独立进程,通过 localhost + URL 通信                                                                                                                                                                                                                                                                                                           | 故障隔离 + 利用 OpenCode + 利用系统 Edge(无 JCEF OSR 性能问题)      |
| **Edge --app 模式**         | `OpenCodeBrowserLauncher` 用 `open -na "Microsoft Edge" --args --app=<url>` 启动(回退 `ProcessBuilder --app=<url>`),窗口只剩 page content,无 tab/address/menu                                                                                                                                                                                                              | 60Hz+ 原生渲染 + 不丢 localStorage 缓存 + 不污染用户日常 Edge       |
| **项目隔离 Edge profile**   | 启动参数含 `--user-data-dir=~/.config/opencode-web-ui/edge-profiles/$projectHash/user-data/`(SHA-256 项目 hash 前 8 字符),与日常 Edge profile 隔离(commit `327c6c2` 由"日常 profile 复用"改为"项目隔离 profile";`--disable-sync` 必传防 Edge Sync 把已登录账号广播到新 profile,见 AGENTS.md 硬约束)                                                                        | 跨 project 上下文不串 + localStorage 持久化 + 跨 IDE 升级不丢       |
| **SSE 复用**                | 复用单个 `/event?directory=<path>` SSE 连接消费所有事件(文件 + bash),连接按 project 路径分流                                                                                                                                                                                                                                                                               | 一个连接处理所有事件类型                                            |
| **每 Project 一 consumer**  | 通过 `SSEConsumerFactory.create(project)` 单例工厂创建独立 consumer(全局 SSE 连接由 `OpenCodeApi.createEventSource(directory)` 按目录路径建立)                                                                                                                                                                                                                             | 多项目状态隔离 + 简化协调                                           |
| **Dashboard 替代 JCEF**     | `MyToolWindow` 重写为纯 Swing 6 区面板(server 状态 / 项目路径 / 端口+SSE 健康 / 3 控制按钮 / sessions 列表)                                                                                                                                                                                                                                                                | 避免 JCEF OSR 卡顿(物理限制无法配置)+ headless / 无 Edge 环境仍能跑 |
| **手动启动 + Singleflight** | Go-style `Singleflight.kt` leader/follower 模式 + `MyToolWindow.isServerReadyHandled` CAS 守卫拦截重复 `onServerReady` 回调                                                                                                                                                                                                                                                | 并发/重复点击合并为单次进程启动                                     |
| **in-IDE 通知 UI 砍掉**     | v2.0.0+ `OpenCodeNotificationService.kt` + `OpenCodeNotificationRouter.kt` 整删,SSE 通知事件从白名单移除,OpenCodeSSEConsumer 的 `dispatchNotification` / `handleSessionIdle` / `sessionTitles` / `idleNotifiedSessions` / `subagentSessionIds` 通知抑制代码全部删除,通知由 OpenCode server / Web UI 自身接管                                                               | 减少 IDE 端维护负担 + 通知 UI 与 Web UI 自然一致                    |
| **自启 opencode server**    | Dashboard Start 按钮触发 `OpenCodeServerManager.startServer()`,`ServerProcessLauncher.startOpenCodeProcess(project)` cwd = `project.basePath`                                                                                                                                                                                                                              | 端到端用户体验:点 Start → 几秒后 OpenCode Web UI 可用               |
| **Port 冲突直接报错**       | health gate 验证 `/global/health.directory == project.basePath`,不匹配时 kill 进程 + `onFailed` 让用户关其他 IDE 实例(M2-T1 升级版)。端口固定 12396 不再升级 — Edge extension `background.js` / `manifest.json` 硬编码 `http://localhost:12396/*`,`MyToolWindow.buildOpenUrl()` 同样硬编码 12396,升级会引入 notification 预置 allow 错位 + Edge 连不上 server 的新失败模式 | 多 IDE 端口冲突时给用户明确错误,而不是"默默错位"                    |
| **配置存储**                | `PropertiesComponent`(IDE 级别 key-value),无独立文件                                                                                                                                                                                                                                                                                                                       | 零开销读 + 自动持久化                                               |
| **进程启动**                | zsh login shell(`-l -c "source ~/.zshrc && opencode serve..."`)                                                                                                                                                                                                                                                                                                            | 加载用户 `.zshrc` 中的 PATH,确保 opencode 可执行文件可被找到        |
| **HTTP 客户端**             | `java.net.http.HttpClient` by lazy 共享(无 OkHttp 依赖)                                                                                                                                                                                                                                                                                                                    | JDK 内置,自动 keep-alive 连接池                                     |
| **工厂解耦**                | `SSEConsumerFactory.create(project)` 单例工厂                                                                                                                                                                                                                                                                                                                              | 工具类 `toolWindow` → `listeners` 依赖单向,无循环引用               |

### 1.3 设计原则

1. **职责单一**:每个类只做一件事(消费 / 路由 / 发送 / 存储)
2. **副作用最小化**:SSE 事件处理纯函数化,只在需要时触发副作用
3. **可降级**:HTTP 调用失败 → 占位符模板;SSE 断开 → 自动重连;进程崩溃 → 4 阶段关闭
4. **可观测**:所有关键路径都有 `thisLogger()` 日志(INFO/WARN/ERROR/DEBUG),DEBUG 日志在生产关闭时零开销(`isDebugEnabled` 守卫)

---

## 2. 关键子系统

### 2.1 `OpenCodeServerManager`(全局单例 `object`)

**职责**:opencode 子进程的生命周期管理 + SSE consumer 单例管理。

**状态字段**(全部用 `AtomicReference` / `AtomicBoolean` / `@Volatile` ):

```kotlin
object OpenCodeServerManager {
    private val serverProcess: AtomicReference<Process?>
    @Volatile private var sseConsumer: OpenCodeSSEConsumer?
    private val consumerProjectRef: AtomicReference<WeakReference<Project>?>
    private val shutdownInProgress: AtomicBoolean
}
```

**关键方法**:

- `startServer(project, onStarted, onFailed)`:异步启 opencode + 等健康 + 创建 SSE consumer
- `ensureSSEConsumer(project)`:获取或创建 SSE consumer(单例),处理多项目
- `disposeForProject(project)`:项目关闭时清理(只在属于该 project 时停止)
- `stopServer()`:IDE 退出时关闭(`serverProcess` 引用路径)
- `shutdownServer()`:用户手动 Shutdown(`findProcessByPort` 路径)
- 共享 `gracefulShutdown(acquireHandle, killFallback, errorTag)`:两个关闭路径共享

### 2.2 `OpenCodeSSEConsumer`(BackgroundEventHandler)

**职责(v2.0.0+)**:消费 SSE 事件流,按事件类型分发给:**文件刷新**(Bash 工具完成 + 文件 watcher 事件)+ **心跳健康信号**。**每 Project 一个实例**,per-instance 状态仅余 `eventSourceRef` / `connectionGen` / `lastEventAt` / `lastHeartbeatAt`(in-IDE 通知 v2.0.0+ 完全拆除)。

**事件分发架构**(v2.0.0+ 在 `onMessage()` 中,白名单 5 事件):

```
onMessage(event, messageEvent)
    │
    ├── 1. SSEEventParser.parse() → ParsedSSEEvent
    │      (非白名单事件 parser 早退,parsedMap=null,直接丢弃)
    │
    ├── 2. dedup 检查 (payload.id)
    │
    ├── 3. eventType = payload.type ?: return
    │
    ├── 4. server.heartbeat → lastHeartbeatAt = now (健康信号)
    │
    ├── 5. 文件事件处理
    │      message.part.updated              → BashCommandHandler
    │      session.diff                      → FullRefreshCoordinator(绕过 debounce)
    │      file.edited / file.watcher.updated → FullRefreshCoordinator
    │
    └── 6. project.basePath == null 跳过刷新
```

> v2.0.0+ 白名单: `message.part.updated` / `session.diff` / `file.edited` / `file.watcher.updated` / `server.heartbeat`(`session.idle` / `session.status` / `permission.asked` / `question.asked` / `session.created` / `session.updated` / `message.updated` 等通知事件在白名单外,parser 早退)。

### 2.3 `SSEEventParser`(object)

**职责**:解析 SSE 事件 JSON,统一为 `ParsedSSEEvent`。v2.0.0+ 白名单仅 5 事件(`message.part.updated` / `session.diff` / `file.edited` / `file.watcher.updated` / `server.heartbeat`),其他事件在 `parse()` 早退,不走全量 Gson 反序列化;`extractSessionID()` / `extractParentID()` / `extractTitle()` 扩展函数(in-IDE 通知相关)已删除。

新 wire 格式(`/event?directory=...` 端点)直出 `{id, type, properties}` 三键,无外层包装,故只需在 `properties.*` 路径内做 3 级 fallback。**已废弃**:旧 wire 格式的 `data.*` / `syncEvent.aggregateID` 路径不再使用(代码 grep 0 命中)。

完整字段位置解释见 `SSEEventParser.kt:21-43` 的 KDoc。

**`parse()` 高频事件快速路径**(`message.part.delta` 跳过 Map 转换):节省每事件 ~2-5KB 对象分配。

### 2.4 ~ 2.6 ~~in-IDE 通知模块~~(v2.0.0+ 完全拆除)

`OpenCodeNotificationRouter` / `OpenCodeNotificationService` / `OpenCodeConfig.notificationEnabled` 等 IDE 端通知代码已**完全删除**:

- `utils/OpenCodeNotificationRouter.kt` + `utils/OpenCodeNotificationService.kt` 整文件删除
- `OpenCodeSSEConsumer.onMessage()` 的通知事件分发(`permission.asked` / `question.asked` / `session.idle` / `session.status`)和 idle 抑制逻辑(`sessionTitles` / `idleNotifiedSessions` / `subagentSessionIds`)全部删除
- SSE 事件白名单从 12 缩到 5(`message.part.updated` / `session.diff` / `file.edited` / `file.watcher.updated` / `server.heartbeat`),通知事件在 parser 早退
- 通知由 OpenCode server / Web UI 自己处理(浏览器原生 Notifications API / 系统通知)

### 2.7 `MyToolWindowFactory`(ToolWindowFactory)

**职责(v2.0.0 起)**:工具窗口入口,创建纯 Swing **Dashboard 面板**(`MyToolWindow.getContent()`),不再持有 JCEF 浏览器。

**Dashboard 6 区**(详见 `MyToolWindow.kt`):

1. Server 状态指示(running/stopped/error + 颜色)
2. 项目路径 + git branch
3. 端口 + SSE 健康标签(`$OPENCODE_PORT ($OPENCODE_HOST)  正常  SSE 已连接`)
4. 3 控制按钮:Stop / Restart / Reset(不再有"Open in Edge"按钮,点击 sessions 列表行直接 Edge 打开)
5. Recent sessions 列表
6. - New Session 按钮

**手动焦点恢复**:`createToolWindowContent()` 不注册任何自动焦点监听(从 v1.x `FocusAdapter` + `ToolWindowManagerListener` 全部移除,见 `commits 95d7faf + 9a959ea`)。JCEF 焦点卡死问题因 v2.0.0 砍掉 JCEF 已**自动消解**。`resetToolWindow()` 仍保留为 public/internal 工具方法,供 Dashboard 的 Reset 按钮或将来扩展触发(目前 Dashboard 的 Reset 按钮只重置 UI 状态,未走 hide+activate 路径)。

---

## 3. 关键时序

### 3.1 启动时序

```
用户点击 IDE 侧边栏 OpenCode 图标
    │
    ▼
MyToolWindowFactory.createToolWindowContent()
    │  - 创建 MyToolWindow (Dashboard, 纯 Swing 6 区面板)
    │  - addContent 到 ToolWindow
    │
    ▼
Dashboard "Start OpenCode Server" 按钮(或 onCreated 触发 — 当前 Dashboard 启动不自动启)
    │
    ▼
OpenCodeServerManager.startServer(project, onStarted, onFailed)
    │
    ├─ health gate (M2-T1,只检测不升级)
    │   OpenCodeApi.getHealthDirectory(port) → directory == project.basePath?
    │   ├─ yes → reuse existing server on this port
    │   └─ no  → kill process + onFailed(让用户关其他 IDE 实例;端口固定 12396)
    │
    ├─ Backgroundable task:
    │   │
    │   ├─ OpenCodeApi.isServerHealthySync() on the chosen port
    │   │   ├─ true  → reuse running server → ensureSSEConsumer
    │   │   └─ false → startOpenCodeProcess(project)  (M0-T1 cwd = project.basePath)
    │   │             │
    │   │             └─ /bin/zsh -l -c "source ~/.zshrc && opencode serve --hostname 127.0.0.1 --port $PORT"
    │   │
    │   ├─ OpenCodeApi.waitForServerHealthy(30s) (轮询 2s)
    │   │   ├─ healthy → ensureSSEConsumer → onStarted
    │   │   └─ timeout → killProcessTreeByHandle → onFailed
    │   │
    │   └─ onStarted() 回调 → Dashboard 状态更新
    │
    └─ Dashboard 显示 ● Server: running
    ↓
用户点击 sessions 列表行
    │
    ▼
OpenCodeBrowserLauncher.launch(url)  →  spawn Edge --app=URL
    └─ OpenCode Web UI 加载,后续所有用户交互在 Edge 窗口内完成
```

### 3.2 SSE 事件流时序(v2.0.0+:文件刷新 + 心跳,通知事件已不在白名单内)

```
OpenCode CLI
    │
    ├─ [agent 工作流 ... file.edited, message.part.delta ...]
    │
    ├─ message.part.updated ───→ OpenCodeSSEConsumer
    │                                  │
    │                                  ├─ BashCommandHandler 检测 bash 工具完成
    │                                  └─ FullRefreshCoordinator.request()
    │
    ├─ file.edited / file.watcher.updated ──→ FullRefreshCoordinator.request()
    │
    ├─ session.diff ──→ FullRefreshCoordinator.request()(绕过 debounce)
    │
    └─ server.heartbeat ──→ lastHeartbeatAt = now
```

> v2.0.0+ `session.idle` / `session.status(idle)` / `permission.asked` / `question.asked` / `session.created` / `session.updated` / `message.updated` 事件均在 SSE 白名单外,parser 早退,IDE 端不消费。

### 3.3 关闭时序(IDE 退出)

```
IDE 退出触发 OpenCodeServerManager.stopServer()
    │
    ├─ shutdownInProgress CAS (true) → 防重入
    │
    ├─ 停止 SSE consumer
    │   synchronized(this) { sseConsumer?.stop(); sseConsumer = null }
    │
    ├─ serverProcess.getAndSet(null) → Process
    │   if null → return (server was externally started)
    │
    ├─ startDisposeThread() 异步发 POST /global/dispose (2s 客户端超时,不等)
    │
    ├─ handle.onExit().get(5s)  ← 真实退出时间
    │   ├─ 成功 → return
    │   └─ TimeoutException / InterruptedException → 继续
    │
    ├─ handle.destroy() (SIGTERM)
    │   handle.onExit().get(2s)
    │   ├─ 成功 → return
    │   └─ TimeoutException / InterruptedException → 继续
    │
    └─ killProcessTreeByPort() (SIGKILL)  ← 兜底
```

---

## 4. 核心机制

### 4.1 ~~Subagent 本地追踪 + 通知两层去重~~(v2.0.0+ 完全拆除)

v2.0.0+ 通知代码已完全拆除,无 `sessionTitles` / `SUBAGENT_TITLE_REGEX` / `handleSessionIdle` / `idleNotifiedSessions` 等子 agent 追踪与去重机制。`session.deleted` 竞态防御不再需要,通知事件(`session.created` / `session.updated` / `session.deleted` / `session.idle` 等)在 SSE 白名单外,parser 早退。

### 4.2 ~~通知两层去重~~(v2.0.0+ 不适用)

参考已被 `SPEC.md §7.1` 标记为不适用。

### 4.3 SSE Watchdog + 自动重连

```
watchdogThread (daemon)
    │
    └─ while (!interrupted) {
         Thread.sleep(SSE_WATCHDOG_INTERVAL_MS = 5s)
         if (now - lastEventAt > SSE_IDLE_TIMEOUT_MS = 30s) reconnect()
       }
```

**重连流程**:

1. `eventSourceRef.getAndSet(null)`
2. `startSseConnection()` 新连接
3. `old?.close()` 旧连接
4. `connectionGen.incrementAndGet()` 旧 consumer 的 `onClosed` 自动短路(generation 不匹配)

**重连后清理**(在 `onClosed()`,v2.0.0+ 通知拆除后无 per-instance 状态集合需清空):

- v2.0.0+ `onClosed()` 仅记录 `connected = false` 与 `SSEEventParser.clearCache()`(`dedupCache` 是 companion object 跨实例共享,本实例无 per-instance 状态需清理)
- 历史:曾清理 `sessionTitles` / `idleNotifiedSessions` / `subagentSessionIds` 等 per-consumer 状态,通知完全拆除后不再需要

**`SSEEventParser.dedupCache` 不在 `onClosed()` 清空**,仅由 `stop()` 清空(`SSEEventParser.clearCache()`,`SSEEventParser.kt:98`)。

### 4.5 ProcessHandle 进程树清理

`OpenCodeServerManager.killProcessTreeByHandle(process)`(用于 `startServer` 启动 30s 超时):

```kotlin
val descendants = process.toHandle().descendants().toList()
descendants.reversed().forEach { it.destroyForcibly() }  // 叶子到根
process.toHandle().destroyForcibly()
handle.onExit().get(3s)  // 等待确认
```

**只杀目标进程的后代**,不会误杀同 PGID 的无关进程。

`OpenCodeServerManager.killProcessTreeByPort()`(用于 `gracefulShutdown` 兜底):

通过 `lsof -tiTCP:$PORT` 找主进程 PID,递归调用 `pgrep -P $pid` 找所有子进程,按从叶到根顺序发 SIGKILL,sh 脚本整体超时 5s。

---

## 5. SSE 事件协议详解

### 5.1 当前 wire 格式(`/event?directory=...` 端点)

```json
{
  "id": "evt_e208271300012MZGbu2gNc47xU",
  "type": "session.status",
  "properties": {
    "sessionID": "ses_xxx",
    "info": {
      "id": "ses_xxx",
      "parentID": "ses_parent",
      "title": "@code-reviewer subagent: review foo"
    },
    "status": { "type": "idle" }
  }
}
```

- `id` 用于去重(`SSEEventParser.dedupCache`)
- `type` 用于直接路由(无 SyncEvent 嵌套)
- `properties` 包含 sessionID / info.title / parentID / status.type
- **已废弃**:旧 SyncEvent V2 格式(`payload.type == "sync"` + `syncEvent.aggregateID`)不再使用

### 5.2 ~~完整事件 → 通知映射~~(v2.0.0+ 完全拆除)

v2.0.0+ 通知代码完全拆除,无 `OpenCodeNotificationService` / `OpenCodeNotificationRouter`,无 `sessionTitles` / `idleNotifiedSessions` 等状态集合,无 `handleSessionIdle` 抑制逻辑。SSE 白名单仅 5 事件(文件刷新 + 心跳),通知事件(`permission.asked` / `question.asked` / `session.idle` / `session.status` / `session.created` / `session.updated` / `message.updated`)在 parser 早退。

通知由 OpenCode server / Web UI 自身处理。

### 5.3 SSE 事件白名单(文件刷新 + 心跳)

| SSE 事件                               | IDE 端处理                                                         |
| -------------------------------------- | ------------------------------------------------------------------ |
| `message.part.updated`                 | `BashCommandHandler` 检测 bash 工具完成 → `FullRefreshCoordinator` |
| `session.diff`                         | `FullRefreshCoordinator`(绕过 debounce)                            |
| `file.edited` / `file.watcher.updated` | `FullRefreshCoordinator` 主路径                                    |
| `server.heartbeat`                     | `lastHeartbeatAt = now`(`isHealthy()` 诊断用)                      |

---

## 6. ~~通知路由与去重~~(v2.0.0+ 完全拆除)

v2.0.0+ 通知代码已完全拆除(详见 §2.4 ~ §2.6、§4.1、§4.2、§5.2)。`OpenCodeSSEConsumer` 不再调用 `OpenCodeNotificationRouter.notify` / `OpenCodeNotificationService.send`,无 `idleNotifiedSessions` / `subagentSessionIds` / `handleSessionIdle` 等去重与抑制逻辑。事件去重仅余 `SSEEventParser.dedupCache`(按 payload.id 拦截 SSE 重发,与通知无关)。

---

## 7. 资源管理

### 7.1 Process 资源

| 资源                                            | 释放方式                                                                         |
| ----------------------------------------------- | -------------------------------------------------------------------------------- |
| `OpenCodeServerManager.serverProcess`           | `gracefulShutdown` 4 阶段(§3.3) + `killProcessTreeByPort` 兜底                   |
| `OpenCodeServerManager.startDisposeThread`      | daemon Thread,`isDaemon = true`                                                  |
| `OpenCodeServerManager.pipeToLogger` (×2)       | daemon Thread 读 stdout/stderr,`IOException` 静默退出,其他异常 warn 日志         |
| `OpenCodeServerManager.findProcessByPort`       | `try-finally` 关闭 Process + `waitFor(3s)`,超时 `destroyForcibly`                |
| `OpenCodeServerManager.killProcessTreeByHandle` | `try` 内递归杀后代 + 根,`onExit(3s)` 等待;`catch` 兜底走 `killProcessTreeByPort` |
| `OpenCodeServerManager.killProcessTreeByPort`   | `try-finally` 关闭 shell 进程 + `waitFor(5s)`,超时 `destroyForcibly`             |

### 7.2 SSE / HTTP 资源

| 资源                                            | 释放方式                                              |
| ----------------------------------------------- | ----------------------------------------------------- |
| `okhttp-eventsource` SSE 连接(`eventSourceRef`) | `eventSourceRef.getAndSet(null)?.close()` in `stop()` |
| `OpenCodeApi.sharedHttpClient`(`by lazy`)       | IDE 退出时 JVM 关闭                                   |
| `BufferedReader` in `findProcessByPort`         | `try-finally` 关闭(实测短命进程,GC 兜底)              |

### 7.3 LRU 缓存

| 集合                        | 容量 | 线程安全                                |
| --------------------------- | ---- | --------------------------------------- |
| `SSEEventParser.dedupCache` | 1000 | `Caffeine Cache` (Caffeine 库,原子操作) |

> v2.0.0+ 通知代码完全拆除后,`OpenCodeSSEConsumer.sessionTitles` / `idleNotifiedSessions` / `OpenCodeNotificationService.SessionInfoCache` 等 LRU 集合已删除;`dedupCache` 仍由 `stop()` 清空,按 payload.id 去重 SSE 重发。

### 7.4 Thread 管理

| Thread                                     | 类型                       | 清理                        |
| ------------------------------------------ | -------------------------- | --------------------------- |
| `OpenCodeSSEConsumer.watchdogThread`       | daemon                     | `interrupt()` in `stop()`   |
| `OpenCodeServerManager.startDisposeThread` | daemon (one-shot)          | 自然结束                    |
| `OpenCodeServerManager.pipeToLogger` (×2)  | daemon                     | 进程退出时 IOException 退出 |
| `FullRefreshCoordinator.scheduler`         | `ScheduledExecutorService` | `shutdownNow()` in `stop()` |

### 7.5 AppLifecycleListener / MessageBusConnection

| 资源                                                   | 释放方式                                                                  |
| ------------------------------------------------------ | ------------------------------------------------------------------------- |
| `MyToolWindowFactory.init` 块的 `MessageBusConnection` | 无 parent disposable,跟随 application 生命周期                            |
| `MyToolWindowFactory.contentManagerListeners`          | 项目关闭时移除(配对 `try-catch IllegalStateException` 防御项目已 dispose) |

v2.0.0 砍掉的资源(JCEF 全部移除,无需清理):

- ~~`JcefJsInjector` 注入 JS 的重试 `Alarm`~~ — 类已删
- ~~`BrowserPanel.disposeBrowser()` 释放 3 个 JCEF handler~~ — 类已删
- ~~`MyToolWindowFactory.sharedJBCefClient`~~ — 字段已删
- ~~`MyToolWindow` `focusListenerWindow` + `focusListener`~~ — 字段已删
- ~~`ResizeObserverThrottler.THROTTLE_SCRIPT`~~ — 类已删,Edge --app 模式无 OSR 性能问题

Dashboard 模式下不涉及 JCEF 浏览器重建,`resetToolWindow()` 仍保留供将来扩展触发(目前 Dashboard 的 Reset 按钮只重置 UI 状态文本)。

---

## 8. 关键文件路径速查

| 想做的事                              | 改哪里                                                                                                                                                                                                     |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 工具窗口入口 + Dashboard 内容         | `toolWindow/MyToolWindowFactory.kt` + `toolWindow/MyToolWindow.kt`(纯 Swing 6 区)                                                                                                                          |
| 服务器进程启停(4 阶段关闭)            | `toolWindow/OpenCodeServerManager.kt`                                                                                                                                                                      |
| Singleflight 防抖(手动启动去重)       | `toolWindow/Singleflight.kt`                                                                                                                                                                               |
| 进程启动 + cwd = project.basePath     | `toolWindow/ServerProcessLauncher.kt`(M0-T1;`startServerSingleflight` 端口固定 12396 + health gate 检测冲突时 kill+onFailed)                                                                               |
| Edge --app 启动器(项目隔离 profile)   | `toolWindow/OpenCodeBrowserLauncher.kt`(M1-T2;`pickBrowser` / `buildUrl` / `checkEdgeInstalled` / `launch` / `hasEdgeWindowOpen` / `hasRendererChild` / `buildOpenCommand` / `buildProcessBuilderCommand`) |
| SSE 消费、自动重连(文件刷新 + 心跳)   | `listeners/OpenCodeSSEConsumer.kt`(v2.0.0+ 仅消费 5 白名单事件)                                                                                                                                            |
| SSE 解析(5 事件白名单)                | `listeners/SSEEventParser.kt`                                                                                                                                                                              |
| Bash 工具事件检测                     | `listeners/BashCommandHandler.kt`                                                                                                                                                                          |
| 文件刷新(生产者-消费者)               | `listeners/FullRefreshCoordinator.kt`                                                                                                                                                                      |
| HTTP API(健康/session 等)             | `utils/OpenCodeApi.kt` + `OpenCodeApiResult.kt`                                                                                                                                                            |
| SSE consumer 单例工厂                 | `utils/SSEConsumerFactory.kt`                                                                                                                                                                              |
| IdeaVim visual 模式选区(反射缓存)     | `utils/IdeaVimIntegration.kt`                                                                                                                                                                              |
| 复制为 Prompt 格式(选中代码 → 剪贴板) | `actions/CopyAsPromptAction.kt`(M1-T5 合并 `AddToPromptAction` 后)                                                                                                                                         |
| 端口/超时/间隔常量(端口 12396)        | `OpenCodeConstants.kt`(M2-T1 加 `HEALTH_VERIFY_TIMEOUT_MS = 500L`)                                                                                                                                         |

> **v2.0.0 整删的文件**(9 个源文件 + 6 个测试):`BrowserPanel.kt` / `JcefKeyboardInterceptor.kt` / `EmacsKeyHandler.kt` / `LinkContextMenuHandler.kt` / `JcefJsInjector.kt` / `ResizeObserverThrottler.kt` / `AddToPromptAction.kt` / `OpenInBrowserAction.kt` / `CleanBrowserLauncher.kt` + 对应 6 个测试。

---

## 附录 A:已知的设计权衡

| 决策                                                                                                                  | 权衡                                                                                                                                   |
| --------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| v2.0.0 砍掉 JCEF 浏览器 + 改用 Edge --app 模式(v2.0.2 移除 Chrome 路径)                                               | 失去 JCEF 内嵌(无 JCEF 焦点卡顿问题),获得原生 60Hz+ 渲染;代价是 IDE 不再是 OpenCode Web UI 的唯一宿主,需用户点击 sessions 列表行打开   |
| v2.0.0 自启 opencode server(从"用户手动启"改为"plugin 启")                                                            | 用户少做一步操作;代价是 plugin 多管一个进程生命周期 + port 冲突处理(M2-T1 health gate 只检测不升级)                                    |
| 每 Project 一个 `OpenCodeSSEConsumer` 实例                                                                            | 多项目状态天然隔离,无需 directory → Project 注册表                                                                                     |
| 静态 `companion object` 集合(`SSEEventParser.dedupCache`)                                                             | 违反 AGENTS.md "禁止静态全局可变状态" HARD RULE,但 dedupCache 是 SSE 事件级语义(与 Project 无关,跨实例共享),已加 NOTE                  |
| `zsh -l` 启动 opencode                                                                                                | 必须 login shell 加载 `.zshrc` 中的 PATH,代价是启动慢 0.5-2s(原 `logDiagnosticEnvironment` 调试代码已删除)                             |
| Edge --app 模式 + 项目隔离 profile(`--user-data-dir=~/.config/opencode-web-ui/edge-profiles/$projectHash/user-data/`) | localStorage / cookies 跨 IDE 升级不丢,跨 project 不串;代价是无法跨用户/跨设备同步,且需 macOS Edge 已装 + `--disable-sync` 防账号广播  |
| 多进程架构(IDE / opencode / Edge 三独立进程)                                                                          | 关闭 IDE 不 kill Edge(用户决定);Edge crash 不影响 IDE;opencode crash 走 SSE watchdog → Dashboard 状态切换                              |
| v2.0.0+ 通知完全拆除(in-IDE 通知 UI 由 OpenCode server / Web UI 自身接管)                                             | 减少 IDE 端维护负担 + 通知 UI 与 Web UI 自然一致;代价是 IDE 端无法在 `permission.asked` 等事件时弹 IDE 内通知,需用户在 Edge 窗口内响应 |
