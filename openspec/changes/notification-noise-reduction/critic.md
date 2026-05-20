# Critical Review: notification-noise-reduction

## Verdict

**VERDICT**: ⚠️ **CONDITIONAL** — 技术方案正确，但 tasks.md 的 OMO 格式存在字段缺失，实现前需补充。

**Summary**: 两个 spec 的 7 个 requirement 全部有对应 task 覆盖，design 的技术决策已在 task 中体现。核心争议点（Task 2.2 的 idleLastFired 保留问题）已在审查后修复。主要问题在于 tasks.md 缺少 QA Scenarios、Agent Profile、Evidence、Commit 四个标准字段。

---

## 1. Oracle Review — Content Split & Coverage

### Content Atomicity

| Task | Split Assessment | Issue                                                          |
| ---- | ---------------- | -------------------------------------------------------------- |
| 0.1  | ✅ 原子化        | 单个研究任务，目标明确                                         |
| 0.2  | ✅ 原子化        | 与 0.1 互补，独立可执行                                        |
| 1.1  | ✅ 原子化        | 一行删除，修改范围清晰                                         |
| 2.1  | ✅ 原子化        | 新增集合 + 三处注入(all in companion/message.updated/onClosed) |
| 2.2  | ✅ 原子化        | 修改单个方法体，职责单一                                       |
| 3.1  | ⚠️ 过细          | 日志代码已在 2.1/2.2 伪代码中体现，可合并                      |
| 4.1  | ✅ 原子化        | 编译验证                                                       |
| 4.2  | ✅ 原子化        | 运行时行为验证                                                 |
| 5.1  | ✅ 原子化        | 文档更新                                                       |

### Wave Grouping

Wave 0 到 Wave 5 的串行逻辑合理：研究 → 修复 → 降噪 → 日志 → 验证 → 文档。Wave 间依赖关系（Blocked By）表达正确。Wave 3（日志）有寄生性，其内容已在 Wave 2 的代码块中体现。

### Coverage Gaps

Spec 的所有 requirement 和 scenario 均有对应 task 覆盖。design.md 的 4 个核心决策均已体现。唯一遗漏：Task 0.1 如果发现 `message.updated` 中没有 `sessionID` 字段，降级路径（`sessionIdleFired.clear()`）没有对应的实现 task。

---

## 2. Oracle Review — Optimization Suggestions

### Dependency Analysis

| Task | Blocked By | Is Dependency Real? | Suggestion                                      |
| ---- | ---------- | ------------------- | ----------------------------------------------- |
| 2.1  | 0.1, 1.1   | ✅ 真实             | 需要 0.1 确认 sessionID 路径 + 1.1 完成竞态修复 |
| 2.2  | 2.1        | ✅ 真实             | 需要 sessionIdleFired 集合存在                  |
| 4.2  | 4.1        | ✅ 真实             | 需要编译通过                                    |

### Wave Restructuring

当前 6 个 Wave 对于 ~10 行代码的变更是偏多的。但作为可追溯的实现记录，粒度合理。

---

## 3. Metis Review — OMO Format Compliance

### Per-Task Field Completeness

| Task | What to do | Must NOT do | Agent Profile | References | Acceptance Criteria | QA Scenarios | Parallelization | Evidence | Commit |
| ---- | ---------- | ----------- | ------------- | ---------- | ------------------- | ------------ | --------------- | -------- | ------ |
| 0.1  | ✅         | ✅          | ❌            | ✅         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 0.2  | ✅         | ✅          | ❌            | ❌         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 1.1  | ✅         | ✅          | ❌            | ✅         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 2.1  | ✅         | ✅          | ❌            | ✅         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 2.2  | ✅         | ✅          | ❌            | ✅         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 3.1  | ✅         | ✅          | ❌            | ✅         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 4.1  | ✅         | ❌          | ❌            | ❌         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 4.2  | ✅         | ✅          | ❌            | ❌         | ✅                  | ❌           | ✅              | ❌       | ❌     |
| 5.1  | ✅         | ❌          | ❌            | ❌         | ✅                  | ❌           | ✅              | ❌       | ❌     |

### Key Format Gaps

- **QA Scenarios**: 全部 9 个 task 缺失（需 Tool/Preconditions/Steps/Expected/Evidence 五要素 + 快乐路径/失败路径）
- **Recommended Agent Profile**: 全部 9 个 task 缺失
- **Evidence**: 全部 9 个 task 缺失
- **Commit**: 全部 9 个 task 缺失
- **Acceptance Criteria**: Task 0.2、4.1、4.2、5.1 表述较模糊

---

## 4. Metis Review — Wave Structure & Optimization

### Wave Balance

| Wave | Task Count | Assessment | Suggestion                 |
| ---- | ---------- | ---------- | -------------------------- |
| 1    | 1          | ✅ 合理    | 单任务 Wave，职责明确      |
| 2    | 2          | ✅ 合理    | 2.1 基础设施 → 2.2 消费    |
| 3    | 1          | ⚠️ 可合并  | 日志内容已在 Wave 2 中实现 |
| 4    | 2          | ✅ 合理    | 编译 → 运行时验证          |
| 5    | 1          | ✅ 合理    | 文档更新                   |

### Dependency Correctness

Wave 间依赖关系正确。1.1 和 2.1 是软依赖（不同代码区域），但当前串行安排不影响正确性。

---

## 5. Conditions for Apply

### 🟡 实现前建议补充（不阻塞实现，但会提高可追溯性）

1. **Task 0.1 失败时的降级路径**：如果实测发现 `message.updated(role=user)` 中没有 `sessionID` 字段，需改为调用 `sessionIdleFired.clear()` 全局清空。建议在 Task 2.1 的 What to do 中增加一条条件注释
2. **Task 3.1 可合并到 2.1/2.2**：日志代码已在 Wave 2 的伪代码中体现，3.1 可改为"审计确认"task 或直接删除
3. **补充 Acceptance Criteria 的具体性**：Task 0.2、4.1、4.2、5.1 的 AC 需要更明确的值/命令

### ✅ 已修复

- Task 2.2 的 `idleLastFired` 矛盾：已从"重写方法"改为"在现有方法体中插入抑制逻辑"，保留 2 秒去重

---

## Decision

**⚠️ CONDITIONAL** — 技术方案和 task 拆分逻辑正确，但格式字段缺失较多。实现前建议：

1. 确认 Task 0.1 的 research 结果（`message.updated` 中的 sessionID 路径）
2. 在实现时补充 QA Scenarios 和 Evidence（作为 Task 4.2 验证的一部分）
3. Task 3.1 可合并到 Wave 2 中一并实现
