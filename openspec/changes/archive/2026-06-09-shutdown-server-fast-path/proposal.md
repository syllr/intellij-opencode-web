# Tool Window Server 状态观察:从轮询迁移到 SSE 回调驱动

## Why

### 动机:Shutdown Server 15s 延迟 + SSE 回调未被充分利用

用户在 JCEF 浏览器内右键点击 "Shutdown Server" 后,需要 **15+ 秒** 才能在工具窗口看到 "OpenCode 服务器未运行 / 启动 OpenCode 服务器" 的空状态界面,违反用户对"主动操作后立即反馈"的合理预期。

**根因: `OpenCodeSSEConsumer.stop()` 主动设 `connected=false` 截断快速通道**(`OpenCodeSSEConsumer.kt:94-107`):

1. **L96** `connected = false` —— **先设**
2. **L99** `eventSourceRef.getAndSet(null)?.close()` —— 再关

因果链:

- `close()` 触发 LaunchDarkly 库 `onClosed()` (L265-280),**只做清理,不调 `onConnectionLost`**
- 后续异步 `onError` (L288-304) 入口 L289 读 `wasConnected = connected = false`,**走 else 分支** (L300-304),只 `logger.warn("SSE error (never connected)")`,**不调 `onConnectionLost`**
- `MyToolWindow.kt:68-69` 注释自认的"server 主动 shutdown 时绕过 15s debounce"在 `stop()` 调用场景下**实际走不到**

UI 状态切换**完全依赖** `HealthMonitor` (`HealthMonitor.kt:46-77`) 的慢通道兜底:`HEALTH_CHECK_INTERVAL_MS (5000ms) × DEBOUNCE_THRESHOLD (3) = 15000ms`,**精确匹配**用户报告的 15+ 秒延迟。

### 机会:HealthMonitor 删后整个状态同步机制可简化

`HealthMonitor.kt:9-12` 自述其设计初衷:"用 SSE server.heartbeat 替代 HTTP 探活,避免 EDT 阻塞"。但**当前架构已完整覆盖** `HealthMonitor` 的所有职责:

| `HealthMonitor` 做的事              | 当前已有覆盖                                                                                     |
| ----------------------------------- | ------------------------------------------------------------------------------------------------ |
| 5s 轮询 SSE 状态                    | LaunchDarkly `ErrorStrategy.alwaysContinue()` 自动重连 + `onError` 回调(快速通道)                |
| server 静默检测                     | `SSE_WATCHDOG` 30s idle 强制 reconnect(`OpenCodeSSEConsumer.kt:141-158`)                         |
| 状态翻转触发 UI 切换                | SSE 4 回调 + `MyToolWindow.onConnectionLost` + `onRecovered`(健康时调 `loadProjectPage`)注册即可 |
| `false→true` 跳过 debounce 立即恢复 | `server.connected` 事件 + onOpen 立即设 `connected=true`                                         |
| 异步线程模型                        | `SSE-Watchdog` 已是 daemon 线程,HealthMonitor 是**第二个**并行 daemon                            |

删 `HealthMonitor` **净 -100+ 行代码** + **删 4 个常量** + **删 1 个 daemon 线程** + **消除一个独立的轮询路径**。

### ⚠️ 但审查揭示了 3 个**新发现的问题**(必须一并修)

**1. SSE_WATCHDOG 触发后,新 consumer 的 onError 走 else 分支(死循环风险)**

```
t=0    server 静默(进程活,不事件)
t=30s  SSE_WATCHDOG 触发 reconnect():
       ├─ old = eventSourceRef.getAndSet(null)   ← 老 consumer 引用清空
       ├─ startSseConnection()                    ← 新 consumer 创建, connected=false
       └─ old?.close()                            ← 老 consumer onClosed 早退(gen 不匹配)

t=30s+ε LaunchDarkly 库尝试重连:
       ├─ server 已死 → connect 失败 → onError 触发
       │  wasConnected = connected = false → 走 else 分支 → **不调 onConnectionLost**
       │  alwaysContinue 继续 1s/2s/4s/8s 重试 → 持续失败 → **永远切不到空状态 UI**
       └─ server 卡死但 TCP 能建 → onOpen 触发 → 30s 后再次 reconnect → 死循环
```

**这是 HealthMonitor 在时就被掩盖的 bug**(HealthMonitor 不依赖 onError 路径,直接看 `lastEventAt`)。删 HealthMonitor 后暴露。

**2. 网络抖动闪烁 = 净回归**

- HealthMonitor 之前 3-streak debounce 容忍网络瞬断
- 删后 onError 立即触发 onConnectionLost,5s 内 LaunchDarkly 重连成功后
- **没人**调 `loadProjectPage` 恢复 web UI(`isShowingStartButton=true` 已守卫,需用户手动点 Start)
- 用户体验:**每次网络瞬断闪一次空状态界面**

**3. 删 HealthMonitor 同时丢失 onRecovered 回调机制**

`HealthMonitor.onRecovered` 当前由 HealthMonitor 检测到 healthy 状态翻转时调用,触发 `MyToolWindow.loadProjectPage()`。**HealthMonitor 删后,没有人**在 SSE 重建后自动恢复 web UI —— 用户需手动点 Start。

## What Changes

### Part A:修复 stop() 时序漏洞(根因修复)

- **`OpenCodeSSEConsumer.stop()`** (L94-107): 在 `connected = false` **之前** 捕获 `wasConnected`;若 `wasConnected == true`,**同步**调 `onConnectionLost()` 通知 UI(沿用 `MyToolWindow.kt:78-82` 已有的 `invokeLater { showServerNotRunning() }` 包装)
- 覆盖所有 `stop()` 调用路径:graceful shutdown / IDE 退出 / project close / HealthMonitor 兜底触发的 `showServerNotRunning` → `disposeForProject`

### Part B:修复 SSE_WATCHDOG reconnect 死循环(新发现 bug)

- **`OpenCodeSSEConsumer.reconnect()`** (L133-139): 在 `old?.close()` **之前**,若 `connected == true`,**同步**调 `onConnectionLost()` 通知 UI(类比 Part A 修法)。这样:
  - 老 consumer 在 SSE_WATCHDOG 触发时立即切 UI,**不依赖**新 consumer 的 onError(wasConnected gate 会被新 consumer 的 connected=false 拦截)
  - 避免"持续 onError 走 else 分支"导致的**永远切不到空状态**的 bug
  - LaunchDarkly `alwaysContinue` 持续重连,新连接 onOpen 触发时 UI 自动恢复(见 Part C)

### Part C:补充 SSE 重建后的"自动恢复"机制

- **`OpenCodeSSEConsumer` 新增 `onConnectionEstablished: () -> Unit` 字段**(类似 onConnectionLost)
- **`OpenCodeSSEConsumer` 新增 `lastEstablishedAt: AtomicLong`** 跟踪上次建立时间,用于防网络抖动误触
- **`OpenCodeSSEConsumer.onOpen()` 末尾**执行:
  - **1.5s debounce 检查**: 若 `now - lastEstablishedAt.get() < 1500`,跳过 `onConnectionEstablished()` 触发(避免 1.5s 内的网络抖动被误判为"恢复"再切回闪烁;1.5s 阈值对齐 LaunchDarkly `alwaysContinue` 首次重试约 1s + jitter)
  - 调 `onConnectionEstablished()`(L160-164)
  - 更新 `lastEstablishedAt = now`
- **`OpenCodeServerManager.getOrCreateConsumer()` 签名扩展**: 新增 `onConnectionEstablished: () -> Unit = {}` 参数
- **`MyToolWindow.startConsumerAndMonitor()`** (L75-94): 新增 `onConnectionEstablished = { invokeLater { loadProjectPage() } }` 注册(已有 `hasLoaded` 守卫避免重复加载)
- **`OpenCodeSSEConsumer` 构造函数 / `SSEConsumerFactory.create()`**: 透传新参数

**完整覆盖两个方向**:

- **失联 → 切 UI**: Part A (stop) + Part B (reconnect) + 现有 LaunchDarkly onError 快速通道
- **重建 → 恢复 UI**: Part C (onConnectionEstablished 调 loadProjectPage + 1.5s debounce 防网络抖动)

**网络抖动 trade-off**: 1.5s 内的网络瞬断(reconnect 成功)仍**不会**触发 loadProjectPage,因为 onOpen 仍在 1.5s debounce 窗口内 → web UI 仍切到空状态显示给用户。这是已知 trade-off: 用户感知"1.5s 内恢复"≈"没发生抖动"。

**复杂时序下风险**(design 阶段需详细 trace): Part B `reconnect()` 触发 onConnectionLost → showServerNotRunning 异步走 `disposeForProject → consumer.stop()` → `eventSourceRef.getAndSet(null)?.close()` **关掉 reconnect() 刚 `startSseConnection()` 创建的 NEW EventSource**。功能正确(zombie connection 被立即回收),但 design 阶段需写明这条"自杀时序"并加注释,避免后人误读。

### Part D:删除冗余的 HealthMonitor(架构简化)

- **删除** `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/toolWindow/HealthMonitor.kt` **整个文件**(96 行)
- **删除** `OpenCodeConstants.kt:7-10` 中 4 个常量:`HEALTH_CHECK_INTERVAL_MS` / `HEALTH_CHECK_START_DELAY_MS` / `HEALTH_CHECK_POLL_INTERVAL_MS` / `HEALTH_CHECK_INITIAL_DELAY_MS`
- **清理** `MyToolWindow.kt` 散落调用: L55 字段 / L84-93 创建块 / L119, L124-125 停止块 / L68-69 注释
- **简化** `startConsumerAndMonitor()` 仅注册 `onConnectionLost` + `onConnectionEstablished` 两个回调(无 HealthMonitor)
- **同步更新** `SPEC.md` §7.5 健康检查机制章节:删除"5s 轮询 + 3 次 debounce"为设计契约的描述,改为"tool window 状态完全由 SSE 4 回调驱动"
- **同步更新** `DESIGN.md` §4 核心机制章节:删除 HealthMonitor 架构图,补充 SSE 回调驱动 + reconnect 主动通知 + onConnectionEstablished 恢复
- **同步更新** `README.md`: 更新 "Crash Detection" 段(原 15s 不再准确)

### Part E:测试覆盖(零基线起步)

- **新增** `src/test/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumerTest.kt`(实测**目前不存在**):
  - 覆盖 `stop()` 在 `connected=true` / `connected=false` 下 onConnectionLost 触发
  - 覆盖 `reconnect()` 在 `connected=true` 时 onConnectionLost 触发
  - 覆盖 `onOpen()` onConnectionEstablished 触发
  - 覆盖多 stop 调用的幂等性
- **(可选)** 端到端测试 4 个场景(stop / crash / silent-crash / network-jitter):[NEEDS INVESTIGATION:tasks 阶段调研端到端测试框架]

### 不变项

- **BREAKING**: 无
- **公共 API 不变** (`HealthMonitor` 是 internal class;新增的 `onConnectionEstablished` 是默认空函数,backward compatible)
- **依赖不变**
- `gracefulShutdown` 主体逻辑**不变**;`OpenCodeServerManager` 主体**不变**(仅 getOrCreateConsumer 签名扩展)
- `SSE_WATCHDOG` 30s idle 机制**保留**(OpenCodeSSEConsumer.kt:141-158)—— Part B 修复了它的死循环 bug,但仍保留作为 server 静默检测
- LaunchDarkly 库 `ErrorStrategy.alwaysContinue()` 自动重连**保留** —— Part B 修复死循环后,库继续负责重连,Part C 处理恢复

## Capabilities

### New Capabilities

- `tool-window-reactive-server-state`: 描述"tool window 与 opencode server 的状态同步**完全由 SSE 4 个回调 + reconnect 主动通知驱动**,无独立的 5s 轮询线程,且失联/重建两个方向都有回调"的语义,覆盖 4 件事:
  1. Part A: graceful shutdown 路径 < 100ms 切 UI
  2. Part B: SSE_WATCHDOG 触发 reconnect 时立即切 UI(避免死循环)
  3. Part C: SSE 重建后自动 loadProjectPage 恢复 web UI
  4. Part D: HealthMonitor 删除,无独立轮询线程

  包含 5 个 requirements:
  - **R1** (Part A 契约): "when the SSE consumer is intentionally stopped while previously connected, `onConnectionLost` MUST be invoked synchronously inside `stop()` to trigger immediate UI feedback, regardless of the SSE EventSource close callback ordering"
  - **R2** (Part B 契约): "when the SSE watchdog (30s idle) forces a reconnect while the consumer is still in `connected=true` state, `onConnectionLost` MUST be invoked synchronously before `old?.close()`, to prevent the new-consumer's onError from silently bypassing the UI notification due to the `wasConnected` gate"
  - **R3** (Part C 契约): "the tool window MUST register an `onConnectionEstablished` callback that invokes `loadProjectPage()` on every SSE `onOpen` (initial connect or reconnect after disconnect), debounced by **1.5s** since the previous establishment, to automatically restore the web UI when SSE recovers while avoiding flicker on transient reconnects; `loadProjectPage`'s existing `hasLoaded` guard MUST be relied upon to skip duplicate loads"
  - **R4** (Part D 契约): "the tool window MUST NOT maintain an independent health-polling thread (`HealthMonitor`); all server-state transitions MUST be observed via SSE library callbacks (onOpen / onError / onClosed) plus the `SSE_WATCHDOG` 30s idle reconnect mechanism"
  - **R5** (测试契约,零基线起步): "tests MUST cover: (a) `OpenCodeSSEConsumer.stop()` in `connected=true` / `connected=false` states triggering onConnectionLost; (b) `reconnect()` in `connected=true` state triggering onConnectionLost; (c) `onOpen()` triggering onConnectionEstablished with 1.5s debounce; (d) the pre-existing `onError` `wasConnected` gate (L288-304) — this gate is the前提 of Part B/C's fix, and a subsequent refactor must not silently break it; current repo has zero existing test coverage for `OpenCodeSSEConsumer`"

### Modified Capabilities

(无。现有 `idle-notification-suppression` / `subagent-complete-detection-fix` 关注 idle 通知去重与 subagent 完成检测,与本 change 无关。本 change 是关于"tool window 状态检测与 UI 反馈路径的架构重构",属于新增语义。)

## Impact

### 受影响代码

| 文件                                                                                      | 改动                                                                                                                                                                                                                                                                                                                       |
| ----------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumer.kt`     | Part A:`stop()` L94-107,新增 ~5 行 (捕获 `wasConnected` + 调 `onConnectionLost()`);Part B:`reconnect()` L133-139,新增 ~3 行 (调 `onConnectionLost()` + 自杀时序注释);Part C:新增 `onConnectionEstablished: () -> Unit` 字段 + `lastEstablishedAt: AtomicLong` + 构造函数透传 + `onOpen()` 末尾 1.5s debounce 检查 (~13 行) |
| `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/utils/SSEConsumerFactory.kt`          | Part C:透传 `onConnectionEstablished` 参数 (~2 行)                                                                                                                                                                                                                                                                         |
| `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/toolWindow/OpenCodeServerManager.kt`  | Part C:`getOrCreateConsumer()` L33-40 签名扩展,新增 `onConnectionEstablished` 参数 (~3 行)                                                                                                                                                                                                                                 |
| `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/toolWindow/MyToolWindow.kt`           | Part C:`startConsumerAndMonitor()` L75-94 注册 `onConnectionEstablished` 调 `loadProjectPage()` (~5 行);Part D:删除 L55 字段 / L84-93 创建块 / L119 注释 / L124-125 停止块 / L68-69 注释 (~15 行)                                                                                                                          |
| `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/toolWindow/HealthMonitor.kt`          | Part D:**整个文件删除**(96 行)                                                                                                                                                                                                                                                                                             |
| `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/OpenCodeConstants.kt`                 | Part D:删除 L7-10 4 个 `HEALTH_CHECK_*` 常量                                                                                                                                                                                                                                                                               |
| `src/test/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumerTest.kt` | Part E:**新增文件**(实测目前不存在),覆盖 R1/R2/R3 契约                                                                                                                                                                                                                                                                     |

**净变化**: -75 行(主要删除)+ 测试新增 ~80-120 行。

### 受影响文档

- `SPEC.md` §6.3 关闭策略:增加"主动 stop 必须立即触发 UI 切换"
- `SPEC.md` §7.5 健康检查机制:**重大更新** —— 删除"5s 轮询 + 3 次 debounce"为设计契约,改为"tool window 状态完全由 SSE 4 回调驱动 + SSE_WATCHDOG 30s 兜底 + reconnect 主动通知 + onConnectionEstablished 恢复"
- `SPEC.md` §3.1 数据一致性:验证 `subagentSessionIds` / `sessionIdleFired` / `idleLastFired` 三个不变量不受影响 [NEEDS INVESTIGATION:tasks 阶段验证]
- `DESIGN.md` §3.3 关闭时序:更新时序图,标注快速通道在 stop 内主动触发
- `DESIGN.md` §4 核心机制:**重大更新** —— 删除 HealthMonitor 架构图,补充"SSE 回调驱动 + reconnect 主动通知 + onConnectionEstablished 恢复"新架构
- `README.md`: 更新 "Crash Detection" 段(15s 不再准确,实际是 < 5s / ~30s 不同场景)

### 公共 API / 依赖

- **公共 API 不变** (`HealthMonitor` 是 internal class,删除不破坏 API;新增的 `onConnectionEstablished` 是默认空函数,backward compatible)
- **依赖不变**

### 风险与回归

| 风险                                                                               | 等级 | 缓解                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ---------------------------------------------------------------------------------- | ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **重复 stop 调用**                                                                 | 🟢   | `consumers.remove(project)` 已是幂等;第二次 stop 时 `wasConnected = false` 不会重复触发 onConnectionLost                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **IDE 退出路径产生 UI 闪烁**                                                       | 🟢   | `showServerNotRunning` 的 `project.isDisposed` 守卫拦截                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **网络抖动闪烁**(删 HealthMonitor 3-streak debounce 后的回归)                      | 🟡   | Part C 引入 `onConnectionEstablished` 触发 `loadProjectPage`,reconnect 成功后**自动恢复** web UI。但**首次** onError 仍会立即切空状态,1-2s 内 LaunchDarkly 重连成功后自动恢复;**UI 切换 → 恢复**会有一次短暂闪烁(1-2s)。这是已知 trade-off;若需完全无闪烁,加 1-2s debounce 是 follow-up                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **server 静默 30s + reconnect → onError 走 else 分支**(Part B 修复前的真 bug)      | 🟢   | Part B 修复后,reconnect 主动调 onConnectionLost,不依赖新 consumer onError 路径                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **多 Project 场景**                                                                | 🟢   | `consumers` map 按 project 分组;`onConnectionLost` / `onConnectionEstablished` 通过 `getOrCreateConsumer` per-project 注入                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **测试覆盖范围扩大**                                                               | 🟡   | Part E 新增 OpenCodeSSEConsumerTest,**零基线起步**;需 design + tasks 阶段详细规划测试用例                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **SPEC §7.5 契约变更**                                                             | 🟡   | 是 **SPEC 级别的契约变更**,需在 tasks 阶段配对 verification 任务;不能裸交付                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **onConnectionEstablished 触发 loadProjectPage 重复执行**                          | 🟢   | `loadProjectPage` 已有 `hasLoaded` 守卫 (L235-237),重复调用被跳过                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **reconnect 触发 onConnectionLost 时,onConnectionLost 内部不应再调 stop**          | 🟡   | `onConnectionLost` 是 `MyToolWindow.showServerNotRunning` 的 lambda 包装,内部通过 `OpenCodeServerManager.disposeForProject` → `consumer.stop` → 但此时 `wasConnected = false`(已被设为 false),不会递归触发 onConnectionLost。设计阶段需详细 trace                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **Part B reconnect() → stop() "自杀时序"**                                         | 🟡   | `reconnect()` 触发 onConnectionLost → showServerNotRunning 异步(invokeLater)走 disposeForProject → consumer.stop() → `eventSourceRef.getAndSet(null)?.close()` **关掉 reconnect() 刚 startSseConnection 创建的 NEW EventSource**。功能正确(zombie connection 被立即回收),但 design 阶段需在 reconnect() 旁加注释说明这条时序,避免后人误读                                                                                                                                                                                                                                                                                                                                                                       |
| **`getOrCreateConsumer` 的 lambda lock-in 问题**                                   | 🟡   | **采用方案**:`OpenCodeSSEConsumer` 把 `onConnectionLost` / `onConnectionEstablished` 从构造参数(`val`)改为 `@Volatile var` 可写字段;`OpenCodeServerManager.getOrCreateConsumer` 在 `computeIfAbsent` 路径之外,追加 `consumers.computeIfPresent(project) { _, c -> c.also { c.onConnectionLost = newLost; c.onConnectionEstablished = newEst } }` 用于"已存在时刷新 lambda"。零新建对象、不打断 watchdog/EventSource 线程、不浪费资源。`disposeForProject`→`remove` 已清理旧 consumer,新 MyToolWindow 实例的首次 `getOrCreateConsumer` 走 `computeIfAbsent` 建新 consumer 不会触发 lock-in;lock-in 实际仅在"server 持续运行 + 同一 project session 内反复 close/reopen tool window"时触发,该方案对此场景精准覆盖 |
| **多 Project 场景下 in-flight onConnectionEstablished 进入已 dispose tool window** | 🟡   | `gracefulShutdown` 内 `consumers.clear()` (L228) 是全 project 清空。多 project 场景下,任何 project 的 in-flight onConnectionEstablished(loadProjectPage) 回调可能进入已 dispose 的 tool window。`project.isDisposed` 守卫会拦截,但 `loadProjectPage` 入口也需自检。tasks 阶段需 grep 所有 `invokeLater { loadProjectPage }` 调用点,统一加 `isDisposed` early return                                                                                                                                                                                                                                                                                                                                             |
| **网络抖动闪烁(Part C 1.5s debounce 后的 trade-off)**                              | 🟢   | **设计决策**:`onConnectionLost` **不** debounce(Part A 要求快速通道);**仅** `onConnectionEstablished` debounce 1.5s(Part C)。**效果**:首次必闪 1-2s(切到空状态),若 1.5s 内 LaunchDarkly 库自动重连成功则**不**闪第二次。完全无闪烁需 follow-up(给 onConnectionLost 也加 1-2s debounce,与 R3 自动恢复形成对称)                                                                                                                                                                                                                                                                                                                                                                                                   |

## 执行顺序(降低 review / 回归风险)

为降低 review 复杂度和回归风险,实施时**严格按 A→B→C→D 顺序**,每步独立可测:

1. **A**: 修 `stop()`(3 行) + 单元测试 → 验证 graceful shutdown < 100ms
2. **B**: 修 `reconnect()`(3 行) + 单元测试 → 验证 server 静默 30s 触发后立即切 UI
3. **C**: 加 `onConnectionEstablished` 字段 + `onOpen` 触发 + MyToolWindow 注册 + 单元测试 → 验证 SSE 重建后自动恢复 web UI
4. **D**: 删 `HealthMonitor.kt` 整个 + 散落调用 + SPEC §7.5 同步 → 验证所有 4 个场景在生产表现

每步**独立提交**,便于回滚。
