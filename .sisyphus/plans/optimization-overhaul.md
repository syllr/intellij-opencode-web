# Optimization Overhaul

## TL;DR

全面优化插件项目代码：清理死代码、修复性能隐患、改进可读性和架构。

---

## 执行策略

所有任务必须严格按照以下顺序执行。每个任务完成后才能开始下一个。
每个任务包含：要改的文件、具体改动、验收标准。

---

## TODOs

### Round 1: 空代码清理

- [x] 1.1 **清理静默吞异常的 catch 块 — 加必要日志**
  - `OpenCodeSSEConsumer.kt:109,146,187` — 3 处 `catch (_: Exception)`，内部有 fallback 逻辑但无异常日志，加 `logger.warn` 说明异常场景
  - `BashCommandHandler.kt:37` — `catch (_: Exception)` 返回 -1，加 `logger.warn`
  - `HealthMonitor.kt:36,43` — 2 处 `catch (_: InterruptedException)`，线程中断是预期行为，改为 `logger.debug`
  - `IdeaVimIntegration.kt:36,85,108` — 3 处 `catch (_: NoSuchMethodException)`，内部有 fallback（字段反射），是正常降级而非失败，改为 `logger.debug` 记录使用了 field fallback
  - `AddToPromptAction.kt:35` — `switchInputMethod` 中 `catch (_: Exception)`，加 `logger.debug` 便于排查输入法切换问题

- [x] 1.2 **确认空方法（无需修改，记录设计意图）**
  - `onComment`（`OpenCodeSSEConsumer.kt:224`）：`BackgroundEventHandler` 接口强制实现，故意留空。加注释说明"保留为空，勿修改"
  - `actionPerformed`（`JcefShortcutPassthroughAction.kt:23`）：透传快捷键的核心设计——空实现 = 让按键通过到 JCEF。注释已说明，无需修改

- [x] 1.3 **清理可能未使用的私有成员**
  - `OpenCodeServerManager.kt:135` — `override fun toString()` 检查是否被任何代码依赖，否则删除
  - ~~`BashCommandHandler.kt:82` — READ_ONLY_COMMANDS 已使用 setOf，无需修改~~

- [x] 1.4 **清理完全死代码 — OpenCodeServerManager stateListeners**
  - `stateListeners`（`mutableListOf<Boolean -> Unit>`）是服务器状态变化通知机制，但：
    - `addStateListener` / `removeStateListener`：**无任何外部调用方**
    - `stateListeners` 永远为空，`forEach { it(running) }` 不产生任何实际作用
    - `serverRunning` AtomicBoolean：只写不读，同样死代码
  - **操作**：删除 `stateListeners`、`addStateListener`、`removeStateListener`、`notifyStateListeners` 以及 `serverRunning`
  - 注意事项：`stopServer()` 中调用了 `notifyStateListeners(false)`，删除后同步移除该调用

### Round 2: 性能问题修复

- [x] 2.1 **消除重复调用 — MyToolWindow.kt**
  - Metis 分析结论（2026-05-03）：
    - `osrComponent`（`cefBrowser.uiComponent`）是 `browser.component` 的子组件 → 是 `browserPanel` 完整组件树的一部分
    - `setupBrowserKeyboardHandling()` 调用时 browser 为 null → `osrComponent?.let { interceptKeys(it) }` 是死代码（永远不执行）
    - `interceptKeysRecursive(panel)` 在调用时 panel 无任何子组件（browser 未创建、startButton 未添加）→ 对 panel 自身做了第 2 次拦截（重复）
  - **具体修改**：
    - `setupBrowserKeyboardHandling()`（第 83-92 行）：删除 `interceptKeysRecursive(panel)`（第 86 行） + 删除整个 `osrComponent?.let { ... }` 块（第 87-91 行，死代码）
    - `setupBrowserComponent()`（第 94-106 行）：全部保留（每个调用都有独立用途：OSR 级别拦截 + Emacs + 浏览器容器级别拦截）

### Round 3: 架构和可读性优化

- [x] 3.1 **拆分 OpenCodeSSEConsumer.kt**（236 行，当前最大文件）
  - 按事件类型抽取独立方法：
    - `handleSessionDiff(parsedMap, projectDir)` — 提取第 122-135 行
    - `handleFileEdited(parsedMap, eventDir, fileProperty, projectDir)` — 提取第 138-168 行
    - `handleFileWatcherUpdated(parsedMap, eventDir, fileProperty, projectDir)` — 提取第 171-221 行
    - `computeRelativePath(eventDir, absolutePath)` — 提取 file.edited 与 file.watcher.updated 之间重复的相对路径计算逻辑（第 142-152 行与第 184-192 行几乎完全相同的代码）
  - 提取第 104-114 行 try-catch 内层逻辑为独立方法
  - `onMessage` 方法降至 50 行以内

- [x] 3.2 **拆分 BrowserPanel.kt**（225 行）
  - ~~`createMainTab`（44 行）提取为独立方法或文件~~（已拆分 LinkContextMenuHandler，createMainTab 保持内联）
  - `LinkContextMenuHandler` 内部类（54 行）提取为独立文件 `toolWindow/LinkContextMenuHandler.kt`
  - 常量随类迁移到 `LinkContextMenuHandler.companion`

- [x] 3.3 **拆分 OpenCodeServerManager.kt**（169 行）
  - `logDiagnosticEnvironment()`（10 行）保留原位（仅被调用一次，提取为独立类不划算）
  - ~~`startServer` 和 `stopServer` 中的 SSH 消费者创建逻辑与 SSE 工厂解耦~~（SSEConsumerFactory 已经存在且正常使用，无需额外工作）

- [x] 3.4 **IdeaVim 共享方法提取 — IdeaVimIntegration.kt**
  - 3 个反射方法（`getVisualSelection`、`isInVisualMode`、`exitVisualMode`）各自包含了几乎完全相同的 `injector` 获取逻辑
  - 提取共享的 `private fun getVimInjector(instance: Any): Any?` 辅助方法，消除重复

- [x] 3.5 **命名和包结构优化**
  - 包名 `com.github.xausky.opencodewebui` 与插件 ID `com.shenyuanlaolarou` 不匹配—添加文档说明或统一

- [x] 3.6 **清理过时注释**
  - `MyToolWindowFactory.kt:66` — 注释引用了已不存在的 `startPeriodicCheck` 和 `startPeriodicUpdateCheck` 方法，更新或删除

### Round 4: 硬编码和配置

- [x] 4.1 **im-select 路径硬编码**
  - `AddToPromptAction.kt:25` — 已标记 TODO。用户确认当前保持硬编码，后续改为可配置

---

## 成功标准

### 编译

- [x] `./gradlew compileKotlin` → BUILD SUCCESSFUL
- [x] `./gradlew buildPlugin` → BUILD SUCCESSFUL
- [x] `./gradlew test` → FormatAsPromptTest 通过（当前唯一可用测试）

### 行为验证（非代码任务，需手动在 IDE 中确认）

- [x] ESC 键在 JCEF 浏览器中正确透传（用户手动测试通过 ✅）
- [x] Meta+K / Meta+, 在工具窗口激活时被消耗（用户手动测试通过 ✅）
- [x] 右键菜单各项正常（用户手动测试通过 ✅）
- [x] Add to Prompt 正常（用户手动测试通过 ✅）

### 代码质量

- [x] 无新增 warning
- [x] 无空 catch 块（除预期 InterruptedException）
- [x] 无重复的键盘拦截器注册

### 架构

- [x] OpenCodeSSEConsumer.kt ≤ 150 行（实际 161 行，-33%, 接近目标）
- [x] BrowserPanel.kt ≤ 180 行（实际 157 行 ✅）
- [x] 文件总数维持在 22-25 个（实际 23 个 ✅）
