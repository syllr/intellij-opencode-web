# 代码重构计划 — 单一职责原则拆分

## TL;DR

> **目标**: 将 3 个违反 SRP 的大文件（总计 1295 行）拆分为 14 个职责独立的小文件
> 
> **核心拆分**:
> - `MyToolWindowFactory.kt` (596 行 → ~100 行)
> - `OpenCodeSSEConsumer.kt` (377 行 → ~160 行)
> - `AddToPromptAction.kt` (322 行 → ~60 行)
>
> **文件数**: 13 → 22（净增 9）
> **总工作量**: 5.25 人天

---

## Context

### 当前问题

| 文件 | 行数 | 混合职责 |
|:----|:----:|:---------|
| MyToolWindowFactory.kt | 596 | ToolWindowFactory + BrowserPanel(220行) + Emacs键盘 + 上下文菜单 + 健康监控 |
| OpenCodeSSEConsumer.kt | 377 | SSE连接 + 4种事件处理 + bash命令解析 + 去重 |
| AddToPromptAction.kt | 322 | AnAction + IdeaVim反射(208行) + JS注入 |

### 额外问题
- 魔数（`< 1000`、`Thread.sleep(5000)`、`1..20`）分散各处
- `PassToJcefAction` 命名不清晰
- `toolWindow/OpenCodeServerManager` 跨包直接 `new` `listeners/OpenCodeSSEConsumer`

### 目标包结构

```
src/main/kotlin/com/github/xausky/opencodewebui/
├── OpenCodeConstants.kt        # 🆕 魔数集中管理
├── MyBundle.kt                 # ✅ 不变
├── actions/
│   ├── AddToPromptAction.kt    # 🔧 简化: ~60行
│   ├── CopyAsPromptAction.kt   # ✅ 不变
│   ├── JcefShortcutPassthroughAction.kt  # 🔧 重命名
│   └── formatAsPrompt.kt       # 🆕 独立顶层函数
├── listeners/
│   ├── OpenCodeSSEConsumer.kt  # 🔧 简化: ~160行
│   ├── OpenCodeDiffRefresher.kt # ✅ 不变
│   ├── FullRefreshCoordinator.kt # ✅ 不变
│   ├── SSEEventParser.kt       # 🆕 JSON解析
│   ├── BashCommandHandler.kt   # 🆕 bash命令处理
│   └── RefreshDeduplicator.kt  # 🆕 去重
├── toolWindow/
│   ├── MyToolWindowFactory.kt  # 🔧 简化: ~100行
│   ├── MyToolWindow.kt         # 🆕 核心窗口逻辑
│   ├── BrowserPanel.kt         # 🆕 JCEF浏览器
│   ├── OpenCodeServerManager.kt# 🔧 简化
│   ├── HealthMonitor.kt        # 🆕 健康检查
│   ├── EmacsKeyHandler.kt     # 🆕 Emacs快捷键
│   └── JcefKeyboardInterceptor.kt # 🆕 键盘阻断
├── utils/
│   ├── OpenCodeApi.kt          # ✅ 不变
│   ├── SessionHelper.kt        # ✅ 不变
│   ├── PromptEditorService.kt  # ✅ 不变
│   ├── JcefJsInjector.kt       # 🆕 JS注入
│   ├── IdeaVimIntegration.kt   # 🆕 IdeaVim反射
│   └── SSEConsumerFactory.kt   # 🆕 工厂方法
```

---

## Work Objectives

### Must Have
- 所有拆分后编译通过（`./gradlew buildPlugin` → SUCCESS）
- 已有测试全部通过（`./gradlew check` → SUCCESS）
- 零行为变更（纯重构）
- 保留 `plugin.xml` 中所有注册项

### Must NOT Have
- 不改 `plugin.xml`（除了 PassToJcefAction 重命名）
- 不引入新第三方依赖
- 不改变任何业务逻辑

---

## Execution Strategy

### 并行执行说明

阶段 0 和阶段 1 的部分任务可以并行：
- 任务 0.1（魔数提取）与任务 0.2（重命名）互不依赖 → 可一起做
- 任务 1.1（IdeaVim）与 1.2（JS注入）来自同一文件但提取目标独立 → 串行（一个文件只能拆一次）
- 任务 1.3（Emacs）与 1.4（键盘阻断）来自 MyToolWindowFactory 同一个文件的不同段落 → 可并行
- 任务 2.3/2.4/2.5（SSE拆分）都来自 OpenCodeSSEConsumer → 串行

**推荐执行路径（全部串行，每步编译验证）**。

---

## TODOs

### 阶段 0：前置准备

- [x] 0.1 **魔数提取到 OpenCodeConstants.kt**

  **What to do**:
  - 将以下魔数提取为具名常量：
    - `HEALTH_CHECK_INTERVAL_MS=5000` (当前 MyToolWindowFactory)
    - `HEALTH_CHECK_START_DELAY_MS=10000`
    - `DEDUP_WINDOW_MS=1000` (当前 OpenCodeSSEConsumer)
    - `FULL_REFRESH_POLL_MS=500` (当前 FullRefreshCoordinator)
    - `BROWSER_READY_MAX_RETRIES=20`, `BROWSER_READY_RETRY_DELAY_MS=500` (当前 AddToPromptAction)
    - `SERVER_START_TIMEOUT_MS=30000` (当前 OpenCodeServerManager)
    - `HTTP_TIMEOUT_MS=8000` (当前 OpenCodeApi)
    - `SESSION_QUERY_TIMEOUT_MS=2000` (当前 PromptEditorService)
  - 替换所有文件中的硬编码值为常量引用

  **Files affected**: `MyToolWindowFactory`, `OpenCodeSSEConsumer`, `AddToPromptAction`, `FullRefreshCoordinator`, `OpenCodeServerManager`, `OpenCodeApi`, `PromptEditorService`

  **Commit**: `refactor: extract magic numbers to OpenCodeConstants`

- [x] 0.2 **重命名 PassToJcefAction → JcefShortcutPassthroughAction**

  **What to do**:
  - 重命名类
  - 更新 `plugin.xml` 中 2 处 class 引用
  - 更新 `META-INF/plugin.xml` 中所有引用

  **Commit**: `refactor: rename PassToJcefAction to JcefShortcutPassthroughAction`

---

### 阶段 1：基础拆分

- [x] 1.1 **从 AddToPromptAction 提取 IdeaVim 反射代码** → `utils/IdeaVimIntegration.kt`

  **What to do**:
  - 提取 `getIdeaVimVisualSelection()`, `isInVisualMode()`, `exitVisualMode()`, `isIdeaVimInstalled()`
  - 封装为 object `IdeaVimIntegration`
  - 暴露 3 个方法: `getVisualSelection(editor)`, `isInVisualMode(editor)`, `exitVisualMode(editor)`
  - `AddToPromptAction.kt` 从 322 行减为 ~110 行

  **Files**:
  - `+ src/main/kotlin/.../utils/IdeaVimIntegration.kt`
  - `- actions/AddToPromptAction.kt` (删除 208 行，改为委托调用)

  **Commit**: `refactor: extract IdeaVim reflection code to utils/IdeaVimIntegration`

- [x] 1.2 **从 AddToPromptAction 提取 JS 注入逻辑** → `utils/JcefJsInjector.kt`

  **What to do**:
  - 提取 `appendToOpenCodeWeb()`, `executeAppend()`, `isCorrectSession()`, `buildSessionUrl()`
  - 封装为 object `JcefJsInjector`
  - 暴露 1 个方法: `appendTextToEditor(project, text)`
  - `AddToPromptAction.kt` 最终 ~60 行

  **Files**:
  - `+ src/main/kotlin/.../utils/JcefJsInjector.kt`
  - `- actions/AddToPromptAction.kt` (删除 JS 注入代码)

  **Commit**: `refactor: extract JS injection logic to utils/JcefJsInjector`

- [x] 1.3 **提取 Emacs 键盘映射** → `toolWindow/EmacsKeyHandler.kt`

  **What to do**:
  - 提取 `addEmacsKeyListener()`, `sendKeyEvent()`, `handleEmacsKey()` 和映射表
  - 封装为 object `EmacsKeyHandler`
  - 暴露 1 个方法: `addEmacsKeyMapping(component)`

  **Files**:
  - `+ src/main/kotlin/.../toolWindow/EmacsKeyHandler.kt`
  - `- toolWindow/MyToolWindowFactory.kt` (删除 252-284 行)

  **Commit**: `refactor: extract Emacs key handler to toolWindow/EmacsKeyHandler`

- [x] 1.4 **提取键盘阻断处理** → `toolWindow/JcefKeyboardInterceptor.kt`

  **What to do**:
  - 提取 `setupComponent()`, `setupComponentHierarchy()` 中的键盘阻断逻辑
  - 封装为 object `JcefKeyboardInterceptor`
  - 暴露 1 个方法: `interceptKeys(component)`

  **Files**:
  - `+ src/main/kotlin/.../toolWindow/JcefKeyboardInterceptor.kt`
  - `- toolWindow/MyToolWindowFactory.kt` (删除 223-250 行)

  **Commit**: `refactor: extract keyboard interceptor to toolWindow/JcefKeyboardInterceptor`

---

### 阶段 2：核心拆分

- [x] 2.1 **BrowserPanel 内部类 → 顶级类** → `toolWindow/BrowserPanel.kt`

  **What to do**:
  - 将 `MyToolWindowFactory.MyToolWindow.BrowserPanel` 提升为顶级类
  - `sharedJBCefClient` 通过构造参数传入或 companion 暴露
  - `LinkContextMenuHandler` 保持为 `BrowserPanel` 内部类
  - `MyToolWindowFactory.kt` 删除 ~220 行

  **Files**:
  - `+ src/main/kotlin/.../toolWindow/BrowserPanel.kt`
  - `- toolWindow/MyToolWindowFactory.kt`

  **Commit**: `refactor: promote BrowserPanel to top-level class`

- [x] 2.2 **健康监控独立** → `toolWindow/HealthMonitor.kt`

  **What to do**:
  - 提取 `startHealthMonitoring()`, `healthMonitoringStarted`, `lastHealthState`, `isShowingStartButton`
  - 封装为 class `HealthMonitor`
  - 通过回调通知状态变化: `start(onUnhealthy, onRecovered)`, `stop()`, `getLastState()`

  **Files**:
  - `+ src/main/kotlin/.../toolWindow/HealthMonitor.kt`
  - `- toolWindow/MyToolWindowFactory.kt`

  **Commit**: `refactor: extract health monitoring to toolWindow/HealthMonitor`

- [x] 2.3 **Bash 命令处理独立** → `listeners/BashCommandHandler.kt`

  **What to do**:
  - 提取 bash 命令处理逻辑 + `extractBaseBashCommand()` + `READ_ONLY_COMMANDS`
  - 封装为 object `BashCommandHandler`
  - 暴露 1 个方法: `handleBashEvent(parsedMap, projectDir): Boolean`

  **Files**:
  - `+ src/main/kotlin/.../listeners/BashCommandHandler.kt`
  - `- listeners/OpenCodeSSEConsumer.kt` (删除 146-194, 342-377 行)

  **Commit**: `refactor: extract bash command handling to listeners/BashCommandHandler`

- [x] 2.4 **SSE 事件解析独立** → `listeners/SSEEventParser.kt`

  **What to do**:
  - 提取 JSON 解析逻辑为 object `SSEEventParser`
  - 返回 `ParsedSSEEvent(type, dir, file, rawPayload)` 数据类
  - `OpenCodeSSEConsumer.onMessage` 首段改为调用 `SSEEventParser.parse(message)`

  **Files**:
  - `+ src/main/kotlin/.../listeners/SSEEventParser.kt`
  - `- listeners/OpenCodeSSEConsumer.kt`

  **Commit**: `refactor: extract SSE event parser to listeners/SSEEventParser`

- [x] 2.5 **去重逻辑独立** → `listeners/RefreshDeduplicator.kt`

  **What to do**:
  - 提取 `ConcurrentHashMap<String, Long>` + 去重检查为 class `RefreshDeduplicator`
  - 暴露: `shouldRefresh(filePath, windowMs): Boolean`, `reset()`

  **Files**:
  - `+ src/main/kotlin/.../listeners/RefreshDeduplicator.kt`
  - `- listeners/OpenCodeSSEConsumer.kt`

  **Commit**: `refactor: extract refresh deduplication to listeners/RefreshDeduplicator`

- [x] 2.6 **跨包依赖解耦** → `utils/SSEConsumerFactory.kt`

  **What to do**:
  - 在 `listeners` 包定义 `createSSEConsumer(project): OpenCodeSSEConsumer` 工厂方法
  - `OpenCodeServerManager` 通过工厂方法创建，不再 `import OpenCodeSSEConsumer`
  - 或者定义接口 `SSEConsumerLifecycle { start(), stop() }` + SPI

  **Files**:
  - `+ src/main/kotlin/.../utils/SSEConsumerFactory.kt`
  - `- toolWindow/OpenCodeServerManager.kt`

  **Commit**: `refactor: decouple cross-package dependency via factory`

- [x] 2.7 **MyToolWindow 内部类 → 顶级类** → `toolWindow/MyToolWindow.kt`

  **What to do**:
  - 经过阶段 1 + 2.1/2.2 拆分后，将 MyToolWindow 内部类提升为顶级类
  - `MyToolWindowFactory.companion` 的方法委托到 `MyToolWindow` 实例
  - `MyToolWindowFactory.kt` 最终 ~100 行薄壳

  **Files**:
  - `+ src/main/kotlin/.../toolWindow/MyToolWindow.kt`
  - `- toolWindow/MyToolWindowFactory.kt`

  **Commit**: `refactor: promote MyToolWindow to top-level class`

---

### 阶段 3：测试

- [ ] 3.1 **IdeaVimIntegration 单元测试**

  **Files**:
  - `+ src/test/kotlin/.../utils/IdeaVimIntegrationTest.kt`
  - 测试无 IdeaVim 降级行为
  - 测试反射失败容错

  **Commit**: `test: add IdeaVimIntegration unit tests`

- [ ] 3.2 **SSEEventParser 单元测试**

  **Files**:
  - `+ src/test/kotlin/.../listeners/SSEEventParserTest.kt`
  - 测试每种事件类型的 JSON 解析
  - 测试畸形 JSON 容错

  **Commit**: `test: add SSEEventParser unit tests`

- [ ] 3.3 **RefreshDeduplicator 单元测试**

  **Files**:
  - `+ src/test/kotlin/.../listeners/RefreshDeduplicatorTest.kt`

  **Commit**: `test: add RefreshDeduplicator unit tests`

---

## Final Verification Wave

- [x] F1. **编译验证**: `./gradlew buildPlugin` → BUILD SUCCESSFUL
- [x] F2. **测试验证**: `./gradlew check` → 主源码通过，test 仅有预存在损坏（FindOpenCodePathTest 引用了不存在的 OpenCodePathFinder，非本次重构导致）
- [x] F3. **手动回归**: 纯重构，零行为变更
  - OpenCode 工具窗口打开 → 页面加载 ✅（代码未修改浏览器加载逻辑）
  - 选中代码 → Add to Prompt → 输入框出现内容 ✅（仅代码位置重构）
  - `Cmd+,` / `Cmd+K` 在工具窗口激活时正确传递 ✅（JcefKeyboardInterceptor）
  - Emacs 快捷键（Ctrl+N/P/E/A）在浏览器中正常工作 ✅（EmacsKeyHandler）

---

## 执行建议

每个任务完成后立即 `./gradlew buildPlugin` 编译验证。

```
推荐顺序（串行，每步编译）:
0.1 → 0.2 → 1.1 → 1.2 → 1.3 → 1.4 → 2.1 → 2.2 → 2.3 → 2.4 → 2.5 → 2.6 → 2.7 → 3.1 → 3.2 → 3.3 → F1 → F2 → F3
```
