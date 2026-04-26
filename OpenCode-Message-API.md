# OpenCode Message API

## POST /session/:id/message

发送消息到指定 session，等待 AI 回复。

**注意**：`sessionID` 是 URL 路径参数，其余参数为请求 body（JSON 格式）。

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `parts` | Part[] | ✅ | 消息内容 |
| `sessionID` | string | ✅ | Session ID（URL 参数） |
| `messageID` | string | ❌ | 续接指定消息 |
| `model` | `{providerID, modelID}` | ❌ | 指定模型 |
| `agent` | string | ❌ | 指定 agent |
| `noReply` | boolean | ❌ | `true` = 不等回复 |
| `format` | Format | ❌ | 结构化输出格式 |
| `system` | string | ❌ | 系统提示词 |
| `variant` | string | ❌ | 变体 |

### Parts 类型

**TextPartInput（文本）**：
```json
{
  "type": "text",
  "text": "消息内容"
}
```

**FilePartInput（文件）**：
```json
{
  "type": "file",
  "mime": "text/markdown",
  "filename": "README.md",
  "url": "file:///path/to/README.md"
}
```

**AgentPartInput（Agent）**：
```json
{
  "type": "agent",
  "name": "agent-name"
}
```

**SubtaskPartInput（子任务）**：
```json
{
  "type": "subtask",
  "prompt": "子任务内容"
}
```

### 调用示例

**基础发送（等待 AI 回复）**：
- 效果等同于用户在 Web UI / TUI 输入框输入文本并发送

```bash
curl -X POST "http://127.0.0.1:4096/session/$SESSION_ID/message" \
  -H "Content-Type: application/json" \
  -d '{"parts": [{"type": "text", "text": "你好"}]}'
```

**实际调用示例（联网查询天气）**：
```bash
curl -X POST "http://127.0.0.1:4096/session/ses_23854e695ffek5wcaadBFbCc6j/message" \
  -H "Content-Type: application/json" \
  -d '{"parts": [{"type": "text", "text": "联网查询今天成都的天气"}]}'
```

**返回示例**：
```json
{
  "info": {
    "role": "assistant",
    "id": "msg_dc84dff92001sGj1KEsv4ETpVE",
    "sessionID": "ses_23854e695ffek5wcaadBFbCc6j"
  },
  "parts": [
    {"type": "step-start"},
    {"type": "reasoning", "text": "根据搜索结果..."},
    {
      "type": "text",
      "text": "**成都今天（4月26日）天气：**\n\n- 🌥️ 阴天间多云，有分散阵雨或雷雨\n- 🌡️ 气温：17℃ ~ 27℃\n- 💨 风力：午后有4~6级偏北阵风\n- ⚠️ 提示：局地大雨"
    },
    {"type": "step-finish"}
  ]
}
```

**不等待回复**：
```bash
curl -X POST "http://127.0.0.1:4096/session/$SESSION_ID/message" \
  -H "Content-Type: application/json" \
  -d '{"parts": [{"type": "text", "text": "你好"}], "noReply": true}'
```

**发送文件**：
```bash
curl -X POST "http://127.0.0.1:4096/session/$SESSION_ID/message" \
  -H "Content-Type: application/json" \
  -d '{
    "parts": [
      {"type": "file", "mime": "text/markdown", "filename": "README.md", "url": "file:///path/to/README.md"}
    ]
  }'
```

**结构化输出**：
```bash
curl -X POST "http://127.0.0.1:4096/session/$SESSION_ID/message" \
  -H "Content-Type: application/json" \
  -d '{
    "parts": [{"type": "text", "text": "查询天气"}],
    "format": {
      "type": "json_schema",
      "schema": {
        "type": "object",
        "properties": {
          "city": {"type": "string"},
          "temperature": {"type": "number"}
        }
      }
    }
  }'
```

### 相关文件

| 文件 | 说明 |
|------|------|
| `/packages/opencode/src/server/routes/instance/session.ts` | API 路由定义 |
| `/packages/opencode/src/session/prompt.ts` | PromptInput 类型 |
| `/packages/opencode/src/session/message-v2.ts` | Part 类型定义 |
