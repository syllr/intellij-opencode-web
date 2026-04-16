# Draft: OpenCode 事件通知 - 完整事件分析

---

## 第一个事儿：OpenCode SSE 事件完整列表

### 事件分类概览

| 分类 | 事件数 | 说明 |
|------|--------|------|
| Session 会话 | 8 | session.* |
| Message 消息 | 5 | message.* |
| TUI 用户界面 | 4 | tui.* |
| PTY 终端 | 4 | pty.* |
| Permission 权限 | 2 | permission.* |
| Question 问答 | 3 | question.* |
| LSP 语言服务 | 2 | lsp.* |
| File 文件 | 2 | file.* |
| Installation 安装 | 2 | installation.* |
| Workspace 工作区 | 3 | workspace.* |
| Server 服务器 | 3 | server.* |
| MCP | 2 | mcp.* |
| VCS 版本控制 | 1 | vcs.* |
| Worktree | 2 | worktree.* |
| Project 项目 | 1 | project.* |
| IDE | 1 | ide.* |
| Todo 任务 | 1 | todo.* |
| Command 命令 | 1 | command.* |
| **总计** | **47** | |

---

## 完整事件详细列表

---

### 一、Session 会话事件（8个）

#### 1. `session.created`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, info: Session.Info }` |
| **触发位置** | `sync/index.ts:152,155` - SyncEvent 系统自动触发 |
| **语义** | 新会话创建。当用户新建一个 session 时触发，info 包含会话的完整元数据（标题、创建时间等） |
| **触发时机** | 数据库记录 session 创建事件时 |

#### 2. `session.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, info: Session.Info }` |
| **触发位置** | `sync/index.ts:152,155` - SyncEvent 系统自动触发 |
| **语义** | 会话信息更新。session 的标题、summary 等信息发生变化时触发 |
| **触发时机** | 数据库记录 session 更新事件时 |
| **⚠️ 注意** | 可能触发非常频繁（每次 AI 回复都算更新） |

#### 3. `session.deleted`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, info: Session.Info }` |
| **触发位置** | `sync/index.ts:152,155` - SyncEvent 系统自动触发 |
| **语义** | 会话被删除。info 包含被删除会话的元数据 |
| **触发时机** | 数据库记录 session 删除事件时 |

#### 4. `session.diff`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, diff: FileDiff[] }` |
| **触发位置** | `session/revert.ts:83`, `session/summary.ts:123` |
| **语义** | 会话产生了文件变更差异。diff 包含变更的文件列表和变更统计 |
| **触发时机** | 执行 revert 操作或生成 summary 时 |
| **FileDiff 结构** | `{ path: string, additions: number, deletions: number }` |

#### 5. `session.error` ⭐重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID?: string, error: NamedError }` |
| **触发位置** | 多处：`session/prompt.ts:583`（LLM错误）、`session/processor.ts:522`（处理错误）、`config/config.ts:219,258,296`（配置错误）等 |
| **语义** | session 执行过程中的错误。包括 LLM 流处理异常、Context 溢出、Agent/Model/Command 找不到、文件读取失败等 |
| **触发时机** | 任何 session 处理过程中的异常 |
| **NamedError** | `{ name: string, message: string }` |
| **⚠️ 注意** | 这是开发者调试最重要的事件，应该通知 |

#### 6. `session.status`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, status: SessionStatus }` |
| **触发位置** | `session/status.ts:74` |
| **语义** | session 状态变化。状态包括 idle（空闲）、retry（重试中）、busy（忙碌） |
| **触发时机** | Session 状态改变时 |
| **⚠️ 注意** | 可能触发非常频繁，不建议通知 |

#### 7. `session.idle`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string }` |
| **触发位置** | `session/status.ts:76` |
| **语义** | session 进入空闲状态（已弃用） |
| **⚠️ 注意** | 已弃用，不建议关注 |

#### 8. `session.compacted`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string }` |
| **触发位置** | `session/compaction.ts:342` |
| **语义** | session 完成压缩操作。compact 是节省 context 长度的操作 |
| **触发时机** | compaction 处理完成且返回 "continue" 时 |

---

### 二、Message 消息事件（5个）

#### 9. `message.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, info: MessageV2.Info }` |
| **触发位置** | `sync/index.ts:152,155` - SyncEvent 系统自动触发 |
| **语义** | 消息更新（新消息创建） |
| **⚠️ 注意** | 可能触发非常频繁，不建议通知 |

#### 10. `message.removed`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, messageID: string }` |
| **触发位置** | `sync/index.ts:152,155` - SyncEvent 系统自动触发 |
| **语义** | 消息被删除 |
| **⚠️ 注意** | 不建议通知 |

#### 11. `message.part.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, part: Part, time: number }` |
| **触发位置** | `sync/index.ts:152,155` - SyncEvent 系统自动触发 |
| **语义** | 消息的某个 part 更新（部分更新） |
| **⚠️ 注意** | 可能触发非常频繁，不建议通知 |

#### 12. `message.part.delta` ⭐核心
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, messageID: string, partID: string, field: string, delta: string }` |
| **触发位置** | `session/index.ts:641` |
| **语义** | **流式输出事件**！AI 生成内容时的增量更新。field 可以是 "content" 等字段，delta 是新增的文本片段 |
| **触发时机** | LLM 流式输出每个 token 时 |
| **⚠️ 注意** | 极其频繁，每秒可能几十次，不建议通知 |

#### 13. `message.part.removed`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, messageID: string, partID: string }` |
| **触发位置** | `sync/index.ts:152,155` - SyncEvent 系统自动触发 |
| **语义** | 消息的某个 part 被删除 |
| **⚠️ 注意** | 不建议通知 |

---

### 三、TUI 用户界面事件（4个）

#### 14. `tui.toast.show` ⭐最重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ title?: string, message: string, variant: "info" \| "success" \| "warning" \| "error", duration?: number }` |
| **触发位置** | `server/instance/tui.ts:309`, `mcp/index.ts:337,348` |
| **语义** | **显示通知 toast**。这是 opencode 内部统一的通知机制，所有模块都通过这个事件显示通知 |
| **触发时机** | 任意模块调用 `bus.publish(TuiEvent.ToastShow, {...})` 时 |
| **variant 说明** | `info`(蓝色)、`success`(绿色)、`warning`(黄色)、`error`(红色) |
| **duration 说明** | 显示时长（毫秒），默认 5000 |

**使用示例**（来自 mcp/index.ts）：
```typescript
bus.publish(TuiEvent.ToastShow, {
  title: "MCP Authentication Required",
  message: `Server "${key}" requires authentication.`,
  variant: "warning",
  duration: 8000,
})
```

#### 15. `tui.prompt.append`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ text: string }` |
| **触发位置** | `server/instance/tui.ts:100` |
| **语义** | 追加 prompt 文本到输入框 |
| **触发时机** | HTTP API 收到 `POST /append` 请求时 |
| **⚠️ 注意** | 这是 TUI 内部操作，不建议通知 |

#### 16. `tui.command.execute`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ command: string }` |
| **触发位置** | `server/instance/tui.ts:122,146,170,194,218,242,269` |
| **语义** | 执行 TUI 命令。包括 session.list, session.new, session.share, session.interrupt, session.compact 等 |
| **command 类型** | `session.list`, `session.new`, `session.share`, `session.interrupt`, `session.compact`, `session.page.up/down`, `session.line.up/down`, `prompt.clear`, `prompt.submit` 等 |
| **⚠️ 注意** | TUI 内部命令执行，不建议通知 |

#### 17. `tui.session.select`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string }` |
| **触发位置** | `server/instance/tui.ts:374` |
| **语义** | 用户在 TUI 中选择切换会话 |
| **触发时机** | HTTP API 收到 `POST /select-session` 请求时 |
| **⚠️ 注意** | 用户主动操作，不建议通知 |

---

### 四、Permission 权限事件（2个）

#### 18. `permission.asked` ⭐重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ id: string, sessionID: string, permission: string, patterns: string[], metadata: ..., always: boolean, tool?: { messageID, callID } }` |
| **触发位置** | `permission/index.ts:195` |
| **语义** | **请求权限确认**。当 AI 要执行敏感操作（文件修改、bash 命令等）时，需要用户授权 |
| **permission 类型** | `edit`（编辑文件）、`bash`（执行命令）、`folder_read`（读文件夹）等 |
| **patterns** | 匹配模式，如 `["**/*.ts", "src/**"]` 表示操作的文件范围 |
| **⚠️ 注意** | 必须通知！用户需要决策是否授权 |

#### 19. `permission.replied`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, requestID: string, reply: "once" \| "always" \| "reject" }` |
| **触发位置** | `permission/index.ts:210,225,253` |
| **语义** | 用户对权限请求的回复。`once`=本次允许，`always`=永久允许，`reject`=拒绝 |
| **⚠️ 注意** | 权限回复后通常不需要再通知，可以记录日志 |

---

### 五、Question 问答事件（3个）

#### 20. `question.asked` ⭐重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ id: string, sessionID: string, questions: Question.Info[], tool?: { messageID, callID } }` |
| **触发位置** | `question/index.ts:148` |
| **语义** | **向用户提问**。当 AI 需要用户回答问题或做选择时触发 |
| **Question.Info 结构** | `{ question: string, header: string (≤30字符), options: Option[], multiple?: boolean }` |
| **⚠️ 注意** | 必须通知！用户需要回复 |

#### 21. `question.replied`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, requestID: string, answers: string[] }` |
| **触发位置** | `question/index.ts:167` |
| **语义** | 用户回答了问题 |
| **⚠️ 注意** | 不需要通知（用户刚回复完，已经知道了） |

#### 22. `question.rejected`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, requestID: string }` |
| **触发位置** | `question/index.ts:184` |
| **语义** | 用户拒绝或忽略问题 |
| **⚠️ 注意** | 可以通知也可以不通知，取决于需求 |

---

### 六、PTY 终端事件（4个）

#### 23. `pty.created`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ info: Pty.Info }` |
| **触发位置** | `pty/index.ts:264` |
| **语义** | 创建新终端会话 |
| **Pty.Info** | `{ id, title, command, args, cwd, status, pid }` |
| **⚠️ 注意** | 不需要通知（用户自己开的终端） |

#### 24. `pty.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ info: Pty.Info }` |
| **触发位置** | `pty/index.ts:278` |
| **语义** | 终端信息更新（标题改变、窗口大小变化等） |
| **⚠️ 注意** | 不需要通知，频繁触发 |

#### 25. `pty.exited` ⭐可关注
| 属性 | 说明 |
|------|------|
| **Schema** | `{ id: string, exitCode: number }` |
| **触发位置** | `pty/index.ts:260` |
| **语义** | 终端进程退出。exitCode 非 0 表示异常退出 |
| **⚠️ 注意** | exitCode != 0 时可能需要通知 |

#### 26. `pty.deleted`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ id: string }` |
| **触发位置** | `pty/index.ts:162` |
| **语义** | 终端会话被删除 |
| **⚠️ 注意** | 不需要通知 |

---

### 七、LSP 语言服务器事件（2个）

#### 27. `lsp.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{}` (空对象) |
| **触发位置** | `lsp/index.ts:307` |
| **语义** | LSP 服务器状态更新 |
| **⚠️ 注意** | 不需要通知，太频繁 |

#### 28. `lsp.client.diagnostics`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ serverID: string, path: string }` |
| **触发位置** | `lsp/client.ts:62` |
| **语义** | LSP 发布诊断信息（错误、警告等） |
| **⚠️ 注意** | 不需要通知，太频繁（每次保存文件都可能触发） |

---

### 八、File 文件事件（2个）

#### 29. `file.edited` ⭐可关注
| 属性 | 说明 |
|------|------|
| **Schema** | `{ file: string }` |
| **触发位置** | `tool/write.ts:59`, `tool/edit.ts:91,132`, `tool/apply_patch.ts:230` |
| **语义** | 文件被编辑。AI 或用户保存文件时触发 |
| **⚠️ 注意** | 可以通知用户"文件已修改"，但可能较频繁 |

#### 30. `file.watcher.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ file: string, event: "add" \| "change" \| "unlink" }` |
| **触发位置** | `file/watcher.ts:101,102,103` |
| **语义** | 文件监视器检测到变化。event: `add`(新建)、`change`(修改)、`unlink`(删除) |
| **⚠️ 注意** | 可以通知，但可能较频繁 |

---

### 九、Workspace 工作区事件（3个）

#### 31. `workspace.ready` ⭐重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ name: string }` |
| **触发位置** | 未在代码中找到明确的 publish 调用 |
| **语义** | 工作区就绪。表示 opencode 成功连接到工作环境 |
| **⚠️ 注意** | 应该通知，工作区连接成功 |

#### 32. `workspace.failed` ⭐重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ message: string }` |
| **触发位置** | 未在代码中找到明确的 publish 调用 |
| **语义** | 工作区失败。无法连接到工作环境 |
| **⚠️ 注意** | 必须通知，工作区异常 |

#### 33. `workspace.status`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ workspaceID: string, status: "connected" \| "connecting" \| "disconnected" \| "error", error?: string }` |
| **触发位置** | `control-plane/workspace.ts:142` |
| **语义** | 工作区连接状态变化 |
| **⚠️ 注意** | 不需要通知，太频繁 |

---

### 十、Installation 安装事件（2个）

#### 34. `installation.update-available` ⭐可关注
| 属性 | 说明 |
|------|------|
| **Schema** | `{ version: string }` |
| **触发位置** | `cli/upgrade.ts:14,24` |
| **语义** | 发现新版本可用 |
| **触发条件** | `Flag.OPENCODE_ALWAYS_NOTIFY_UPDATE` 或 `config.autoupdate === "notify"` |
| **⚠️ 注意** | 可以通知用户有更新 |

#### 35. `installation.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ version: string }` |
| **触发位置** | `cli/upgrade.ts:30` |
| **语义** | 自动更新成功完成 |
| **⚠️ 注意** | 不需要通知，更新完成用户自己知道 |

---

### 十一、Command 命令事件（1个）

#### 36. `command.executed` ⭐可关注
| 属性 | 说明 |
|------|------|
| **Schema** | `{ name: string, sessionID: string, arguments: string, messageID: string }` |
| **触发位置** | `session/prompt.ts:1650` |
| **语义** | 命令执行完成 |
| **⚠️ 注意** | 可以通知，但可能频繁 |

---

### 十二、MCP 事件（2个）

#### 37. `mcp.tools.changed`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ server: string }` |
| **触发位置** | `mcp/index.ts:479` |
| **语义** | MCP 服务器工具列表变更 |
| **⚠️ 注意** | 不需要通知，太技术化 |

#### 38. `mcp.browser.open.failed` ⭐重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ mcpName: string, url: string }` |
| **触发位置** | `mcp/index.ts:795` |
| **语义** | OAuth 认证时浏览器打开失败 |
| **⚠️ 注意** | 应该通知，用户需要手动打开 URL |

---

### 十三、VCS 版本控制事件（1个）

#### 39. `vcs.branch.updated` ⭐可关注
| 属性 | 说明 |
|------|------|
| **Schema** | `{ branch?: string }` |
| **触发位置** | `project/vcs.ts:186` |
| **语义** | Git 分支变更 |
| **⚠️ 注意** | 可以通知，分支切换是重要操作 |

---

### 十四、Server 服务器事件（3个）

#### 40. `server.connected`
| 属性 | 说明 |
|------|------|
| **Schema** | `{}` (空对象) |
| **触发位置** | `server/instance/global.ts:29` - SSE 客户端连接时 |
| **语义** | SSE 连接建立。这是连接成功的基础事件 |
| **⚠️ 注意** | 不需要通知，连接成功不代表任何业务意义 |

#### 41. `server.heartbeat`
| 属性 | 说明 |
|------|------|
| **Schema** | `{}` (空对象) |
| **触发位置** | `server/instance/global.ts:38` - 每 10 秒一次 |
| **语义** | SSE 心跳保活 |
| **⚠️ 注意** | 绝对不需要通知 |

#### 42. `server.instance.disposed`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ directory: string }` |
| **触发位置** | `project/instance.ts:128,149`, `server/instance/global.ts:249` |
| **语义** | 实例关闭/重新加载时触发 |
| **⚠️ 注意** | 不需要通知 |

---

### 十五、Worktree 事件（2个）

#### 43. `worktree.ready` ⭐可关注
| 属性 | 说明 |
|------|------|
| **Schema** | `{ name: string, branch: string }` |
| **触发位置** | `worktree/index.ts:287` |
| **语义** | Worktree 创建并引导完成 |
| **⚠️ 注意** | 可以通知 |

#### 44. `worktree.failed` ⭐重要
| 属性 | 说明 |
|------|------|
| **Schema** | `{ message: string }` |
| **触发位置** | `worktree/index.ts:257,276` |
| **语义** | Worktree 启动失败 |
| **⚠️ 注意** | 应该通知，用户需要知道失败了 |

---

### 十六、Project 项目事件（1个）

#### 45. `project.updated`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ id: string, worktree: string, vcs?: "git", name?: string, ... }` |
| **触发位置** | `project/project.ts:139` (GlobalBus.emit) |
| **语义** | 项目信息更新 |
| **⚠️ 注意** | 不需要通知，太频繁 |

---

### 十七、IDE 事件（1个）

#### 46. `ide.installed`
| 属性 | 说明 |
|------|------|
| **Schema** | `{ ide: string }` |
| **触发位置** | 定义于 `ide/index.ts:20`，但未找到 publish |
| **语义** | IDE 插件安装完成 |
| **⚠️ 注意** | 应该通知，但未找到触发代码 |

---

### 十八、Todo 任务事件（1个）

#### 47. `todo.updated` ⭐可关注
| 属性 | 说明 |
|------|------|
| **Schema** | `{ sessionID: string, todos: Todo.Info[] }` |
| **触发位置** | `session/todo.ts:59` |
| **语义** | 任务列表更新。todos 包含所有任务的状态 |
| **Todo.Info** | `{ content: string, status: "pending" \| "in_progress" \| "completed" \| "cancelled", priority: "high" \| "medium" \| "low" }` |
| **⚠️ 注意** | 可以通知任务完成 |

---

## 通知决策表（待确认）

| # | 事件 | 建议 | 理由 |
|---|------|------|------|
| 1 | `tui.toast.show` | ✅ 通知 | **核心通知机制**，所有模块都用它 |
| 2 | `session.error` | ✅ 通知 | 重要错误需知晓 |
| 3 | `permission.asked` | ✅ 通知 | 需用户授权 |
| 4 | `question.asked` | ✅ 通知 | 需用户回复 |
| 5 | `workspace.failed` | ✅ 通知 | 工作区异常 |
| 6 | `worktree.failed` | ✅ 通知 | Worktree 异常 |
| 7 | `mcp.browser.open.failed` | ✅ 通知 | 需用户手动操作 |
| 8 | `session.created` | ❓待确认 | |
| 9 | `session.deleted` | ❓待确认 | |
| 10 | `pty.exited` | ❓待确认（exitCode 非0时）| |
| 11 | `file.edited` | ❓待确认 | |
| 12 | `file.watcher.updated` | ❓待确认 | |
| 13 | `workspace.ready` | ❓待确认 | |
| 14 | `installation.update-available` | ❓待确认 | |
| 15 | `vcs.branch.updated` | ❓待确认 | |
| 16 | `worktree.ready` | ❓待确认 | |
| 17 | `todo.updated` | ❓待确认 | |
| 18 | `question.rejected` | ❓待确认 | |
| 19 | 其他所有 | ❌ 不通知 | 太频繁或无意义 |