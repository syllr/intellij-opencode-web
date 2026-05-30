# Plan: notification-noise-reduction

## TL;DR

消除 agent 循环中父 session 多次 idle 触发的重复 complete 通知噪音，同时修复 `session.deleted` 时序竞态导致的子 agent idle 误判。仅首次 complete 通知、用户发新消息后重置，permission/question 等原则 2 通知不受影响。

## Context

当前 `OpenCodeSSEConsumer.kt` 中的 `handleSessionIdle()` 在父 session 每次 idle 时都发送 complete 通知（默认开），而 agent 循环中多次 task() 调用会产生多次 idle。此外 `session.deleted` 在 idle 之前到达时清除了 `subagentSessionIds` 追踪，导致子 agent idle 被误判为 complete。

## Work Objectives

- **idle-notification-suppression**: 父 session 的 complete 通知抑制，仅在用户发消息后首次 idle 通知，agent 循环中间 idle 静默。重置信号为 `message.updated(role=user)` 而非 `session.status(busy)`
- **subagent-complete-detection-fix**: 移除 `session.deleted` 中的 `subagentSessionIds.remove`，消除时序竞态

## Verification Strategy

- 编译验证：`./gradlew buildPlugin`
- 运行时验证 4 个场景：首次完成通知、agent 循环抑制、新消息重置、子 agent 识别

## Execution Strategy

- Step 1: 删除 `session.deleted` 中 `subagentSessionIds.remove(sid)`（1 行改动）
- Step 2: 新增 `sessionIdleFired` 集合 + `handleSessionIdle` 注入抑制 + `message.updated` 重置 + `onClosed` 清理
- 先做 Wave 0 研究（curl 实测 SSE 事件结构），再实现代码

## Tasks

### Wave 0: 前置验证（Research）

- [ ] 0.1 实测 `message.updated(role=user)` 事件的 JSON 结构
      **What to do**: curl SSE 端点，观察 message.updated 事件中 sessionID 字段位置
      **Must NOT do**: 不依赖猜测的 JSON 路径
      **References**: `SSEEventParser.kt:82-128`, `OpenCodeSSEConsumer.kt:86-150`
      **Acceptance Criteria**: 确认 sessionID 在 `payload.properties.sessionID`

- [ ] 0.2 验证 agent 循环中 session.status(idle/busy) 发送行为
      **What to do**: 触发 multi-task agent 循环，观察父 session 在 task() 期间的事件序列
      **Must NOT do**: 不假设 OpenCode 行为
      **Acceptance Criteria**: 确认事件序列

### Wave 1: 竞态修复

- [ ] 1.1 删除 `session.deleted` 中的 `subagentSessionIds.remove(sid)`
      **What to do**: 打开 `OpenCodeSSEConsumer.kt:103-109`，删除 remove 行
      **Must NOT do**: 不删除日志行，不修改 onClosed
      **References**: `OpenCodeSSEConsumer.kt:103-109`
      **Acceptance Criteria**: 无 remove 调用

### Wave 2: 核心降噪

- [ ] 2.1 新增 `sessionIdleFired` 集合和相关状态管理
      **What to do**: companion object 新增集合 + message.updated 重置 + onClosed 清理
      **Must NOT do**: 不修改已有事件路由
      **References**: `OpenCodeSSEConsumer.kt:23-35,141-149,209-214`
      **Acceptance Criteria**: 编译通过

- [ ] 2.2 在 `handleSessionIdle` 中注入抑制逻辑
      **What to do**: 在现有 handleSessionIdle 方法中插入 sessionIdleFired 检查，保留 idleLastFired
      **Must NOT do**: 不重写方法体，不删除 idleLastFired
      **References**: `OpenCodeSSEConsumer.kt:191-207`
      **Acceptance Criteria**: 父 session 首次 idle 正常通知、后续抑制

### Wave 3: 日志

- [ ] 3.1 为 `sessionIdleFired` 操作添加调试日志
      **What to do**: 在抑制、dispatch、重置、onClosed 处加 logger.debug
      **Must NOT do**: 不使用 logger.info
      **References**: `OpenCodeSSEConsumer.kt:97-98`
      **Acceptance Criteria**: 日志输出到 build/idea-sandbox/

### Wave 4: 验证

- [ ] 4.1 编译验证
      **What to do**: `./gradlew buildPlugin`
      **Acceptance Criteria**: 编译成功

- [ ] 4.2 运行时行为验证
      **What to do**: 4 个验证场景（首次完成、agent 循环抑制、新消息重置、子 agent 识别）
      **Must NOT do**: 不修改源文件
      **Acceptance Criteria**: 全部 4 场景通过

### Wave 5: 文档

- [ ] 5.1 更新 AGENTS.md
      **What to do**: 如有 AGENTS.md，记录 sessionIdleFired 机制
      **Acceptance Criteria**: AGENTS.md 包含关键设计决策

## Final Verification Wave

- F1: 编译 `./gradlew buildPlugin` ✅
- F2: 4 个运行时验证场景
- F3: 日志确认（抑制日志、重置日志、首次通知日志）

## Commit Strategy

- Wave 1 + Wave 2 + Wave 3: 合并为一个 commit `feat: 实现 sessionIdleFired 抑制机制，消除 agent 循环中的重复 complete 通知`
- Wave 5: 独立 commit `docs: 更新 AGENTS.md 记录 sessionIdleFired 设计决策`
- Wave 0/4: 不 commit

## Success Criteria

- 用户发消息后仅首次 idle 有 complete 通知
- agent 循环中的中间 idle 无通知
- permission/question 通知正常发送
- SSE 重连后状态重置
