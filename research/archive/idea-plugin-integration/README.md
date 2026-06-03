# IDEA 插件功能集成调研

**日期**: 2026-05-30
**状态**: 调研完成，准备实施
**目标**: 发挥 IDEA 插件优势，增强与 opencode 后端的联动

---

## 一、联动机会总览

### 1.1 高价值联动点（按优先级）

| 优先级 | 功能               | 复杂度 | 价值 | 依赖 API          | 实施周期 |
| ------ | ------------------ | ------ | ---- | ----------------- | -------- |
| 🔴 P0  | 代码上下文自动注入 | 低     | 高   | PsiElement        | 1-2 天   |
| 🔴 P0  | 代码导航集成       | 中     | 高   | FileEditorManager | 2-3 天   |
| 🔴 P0  | Git 工作流集成     | 中     | 高   | Git4Idea          | 2-3 天   |
| 🟡 P1  | 自动化重构集成     | 高     | 高   | Refactoring API   | 3-5 天   |
| 🟡 P1  | 调试信息集成       | 高     | 中   | XDebugger API     | 3-5 天   |
| 🟡 P1  | 代码审查集成       | 中     | 中   | File API          | 2-3 天   |
| 🟢 P2  | 终端命令集成       | 中     | 中   | Terminal API      | 2-3 天   |
| 🟢 P2  | LSP 诊断集成       | 低     | 低   | LSP API           | 1-2 天   |

---

## 二、OpenCode API 体系

### 2.1 API 架构

| 特性     | 说明                            |
| -------- | ------------------------------- |
| 协议     | REST + SSE + WebSocket          |
| 框架     | Effect-IO HTTP API (TypeScript) |
| 默认端口 | 12396                           |
| 认证     | `x-opencode-directory` header   |

### 2.2 核心 API 分组

| API 组           | 路径前缀             | 功能                              |
| ---------------- | -------------------- | --------------------------------- |
| **Session API**  | `/session/*`         | 会话管理、消息收发                |
| **Event API**    | `/event`             | SSE 实时事件                      |
| **File API**     | `/find/*`, `/file/*` | 文件搜索、读取                    |
| **V2 API**       | `/api/*`             | 新版会话、消息 API（cursor 分页） |
| **Instance API** | `/instance/*`        | VCS、命令、Agent                  |
| **MCP API**      | `/mcp/*`             | MCP 服务器管理                    |

### 2.3 关键端点

| 方法 | 路径                               | 功能               |
| ---- | ---------------------------------- | ------------------ |
| POST | `/session/:sessionID/message`      | 发送消息（流式）   |
| POST | `/session/:sessionID/prompt_async` | 异步发送消息       |
| POST | `/session/:sessionID/abort`        | 中止会话           |
| GET  | `/global/event`                    | SSE 实时事件       |
| GET  | `/find`                            | 文本搜索 (ripgrep) |
| GET  | `/file/content`                    | 读取文件           |
| GET  | `/file/status`                     | Git 状态           |
| POST | `/vcs/apply`                       | 应用 patch         |

---

## 三、事件系统

### 3.1 双层架构

```
SyncEvent（事件溯源 + 持久化）→ BusEvent（实时通知 + SSE）
EventV2（统一事件系统，支持类型化订阅和 Location 机制）
```

### 3.2 核心事件类型

| 事件                       | 说明                           |
| -------------------------- | ------------------------------ |
| `session.status`           | 会话状态变化 (idle/busy/retry) |
| `session.next.text.delta`  | 文本增量（流式）               |
| `session.next.tool.called` | 工具调用                       |
| `message.updated`          | 消息更新                       |
| `file.edited`              | 文件编辑                       |
| `permission.asked`         | 权限请求                       |

---

## 四、工具系统和 Agent 系统

### 4.1 工具系统

- `Tool.define` 模式定义工具
- 内置 18 个工具（read/write/edit/grep/glob/shell/task 等）
- 基于 Effect Schema 参数验证
- 支持自定义工具（`{tool,tools}/` 目录）

### 4.2 Agent 系统

- 支持 primary/subagent/all 三种模式
- 基于 `Permission.Ruleset` 权限控制
- 支持自定义 Agent（Markdown 文件或 opencode.json）
- 子 Agent 权限从父 Agent 派生

---

## 五、配置系统

### 5.1 多层合并顺序

```
well-known → 全局 → 项目 → .opencode → 环境变量 → Console → MDM
```

### 5.2 核心配置

- `agent` - Agent 配置（model, prompt, permission, steps）
- `permission` - 权限配置（read/edit/bash/task 等）
- `provider` - Provider 配置
- `mcp` - MCP 服务器配置（local/remote）
- `tools` - 工具开关

---

## 六、其他技术要点

### 6.1 安全

- 凭据使用 `PasswordSafe` API
- HTTPS 强制 TLS 1.2+
- 输入验证和日志脱敏

### 6.2 部署

- Gradle IntelliJ Plugin 构建
- JetBrains Marketplace 发布
- Plugin Verifier 验证兼容性

### 6.3 国际化

- 自定义 `DynamicPluginBundle`
- `@PropertyKey` 注解编译时验证
- Unicode 转义非 ASCII 字符

### 6.4 MCP/LSP

- MCP：标准化 AI 与外部工具通信
- LSP：代码智能（补全、跳转、诊断）

### 6.5 调试

- 远程调试（Remote JVM Debug）
- JCEF DevTools 远程调试
- `thisLogger().info/warn/error()` 日志
- 日志文件：`build/idea-sandbox/IU-2026.1/log/`

---

## 七、实施路径

### 阶段一：代码上下文注入（1-2 天）

利用 PsiElement 获取代码上下文，通过 API 发送到 opencode。

```kotlin
val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
val offset = editor.caretModel.offset
val element = psiFile.findElementAt(offset)
// 构建上下文 → POST /session/:sessionID/prompt_async
```

### 阶段二：代码导航集成（2-3 天）

监听 SSE 事件，在 IDEA 中打开文件并导航到指定行。

```kotlin
FileEditorManager.getInstance(project)
    .openTextEditor(OpenFileDescriptor(project, virtualFile, line - 1), true)
```

### 阶段三：Git 工作流集成（2-3 天）

AI 辅助生成 commit message 和代码审查。

```kotlin
val diff = Git.getInstance().collectUncommittedChanges(repository)
// 发送 diff → POST /session/:sessionID/message → 获取 commit message
```

---

## 八、文档索引

| 文件                                          | 内容                                 |
| --------------------------------------------- | ------------------------------------ |
| `02-idea-plugin-integration-opportunities.md` | IDEA 与 opencode 联动机会总览        |
| `04-opencode-api-integration-details.md`      | OpenCode API 架构、请求/响应格式     |
| `05-opencode-v2-api-details.md`               | V2 API 端点、消息结构、分页          |
| `09-opencode-event-system.md`                 | 事件架构、订阅、处理、性能优化       |
| `12-psi-git-integration.md`                   | PSI 代码分析、Git 操作集成           |
| `13-editor-notification-integration.md`       | 编辑器监听、通知系统、进度指示器     |
| `17-mcp-lsp-integration.md`                   | MCP 协议、LSP 协议、高级功能         |
| `22-opencode-tool-agent-system.md`            | 工具定义、权限、Agent 配置           |
| `24-opencode-session-message-system.md`       | Session 生命周期、流式响应、状态管理 |
| `25-security-config-deep-dive.md`             | 密码安全存储、配置管理               |
| `26-deployment-plugin-system.md`              | 插件打包、Marketplace 发布           |
| `27-debugging-event-system-v2.md`             | 远程调试、EventV2 架构               |
| `28-i18n-project-management.md`               | DynamicBundle、项目切换              |

---

## 九、参考资源

| 资源                      | URL                                                              |
| ------------------------- | ---------------------------------------------------------------- |
| IntelliJ SDK Code Samples | https://github.com/JetBrains/intellij-sdk-code-samples           |
| PsiElement 文档           | https://plugins.jetbrains.com/docs/intellij/psi-elements.html    |
| Action System             | https://plugins.jetbrains.com/docs/intellij/action-system.html   |
| Git4Idea API              | https://plugins.jetbrains.com/docs/intellij/git-integration.html |
| MCP 官方文档              | https://modelcontextprotocol.io/                                 |
| LSP 官方文档              | https://microsoft.github.io/language-server-protocol/            |
| OpenCode API 文档         | 运行 opencode 后访问 GET /doc                                    |
| OpenCode GitHub           | https://github.com/anomalyco/opencode                            |
