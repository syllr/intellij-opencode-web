# JCEF 右键菜单增强：修复 Open in Browser + 添加 Copy Link

## TL;DR

> **Quick Summary**: 修复 JCEF 右键菜单中 "Open in Browser" 失效问题（移除内网地址过滤），并添加 "Copy Link" 功能复制链接 URL
>
> **Deliverables**:
> - 修复 `MyToolWindowFactory.kt` 中 "Open in Browser" 链接打开逻辑
> - 添加 "Copy Link" 菜单项和处理器
>
> **Estimated Effort**: Quick (单文件修改)
> **Parallel Execution**: NO (顺序修改)
> **Critical Path**: Task 1 → Task 2 → Verification

---

## Context

### Original Request
用户反馈 JCEF 右键菜单中 "Open in Browser" 失效，经分析发现 `isExternalUrl()` 函数过滤掉了所有内网地址导致静默失败。用户要求：
1. 修复 "Open in Browser"：不过滤任何地址，所有链接都直接打开
2. 在 JCEF 浏览器中添加 "Copy Link" 功能（只在 JCEF 中，不在 IDE 编辑器中）

### Interview Summary
**Key Discussions**:
- [需求确认]: 只在 JCEF 浏览器中添加功能，不修改 IDE 编辑器
- [修复策略]: 移除 isExternalUrl() 检查，所有链接都直接打开浏览器

**Research Findings**:
- [代码位置]: `MyToolWindowFactory.kt` 第 926-969 行处理 JCEF 右键菜单
- [问题根因]: `isExternalUrl()` 函数过滤掉 localhost/127.0.0.1/192.168.x.x/10.x.x.x/0.0.0.0

### Metis Review
**Identified Gaps** (addressed):
- [简化范围]: 只需修改 MyToolWindowFactory.kt，不涉及 IDE 编辑器或 plugin.xml
- [无歧义]: 需求清晰，直接实现

---

## Work Objectives

### Core Objective
修复 JCEF 右键菜单的 "Open in Browser" 功能并添加 "Copy Link" 选项

### Concrete Deliverables
- 修改 `MyToolWindowFactory.kt` 中 `LinkContextMenuHandler` 的菜单项和行为

### Definition of Done
- [ ] JCEF 右键点击任意链接，"Open in Browser" 能正常打开（不再过滤内网地址）
- [ ] JCEF 右键点击链接，出现 "Copy Link" 选项，能复制链接 URL

### Must Have
- 修复后所有 URL 都能通过浏览器打开
- "Copy Link" 能正确复制链接 URL

### Must NOT Have (Guardrails)
- 不修改 IDE 编辑器右键菜单（不在 EditorPopupMenu 添加功能）
- 不修改 CopyAsPromptAction
- 不添加键盘快捷键

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: NO (此项目无测试框架)
- **Automated tests**: None
- **Agent-Executed QA**: YES (手动测试验证)

### QA Policy
Every task includes agent-executed QA scenarios.

---

## Execution Strategy

### Tasks Overview
```
Task 1: 修复 "Open in Browser" - 移除 isExternalUrl() 检查
Task 2: 添加 "Copy Link" 菜单项和处理器
```

---

## TODOs

- [x] 1. 修复 "Open in Browser" 功能

  **What to do**:
  - 修改 `MyToolWindowFactory.kt` 第 957-961 行
  - 移除 `isExternalUrl(linkUrl)` 检查
  - 直接调用 `BrowserUtil.browse(java.net.URI(linkUrl))`

  **Must NOT do**:
  - 不修改 isExternalUrl() 函数本身（其他代码可能在使用）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 单文件小改动，简单直接
  - **Skills**: `[]`
  - **Skills Evaluated but Omitted**:
    - `intellij-platform`: 不需要，此任务只是移除一个条件判断

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: Task 2 (依赖同一文件的上下文)

  **References**:
  - `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt:957-961` - 当前有问题的 "Open in Browser" 处理逻辑

  **Acceptance Criteria**:
  - [ ] 修改后的代码能编译通过
  - [ ] 注释说明修改原因

  **QA Scenarios**:

  ```
  Scenario: Open internal link (session URL) in browser
    Tool: Bash
    Preconditions: OpenCode 服务运行中，JCEF 加载了 OpenCode 页面
    Steps:
      1. 在 JCEF 中右键点击任意内部链接（如 session 链接）
      2. 选择 "Open in Browser"
    Expected Result: 链接在系统浏览器中打开
    Failure Indicators: 静默无反应、日志报错
    Evidence: N/A (手动测试)

  Scenario: Open external link (github.com) in browser
    Tool: Bash
    Preconditions: OpenCode 服务运行中，JCEF 加载了包含外部链接的页面
    Steps:
      1. 右键点击 GitHub 等外部链接
      2. 选择 "Open in Browser"
    Expected Result: 外部链接在系统浏览器中打开
    Failure Indicators: 静默无反应
    Evidence: N/A (手动测试)
  ```

  **Commit**: NO (小改动，可后续批量提交)

  ---

- [x] 2. 添加 "Copy Link" 菜单项和处理器

  **What to do**:
  - 在 `MyToolWindowFactory.kt` 的 `LinkContextMenuHandler` 中：
    1. 定义新的命令 ID `COPY_LINK_COMMAND_ID = 26503`
    2. 在 `onBeforeContextMenu` 中添加 "Copy Link" 菜单项
    3. 在 `onContextMenuCommand` 中添加处理器，复制链接 URL 到剪贴板
  - 使用 `java.awt.Toolkit.getDefaultToolkit().systemClipboard` 复制

  **Must NOT do**:
  - 不修改 IDE 编辑器相关代码
  - 不添加新的 Action 类（只在现有 Handler 中修改）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 单文件小改动，复制剪贴板是标准 API
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: None (前置任务已完成)
  - **Blocked By**: Task 1

  **References**:
  - `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt:926-969` - 现有 LinkContextMenuHandler 实现
  - `src/main/kotlin/com/github/xausky/opencodewebui/actions/CopyAsPromptAction.kt:40-41` - 剪贴板复制参考代码

  **Acceptance Criteria**:
  - [ ] 新增命令 ID 不与其他命令冲突
  - [ ] "Copy Link" 菜单项在链接右键时显示
  - [ ] 点击后链接 URL 被复制到系统剪贴板

  **QA Scenarios**:

  ```
  Scenario: Copy internal link URL to clipboard
    Tool: Bash
    Preconditions: OpenCode 服务运行中，JCEF 加载了 OpenCode 页面
    Steps:
      1. 右键点击任意内部链接
      2. 选择 "Copy Link"
      3. 在文本编辑器中粘贴
    Expected Result: 粘贴出完整的链接 URL
    Failure Indicators: 粘贴为空、粘贴的不是链接
    Evidence: N/A (手动测试)

  Scenario: Copy external link URL to clipboard
    Tool: Bash
    Preconditions: OpenCode 服务运行中，页面包含 GitHub 等外部链接
    Steps:
      1. 右键点击外部链接
      2. 选择 "Copy Link"
      3. 粘贴到文本编辑器
    Expected Result: 粘贴出完整的外部链接 URL
    Failure Indicators: 粘贴为空或错误内容
    Evidence: N/A (手动测试)

  Scenario: Context menu without link
    Tool: Bash
    Preconditions: OpenCode 页面已加载
    Steps:
      1. 右键点击页面空白处（非链接区域）
      2. 观察右键菜单
    Expected Result: "Copy Link" 菜单项不显示
    Failure Indicators: "Copy Link" 出现在非链接区域右键菜单
    Evidence: N/A (手动测试)
  ```

  **Commit**: NO (小改动，可后续批量提交)

---

## Final Verification Wave

- [x] F1. **Build Verification** — `quick`
  执行 `./gradlew buildPlugin` 验证插件能正常编译。
  Output: `BUILD SUCCESSFUL` 或错误信息

---

## Commit Strategy

- **1**: `fix(jcef):修复OpenInBrowser并添加CopyLink功能` - MyToolWindowFactory.kt

---

## Success Criteria

### Verification Commands
```bash
./gradlew buildPlugin  # 期望: BUILD SUCCESSFUL
```

### Final Checklist
- [ ] "Open in Browser" 能打开任意 URL（内网/外网）
- [ ] "Copy Link" 能复制任意链接 URL
- [ ] 非链接区域右键不显示 "Copy Link"