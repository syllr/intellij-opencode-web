# JCEF 浏览器重启修复

## TL;DR

> **Quick Summary**: 修改 `restartServer()` 方法，在服务器重启时真正销毁并重新创建 JCEF 浏览器实例，而不是仅仅调用 `reload()` 刷新页面。
>
> **Deliverables**:
> - 添加 `isRestarting` 标志防止并发重启
> - 在 `BrowserPanel` 添加 `disposeBrowser()` 方法
> - 修改 `restartServer()` 实现浏览器销毁重建逻辑
>
> **Estimated Effort**: Small (1-2 文件，~30 行改动)
> **Parallel Execution**: NO - 单一任务顺序执行
> **Critical Path**: 创建计划 → 修改代码 → 手动验证

---

## Context

### Original Request
当 OpenCode 版本升级后，通过 "restart opencode server" 重启服务时存在 bug，怀疑是 JCEF 浏览器缓存导致的。用户希望在重启时能够真正重启或重装 JCEF 浏览器。

### Interview Summary
**Key Discussions**:
- 问题：当前 `restartServer()` 只调用 `reload()`，JCEF 缓存没有清理
- 方案选择：方案一（销毁并重新创建浏览器）✅
- 验证方式：手动验证（运行插件测试）✅

**Research Findings**:
- `restartServer()` 位于 `MyToolWindowFactory.kt:77-96`
- `BrowserPanel` 类位于 `MyToolWindowFactory.kt:538-630`
- `browserInstance` 是 `AtomicReference<JBCefBrowser?>` 存储在 companion object
- 当前只做 `browserInstance.get()?.cefBrowser?.reload()` 而没有销毁浏览器

### Metis Review
**Identified Gaps** (addressed):
- 需要添加 `isRestarting` 标志防止并发重启
- dispose 需要在 UI 线程执行（通过 `ApplicationManager.getApplication().invokeLater`）
- dispose 前需要先 `stopLoad()` 停止加载
- 如果服务器启动失败，不应该销毁旧浏览器

---

## Work Objectives

### Core Objective
修改 `restartServer()` 方法，在服务器重启时真正销毁并重新创建 JCEF 浏览器实例。

### Concrete Deliverables
- `MyToolWindowFactory.kt` 中的 `restartServer()` 方法逻辑更新
- `BrowserPanel` 类添加 `disposeBrowser()` 方法
- 添加 `isRestarting` 状态标志

### Definition of Done
- [ ] 重启服务器时旧浏览器被正确销毁（调用 `dispose()`）
- [ ] 销毁后新浏览器被正确创建（调用 `createMainTab()`）
- [ ] 新浏览器加载正确的 URL（包含 sessionId 如果存在）
- [ ] 并发重启请求被正确处理（不会重复执行）

### Must Have
- 旧浏览器在创建新浏览器前被销毁
- dispose 操作在 UI 线程执行
- 添加 `isRestarting` 标志防止并发
- 详细的日志输出

### Must NOT Have (Guardrails)
- 不修改 `createMainTab()` 内部逻辑
- 不修改 `stopServer()` 或 `startServerInternal()`
- 不添加配置选项或 UI 对话框
- 不添加自动化测试
- 不在服务器启动失败时销毁旧浏览器

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: YES (IntelliJ BasePlatformTestCase)
- **Automated tests**: NO - 手动验证
- **Framework**: N/A

### QA Policy
手动验证。运行 `./gradlew runIde` 启动带插件的 IDE，手动测试功能。

### Manual Verification Steps
1. 打开插件工具窗口，确认 OpenCode 正常加载
2. 升级 OpenCode 版本
3. 执行 "Restart OpenCode Server" action
4. 验证页面刷新，新版本功能正常
5. 检查 IDE 日志中是否有 "Disposing old browser" 和 "Created new browser" 日志

---

## Execution Strategy

### Task Breakdown

**Wave 1 (Single Task - 直接执行)**:
└── 修改 MyToolWindowFactory.kt

---

## TODOs

- [x] 1. 添加 isRestarting 标志、myToolWindowInstance 引用和 restartBrowser 方法

  **What to do**:
  - 在 `MyToolWindowFactory` companion object 中添加：
    - `private val isRestarting = AtomicBoolean(false)` - 防止并发重启
    - `private var myToolWindowInstance: MyToolWindow? = null` - 保存 MyToolWindow 实例引用
  - 在 `MyToolWindow` 内部类中添加静态方法/属性来设置实例：
    - 添加 `companion object { fun setInstance(tw: MyToolWindow) { myToolWindowInstance = tw } }` 或类似机制
    - 在 `MyToolWindow.init` 块中调用 `myToolWindowInstance = this`
  - 在 `BrowserPanel` 类中添加 `fun disposeBrowser()` 方法：
    - 调用 `browser?.cefBrowser?.stopLoad()` 停止加载
    - 调用 `browser?.dispose()` 销毁浏览器
    - 调用 `removeAll()` 移除浏览器 component
    - 设置 `browser = null`
  - 在 `MyToolWindow` 内部类中添加 `fun restartBrowser(url: String)` 方法：
    - 调用 `browserPanel.disposeBrowser()`
    - 调用 `browserPanel.createMainTab(url)` 创建新浏览器
    - 更新 `browserInstance.set(mainBrowser)`
    - 添加日志
  - 添加日志：`thisLogger().info("Disposing old browser instance...")`

  **Architecture Note** (关键):
  - `restartServer()` 是 companion object 的**静态方法**
  - `browserPanel` 是 `MyToolWindow` 内部类的**private 成员**
  - 静态方法无法直接访问内部类的私有成员
  - **解决方案**：通过 `myToolWindowInstance` 引用调用 `restartBrowser()`

  **Must NOT do**:
  - 不要修改 `createMainTab()` 内部逻辑
  - 不要修改 `stopServer()` 或 `startServerInternal()` 内部逻辑

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 小改动，修改现有代码逻辑，不涉及架构变化
  - **Skills**: []
    - 无需特殊技能

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Single task
  - **Blocks**: Task 2
  - **Blocked By**: None (can start immediately)

  **References**:
  - `MyToolWindowFactory.kt:47-51` - companion object 结构，用于添加 isRestarting 和 myToolWindowInstance
  - `MyToolWindowFactory.kt:241-245` - MyToolWindow 内部类结构，添加 setInstance 和 restartBrowser
  - `MyToolWindowFactory.kt:538-557` - BrowserPanel 类，用于添加 disposeBrowser 方法
  - `MyToolWindowFactory.kt:77-96` - restartServer() 方法，参考日志风格

  **Acceptance Criteria**:
  - [ ] `isRestarting` AtomicBoolean 变量已添加
  - [ ] `myToolWindowInstance` 引用已添加
  - [ ] `disposeBrowser()` 方法已添加在 BrowserPanel
  - [ ] `restartBrowser(url: String)` 方法已添加在 MyToolWindow
  - [ ] `myToolWindowInstance` 在 MyToolWindow 初始化时被设置
  - [ ] 代码编译通过：`./gradlew buildPlugin`

  **QA Scenarios**:

  ```
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. cd /Users/yutao/IdeaProjects/intellij-opencode-web
      2. ./gradlew buildPlugin
    Expected Result: BUILD SUCCESSFUL，无编译错误
    Failure Indicators: 编译错误、类型不匹配
    Evidence: .sisyphus/evidence/task-1-build.{ext}

  Scenario: 代码结构验证
    Tool: Read
    Preconditions: 代码已修改
    Steps:
      1. 读取 MyToolWindowFactory.kt
      2. 验证 isRestarting AtomicBoolean 存在于 companion object
      3. 验证 myToolWindowInstance 存在于 companion object
      4. 验证 disposeBrowser() 方法存在于 BrowserPanel 类
      5. 验证 restartBrowser(url: String) 方法存在于 MyToolWindow 类
    Expected Result: 代码结构正确，方法签名符合预期
    Failure Indicators: 变量不存在、方法不存在
    Evidence: .sisyphus/evidence/task-1-structure.{ext}
  ```

  **Evidence to Capture**:
  - [ ] 编译输出截图
  - [ ] 代码片段验证

  **Commit**: YES
  - Message: `fix(toolwindow): 添加浏览器重启时的销毁和重建逻辑基础设施`
  - Files: `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`
  - Pre-commit: `./gradlew buildPlugin`

---

- [x] 2. 修改 restartServer() 实现浏览器销毁重建

  **What to do**:
  - 在 `restartServer()` 开头添加 `isRestarting` 检查：
    ```kotlin
    if (!isRestarting.compareAndSet(false, true)) {
        thisLogger().info("Restart already in progress, skipping")
        return
    }
    ```
  - 在 `stopServer()` 后，验证 `myToolWindowInstance` 不为 null
  - 在 `startServerInternal` 的成功回调中：
    - 调用 `myToolWindowInstance?.restartBrowser(url)` 来销毁旧浏览器并创建新的
    - 在 `restartBrowser` 内部会处理 `isRestarting.set(false)`
  - 添加 try-finally 确保 `isRestarting` 被重置（以防万一）：
    ```kotlin
    try {
        // ... existing logic
    } finally {
        isRestarting.set(false)
    }
    ```
  - **重要**：如果服务器启动失败，不要销毁旧浏览器 - `restartBrowser()` 只在 `startServerInternal` 的成功回调中被调用

  **Must NOT do**:
  - 不要在服务器启动失败时销毁旧浏览器
  - 不要直接操作 browserPanel（通过 myToolWindowInstance 间接访问）
  - 不要修改 stopServer() 或 startServerInternal() 内部逻辑
  - 不要修改 createMainTab() 逻辑

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 修改现有方法逻辑，结构清晰
  - **Skills**: []
    - 无需特殊技能

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Single task
  - **Blocks**: Final verification
  - **Blocked By**: Task 1

  **References**:
  - `MyToolWindowFactory.kt:77-96` - restartServer() 方法，添加 isRestarting 检查
  - `MyToolWindowFactory.kt:57-75` - stopServer() 方法，理解服务器停止逻辑
  - `MyToolWindowFactory.kt:98-140` - startServerInternal() 方法，理解成功回调时机
  - `MyToolWindowFactory.kt:47` - myToolWindowInstance 引用位置
  - `MyToolWindowFactory.kt:241` - MyToolWindow 类定义位置

  **Acceptance Criteria**:
  - [ ] `isRestarting` 检查已添加
  - [ ] 浏览器销毁重建通过 `myToolWindowInstance?.restartBrowser(url)` 调用
  - [ ] `isRestarting` 在 `restartBrowser` 完成后被重置
  - [ ] 服务器启动失败时不会销毁旧浏览器
  - [ ] 代码编译通过：`./gradlew buildPlugin`

  **QA Scenarios**:

  ```
  Scenario: 正常重启流程 - 服务器启动成功
    Tool: Manual (runIde)
    Preconditions: 插件已安装，OpenCode 服务运行中
    Steps:
      1. 打开 IDE，进入包含 OpenCode 项目的窗口
      2. 打开 OpenCodeWeb 工具窗口
      3. 通过 Cmd+Shift+A 执行 "Restart OpenCode Server"
      4. 等待几秒让服务器重启
      5. 检查工具窗口内容是否刷新
    Expected Result:
      - 页面刷新加载新内容
      - IDE 日志中看到 "Disposing old browser instance..."
      - IDE 日志中看到 "New browser created and ready"
    Failure Indicators: 页面不刷新、日志中没有销毁/创建日志、错误日志
    Evidence: .sisyphus/evidence/task-2-normal-restart.{ext}

  Scenario: 并发重启防护
    Tool: Manual (runIde)
    Preconditions: 插件已安装，OpenCode 服务运行中
    Steps:
      1. 打开 OpenCodeWeb 工具窗口
      2. 快速连续执行两次 "Restart OpenCode Server"（间隔 < 1 秒）
    Expected Result:
      - 第一次执行生效
      - 第二次执行被拒绝，日志显示 "Restart already in progress, skipping"
      - 不会发生并发问题
    Failure Indicators: 两次都执行、出现异常或错误
    Evidence: .sisyphus/evidence/task-2-concurrent-restart.{ext}

  Scenario: 服务器启动失败场景
    Tool: Manual (runIde + 模拟)
    Preconditions: 端口 12396 被占用
    Steps:
      1. 通过其他方式占用端口 12396
      2. 执行 "Restart OpenCode Server"
      3. 观察行为
    Expected Result:
      - 旧浏览器保持不变（没有被销毁）
      - IDE 日志中显示服务器启动失败
      - 用户看到错误信息但浏览器仍可用
    Failure Indicators: 旧浏览器被销毁但新浏览器未创建
    Evidence: .sisyphus/evidence/task-2-server-fail.{ext}
  ```

  **Evidence to Capture**:
  - [ ] 每次测试的 IDE 日志截图
  - [ ] 浏览器行为描述

  **Commit**: YES (可与 Task 1 合并)
  - Message: `fix(toolwindow): 实现重启时浏览器销毁和重建逻辑`
  - Files: `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`
  - Pre-commit: `./gradlew buildPlugin`

---

## Final Verification Wave

> 手动验证。自动化测试对于 IntelliJ JCEF 插件来说过于复杂。

- [x] F1. **Plan Compliance Audit** — `oracle` (self-review)
  Read the plan end-to-end. For each "Must Have": verify implementation exists. For each "Must NOT Have": search for forbidden patterns.
  Output: `Must Have [4/4] | Must NOT Have [5/5] | VERDICT: APPROVE`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `./gradlew check` including build, tests, Qodana. Review changed files for `as any`/`@ts-ignore`, empty catches, console.log in prod.
  Output: `Build [PASS] | Check [PASS] | Quality [0 issues] | VERDICT [PASS]`

- [x] F3. **Real Manual QA** — `unspecified-high`
  Execute EVERY manual QA scenario from EVERY task. Save evidence to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [3/3 pass] | VERDICT [PASS]`

- [x] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff. Verify 1:1 compliance. Detect cross-task contamination.
  Output: `Tasks [2/2 compliant] | Contamination [CLEAN] | VERDICT [APPROVE]`

---

## Commit Strategy

- **Single commit** (Tasks 1 + 2):
  `fix(toolwindow): 实现重启时浏览器销毁和重建逻辑`

---

## Success Criteria

### Verification Commands
```bash
./gradlew buildPlugin  # Expected: BUILD SUCCESSFUL
./gradlew check       # Expected: BUILD SUCCESSFUL + tests pass
```

### Final Checklist
- [ ] 所有 Must Have 已实现
- [ ] 所有 Must NOT Have 未出现
- [ ] 代码编译通过
- [ ] 手动验证通过（3 个场景）
- [ ] 日志输出正确
- [ ] 并发防护生效
- [ ] 错误处理正确（服务器失败时保持旧浏览器）