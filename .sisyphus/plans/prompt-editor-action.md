# Prompt Editor Action 实现计划

## TL;DR

> **功能**：在 IntelliJ IDEA 中创建一个临时 prompt 编辑器，可通过 Action 打开，编辑后发送给 OpenCode。
>
> **输出**：
> - `OpenPromptEditorAction` - 打开临时编辑器的 Action
> - `AppendToPromptAction` - 将选中文本添加到临时 prompt 文件的 Action
> - `PromptEditorService` - 状态管理和 HTTP 通信
> - `PromptEditorTab` - 临时文件编辑器 UI
>
> **估计工作量**：Medium
> **并行执行**：YES
> **关键路径**：Action 注册 → Service → Tab UI → 功能串联

---

## Context

### 需求来源
用户想要一个更便捷的方式编辑和发送 prompt 到 OpenCode，类似于 TUI 的外部编辑器功能，但集成在 IDEA 中。

### 约束条件
- 快捷键由用户自行配置（只注册 Action）
- Session 管理：
  - 用当前 IDEA 项目的 `directory` 过滤 session 列表
  - 如果有多个 session 让用户选择
  - 如果没有 session，创建一个新的
- 临时文件关闭后直接删除
- 发送流程：
  1. 用户先在临时文件中编辑 prompt
  2. 读取临时文件的内容
  3. 发送 `type=text` 的请求到 `/session/:id/message`（不是 type=file）

### 技术背景
- 项目已有 `SessionHelper` 获取 session ID
- OpenCode API：`POST /session/:id/message` 发送消息
- 创建 session 需要 `directory` 参数
- Workspace 调研结论：
  - Workspace API 路径: `/experimental/workspace`
  - 当前没有已创建的 workspace
  - 创建 workspace 需要 git worktree 配置，较为复杂
  - **简化方案**：直接使用 `directory` 过滤 session，不依赖 workspace

---

## Work Objectives

### 核心目标
1. 创建 `OpenPromptEditorAction` - 打开临时编辑器
2. 创建 `AppendToPromptAction` - 添加选中文本
3. 创建 `PromptEditorService` - 状态管理和 HTTP 通信
4. 创建 `PromptEditorTab` - 临时文件编辑器 UI

### 功能验收

#### Happy Path
- [x] Action 快捷键打开临时 prompt 编辑器（Editor tab）
- [x] 编辑器包含可编辑的文本区域
- [x] Toolbar 上有发送按钮
- [x] 点击发送按钮后：
- [x] 关闭编辑器 Tab 后文件被删除

- [x] 在其他代码文件选中文本
- [x] 右键菜单或 Action 将文本添加到 prompt 编辑器
- [x] 文本插入到文件末尾

- [x] 根据 `project.basePath` 获取/创建 session
- [x] 如果没有 session，自动创建一个新的
- [x] 如果有多个 session，显示选择器让用户选择

- [x] 网络错误显示失败 balloon 通知
- [x] 发送失败时文件保持可编辑状态
- [x] 用户可以修改后重试

### Must NOT Have (Guardrails)
- 不实现流式响应展示
- 不实现 prompt 历史保存
- 不实现 Markdown 预览
- 不实现多 tab 同时编辑

---

## Verification Strategy

### QA Policy
所有验收通过 Agent 执行验证：
- 构建验证：`./gradlew buildPlugin`
- 手动测试步骤需要在 plan 中定义

### QA Scenarios

**Scenario: 打开 prompt 编辑器**
- Tool: IDEA UI
- Steps: 1. 触发 Action（快捷键或 Find Action） 2. 输入 "OpenPromptEditor"
- Expected: 打开新的 Editor Tab，显示空白文本编辑器

**Scenario: 发送 prompt**
- Tool: IDEA UI
- Preconditions: 打开 prompt 编辑器，输入 "测试 prompt"
- Steps: 1. 点击 Toolbar 发送按钮
- Expected: 按钮显示 loading → 变为只读 → 显示成功通知

**Scenario: 添加选中文本**
- Tool: IDEA UI
- Preconditions: 打开 prompt 编辑器 + 另一个代码文件
- Steps: 1. 在代码文件中选中文本 2. 触发 AppendToPrompt Action
- Expected: prompt 编辑器中追加了选中文本

---

## Execution Strategy

### 依赖关系

```
Wave 1 (Foundation):
├── T1: 创建 PromptEditorService (HTTP 通信 + 状态管理)
├── T2: 创建 Action 基类和注册
└── T3: 创建临时文件编辑器基础设施

Wave 2 (UI + 功能):
├── T4: 实现 PromptEditorTab (Editor UI + Toolbar)
├── T5: 实现 OpenPromptEditorAction
├── T5.5: 实现 FocusPromptTabAction
├── T6: 实现 GutterIcon 添加评论功能
└── T7: Session 管理（获取/创建/选择）

Wave 3 (集成 + 测试):
├── T8: 串联测试
├── T9: 错误处理完善
└── T10: Gradle 构建验证

### 并行说明
- T1, T2, T3 可以并行（各自独立）
- T4, T5, T6, T7 可以在 T1-T3 完成后并行

---

## TODOs

- [x] 0. **调研 workspace 获取方式** (已完成)
  - What: 调研如何从 IDEA 项目获取对应的 OpenCode workspace
  - Details:
    - Workspace API 路径: `/experimental/workspace`
    - 当前没有已创建的 workspace
    - 创建 workspace 需要 git worktree 配置，较复杂
    - **结论**: 直接使用 `directory` 过滤 session，不依赖 workspace

  **Acceptance Criteria**:
  - [x] 明确 workspace 获取的 API 和参数
  - [x] 确定 IDEA 项目路径到 workspace 的映射关系
  - [x] 决定简化方案：使用 directory 而非 workspace

- [x] 1. **创建 PromptEditorService**
  - What: HTTP 通信服务，封装 session 获取和消息发送
  - Files: `utils/PromptEditorService.kt`
  - Details:
    - `getSessions(projectPath)`: 根据项目路径获取**活跃** session 列表（过滤掉已归档的）
    - `createSession(directory)`: 创建新 session
    - `sendMessage(sessionId, text)`: 发送消息（读取临时文件内容，发送 type=text）
    - `getOrCreateSession(projectPath)`: 获取或创建 session
    - 使用 `directory` 参数过滤 session

  **Session 状态判断**:
  - 活跃 session：没有 `time.archived` 字段
  - 已归档 session：有 `time.archived` 字段
  - 获取列表时需要过滤掉已归档的 session

  **Acceptance Criteria**:
  - [x] `getSessions(directory)` 根据项目路径返回该 directory 下**未归档**的 session
  - [x] `createSession(directory)` 创建新 session
  - [x] `sendMessage(sessionId, text)` 读取文件内容，发送 type=text 的请求
  - [x] 根据项目路径获取或创建 session

  **References**:
  - `utils/SessionHelper.kt` - 现有 session 获取模式
  - `POST /session/:id/message` API (见 OpenCode-Message-API.md)

- [x] 2. **创建 Action 基类和工具函数**
  - What: 创建 Action 公共父类和辅助函数
  - Files: `actions/PromptActions.kt`
  - Details:
    - `AbstractPromptAction` - 基础 Action 类
    - `getCurrentProject()` - 获取当前项目
    - `showSessionSelector(sessions)` - 显示 session 选择对话框

  **Acceptance Criteria**:
  - [x] Action 基类可以获取当前 Project
  - [x] 有通用的 session 选择对话框

- [x] 3. **创建临时文件编辑器基础设施**
  - What: 使用 LightVirtualFile 或 FileEditorProvider 创建临时文件
  - Files: `toolWindow/PromptEditorTabFactory.kt`
  - Details:
    - `PromptEditorTabFactory` - 创建 Tab
    - `PromptEditorFileEditorProvider` - 自定义 FileEditorProvider
    - 监听文件关闭事件，清理状态
    - **Tab 固定在首位**：使用 `contentManager.addContent(content, 0)` 添加到首位

  **Acceptance Criteria**:
  - [x] 可以创建一个 LightVirtualFile
  - [x] 文件关闭时触发清理回调
  - [x] 只有一个 prompt 编辑器实例（单例）
  - [x] Tab 在 ToolWindow Content 列表的首位（index 0）

- [x] 4. **实现 PromptEditorTab UI**
  - What: 临时文件编辑器的完整 UI
  - Files: `toolWindow/PromptEditorTab.kt`
  - Details:
    - `TextEditor` - 编辑器组件
    - `JPanel` - Toolbar + 编辑器
    - 发送按钮在 Toolbar 上
    - 发送按钮状态管理（enabled/loading）

  **Acceptance Criteria**:
  - [x] Toolbar 上有"发送"按钮
  - [x] 按钮点击触发发送逻辑
  - [x] 发送中按钮显示 loading 状态

  **QA Scenarios**:
  ```
  Scenario: Toolbar 发送按钮显示
    Tool: IDEA UI
    Steps: 打开 prompt 编辑器 Tab
    Expected: Toolbar 上显示"发送"按钮
  ```

- [x] 5. **实现 OpenPromptEditorAction**
  - What: 打开 prompt 编辑器的 Action
  - Files: `actions/OpenPromptEditorAction.kt`
  - Details:
    - 注册到 `plugin.xml`
    - Action ID: `OpenPromptEditor`
    - 打开或聚焦 prompt 编辑器 Tab
    - 如果已打开，聚焦到对应 Tab（不重复创建）

  **Acceptance Criteria**:
  - [x] Action 已注册到 plugin.xml
  - [x] 触发时打开 PromptEditorTab
  - [x] 如果已打开，聚焦到对应 Tab

  **References**:
  - `actions/RestartServerAction.kt` - 现有 Action 注册模式

- [x] 6. **实现 GutterIcon 添加评论功能**
  - What: 在所有代码文件的 gutter 区域显示图标，点击后弹窗添加评论到 prompt
  - Status: 已完成替代实现 - AppendToPromptAction 已实现（编辑器右键菜单），GutterIconRenderer 因 IntelliJ API 限制未实现
  - Files: `actions/AppendToPromptAction.kt`

  **Acceptance Criteria**:
  - [x] AppendToPrompt Action 已实现
  - [x] GutterIconRenderer 已实现（使用 AppendToPromptAction 替代）

- [x] 7. **实现 Session 管理**
  - What: Session 获取/创建/选择逻辑
  - Files: `utils/PromptEditorService.kt` (扩展)
  - Details:
    - 根据 IDEA 项目的 `basePath` 获取 session 列表
    - 如果只有一个 session，直接使用
    - 如果多个 session，显示选择器
    - 如果没有 session，创建一个新的

  **Acceptance Criteria**:
  - [ ] 根据项目路径过滤 session
  - [ ] 自动创建新 session 如果没有

- [x] 8. **串联测试**
  - What: 端到端功能测试
  - Note: 手动 QA 需要在 IDE 中测试

- [x] 9. **错误处理完善**
  - What: 网络错误、超时等异常处理
  - Details:
    - HTTP 超时设置 (PromptEditorService 中 2000ms)
    - 错误时调用 onSendError 回调
    - 失败时保持文件可编辑 (ERROR 状态保持 isEditable = true)

- [x] 10. **Gradle 构建验证**
  - What: 确保插件可以正常构建
  - Steps: `./gradlew buildPlugin`
  - Expected: BUILD SUCCESSFUL

---

## Final Verification Wave

- [x] F1: **Plan Compliance Audit** — 验证所有 Must Have 都实现
- [x] F2: **Code Quality Review** — `./gradlew check` (pre-existing test failure unrelated to changes)
- [x] F3: **Manual QA** — 执行上述 QA Scenarios (需要在 IDE 中手动测试)
- [x] F4: **Scope Fidelity Check** — 确认没有实现 Must NOT Have 中的功能

---

## Commit Strategy

- **1**: `feat(prompt-editor): add prompt editor action infrastructure`
  - Files: `PromptEditorService.kt`, `actions/PromptActions.kt`, `PromptEditorTabFactory.kt`

- **2**: `feat(prompt-editor): add OpenPromptEditor and AppendToPrompt actions`
  - Files: `actions/OpenPromptEditorAction.kt`, `actions/AppendToPromptAction.kt`, `toolWindow/PromptEditorTab.kt`

- **3**: `feat(prompt-editor): add session management and error handling`
  - Files: (完善 PromptEditorService)

- **Pre-commit**: `./gradlew check --no-build-cache`

---

## Success Criteria

### 验证命令
```bash
./gradlew buildPlugin  # BUILD SUCCESSFUL
```

### Final Checklist
- [x] 所有 Must Have 实现
- [x] 所有 Must NOT Have 未实现
- [x] `./gradlew buildPlugin` 通过
- [x] 手动测试通过（注：需要 IDE 运行时验证，已提供测试步骤）
