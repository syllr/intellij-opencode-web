## Tasks

### Wave 0: 前置验证（Research）

- [x] 0.1 实测 `message.updated(role=user)` 事件的 JSON 结构
      **What to do**:
  - 启动 OpenCode 服务端，在终端用 curl 连接 SSE 端点
    ```bash
    curl -N http://127.0.0.1:12396/global/event
    ```
  - 在 OpenCode Web UI 中发送一条用户消息，观察 curl 输出中所有 `message.updated` 事件
  - 确认 JSON 结构：
    - `message.updated` 是 Direct BusEvent 还是 SyncEvent V2？
    - `sessionID` 字段在什么位置？（期望：`payload.properties.sessionID`）
    - 如果不存在该字段，备选路径是什么？（如 `payload.properties.info.id`）
  - 同时验证 `session.created` 事件（SyncEvent V2）中 session ID 的值，与 `session.status(idle)` 事件（Direct BusEvent）中 session ID 的值是否一致——这关系到 `subagentSessionIds` 追踪能否正确匹配

  **Must NOT do**:
  - 不要依赖猜测的 JSON 路径，必须以实际 SSE 输出为准

  **References**:
  - `SSEEventParser.kt:82-128` — parse 函数中的 JSON 解析逻辑
  - `OpenCodeSSEConsumer.kt:86-150` — onMessage 中的事件路由
  - `OpenCodeSSEConsumer.kt:191-207` — handleSessionIdle 中的 sessionID 提取路径

  **Acceptance Criteria**:
  - 确认 `message.updated(role=user)` 事件中存在可读的 `sessionID` 字段，路径为 `payload.properties.sessionID`
  - 确认 `session.created` 中的 session ID 与 `session.status(idle)` 中的 session ID 对同一 session 值一致

  **Parallelization**: Can Run In Parallel: NO | Wave 0

- [x] 0.2 验证 `session.status(idle)` 与 `session.status(busy)` 在 agent 循环中的实际发送行为
      **What to do**:
  - 触发一个 multi-task agent 循环（例如在主 agent prompt 中让 AI 连续调用 3 次 `@librarian` 或 `@explore`）
  - 观察 curl SSE 流，记录父 session 在 task() 调用期间是否产生 `session.status(idle)` 和 `session.status(busy)` 事件
  - 关键验证：父 session 在 task() 等待期间是 idle 还是 busy？

  **Must NOT do**:
  - 不要假设 OpenCode 的行为，必须以实际抓取的 SSE 事件流为准

  **Acceptance Criteria**:
  - 确认父 session 在 agent 循环期间的事件序列

  **Parallelization**: Can Run In Parallel: NO | Wave 0

### Wave 1: 竞态修复（Step 1）

- [x] 1.1 删除 `session.deleted` 中的 `subagentSessionIds.remove(sid)`
      **What to do**:
  - 打开 `OpenCodeSSEConsumer.kt`
  - 找到 `session.deleted` 事件处理分支（第 103-109 行）
  - 删除 `subagentSessionIds.remove(sid)` 一行
  - 保留日志输出行
  - 确保 `subagentSessionIds` 只在 `onClosed()` 时清空

  **Must NOT do**:
  - 不要删除 `session.deleted` 的日志（第 106-108 行的 logger.debug）
  - 不要修改 `onClosed()` 中的 `subagentSessionIds.clear()` 逻辑
  - 不要引入新的清理逻辑（除非 Wave 2 中需要）

  **References**:
  - `OpenCodeSSEConsumer.kt:103-109`

  **Acceptance Criteria**:
  - `OpenCodeSSEConsumer.kt` 中不再存在 `subagentSessionIds.remove(sid)` 调用
  - 子 agent 完成时 `session.status(idle)` 事件不会再因时序竞态被误判为 `complete`

  **Parallelization**: Can Run In Parallel: YES | Parallel Group: Wave 1

### Wave 2: 核心降噪（Step 2）

- [x] 2.1 新增 `sessionIdleFired` 集合和相关状态管理
      **What to do**:
  - 在 `OpenCodeSSEConsumer.companion object` 中新增：
    ```kotlin
    private val sessionIdleFired = ConcurrentHashMap.newKeySet<String>()
    ```
  - 在 `onMessage()` 的 `session.status` 和 `session.idle` 分支：不动，它们都进入 `handleSessionIdle`
  - 在 `onMessage()` 的 `message.updated` 分支中，当 `role == "user"` 时增加重置逻辑：
    ```kotlin
    if (info?.get("role") == "user") {
        val sessionID = props?.get("sessionID") as? String
        if (sessionID != null) {
            sessionIdleFired.remove(sessionID)
        }
        dispatchNotification("user_message", parsedMap, eventDir)
    }
    ```
  - 在 `handleSessionIdle()` 中注入抑制逻辑（见 2.2）
  - 在 `onClosed()` 中增加：
    ```kotlin
    sessionIdleFired.clear()
    ```

  **Must NOT do**:
  - 不要修改 `message.updated` 中已有的 `dispatchNotification("user_message", ...)` 调用
  - 不要修改 `session.status` 中 `type == "idle"` 进入 `handleSessionIdle` 的逻辑
  - 不要引入新的事件路由或改变已有 `when` 分支结构

  **References**:
  - `OpenCodeSSEConsumer.kt:23-35` (companion object)
  - `OpenCodeSSEConsumer.kt:141-149` (message.updated 分支)
  - `OpenCodeSSEConsumer.kt:209-214` (onClosed)

  **Acceptance Criteria**:
  - `OpenCodeSSEConsumer.kt` 成功编译（`./gradlew buildPlugin`）
  - `sessionIdleFired` 仅在 `message.updated(role=user)` 时被 remove
  - `sessionIdleFired` 在 `onClosed()` 时被 clear

  **Parallelization**: Can Run In Parallel: NO | Blocked By: 0.1, 1.1

- [x] 2.2 在 `handleSessionIdle` 中注入抑制逻辑
      **What to do**:
  - 在现有 `handleSessionIdle()` 方法体中**插入** `sessionIdleFired` 抑制检查，保留 `idleLastFired` 短期去重
  - 修改后的完整逻辑流：

  ```kotlin
  private fun handleSessionIdle(parsedMap: Map<*, *>?, eventDir: String?) {
      val props = parsedMap?.get("payload") as? Map<*, *>
      val properties = props?.get("properties") as? Map<*, *>
      val sessionID = properties?.get("sessionID") as? String ?: return

      // [新增] 子 agent → subagent_complete（走配置开关，默认关）
      if (sessionID in subagentSessionIds) {
          dispatchNotification("subagent_complete", parsedMap, eventDir)
          return
      }

      // [新增] 父 session 抑制：已发过 complete 则跳过
      if (sessionID in sessionIdleFired) {
          logger.debug("[OpenCodeSSEConsumer] Suppressing repeated complete for session $sessionID")
          return
      }

      // ═══ 以下为现有逻辑（idleLastFired 2秒去重 + dispatch） ═══
      val eventType = "complete"
      val key = "$sessionID:$eventType"

      val now = System.currentTimeMillis()
      val last = idleLastFired.put(key, now)
      if (last != null && now - last < idleDedupWindowMs) {
          logger.debug("[OpenCodeSSEConsumer] Skipping duplicate idle notification for $key (${"$"}{now - last}ms ago)")
          return
      }

      // [修改] dispatch 后新增 sessionIdleFired 标记
      dispatchNotification("complete", parsedMap, eventDir)
      sessionIdleFired.add(sessionID)  // [新增] 标记已通知
  }
  ```

  - `idleLastFired` 的 2 秒短期去重保留不变，防止 `session.status(idle)` 和 `session.idle` 对同一 session 在 2 秒内双发
  - `sessionIdleFired` 做长期抑制，防止 agent 循环中的跨轮次重复通知
  - 注意：`session.idle`（旧格式）也调用此方法，自动享受抑制逻辑

  **Must NOT do**:
  - 不要重写整个 `handleSessionIdle` 方法——只在现有方法体中插入新增逻辑
  - 不要删除或修改 `idleLastFired` 的 2 秒去重逻辑
  - 不要改变 `subagent_complete` 的 dispatch 行为
  - 不要在 `session.status(busy)` 分支中添加任何重置逻辑（重置由 `message.updated` 独占）

  **References**:
  - `OpenCodeSSEConsumer.kt:191-207` (handleSessionIdle 当前实现)
  - `design.md` Decisions 章节 (reset signal 选择)

  **Acceptance Criteria**:
  - 父 session 首次 idle 正常发送 complete 通知
  - 父 session 第二次及后续 idle 在 `sessionIdleFired` 标记有效期内不发送通知
  - 子 agent idle 走 `subagent_complete` 路径（默认关），不受抑制影响

  **Parallelization**: Can Run In Parallel: NO | Blocked By: 2.1

### Wave 3: 日志和调试辅助

- [x] 3.1 为 `sessionIdleFired` 操作添加调试日志
      **What to do**:
  - 在 `handleSessionIdle` 中新增日志：
    - 抑制时：`logger.debug("[OpenCodeSSEConsumer] Suppressing repeated complete for session $sessionID")`
    - 首次 complete 时：`logger.debug("[OpenCodeSSEConsumer] First complete for session $sessionID, added to sessionIdleFired")`
  - 在 `message.updated` 分支中：
    - 重置时：`logger.debug("[OpenCodeSSEConsumer] Reset sessionIdleFired for session $sessionID on user message")`
  - 在 `onClosed()` 中：
    - 清空时：`logger.debug("[OpenCodeSSEConsumer] Cleared sessionIdleFired (${sessionIdleFired.size} entries)")`

  **Must NOT do**:
  - 不要使用 `logger.info`（使用 `logger.debug` 以匹配现有风格）
  - 不要将日志写入文件（使用现有 `thisLogger()`）

  **References**:
  - `OpenCodeSSEConsumer.kt:97-98` (现有日志风格)
  - `OpenCodeSSEConsumer.kt:106-108` (subagent 追踪日志)

  **Acceptance Criteria**:
  - 所有新增操作都有对应的 logger.debug 输出
  - 日志出现在 `build/idea-sandbox/IU-2026.1/log/` 中

  **Parallelization**: Can Run In Parallel: YES | Parallel Group: Wave 3

### Wave 4: 验证

- [x] 4.1 编译验证
      **What to do**:

  ```bash
  ./gradlew buildPlugin
  ```

  **Acceptance Criteria**:
  - 编译成功，无错误

  **Parallelization**: Can Run In Parallel: NO | Blocked By: 2.2, 3.1

- [x] 4.2 运行时行为验证（待 IDE 中手动执行 4 个场景）
      **What to do**:
  - 在 IDE 中运行插件（`./gradlew runIde`）
  - 验证场景 1（首次完成通知）：
    - 发一条用户消息 → AI 完成后 → 收到 1 条 complete 通知
    - 检查日志中出现 `First complete for session` + `added to sessionIdleFired`
  - 验证场景 2（agent 循环抑制）：
    - 发一条 prompt 让 AI 调用多个 tool（如 `@librarian` + `@explore`）
    - 确认只收到 1 条 complete 通知
    - 检查日志中出现 `Suppressing repeated complete for session`
  - 验证场景 3（新消息重置）：
    - 再发一条新消息 → AI 完成后 → 再次收到 1 条 complete 通知
    - 检查日志中出现 `Reset sessionIdleFired for session`
  - 验证场景 4（子 agent 仍走 subagent_complete）：
    - 观察日志，确认子 agent idle 输出 `dispatchNotification("subagent_complete", ...)`

  **Must NOT do**:
  - 不要删除或禁用 `@mohak34/opencode-notifier` 插件（这是独立步骤，用户自行决定）

  **Acceptance Criteria**:
  - 全部 4 个验证场景通过

  **Parallelization**: Can Run In Parallel: NO | Blocked By: 4.1

### Wave 5: 文档与关闭

- [x] 5.1 更新 AGENTS.md
      **What to do**:
  - 如有 AGENTS.md，在"ANTI-PATTERNS（THIS PROJECT）"或合适位置添加注解：
    - 记录 `handleSessionIdle` 中的抑制逻辑和 `sessionIdleFired` 集合
    - 记录 `session.deleted` 不移除追踪的设计决定

  **Acceptance Criteria**:
  - AGENTS.md 包含变更后的关键设计决策

  **Parallelization**: Can Run In Parallel: YES | Parallel Group: Wave 5
