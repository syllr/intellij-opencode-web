## Why

当前通知功能上线后，用户收到大量不必要的通知。核心诉求可归纳为两个原则：

**原则 1：仅在 AI 等用户输入时才通知。** 例如用户通过提示词让 AI 调用 @librarian 调研技术框架，虽然 librarian 调研完了，但主 agent 需要综合调研结果再思考、再推理，最终给出结论。此时真正应该通知的时刻是——AI 给出结论后等待用户输入。中间过程（子 agent 完成、task 调用返回、plan 执行等）的任何 idle 都不应打扰用户。

**原则 2：仅在工作流被阻塞、用户必须介入时才通知。** 例如 AI 运行中遇到权限问题，或需要调用 qu## Why

当前通知功能上线后，用户收到大量不必要的通知。核心诉求可归纳为两个原则：

**原则 1：仅在 AI 等用户输入时才通知。** 例如用户通过提示词让 AI 调用 @librarian 调研技术框架，虽然 librarian 调研完了，但主 agent 需要综合调研结果再思考、再推理，最终给出结论。此时真正应该通知的时刻是——AI 给出结论后等待用户输入。中间过程（子 agent 完成、task 调用返回、plan 执行等）的任何 idle 都不应打扰用户。

**原则 2：仅在工作流被阻塞、用户必须介入时才通知。** 例如 AI 运行中遇到权限问题，或需要调用 question 工具询问用户。如果用户不介入，AI 无法继续完成工作流，此时应通知。

现实中的问题是：

- agent 多轮 task() 调用循环中，每次父 session 进入 idle 状态都会触发 `complete` 通知，形成海量噪音
- `session.deleted` 事件在 `session.status(idle)` 之前到达时，子 agent 完成事件被误判为主 session 的 `complete`，进一步加剧噪音
- 原则 2 的场景（permission / question）虽然已有独立通知路径，但需要确保本次变更不引入副作用

## What Changes

- **Step 1（竞态修复）**：删除 `session.deleted` 处理中的 `subagentSessionIds.remove(sid)`，消除子 agent idle 事件因时序竞态被误判为 `complete` 的问题。`subagentSessionIds` 仅在 SSE 重连时通过 `onClosed()` 清空
- **Step 2（核心降噪）**：引入 `sessionIdleFired` 集合追踪已发送过 complete 通知的父 session，以 `message.updated(role=user)` 事件作为唯一重置信号。确保 agent 循环中的中间 idle 不重复通知，而用户发新消息后的首次 idle 正常通知
- 确保 `session.idle`（旧格式事件）和 `session.status(idle)`（新格式事件）两条路径都应用新的抑制逻辑
- 在 `onClosed()` 中添加 `sessionIdleFired.clear()`，确保 SSE 重连后状态重置正确
- **本次变更不影响原则 2 的通知路径**：`permission.asked` → `permission` 通知、`question.asked`/`session.next.tool.called(tool=question)` → `question` 通知，它们在 `when(eventType)` 中与 `handleSessionIdle` 是完全独立的分支，不受 `sessionIdleFired` 抑制逻辑影响
- 不修改 `subagent_complete` 事件类型的默认配置（保持关闭）

## 用户故事

### 变更后应该通知的场景

| 场景                                                   | 事件                                                         | 通知类型        | 对应原则 |
| ------------------------------------------------------ | ------------------------------------------------------------ | --------------- | -------- |
| 用户发消息 → AI 完成全部处理 → idle 等待用户下一轮输入 | `session.status(idle)`                                       | `complete` ✅   | 原则 1   |
| AI 遇到权限申请，需要用户确认                          | `permission.asked`                                           | `permission` ✅ | 原则 2   |
| AI 调用 question 工具询问用户                          | `question.asked` / `session.next.tool.called(tool=question)` | `question` ✅   | 原则 2   |
| AI 执行出错                                            | `session.error`                                              | `error` ✅      | —        |

### 变更后不应通知的场景（噪音消除）

| 场景                                                                  | 当前行为                      | 修复后行为                                  |
| --------------------------------------------------------------------- | ----------------------------- | ------------------------------------------- |
| agent 循环中 task() 调用完成后父 session 短暂 idle（准备下一轮 task） | `complete` 通知 ❌            | 静默抑制 ✅                                 |
| 子 agent 完成，且 `session.deleted` 比 `session.status(idle)` 先到达  | `complete` 误判 ❌            | 正确识别为 `subagent_complete`，默认关闭 ✅ |
| 用户刚发完消息（自己知道已发送）                                      | `user_message` 通知（默认关） | 不变，默认关 ✅                             |

## Capabilities

### New Capabilities

- `idle-notification-suppression`: 父 session 的 complete 通知抑制逻辑，仅在用户发消息后首次 idle 时通知，agent 循环中的中间 idle 静默跳过
- `subagent-complete-detection-fix`: 修复 `session.deleted` 时序竞态导致的子 agent idle 事件误判，确保子 agent 完成不被错误地触发 `complete` 通知

### Modified Capabilities

<!-- 无既有 specs 需要修改 -->

## Impact

- `OpenCodeSSEConsumer.kt` — 修改 `handleSessionIdle` 和 `onMessage` 中的 `session.status` / `session.idle` / `session.deleted` / `message.updated` 分支
- 无 API 变更，无配置变更，无新依赖
  estion 工具询问用户。如果用户不介入，AI 无法继续完成工作流，此时应通知。

现实中的问题是：

- agent 多轮 task() 调用循环中，每次父 session 进入 idle 状态都会触发 `complete` 通知，形成海量噪音
- `session.deleted` 事件在 `session.status(idle)` 之前到达时，子 agent 完成事件被误判为主 session 的 `complete`，进一步加剧噪音
- 原则 2 的场景（permission / question）虽然已有独立通知路径，但需要确保本次变更不引入副作用

## What Changes

- **Step 1（竞态修复）**：删除 `session.deleted` 处理中的 `subagentSessionIds.remove(sid)`，消除子 agent idle 事件因时序竞态被误判为 `complete` 的问题。`subagentSessionIds` 仅在 SSE 重连时通过 `onClosed()` 清空
- **Step 2（核心降噪）**：引入 `sessionIdleFired` 集合追踪已发送过 complete 通知的父 session，以 `message.updated(role=user)` 事件作为唯一重置信号。确保 agent 循环中的中间 idle 不重复通知，而用户发新消息后的首次 idle 正常通知
- 确保 `session.idle`（旧格式事件）和 `session.status(idle)`（新格式事件）两条路径都应用新的抑制逻辑
- 在 `onClosed()` 中添加 `sessionIdleFired.clear()`，确保 SSE 重连后状态重置正确
- **本次变更不影响原则 2 的通知路径**：`permission.asked` → `permission` 通知、`question.asked`/`session.next.tool.called(tool=question)` → `question` 通知，它们在 `when(eventType)` 中与 `handleSessionIdle` 是完全独立的分支，不受 `sessionIdleFired` 抑制逻辑影响
- 不修改 `subagent_complete` 事件类型的默认配置（保持关闭）

## 用户故事

### 变更后应该通知的场景

| 场景                                                   | 事件                                                         | 通知类型        | 对应原则 |
| ------------------------------------------------------ | ------------------------------------------------------------ | --------------- | -------- |
| 用户发消息 → AI 完成全部处理 → idle 等待用户下一轮输入 | `session.status(idle)`                                       | `complete` ✅   | 原则 1   |
| AI 遇到权限申请，需要用户确认                          | `permission.asked`                                           | `permission` ✅ | 原则 2   |
| AI 调用 question 工具询问用户                          | `question.asked` / `session.next.tool.called(tool=question)` | `question` ✅   | 原则 2   |
| AI 执行出错                                            | `session.error`                                              | `error` ✅      | —        |

### 变更后不应通知的场景（噪音消除）

| 场景                                                                  | 当前行为                      | 修复后行为                                  |
| --------------------------------------------------------------------- | ----------------------------- | ------------------------------------------- |
| agent 循环中 task() 调用完成后父 session 短暂 idle（准备下一轮 task） | `complete` 通知 ❌            | 静默抑制 ✅                                 |
| 子 agent 完成，且 `session.deleted` 比 `session.status(idle)` 先到达  | `complete` 误判 ❌            | 正确识别为 `subagent_complete`，默认关闭 ✅ |
| 用户刚发完消息（自己知道已发送）                                      | `user_message` 通知（默认关） | 不变，默认关 ✅                             |

## Capabilities

### New Capabilities

- `idle-notification-suppression`: 父 session 的 complete 通知抑制逻辑，仅在用户发消息后首次 idle 时通知，agent 循环中的中间 idle 静默跳过
- `subagent-complete-detection-fix`: 修复 `session.deleted` 时序竞态导致的子 agent idle 事件误判，确保子 agent 完成不被错误地触发 `complete` 通知

### Modified Capabilities

<!-- 无既有 specs 需要修改 -->

## Impact

- `OpenCodeSSEConsumer.kt` — 修改 `handleSessionIdle` 和 `onMessage` 中的 `session.status` / `session.idle` / `session.deleted` / `message.updated` 分支
- 无 API 变更，无配置变更，无新依赖
