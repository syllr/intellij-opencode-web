# 抑制 message.part.delta 的刷屏日志

## TL;DR

> **Quick Summary**: 去除 `OpenCodeSSEConsumer.kt` 中 `message.part.delta` 事件的 INFO 级别日志，这些事件在 AI 输出时每字符触发一次，严重刷屏
>
> **Deliverables**:
>
> - `listeners/OpenCodeSSEConsumer.kt` 一行改动
>
> **Estimated Effort**: Quick
> **Parallel Execution**: N/A - 单文件微调

---

## TODOs

- [x] 1. 在 `OpenCodeSSEConsumer.kt` 的 `onMessage` 中，解析完事件后立即判断 `payloadType`，如果是 `message.part.delta` 则跳过所有 INFO 日志（只处理文件相关事件），直接 return

  **What to do**:
  - 把当前第 63-64 行的 `logger.info("RAW SSE event:...")` 整行删除
  - 在第 78 行 `logger.info("Event: type=...")` 之前插入一段 `message.part.delta` 的早期 return

  **Must NOT do**:
  - 不要删除其他事件的日志
  - 不要影响 `fileProperty != null` 的检测（文件变更事件可能以 delta 形式发出）

  **Recommended Agent Profile**:
  - Category: `quick`

  **Parallelization**:
  - Can Run In Parallel: N/A

  **Acceptance Criteria**:
  - [ ] `./gradlew compileKotlin` → BUILD SUCCESSFUL
  - [ ] `message.part.delta` 事件不再输出 `RAW SSE event` 和 `Event: type=` 日志行

  **QA Scenarios**:

  ```
  Scenario: verify delta logs suppressed
    Tool: grep
    Preconditions: plugin built and deployed
    Steps:
      1. 用 opencode AI 产生一些输出，触发 message.part.delta 事件
      2. grep "message.part.delta" idea.log | wc -l
    Expected Result: 只有 SSE 原始消息内容中出现 message.part.delta（作为事件数据），
      没有 `[OpenCodeSSEConsumer] Event: type='message.part.delta'` 这样的日志行
    Evidence: grep 结果截图
  ```

  **Commit**: YES
  - Message: `fix: suppress verbose message.part.delta SSE event logging`
  - Files: `src/main/kotlin/.../listeners/OpenCodeSSEConsumer.kt`
