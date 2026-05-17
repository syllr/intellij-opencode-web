# SSE 事件格式实测验证

## 方法

通过 curl 连接 OpenCode 服务的 `/global/event` SSE 端点，在正常使用 IDE 插件过程中实时捕获事件流。

## 命令

```bash
curl -sN --max-time 15 "http://127.0.0.1:12396/global/event"
```

## 事件格式

### Direct BusEvent

```json
{
  "directory": "/Users/yutao/IdeaProjects/...",
  "project": "hash",
  "payload": {
    "id": "evt_e208271300012MZGbu2gNc47xU",
    "type": "server.connected",
    "properties": {}
  }
}
```

### SyncEvent（V2）

```json
{
  "directory": "/Users/yutao/IdeaProjects/...",
  "project": "hash",
  "payload": {
    "type": "sync",
    "syncEvent": {
      "type": "session.next.agent.switched.1",
      "id": "evt_xxx",
      "seq": 0,
      "aggregateID": "ses_xxx",
      "data": { "sessionID": "ses_xxx", "agent": "...", "timestamp": "..." }
    },
    "id": "evt_xxx"
  }
}
```

### 额外验证（新建 session + 发送消息）

通过 HTTP API 新建 session 并发送消息后捕获 SSE 流：

**已验证的事件类型：**
| 事件类型 | 格式 | 说明 |
|---|---|---|
| `server.connected` | Direct | SSE 连接建立时立即发送 |
| `server.heartbeat` | Direct | 每 10 秒保活 |
| `message.updated` | Direct | `properties.info.role == "user"` 时可用于 `user_message` 通知 |
| `message.part.updated` | Direct | 包含 step-start/reasoning/text 等类型，已处理 |
| `message.part.delta` | Direct | 流式文本增量，已处理 |
| `session.next.agent.switched` | Direct + Sync | 代理切换事件 |
| `session.next.model.switched` | Direct + Sync | 模型切换事件 |
| `session.updated` | Sync | 会话更新 |
| `project.updated` | Direct | 项目更新 |

**SSE event 名称**：所有事件使用 `event: message`（通过 okhttp-eventsource 收到时名为 "message"）。

**未实测的事件（待触发）**：
| 事件类型 | 预期格式 | 如何触发 |
|---|---|---|
| `permission.asked` | Direct | 让 AI 执行需要权限的操作 |
| `session.status(idle)` | Direct | 等待会话完成 |
| `session.error` | Direct | 触发 AI 错误 |
| `session.idle` | Direct | 同上（旧格式） |
| `session.created` | Sync | 创建新 session |
| `session.next.tool.called` | Direct + Sync | 触发 AI 调用 question/plan_exit 工具 |
| `question.asked` | Direct | 同上 |

## 结论

- SSE 流工作正常，事件格式已确认
- 所有通知事件理论上都在 SSE 流中（都通过 GlobalBus 桥接）
- P0 风险：`session.next.tool.called` 需实测验证
