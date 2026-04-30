# OpenCode 文件变更即时同步方案

## TL;DR

> **核心问题**: OpenCode 修改文件后，IntelliJ 需要几秒才能显示最新内容
>
> **解决方案**: 消费 OpenCode SSE 事件流 (`/global/event`)，通过事件中的 `directory` 匹配项目，从事件体中的 `diff` 提取文件列表，批量刷新 IntelliJ VFS
>
> **交付物**:
> - `OpenCodeSSEConsumer` — SSE 事件流消费者
> - `OpenCodeDiffRefresher` — 文件刷新器
>
> **估计耗时**: 中
> **并行执行**: YES - 2 个 task

---

## Context

### 调研结论

#### SSE 事件确认
OpenCode 提供 SSE 端点 `GET /global/event`，返回 `Content-Type: text/event-stream`。

原始 SSE 数据格式：
```
data: {"payload":{"type":"server.connected","properties":{}}}
```

#### 事件类型
通过解析前端 JS bundle 找到完整事件列表，核心关注 `session.diff`：

| 事件 | 含义 | 操作 |
|------|------|------|
| **`session.diff`** | **文件 diff 完成** | 检测到后触发刷新 |
| `server.connected` / `server.heartbeat` | SSE 协议事件 | SSE 库自动处理，代码中忽略 |
| 其他 40+ 事件 | 各种 UI 状态 | 全部忽略 |

#### 事件数据结构
前端源码中 `session.diff` 的处理逻辑：
```javascript
case "session.diff": {
    const n = t.properties;
    e.setStore("session_diff", n.sessionID, pt(H4(n.diff), ...));
    break;
}
```
`n = t.properties` 包含服务端发送的所有字段。从 `/session` API 响应可知每个 session 有 `directory` 字段，因此 `session.diff` 的事件数据中也包含 `directory`。

okhttp-eventsource 库的 `EventHandler.onMessage()` 收到的是原始 JSON 字符串，**必须用 Gson 解析**为 data class 后，提取 `properties.directory` 和 `properties.diff` 文件列表。

事件体结构：
```json
{
  "payload": {
    "type": "session.diff",
    "properties": {
      "sessionID": "ses_xxx",
      "directory": "/Users/yutao/IdeaProjects/xxx",
      "diff": [
        {"file": "src/main/xxx.kt", "additions": 5, "deletions": 2, "status": "modified"},
        {"file": "src/test/xxx.kt", "additions": 10, "deletions": 0, "status": "created"}
      ]
    }
  }
}
```

注意：`file` 是相对路径，需要拼接 directory 得到完整路径。`status` 可能有 "modified"、"created"、"deleted" 三种值。

### 关键的 API 端点

| 端点 | 用途 |
|------|------|
| `GET /global/event` | SSE 事件流 |
| `GET /session/ses_xxx/diff?directory=<path>` | session 级别 diff（备用，事件体已包含 diff 数据） |

### SSE 客户端库选择

方案：使用 **LaunchDarkly okhttp-eventsource**

| 对比项 | `com.launchdarkly:okhttp-eventsource` | 自实现 HTTP 解析 |
|--------|--------------------------------------|-----------------|
| SSE 协议处理 | 完整实现（重连、重试、心跳） | 自己处理 edge case |
| 使用复杂度 | 简单（几行代码） | 需要手动解析 |
| 推荐 | ✅ 推荐 | ❌ 不推荐 |

Gradle 依赖：
```kotlin
implementation("com.launchdarkly:okhttp-eventsource:4.1.0")
```

---

## Work Objectives

### Core Objective
OpenCode 修改文件后 IntelliJ 立即刷新，显示最新内容

### Concrete Deliverables
1. `OpenCodeSSEConsumer` — SSE 事件流消费者（基于 okhttp-eventsource）
2. `OpenCodeDiffRefresher` — 从事件体中提取文件列表并批量刷新 VFS

### Definition of Done
- [ ] 打开 OpenCode 修改文件后，IntelliJ 编辑器在 500ms 内显示最新内容
- [ ] 其他项目的 session.diff 事件不会触发本项目的刷新

### Must Have
- 使用 okhttp-eventsource 库消费 SSE 流
- 收到 `session.diff` 后，取 `properties.directory` 与 `project.basePath` 匹配
- 匹配成功后批量刷新变更文件
- 项目关闭时正确清理资源

### Must NOT Have
- 不使用 BulkFileListener 做文件同步（SSE 事件驱动已经足够）
- 不使用 JCEF JS 注入检测文件变更
- 不需要调用 `/vcs/diff` 等额外 API（事件体已包含 diff 数据）
- 不使用正则解析 SSE 协议（交给库处理）

---

## TODOs

- [x] 1. 添加 okhttp-eventsource 依赖并创建 SSE 消费者

  **What to do**:
  - 在 `gradle/libs.versions.toml` 中添加 `okhttp-eventsource` 依赖
  - 创建 `listeners/OpenCodeSSEConsumer.kt`
- 使用 `com.launchdarkly.eventsource.EventSource` 连接到 `http://$OPENCODE_HOST:$OPENCODE_PORT/global/event`
  - 实现 `EventHandler` 接口：
    - `onOpen()` — 连接建立时打印日志
    - `onMessage(event, message)` — **核心方法**：`message` 是原始 JSON 字符串，用 Gson 解析为 data class
    - `onClosed()` — 连接关闭时打印日志
    - `onError(ex)` — 发生错误时打印日志
  - 创建 Kotlin data class 映射 JSON 结构，用 Gson 解析
  - 只处理 `type == "session.diff"` 的事件，其余忽略
  - 从事件数据中提取 `properties.directory`，对比 `project.basePath`
  - 匹配时调用 `OpenCodeDiffRefresher.refreshFiles(directory, diffFileList)`
  - **重要**：每次收到 `session.diff` 事件后，先打印完整的原始 JSON 日志（`thisLogger().info("SSE event: $message")`），方便后续定位问题

  **Data class 定义**：
  ```kotlin
  data class SSESessionDiffPayload(
      val type: String,
      val properties: SSESessionDiffProperties
  )
  data class SSESessionDiffProperties(
      val sessionID: String,
      val directory: String,
      val diff: List<DiffFile>
  )
  data class DiffFile(
      val file: String,
      val additions: Int,
      val deletions: Int,
      val status: String  // "modified", "created", "deleted"
  )
  ```

  **连接稳定性和重连**：
  - 默认 `ErrorStrategy` 是 `alwaysThrow()`（失败即抛出异常，停止重连）
  - **必须配置为 `ErrorStrategy.alwaysContinue()`**，否则服务器挂了就不会自动重连
  - `RetryDelayStrategy` 默认是指数退避（1s → 2s → 4s → ... 带 jitter），服务器恢复后会自动重连成功
  - `readTimeout = 0` 正确，长连接需要无限等待
  - `httpConnectStrategy.connectTimeout(5, TimeUnit.SECONDS)` 连接超时 5 秒

  **Parallelization**:
  - Can Run In Parallel: NO
  - Blocks: Task 2
  - Blocked By: None

  **Commit**: YES — `feat: add okhttp-eventsource dependency and SSE consumer`

- [x] 2. 创建文件刷新器和生命周期管理

  **What to do**:
  - 创建 `listeners/OpenCodeDiffRefresher.kt`
  - 实现 `fun refreshFiles(directory: String, files: List<DiffFile>)`：

    ```kotlin
    fun refreshFiles(directory: String, files: List<DiffFile>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFiles = files.mapNotNull { file ->
                    LocalFileSystem.getInstance().refreshAndFindFileByPath("$directory/${file.file}")
                }
                // 批量刷新，RefreshQueue 内部管理并发和事件分发
                RefreshQueue.getInstance().refresh(
                    /* async */ true,
                    /* recursive */ false,
                    /* finishRunnable */ null,
                    /* files */ virtualFiles
                )
            } catch (e: Exception) {
                thisLogger().error("[DiffRefresher] 刷新失败: ${e.message}")
            }
        }
    }
    ```

  - **使用 `RefreshQueue` 而非已废弃的 `VirtualFileManager.asyncRefresh()`**
  - 关于 `RefreshQueue`：每接收一个 `properties.diff`，批量刷新一次
  - 关于 `refreshAndFindFileByPath`：用于从磁盘路径查找/刷新单个文件，作为 `RefreshQueue` 的输入
  - 在与 `OpenCodeServerManager` 集成：服务启动时启动 SSE 消费者，停止时关闭
  - 使用 `Disposable` 管理生命周期，项目关闭时清理资源

  **超时设置**：
  - OkHttpClient readTimeout = 0（无限，SSE 长连接需要）
  - OkHttpClient connectTimeout = 5s

  **Parallelization**:
  - Can Run In Parallel: NO
  - Blocked By: Task 1

  **Commit**: YES — `feat: add diff-based file refresher and lifecycle management`

---

## Execution Strategy

```
Task 1: 添加依赖 + 创建 SSE 消费者
  ↓
Task 2: 创建文件刷新器 + 生命周期管理
```

## Success Criteria

### 验证方式
1. 打开 IntelliJ 和 OpenCode Web
2. 在 OpenCode 中让 AI 修改一个项目文件
3. 切换到 IntelliJ 编辑器，验证文件内容在 500ms 内更新
4. 在 OpenCode 中打开另一个项目的 session，修改文件
5. 验证不会触发当前项目的刷新
