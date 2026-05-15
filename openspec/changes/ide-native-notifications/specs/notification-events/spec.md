## ADDED Requirements

### Requirement: SSE 通知事件监听

系统 MUST 通过已有 SSE 连接监听 OpenCode 服务端的通知相关事件。

#### Scenario: server.connected 事件

- **WHEN** SSE 连接建立成功，收到 `server.connected` 事件
- **THEN** 系统触发 `client_connected` 通知

#### Scenario: session.created 事件（SyncEvent）

- **WHEN** SSE 收到 `payload.type == "sync"` 且 `syncEvent.type == "session.created.1"` 且 `data.info.parentID` 为空
- **THEN** 系统触发 `session_started` 通知

#### Scenario: session.status idle 事件

- **WHEN** SSE 收到 `payload.type == "session.status"` 且 `properties.status.type == "idle"`
- **THEN** 系统通过 HTTP API 查询 session 是否有 parentID，无为 complete、有为 subagent_complete，延迟 350ms 去抖后触发通知

#### Scenario: session.error 事件

- **WHEN** SSE 收到 `payload.type == "session.error"`
- **THEN** 系统检查 `properties.error.name`，为 `MessageAbortedError` 则触发 `user_cancelled` 通知，否则触发 `error` 通知

#### Scenario: permission.asked 事件

- **WHEN** SSE 收到 `payload.type == "permission.asked"`
- **THEN** 系统触发 `permission` 通知

#### Scenario: session.next.tool.called 事件

- **WHEN** SSE 收到 `payload.type == "session.next.tool.called"` 且 `properties.tool == "question"`
- **THEN** 系统触发 `question` 通知

- **WHEN** SSE 收到 `payload.type == "session.next.tool.called"` 且 `properties.tool == "plan_exit"`
- **THEN** 系统触发 `plan_exit` 通知

#### Scenario: message.updated 用户消息事件

- **WHEN** SSE 收到 `payload.type == "message.updated"` 且 `properties.info.role == "user"`
- **THEN** 系统触发 `user_message` 通知

#### Scenario: session.next.tool.called 属性格式容错

- **WHEN** 收到 `session.next.tool.called` 事件但 `properties.tool` 不存在或非预期的格式
- **THEN** 系统 MUST NOT 崩溃，静默忽略该事件

### Requirement: 事件类型去重

系统 SHALL 避免同一事件被处理两次（BusEvent 和 SyncEvent 同时到达时只处理一次）。

#### Scenario: 直连事件优先

- **WHEN** 同一事件同时通过直连 BusEvent（`payload.type`）和 SyncEvent（`payload.type == "sync"`）到达
- **THEN** 系统以直连 BusEvent 为准，跳过对应的 SyncEvent

#### Scenario: session.status idle 去抖

- **WHEN** 短时间内连续收到同一 sessionID 的多次 `session.status(idle)` 事件
- **THEN** 系统只触发一次通知（350ms 去抖窗口）
