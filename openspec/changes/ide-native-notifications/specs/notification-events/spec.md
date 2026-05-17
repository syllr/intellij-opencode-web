## ADDED Requirements

### Requirement: SSE 通知事件监听

系统 MUST 通过已有 SSE 连接监听 OpenCode 服务端的通知相关事件。

#### Scenario: server.connected 事件

- **WHEN** SSE 收到 `server.connected` 事件
- **THEN** 系统触发 `client_connected` 通知

#### Scenario: session.created SyncEvent

- **WHEN** SSE 收到 `payload.type == "sync"` 且 `syncEvent.type == "session.created.1"` 且 `data.info.parentID` 为空
- **THEN** 系统触发 `session_started` 通知

#### Scenario: session.status idle 事件

- **WHEN** SSE 收到 `payload.type == "session.status"` 且 `properties.status.type == "idle"`
- **THEN** 系统查本地 `subagentSessionIds` 集合：该 sessionID 在集合中则为 `subagent_complete`，否则为 `complete`，延迟 350ms 去抖后触发通知

#### Scenario: session.idle 旧格式

- **WHEN** SSE 收到 `payload.type == "session.idle"` 且同一 sessionID 未收到过 `session.status(idle)`
- **THEN** 系统按 session.status(idle) 相同逻辑处理（350ms 去抖 + subagent 判断）

#### Scenario: session.idle 与 session.status 去重

- **WHEN** 同一 sessionID 先后收到 `session.idle` 和 `session.status(idle)`
- **THEN** 以先收到为准触发，后到事件因去抖窗口而忽略

#### Scenario: session.error 事件

- **WHEN** SSE 收到 `payload.type == "session.error"`
- **THEN** 系统检查 `properties.error.name`，为 `MessageAbortedError` 则触发 `user_cancelled`，否则 `error`

#### Scenario: permission.asked 事件

- **WHEN** SSE 收到 `payload.type == "permission.asked"`
- **THEN** 系统触发 `permission` 通知

#### Scenario: session.next.tool.called（Direct BusEvent）

- **WHEN** SSE 收到 `payload.type == "session.next.tool.called"` 且 `properties.tool == "question"`
- **THEN** 系统触发 `question` 通知

- **WHEN** SSE 收到 `payload.type == "session.next.tool.called"` 且 `properties.tool == "plan_exit"`
- **THEN** 系统触发 `plan_exit` 通知

#### Scenario: session.next.tool.called（SyncEvent 格式）

- **WHEN** `syncEvent.type == "session.next.tool.called.1"` 且 `data.tool == "question"`
- **THEN** 系统触发 `question` 通知（兼容 SyncEvent 字段路径）

#### Scenario: message.updated 用户消息

- **WHEN** SSE 收到 `payload.type == "message.updated"` 且 `properties.info.role == "user"`
- **THEN** 系统触发 `user_message` 通知

#### Scenario: 容错处理

- **WHEN** 收到未知或不支持的事件类型
- **THEN** 系统 MUST NOT 崩溃，静默忽略

### Requirement: subagent 会话本地追踪

系统 MUST 通过 session.created/session.deleted 事件维护本地 subagent session ID 集合，不依赖 HTTP API。

#### Scenario: subagent 加入追溯

- **WHEN** SSE 收到 `session.created` 且 `data.info.parentID` 不为空
- **THEN** 该 sessionID 加入本地 `subagentSessionIds` 集合

#### Scenario: subagent 移出追溯

- **WHEN** SSE 收到 `session.deleted`
- **THEN** 该 sessionID 从 `subagentSessionIds` 移除

### Requirement: 事件去重

系统 SHALL 避免同一事件被处理两次。

#### Scenario: eventID 去重

- **WHEN** 同一 `payload.id` 的 BusEvent 和 SyncEvent 先后到达
- **THEN** 以先到为准处理，后到者通过 `payload.id` 缓存忽略

#### Scenario: LRU 缓存容量

- **WHEN** 去重缓存条目超过上限（建议 1000 条）
- **THEN** 淘汰最久未使用的条目

#### Scenario: session.status idle debounce

- **WHEN** 收到 idle 事件
- **THEN** 启动 350ms 定时器，同 sessionID 的新 idle 事件重置定时器，定时器到期才触发通知
