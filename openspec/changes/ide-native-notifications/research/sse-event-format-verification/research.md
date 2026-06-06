# SSE 事件格式实测验证(代码同步版)

> **状态说明**:本文件是 2026-05-15 调研结果,SSE 事件格式实测数据仍然有效。后续落地时**未实施 SyncEvent V2 解析**,只支持 Direct BusEvent 新 wire 格式。

## 方法

通过 curl 连接 OpenCode 服务的 `/global/event` SSE 端点,在正常使用 IDE 插件过程中实时捕获事件流。

## 命令

```bash
curl -sN --max-time 15 "http://127.0.0.1:12396/global/event"
```

## 事件格式(原实测,仍然有效)

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

### SyncEvent(V2)

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

### 额外验证(新建 session + 发送消息)

通过 HTTP API 新建 session 并发送消息后捕获 SSE 流:

**已验证的事件类型:**
| 事件类型 | 格式 | 说明 |
|---|---|---|
| `server.connected` | Direct | SSE 连接建立时立即发送 |
| `server.heartbeat` | Direct | 每 10 秒保活 |
| `message.updated` | Direct | `properties.info.role == "user"` 时可用于 `user_message` 通知 |
| `message.part.updated` | Direct | 包含 step-start/reasoning/text 等类型,已处理 |
| `message.part.delta` | Direct | 流式文本增量,已处理 |
| `session.next.agent.switched` | Direct + Sync | 代理切换事件 |
| `session.next.model.switched` | Direct + Sync | 模型切换事件 |
| `session.updated` | Sync | 会话更新 |
| `project.updated` | Direct | 项目更新 |

**SSE event 名称**:所有事件使用 `event: message`(通过 okhttp-eventsource 收到时名为 "message")。

**未实测的事件(待触发)**:
| 事件类型 | 预期格式 | 如何触发 |
|---|---|---|
| `permission.asked` | Direct | 让 AI 执行需要权限的操作 |
| `session.status(idle)` | Direct | 等待会话完成 |
| `session.error` | Direct | 触发 AI 错误 |
| `session.idle` | Direct | 同上(旧格式) |
| `session.created` | Sync | 创建新 session |
| `session.next.tool.called` | Direct + Sync | 触发 AI 调用 question/plan_exit 工具 |
| `question.asked` | Direct | 同上 |

## 实际落地时使用的 wire 格式

> **本节为代码同步版新增**

**SSE Consumer 实际连接 URL**:

```kotlin
val uri = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/event?directory=" + URLEncoder.encode(normalizedPath, "UTF-8"))
```

注意 URL 与原调研的 `/global/event` **不同**——实际使用 `/event?directory=<encoded>`(per-project 端点)。

**SSE 事件实际格式**(从代码反推 + 实际抓包):

- 事件流是 `{id, type, properties}` 直出,无 `{directory, project, payload}` 外层包装
- `properties` 内嵌套 `info` 对象(部分事件,如 `message.updated`)
- 示例(简化):
  ```json
  {
    "id": "evt_xxx",
    "type": "session.idle",
    "properties": {
      "sessionID": "ses_xxx"
    }
  }
  ```
- 示例(message.updated 嵌套):
  ```json
  {
    "id": "evt_xxx",
    "type": "message.updated",
    "properties": {
      "sessionID": "ses_xxx",
      "info": { "role": "user", "id": "msg_xxx" }
    }
  }
  ```

## SSEEventParser 实际白名单(`SSEEventParser.kt:74-90`)

```kotlin
private val ALLOW_PARSE_EVENT_TYPES = setOf(
    // 4 通知
    "session.idle",
    "session.status",
    "permission.asked",
    "question.asked",
    // 6 业务
    "session.created",
    "session.updated",
    "message.updated",
    "message.part.updated",
    "file.edited",
    "file.watcher.updated",
    "session.diff",
    // 1 健康
    "server.heartbeat",
)
```

## 实际落地偏离

| 调研项                                             | 实际落地                                                 | 状态            |
| -------------------------------------------------- | -------------------------------------------------------- | --------------- |
| `payload.type` 路径                                | **新 wire 格式无 `payload` 外层**,使用 root `type` 字段  | 🟠 格式变更     |
| `properties.info.sessionID` fallback               | ✅ 已实施(SSEEventParser.extractSessionID 3 级 fallback) | ✅ 符合         |
| `payload.id` 去重                                  | **改用 root `id` 字段**(无 `payload` 外层)               | 🟠 字段路径变更 |
| SyncEvent V2 (`payload.syncEvent.*`)               | **未实施**                                               | ❌              |
| `session.next.tool.called` 检测 question/plan_exit | **未实施**,改用 `question.asked` BusEvent                | 🟠 降级方案     |
| 全 12 个白名单事件                                 | **已实施**(白名单就是 12 个事件)                         | ✅ 符合         |
| LRU 1000 eventID 去重                              | **已实施**                                               | ✅ 符合         |

## 结论

- 原调研中 SSE 流工作正常的结论仍然成立
- 实际落地采用了**新 wire 格式**(每项目独立 `/event?directory=...` 端点,直出 `{id, type, properties}`)
- SyncEvent V2 解析**未实施**,仅 Direct BusEvent 工作
- `session.next.tool.called` 是否在 SSE 流中**仍未实测**(且当前不依赖该事件)
