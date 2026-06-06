# Critical Review: ide-native-notifications(代码同步版)

> **状态说明**:本文档保留 2026-05-31 当时的 plan 审核结果,并增加「实际落地 vs plan 差异」段以反映代码现状。

## Verdict (原 plan 审核)

**VERDICT**: ✅ PASS(2026-05-31 当时对 plan 的结论)

**Summary**: 经过两轮 Metis + Oracle 审核 + Momus 最终门禁审查,全部阻塞项已修复。Plan 结构完整、spec 覆盖充分、引用有效。Momus 判定 **OKAY**。

## 1. 修改历史(原 plan 审核)

| 轮次      | 审核方                 | 结论                            | 已修复      |
| --------- | ---------------------- | ------------------------------- | ----------- |
| 第一轮    | Metis + Oracle         | B1-B3 blocking + W1-W6 warnings | ✅ 全部修复 |
| 第二轮    | Metis + Oracle         | 9/9 确认修复 + 3 个次要问题     | ✅ 全部修复 |
| Plan 审核 | Oracle + Metis + Momus | Momus ✅ OKAY                   | ✅ 可实施   |

## 2. 原 Plan 审核结果(2026-05-31 当时)

| 审核方 | 结论        | 要点                                                        |
| ------ | ----------- | ----------------------------------------------------------- |
| Oracle | 🟡 有改进项 | Verification Strategy 和 Success Criteria 有质量瑕疵,不阻塞 |
| Metis  | 🟡 有改进项 | 任务颗粒度合理、Wave 结构正确                               |
| Momus  | ✅ OKAY     | 无 blocking issues,引用有效,FVW 覆盖完整                    |

## 3. 实施就绪清单(原 plan)

- [x] proposal.md — 变更范围和动机
- [x] design.md — 架构设计、决策矩阵、Research
- [x] specs/ — 3 个规格文件,WHEN/THEN 场景覆盖
- [x] research/ — 4 个调研主题
- [x] tasks.md — 5 Wave × 11 个任务
- [x] critic.md — 三级审核通过

**当时结论:可进入实施阶段。**

## 4. 实际落地 vs 原 plan 审核结论(2026-06-06 现状)

> **本节为代码同步版新增,反映 2026-06-06 实际代码状态**

| 维度                    | 原 plan 期望                                   | 实际代码                                                | 偏离程度  |
| ----------------------- | ---------------------------------------------- | ------------------------------------------------------- | --------- |
| **Wave 0**(前置验证)    | SSE 事件格式 + HTTP API 实测                   | ✅ 实施(但 `session.next.tool.called` 未实测)           | 🟡 小偏离 |
| **Wave 1**(SSE 监听)    | 完整 SyncEvent 解析 + 11 事件分发              | 🟡 Direct BusEvent 完整 + 4 事件分发,SyncEvent 未实施   | 🟠 中偏离 |
| **Wave 2**(通知核心)    | Router + Service + Config 三件套               | ✅ 实施(但 Router 简化为 10 行委托,Config 只 4 setting) | 🟠 中偏离 |
| **Wave 3**(Settings UI) | OpenCodeConfigurable + applicationConfigurable | ❌ **未实施**                                           | 🔴 大偏离 |
| **Wave 4**(集成验证)    | 端到端集成 + 构建验证                          | 🟡 单元测试 4 文件 / 29 用例,无端到端,无构建验证        | 🟠 中偏离 |

**总体实施完成度**:约 **45-55%**(`tasks.md` 中 11 个 task,完全完成 0 个,部分完成 5 个,未实施 4 个,加 Wave 4.2 待运行)

**最严重的偏离**:

1. 🔴 **Settings UI 整 Wave 未实施** — 11 事件开关和 11 消息模板随之未实施
2. 🟠 **`displayType` 从 `BALLOON` 改为 `TOOL_WINDOW`** — 带来 UX 副作用(通知停留时间极短)
3. 🟠 **多项目路由简化为 10 行委托** — 与 plan 设计差距大,但**实际满足需求**(per-project SSE Consumer)
4. 🟠 **subagent 检测改用 title 正则** — 实现简单,但引入对 opencode 服务端 title 格式的强依赖

**已落地的设计价值**:

- ✅ `OpenCodeNotificationService` 通知发送核心(5 层守卫)完整可用
- ✅ `SessionInfoCache` 30s TTL 解决 SSE 线程同步 HTTP 阻塞
- ✅ `handleSessionIdle()` 三层去重(title 正则 + per-session Set + message.updated 重置)
- ✅ `OpenCodeApi.getSession()` HTTP API 集成
- ✅ 4 类通知事件(permission / complete / question) + 预留 error 已能端到端工作

## 5. 后续变更建议(本 critic 提交时)

> **非本 change 范围**,列出供后续独立 change 参考

1. **新 change:补全 Settings UI** — 实施 `OpenCodeConfigurable` + `applicationConfigurable` 注册 + 11 事件开关 + 11 消息模板
2. **新 change:通知停留时间修复** — `TOOL_WINDOW` 改为 `STICKY_BALLOON` 或 `BALLOON` + `setSuggestionType(true)`,解决 UX 问题
3. **新 change:SyncEvent V2 解析** — 实测 `session.next.tool.called` 是否在 SSE 流,实施后开启 `plan_exit` 通知
4. **新 change:补全通知事件** — error / user_cancelled / subagent_complete / session_started / user_message / client_connected

## 6. SPEC.md GAP-8 修正

原 SPEC.md 附录 A 记录:

> **GAP-8**: `ide-native-notifications` change 0/11 tasks 待实施

**本 critic 同步后,GAP-8 应更新为**:

> **GAP-8**(已修正): `ide-native-notifications` change 部分实施 — Wave 0-2 部分完成,Wave 3-4 未实施;4 类通知事件(permission / complete / question)端到端可用,其余 7 类通知 + Settings UI + 集成测试未实施;`displayType` 偏离 plan(实际 `TOOL_WINDOW`,plan `BALLOON`)。详见 `openspec/changes/ide-native-notifications/proposal.md` 和 `tasks.md`。
