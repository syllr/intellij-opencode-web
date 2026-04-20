# Shutdown Server 按端口关闭修复

## TL;DR

> **Quick Summary**: 修复右键"Shutdown Server"功能，当 ProcessHandler 引用丢失时（如 IDEA 重启后），仍能通过端口号强制关闭 OpenCode 进程。
>
> **Deliverables**:
> - 修改 `stopServer()` 方法，优先调用 `killProcessByPort(PORT)`
>
> **Estimated Effort**: Quick (5行代码修改)
> **Parallel Execution**: NO - 顺序执行
> **Critical Path**: 阅读代码 → 修改 → 测试验证

---

## Context

### 问题描述

当前 `stopServer()` 方法只使用 `ProcessHandler.destroyProcess()` 关闭进程，但当 IDEA 重启后，`serverProcess` 引用会丢失（变成 null），导致 Shutdown Server 功能失效。

### 用户需求

1. **退出 IDEA 时** → 不关闭任何 opencode server ✅（已实现）
2. **右键 Shutdown Server 时** → 按端口（12396）强制关闭进程 ❌（失效了）

### 技术背景

- OpenCode 服务端口：`12396`
- `killProcessByPort(PORT)` 方法已存在且正常工作
- `stopServer()` 在 IDEA 退出时不再被调用（由 `disposeToolWindow` 调用）

---

## Work Objectives

### Core Objective

修复 `stopServer()` 方法，使其在 ProcessHandler 引用丢失时仍能通过端口号关闭进程。

### Concrete Deliverables

- [ ] 修改 `MyToolWindowFactory.kt` 中的 `stopServer()` 方法
- [ ] 确保 `killProcessByPort(PORT)` 被优先调用

### Definition of Done

- [ ] Shutdown Server 功能在 IDEA 重启后仍能正常工作
- [ ] IDEA 退出时仍然不关闭 server（行为不变）

### Must Have

- 直接调用 `killProcessByPort(PORT)` 关闭进程
- 保留 `serverRunning.set(false)` 和 `serverProcess.set(null)` 状态重置
- 删除 ProcessHandler 冗余代码

### Must NOT Have

- 不修改 `killProcessByPort` 本身（已正常工作）
- 不修改 IDEA 退出行为
- 不修改服务器启动逻辑
- 不添加 UI 通知或确认对话框

---

## Verification Strategy

### Test Decision

- **Infrastructure exists**: NO (无单元测试)
- **Automated tests**: None
- **Agent-Executed QA**: 手动测试验证

### QA Policy

手动测试以下场景：

---

## Execution Strategy

### 修改步骤

**文件**: `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`

**修改位置**: `stopServer()` 方法（第 83-104 行）

**当前代码**:
```kotlin
fun stopServer() {
    try {
        checkScheduledFuture?.cancel(true)
        checkScheduledFuture = null

        serverProcess.get()?.let { handler ->
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
                thisLogger().info("OpenCode server process stopped (via ProcessHandler)")
            }
        }

        // 注意：不再调用 killProcessByPort(PORT)

        serverRunning.set(false)
        serverProcess.set(null)
    } catch (e: Exception) {
        thisLogger().error("Error stopping OpenCode server: ${e.message}")
    }
}
```

**修改后代码**:
```kotlin
fun stopServer() {
    try {
        checkScheduledFuture?.cancel(true)
        checkScheduledFuture = null

        // 通过端口号强制关闭进程（确保 IDEA 重启后仍能关闭）
        killProcessByPort(PORT)

        serverRunning.set(false)
        serverProcess.set(null)
    } catch (e: Exception) {
        thisLogger().error("Error stopping OpenCode server: ${e.message}")
    }
}
```

**关键变更**:
1. 删除 ProcessHandler 那段冗余代码（因为 IDEA 重启后它就是 null，没用）
2. 直接调用 `killProcessByPort(PORT)` 通过端口关闭进程

---

## TODOs

- [x] 1. 修改 `stopServer()` 方法，通过端口关闭进程

  **What to do**:
  - 删除 ProcessHandler 那段冗余代码（因为 IDEA 重启后它就是 null，没用）
  - 直接调用 `killProcessByPort(PORT)` 通过端口关闭进程

  **Must NOT do**:
  - 不修改 `killProcessByPort` 方法本身
  - 不添加 UI 通知

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 简单的代码修改，5行以内
  - **Skills**: []
    - 不需要特殊技能

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: None
  - **Blocked By**: None

  **References**:
  - `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt:83-104` - stopServer() 方法（修改前）
  - `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt:106-126` - killProcessByPort() 方法
  - `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt:993-996` - 右键菜单调用 stopServer() 的地方

  **Acceptance Criteria**:

  - [ ] `stopServer()` 只调用 `killProcessByPort(PORT)`，不再有 ProcessHandler 冗余代码
  - [ ] 代码能够编译通过：`./gradlew buildPlugin`

  **QA Scenarios**:

  ```
  Scenario: Shutdown Server 在 IDEA 重启后仍能正常关闭进程
    Tool: Manual test
    Preconditions: IDEA 重启过，OpenCode server 正在运行，serverProcess 引用为 null
    Steps:
      1. 启动 IDEA，打开 OpenCode 工具窗口，确认 server 运行
      2. 重启 IDEA（不关闭 OpenCode server）
      3. 再次打开 IDEA，打开 OpenCode 工具窗口
      4. 在 JCEF 浏览器内右键，点击 "Shutdown Server"
    Expected Result: OpenCode server 进程被关闭，端口 12396 被释放
    Evidence: 终端执行 `lsof -i :12396` 确认无进程占用

  Scenario: IDEA 退出时仍然不关闭 server
    Tool: Manual test
    Preconditions: OpenCode server 正在运行
    Steps:
      1. 保持 OpenCode server 运行
      2. 关闭 IDEA
      3. 终端执行 `lsof -i :12396` 检查 server 是否仍在运行
    Expected Result: OpenCode server 仍在运行，端口 12396 仍被占用
    Evidence: `lsof -i :12396` 显示 opencode 进程
  ```

  **Commit**: YES
  - Message: `fix(shutdown): use killProcessByPort in stopServer()`
  - Files: `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`
  - Pre-commit: `./gradlew buildPlugin`

---

## Final Verification Wave

> 手动验证，检查代码修改是否正确

- [x] F1. 代码修改正确
  - 确认 `stopServer()` 只调用 `killProcessByPort(PORT)`
  - 确认 ProcessHandler 冗余代码已删除

- [x] F2. 编译通过
  - `./gradlew buildPlugin` 成功

---

## Success Criteria

### Verification Commands
```bash
./gradlew buildPlugin  # 编译成功
lsof -i :12396        # 验证端口状态
```

### Final Checklist
- [x] `stopServer()` 只调用 `killProcessByPort(PORT)`
- [x] ProcessHandler 冗余代码已删除
- [ ] IDEA 重启后 Shutdown Server 功能正常 (手动测试)
- [ ] IDEA 退出时 server 保持运行 (手动测试)
- [x] 编译通过