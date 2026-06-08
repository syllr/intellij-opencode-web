## Context

### 背景

本 change 修复 `shutdown-server-fast-path` 工具窗口关闭反馈延迟,并简化冗余的 SSE 健康检查架构。

**当前状态**:

- 用户在 JCEF 浏览器内右键 "Shutdown Server" 后,UI 状态切换延迟 **15+ 秒**(用户报告)
- `OpenCodeSSEConsumer.stop()` (L94-107) 主动设 `connected=false` 截断 LaunchDarkly 库 `onError` 快速通道
- UI 状态切换**完全依赖** `HealthMonitor` (`HealthMonitor.kt:46-77`) 的 5s × 3 = 15s debounce 慢通道兜底
- `HealthMonitor` 是**历史冗余**: SSE 4 回调 + LaunchDarkly `alwaysContinue` 自动重连 + `SSE_WATCHDOG` 30s 已完整覆盖 server 状态检测

### 根因(已确证)

`OpenCodeSSEConsumer.stop()` (L94-107) 执行顺序:

1. L96 `connected = false` —— **先设**
2. L99 `eventSourceRef.getAndSet(null)?.close()` —— **再关**

因果链:

- `close()` 触发 LaunchDarkly `onClosed()` (L265-280),**只做清理,不调 `onConnectionLost`**
- 后续异步 `onError` (L288-304) 入口 L289 读 `wasConnected = connected = false`,**走 else 分支** (L300-304),不调 `onConnectionLost`
- `wasConnected` 门控本意区分"从未连上"与"连上后断开"两种 error 语义;`stop()` 顺序意外让后者**静默旁路**
- UI 状态切换只能等 HealthMonitor 15s 兜底

### 新发现的问题(proposal 阶段评审揭示)

1. **SSE_WATCHDOG 死循环**: `reconnect()` 触发后,新 consumer 的 onError 走 `wasConnected=false` 分支,永远切不到空状态 UI
2. **网络抖动闪烁 = 净回归**: 删 HealthMonitor 后,3-streak debounce 消失,首次 onError 立即切空状态;1.5s 内 LaunchDarkly 重连成功后 UI 需手动点 Start 恢复
3. **onRecovered 回调机制丢失**: `HealthMonitor.onRecovered` 当前在 healthy 翻转时调 `loadProjectPage`;HealthMonitor 删后**无人**在 SSE 重建后自动恢复 web UI

### 干系人

- **本仓库开发者**: 修改 `OpenCodeSSEConsumer` / `MyToolWindow` / `OpenCodeServerManager` / `OpenCodeConstants` / SPEC.md / DESIGN.md
- **终端用户**: 体验"立即反馈"+"自动恢复 web UI"
- **下游 maintainer**: 阅读 SPEC §7.5 新契约 + DESIGN §4 新架构图

### 线程安全约束

所有 `stop()` / `reconnect()` / `startSseConnection()` 操作通过以下机制保证线程安全:

- `consumers: ConcurrentHashMap<Project, OpenCodeSSEConsumer>` 原子增删改(由 `OpenCodeServerManager` 持有)
- `eventSourceRef.getAndSet(null)?.close()` CAS-style 原子操作(避免并发 close)
- `lastEventAt: AtomicLong` 跨 LaunchDarkly 后台线程 + `SSE-Watchdog` daemon + EDT 共享
- `lastEstablishedAt: AtomicLong` 跨 SSE 重建场景共享
- `onConnectionLost` / `onConnectionEstablished` 字段为 `@Volatile var`,跨线程可见性 + 写入原子性
- `consumers.computeIfPresent(...)` 一次原子操作完成"读+更新"两步

**已知竞态场景**(设计中已隐式处理,未在验收场景中显式覆盖):

- 并发 `stop()` + `onOpen()`: 各自独立写 `connected` 字段,Last-Writer-Wins(`@Volatile` 保证可见性);LaunchDarkly 库 onOpen 触发时 `connected` 已是 false → 下次 `stop()` 时 `wasConnected = false` → 不调 `onConnectionLost`,**行为正确**
- 并发 `reconnect()` + `stop()` (跨 SSE-Watchdog daemon + EDT): `eventSourceRef.getAndSet(null)?.close()` 原子操作保证只有一个 close 生效
- 并发 `getOrCreateConsumer`: `consumers.computeIfAbsent` 原子操作保证同一 project 只有一个 consumer 实例

tasks 阶段在 `OpenCodeSSEConsumerTest` 中可补充这些并发场景的 stress test,但本 change 不要求**显式**覆盖(现有原子操作 + `@Volatile` 已足够保证正确性)。

### 约束

- **公共 API 不变**(`HealthMonitor` 是 internal class)
- **依赖不变**
- **测试基线为零**:`OpenCodeSSEConsumerTest.kt` 实测不存在,Part E 从零起步
- **执行顺序必须 A→B→C→D**: Part D 删 HealthMonitor 之前必须先验证 A/B/C 都工作(避免失去 fallback)

---

## Goals / Non-Goals

### Goals

- **G1**: graceful shutdown 路径 < 100ms 切 UI(从 15s 提升 ~150x)
- **G2**: server 静默 30s 路径仍能切 UI(修 SSE_WATCHDOG 死循环)
- **G3**: SSE 重建后**自动**调 `loadProjectPage()` 恢复 web UI(无需用户手动点 Start)
- **G4**: 网络抖动 1.5s 内不闪第二次(`onConnectionEstablished` debounce)
- **G5**: 删除冗余的 `HealthMonitor` 整个文件 + 4 个 `HEALTH_CHECK_*` 常量
- **G6**: 0 → 1 建 `OpenCodeSSEConsumer` 测试覆盖(R1/R2/R3 + 既有的 onError gate)
- **G7**: SPEC §7.5 / DESIGN §4 / README 同步更新契约描述

### Non-Goals

- **NG1**: 给 `onConnectionLost` 加 1-2s debounce(防"首次必闪 1-2s")→ 放 follow-up
- **NG2**: "被动自启探活"(让 plugin 启动时自动建 SSE 连接,不用用户点 Start)→ 独立 change
- **NG3**: 删 `lastHeartbeatAt` / `isHealthy()` / `server.heartbeat` 移出白名单 → 独立 follow-up(因 sse-streaming-reader change 已设计这 3 个保留在白名单内)
- **NG4**: 修改 `gracefulShutdown` 4 阶段主体逻辑(本 change 修复点在 consumer.stop 内部,对上游透明)
- **NG5**: 升级 `okhttp-eventsource` / 改 LaunchDarkly 库配置
- **NG6**: 给 SSE 端点增加新事件 / 改 opencode server 端

---

## Decisions

### Decision 1: Part A — `OpenCodeSSEConsumer.stop()` 主动调 `onConnectionLost`

**Rationale**: 类比 graceful shutdown 应当"立即通知 UI"的语义,`stop()` 在销毁资源前先**同步**调 `onConnectionLost()`,确保 tool window 立即切到空状态。LaunchDarkly 库后续的 onError/onClosed 异步回调**无关紧要**——主线程已主动通知。

**实施** (OpenCodeSSEConsumer.kt L94-107):

```kotlin
fun stop() {
    val wasConnected = connected  // [新增] 在设 false 之前捕获
    connected = false
    if (wasConnected) {
        onConnectionLost()  // [新增] 同步通知 tool window
    }
    // ... 现有清理逻辑 ...
}
```

**Alternatives Considered**:

- **A.1** 在 `gracefulShutdown` 入口处先发通知再 stop:侵入 `OpenCodeServerManager`,且对 `disposeForProject` 路径不生效(那路径不经过 `gracefulShutdown`)
- **A.2** 改 `MyToolWindow.showServerNotRunning` 内部加 `wasConnected` 判断:tool window 端不知道 SSE consumer 状态,需要查询,污染抽象

### Decision 2: Part B — `OpenCodeSSEConsumer.reconnect()` 主动调 `onConnectionLost`

**Rationale**: SSE_WATCHDOG 30s 触发 `reconnect()` 后,新 consumer 的 onError 走 `wasConnected=false` 分支,UI 永远切不到空状态。类比 Part A 修法,在 reconnect 内部、`startSseConnection()` **之前**同步调 `onConnectionLost()`(早触发 invokeLater → EDT 队列上 UI 切换更早)。

**自杀时序** (设计阶段需在 `reconnect()` 旁加 1 行 warning 注释):

```
T=0    SSE_WATCHDOG 30s tick 触发 reconnect() (in SSE-Watchdog daemon thread)
T=0+ε  reconnect() 内部:
       ├─ if (connected) onConnectionLost()              // [1] 早通知,invokeLater 排入 EDT 队列
       │     (wasConnected=true,触发 showServerNotRunning)
       ├─ old = eventSourceRef.getAndSet(null)            // [2] 老 source 引用清空
       ├─ startSseConnection()                            // [3] 新 source 创建,ref 更新到新 source
       │     gen=N+1, activeConnectionGen=N+1, connected=false
       └─ old?.close()                                    // [4] 关闭老 source(gen 不匹配,异步 onClosed 早退)

T=ε'   [EDT 异步] invokeLater 队列执行 showServerNotRunning:
       ├─ isShowingStartButton guard 检查
       ├─ disposeBrowser() + showStartButton()
       ├─ healthMonitor?.stop()  (过渡期 B+C 已部署,D 未部署)
       └─ disposeForProject → consumer.stop()  // 第二次 stop()
              - wasConnected = connected  // 此时 connected=true(没人改过)→ wasConnected=true
              - connected = false
              - if (wasConnected) onConnectionLost()  // 第二次调,但 showServerNotRunning 守卫 isShowingStartButton=true 早退
              - eventSourceRef.getAndSet(null)?.close()  // [5] 关闭的是 [3] 创建的 NEW source(zombie 回收)
```

**关键观察**:

- `onConnectionLost()` 在 [1] 早触发,**不依赖** [3] 创建新 source
- 第二次 `stop()` 在 EDT 异步跑时,`eventSourceRef` 已指向 [3] 创建的 NEW source(不是老 source)
- 所以 [5] 关闭的是 NEW source —— **zombie 立即回收**
- 老 source 在 [4] `old?.close()` 关闭(gen 不匹配,异步 onClosed 早退,**不**调 onConnectionLost)

**顺序选择理由**:`onConnectionLost()` 放 [1] 而不是 [2] 之后,是因为:

- 早触发 `invokeLater`,EDT 队列上 UI 切换更早(用户感知)
- 两种顺序 zombie 行为完全一致(都关闭 NEW source)
- "先通知 UI、再重建 source" 语义上更清晰

**实施** (OpenCodeSSEConsumer.kt L133-139):

```kotlin
private fun reconnect() {
    val idle = System.currentTimeMillis() - lastEventAt.get()
    logger.warn("[OpenCodeSSEConsumer] No event for ${idle}ms, forcing reconnect")

    // [Fix SSE_WATCHDOG 死循环] 类比 stop() 主动调 onConnectionLost 的修法。
    // [Warning: 自杀时序] 此 onConnectionLost 通过 invokeLater 异步触发 showServerNotRunning →
    // disposeForProject → consumer.stop(),stop() 第二次执行时会关闭 reconnect() 紧接着
    // startSseConnection() 创建的 NEW source(zombie 立即回收,功能正确)。
    if (connected) {
        onConnectionLost()
    }

    val old = eventSourceRef.getAndSet(null)
    startSseConnection()
    old?.close()
}
```

**Alternatives Considered**:

- **B.1** 改 `onError` 让 `wasConnected` 升级为"lifetime connected"(`AtomicBoolean`,只设不撤):语义破坏——onError 设计本意是"error 时是否曾 connected",改成 lifetime 会让从未连上的 error 也触发 onConnectionLost
- **B.2** 让 SSE_WATCHDOG 线程直接调 onConnectionLost 不经 reconnect:破坏封装,watchdog 不该知道 consumer 内部回调
- **B.3** `onConnectionLost()` 放 `startSseConnection()` 之后(顺序 2):功能等价但 invokeLater 触发晚,UI 切换晚 1 帧;**功能完全等价**但用户体验稍逊

### Decision 3: Part C — 新增 `onConnectionEstablished` 回调 + 1.5s debounce

**Rationale**: Part D 删 HealthMonitor 后,失去 `onRecovered = { loadProjectPage() }` 机制。在 `onOpen()` 末尾调 `onConnectionEstablished()` 替代,tool window 注册时 `loadProjectPage()`。1.5s debounce 防网络抖动(< 1.5s 内 LaunchDarkly 重连)被误判为"恢复"再切回闪烁。

**实施** (OpenCodeSSEConsumer.kt):

```kotlin
// [新增字段]
@Volatile private var onConnectionEstablished: () -> Unit = {}  // 注:Decision 5 改为 @Volatile var
private val lastEstablishedAt = AtomicLong(0L)

// [修改 onOpen() 末尾] (L160-164)
override fun onOpen() {
    connected = true
    lastEventAt.set(System.currentTimeMillis())
    logger.info("[OpenCodeSSEConsumer] SSE connection opened")

    // 1.5s debounce 防网络抖动
    val now = System.currentTimeMillis()
    if (now - lastEstablishedAt.get() < 1500) {
        logger.debug("[OpenCodeSSEConsumer] onOpen within 1.5s debounce, skip onConnectionEstablished")
        return
    }
    lastEstablishedAt.set(now)
    onConnectionEstablished()
}
```

**`getOrCreateConsumer()` 签名扩展** (OpenCodeServerManager.kt L33-40):

```kotlin
fun getOrCreateConsumer(
    project: Project,
    onConnectionLost: () -> Unit = {},
    onConnectionEstablished: () -> Unit = {}  // [新增]
): OpenCodeSSEConsumer { ... }
```

**`MyToolWindow.startConsumerAndMonitor()` 注册** (L75-94):

```kotlin
val consumer = OpenCodeServerManager.getOrCreateConsumer(
    project = project,
    onConnectionLost = {
        ApplicationManager.getApplication().invokeLater {
            showServerNotRunning()  // 已有
        }
    },
    onConnectionEstablished = {  // [新增]
        ApplicationManager.getApplication().invokeLater {
            loadProjectPage()  // 已有 hasLoaded 守卫避免重复加载
        }
    }
)
```

**1.5s 阈值理由**:

- LaunchDarkly `alwaysContinue` 首次重试 ~1s + jitter(≤1s),1.0s 会踩 jitter 边缘
- 1.5s 留足余量
- 对齐 heartbeat(10s) / watchdog(30s) 会失去 debounce 意义

**Alternatives Considered**:

- **C.1** 1.0s 阈值:过短,jitter 边缘
- **C.2** 2.0s 阈值:用户感知"无 UI 2s"可能误判
- **C.3** 不 debounce(原始设计):网络抖动会闪两次(空状态 → 恢复 → 空状态,1.5s 内)

### Decision 4: Part D — 删 `HealthMonitor.kt` 整个文件

**Rationale**: 修完 Part A/B/C 后:

- graceful shutdown < 100ms 切 UI(Part A 快速通道)
- server 静默 30s 切 UI(Part B 修死循环 + SSE_WATCHDOG 30s 强制 reconnect)
- SSE 重建自动恢复 web UI(Part C onConnectionEstablished)
- HealthMonitor 的 5s × 3 = 15s debounce **唯一**兜底场景就是 graceful shutdown,已被 Part A 覆盖
- HealthMonitor 的 `onRecovered` 机制由 Part C 替代

**实施** (4 处改动):

1. 删除 `HealthMonitor.kt` 整个文件(96 行)
2. 删除 `OpenCodeConstants.kt:7-10` 4 个常量
3. 清理 `MyToolWindow.kt` 散落代码(L55 字段、L84-93 创建块、L119 注释、L124-125 停止块、L68-69 注释,共 ~15 行)
4. 简化 `startConsumerAndMonitor()` 仅注册 `onConnectionLost` + `onConnectionEstablished`

**Alternatives Considered**:

- **D.1** 保留 HealthMonitor 但禁用(注释化):代码仍被维护,违反"删冗余"目标
- **D.2** 保留 HealthMonitor 但把 debounce 阈值改 1:失去防抖意义,与 Part A/B 快速通道冲突

### Decision 5: F2 lambda lock-in — 候选 2(`@Volatile var` + `computeIfPresent`)

**Rationale**: `consumers.computeIfAbsent` 只在首次创建时捕获 lambda。Part D 删 HealthMonitor 后,如果 tool window close→reopen(同 project session 内),新 MyToolWindow 实例的 lambda 被静默忽略 → 失去 onConnectionLost/onConnectionEstablished 自动恢复。

**采用方案**: 把 `onConnectionLost` / `onConnectionEstablished` 从构造参数(`val`)改为 `@Volatile var` 可写字段;`getOrCreateConsumer` 在已存在路径追加 `consumers.computeIfPresent(project) { _, c -> c.also { c.onConnectionLost = newLost; c.onConnectionEstablished = newEst } }`。

**实施**:

```kotlin
class OpenCodeSSEConsumer(
    private val project: Project,
) : BackgroundEventHandler {
    @Volatile private var onConnectionLost: () -> Unit = {}  // [修改] val → @Volatile var
    @Volatile private var onConnectionEstablished: () -> Unit = {}  // [修改] val → @Volatile var
    // ... 其他字段不变
}

// OpenCodeServerManager.kt
fun getOrCreateConsumer(
    project: Project,
    onConnectionLost: () -> Unit = {},
    onConnectionEstablished: () -> Unit = {}
): OpenCodeSSEConsumer {
    return consumers.computeIfAbsent(project) { p ->
        SSEConsumerFactory.create(p, onConnectionLost, onConnectionEstablished).also { it.start() }
        // 首次创建时通过 SSEConsumerFactory 把 lambda 注入
    }.also { existingConsumer ->
        // [新增] 已存在时刷新 lambda
        existingConsumer.onConnectionLost = onConnectionLost
        existingConsumer.onConnectionEstablished = onConnectionEstablished
    }
}
```

**Alternatives Considered**:

- **5.1** `consumers.compute` 重建 consumer:浪费 EventSource + watchdog + 重启 SSE
- **5.2** 不支持重注册(tool window 关闭后不让 reopen):违背 G3 自动恢复契约
- **5.3** 给 OpenCodeSSEConsumer 加 `reRegisterCallbacks` 显式方法:侵入 consumer API,候选 2 更轻

### Decision 6: 1-2s debounce on `onConnectionLost` — **不**实施(NG1)

**Rationale**: 给 `onConnectionLost` 加 1-2s debounce 可防"首次必闪 1-2s",但:

- Part A 要求"graceful shutdown 路径 < 100ms 切 UI"是核心契约(G1)
- 1-2s debounce 与 G1 冲突
- 已知 trade-off: 首次必闪 1-2s,但 1.5s 内 LaunchDarkly 重连成功则不闪第二次(Part C 1.5s debounce 救场)
- 真正"完全无闪烁"需复杂方案(Layered debounce + 状态机),超出本 change scope

**结论**: 放 follow-up,本 change 不处理。

### Decision 7: F3 — `loadProjectPage` 入口加 `project.isDisposed` early return

**Rationale**: 多 Project 场景下,`gracefulShutdown` 内 `consumers.clear()` 是全 project 清空。任何 project 的 in-flight `onConnectionEstablished` 回调(`loadProjectPage`)可能进入已 dispose 的 tool window。`showServerNotRunning` (L108-111) 已有同样守卫,`loadProjectPage` 镜像防御是标准 EDT callback 安全措施。

**实施**:

```kotlin
// MyToolWindow.kt
private fun loadProjectPage(force: Boolean = false) {
    if (project.isDisposed) return  // [新增] 与 showServerNotRunning 守卫一致
    if (hasLoaded && !force) return
    // ... 现有逻辑 ...
}
```

---

## Risks / Trade-offs

| #       | 风险 / Trade-off                                                                            | 严重度 | 缓解                                                                                                                                                                                                                                                                                                                                                              |
| ------- | ------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **R1**  | 重复 stop 调用(consumer 已 stop 后 Disposer 又调)                                           | 🟢     | `consumers.remove(project)` 已是幂等;第二次 stop 时 `wasConnected = false` 不会重复触发                                                                                                                                                                                                                                                                           |
| **R2**  | IDE 退出路径产生 UI 闪烁                                                                    | 🟢     | `showServerNotRunning` 的 `project.isDisposed` 守卫 (L108-111) 拦截                                                                                                                                                                                                                                                                                               |
| **R3**  | Part B reconnect → stop "自杀时序"(reconnect 刚创建的 NEW source 被异步 stop 关掉)          | 🟡     | Decision 2 已给出时序图 + warning 注释;功能正确(zombie 立即回收)                                                                                                                                                                                                                                                                                                  |
| **R4**  | `getOrCreateConsumer` lambda lock-in(tool window close→reopen 后新 lambda 被忽略)           | 🟡     | Decision 5 候选 2: `@Volatile var` + `computeIfPresent` 原子更新                                                                                                                                                                                                                                                                                                  |
| **R5**  | 多 Project in-flight `onConnectionEstablished`(`loadProjectPage`)进入已 dispose tool window | 🟡     | Decision 7: `loadProjectPage` 入口加 `isDisposed` 守卫                                                                                                                                                                                                                                                                                                            |
| **R6**  | 网络抖动闪烁(首次必闪 ≤2s,1.5s 内 LaunchDarkly 重连则不闪第二次)                            | 🟢     | 量化: flash duration ≤ LaunchDarkly 首次重试(≈1s) + jitter(≤1s) = worst-case ≤ 2s;Decision 6 已知 trade-off,放 follow-up;Part C 1.5s debounce 救第二次                                                                                                                                                                                                            |
| **R7**  | server 静默 30s + reconnect → onError 走 else 分支(Part B 修复前的真 bug)                   | 🟢     | Part B 修复后,reconnect 主动调 onConnectionLost,不依赖新 consumer onError 路径                                                                                                                                                                                                                                                                                    |
| **R8**  | 测试覆盖范围扩大                                                                            | 🟡     | Part E 新增 OpenCodeSSEConsumerTest,**零基线起步**;tasks 阶段详细规划测试用例                                                                                                                                                                                                                                                                                     |
| **R9**  | SPEC §7.5 契约变更(删除"5s × 3 debounce"为设计契约)                                         | 🟡     | tasks 阶段配对 verification 任务;不能裸交付                                                                                                                                                                                                                                                                                                                       |
| **R10** | onConnectionEstablished 触发 loadProjectPage 重复执行                                       | 🟢     | `loadProjectPage` 已有 `hasLoaded` 守卫 (L235-237),重复调用被跳过                                                                                                                                                                                                                                                                                                 |
| **R11** | reconnect 触发 onConnectionLost 时,onConnectionLost 内部不应再调 stop                       | 🟡     | showServerNotRunning 异步(invokeLater)走 disposeForProject → consumer.stop();此时 `wasConnected = false` 不会递归触发 onConnectionLost                                                                                                                                                                                                                            |
| **R12** | 用户反复点 Shutdown Server 不会反复启动 shutdown 流程                                       | 🟢     | **三层防护闭环**:**防护 1** `shutdownInProgress` CAS 守卫(`OpenCodeServerManager.kt` L220) — 整个 `gracefulShutdown` 重入直接 return;**防护 2** Part A 修复后 `wasConnected` 自防递归(`stop()` 第二次不调 `onConnectionLost`);**防护 3** `showServerNotRunning` 的 `isShowingStartButton` 守卫(L101-104) — 即使 `onConnectionLost` 真的被多次调,UI 端也只执行一次 |

---

## Migration Plan

### 实施顺序(严格 A→B→C→D)

```
Step A: 修 stop() 主动调 onConnectionLost(5 行) + 单元测试
  └─ 验证: graceful shutdown < 100ms
  └─ 提交: feat(shutdown): stop() triggers onConnectionLost proactively

Step B: 修 reconnect() 主动调 onConnectionLost(3 行) + 注释 + 单元测试
  └─ 验证: server 静默 30s 触发后立即切 UI(zombie 回收正常)
  └─ 提交: fix(sse-watcher): reconnect() triggers onConnectionLost proactively

Step C: 新增 onConnectionEstablished 字段 + 1.5s debounce + MyToolWindow 注册(18 行) + 单元测试
  └─ 验证: SSE 重建后自动恢复 web UI(无空状态闪烁超过 1.5s)
  └─ 提交: feat(sse-callback): add onConnectionEstablished for auto-recovery

Step D: 删 HealthMonitor.kt 整个 + 4 个常量 + 散落调用 + SPEC §7.5 同步(-115 行)
  └─ 验证: 4 个场景在生产表现(graceful / crash / silent-crash / network-jitter)
  └─ 提交: refactor(health-check): remove redundant HealthMonitor
```

每步独立 commit / 测 / 回滚。Step D **必须**等 A/B/C 都验证通过。

### 部署步骤

1. **本地构建**: `./gradlew buildPlugin` — 确认编译通过
2. **本地测试**: `./gradlew check` — 跑全部测试(含 Part E 新增的 OpenCodeSSEConsumerTest)
3. **本地 IDE 验证**: `./gradlew runIde` — 手动测 4 个场景:
   - 点 Shutdown Server → < 100ms 切空状态
   - 启 server 后 kill -9 → < 5s 切空状态
   - 静默 server 30s → 切空状态
   - 短暂网络断(模拟)→ 1.5s 内自动恢复 web UI

   **过渡期幂等性验证**(Step C 部署后、Step D 未部署):
   - SSE 重建后,`HealthMonitor.onRecovered`(旧路径)与 `onConnectionEstablished`(新路径)会**同时**调 `loadProjectPage`
   - 验证 `loadProjectPage` 入口 `hasLoaded` 守卫(L313-315)正确拒绝第二次调用
   - 预期:web UI 只加载一次,无重复 dispose/createMainTab 开销

4. **Run UI tests**: `./gradlew runIdeForUiTests` — CI 跑 UI 自动化
5. **发布**: `./gradlew publishPlugin` — 用户显式调用(per AGENTS.md)

### 回滚策略

每步独立可回滚:

- **Step A 回滚**: revert `stop()` 改 1 行即可,UI 切回 15s(原行为)
- **Step B 回滚**: revert `reconnect()` 改 1 行,server 静默恢复成"持续 SSE_WATCHDOG 死循环"(HealthMonitor 在时掩盖,Part D 不实施回滚时本 bug 暴露)
- **Step C 回滚**: revert `onOpen` 末尾 + `onConnectionEstablished` 字段删除 + MyToolWindow 注册恢复,SSE 重建后无自动恢复(用户需手动点 Start)
- **Step D 回滚**: git revert 整个 commit,HealthMonitor.kt 恢复(注意 `disposeForProject` 路径在 D 删除前的所有改动可能与 A/B/C 不兼容,回滚后需重新跑 4 个场景)

### 文档同步

| 文档                        | 改动                                                                                                                                                                                                                 |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SPEC.md` §6.3 关闭策略     | 增加"主动 stop 必须立即触发 UI 切换"                                                                                                                                                                                 |
| `SPEC.md` §7.5 健康检查机制 | **重大更新**: 删除"5s 轮询 + 3 次 debounce"为设计契约,改为"tool window 状态完全由 SSE 4 回调驱动 + SSE_WATCHDOG 30s 兜底 + reconnect 主动通知 + onConnectionEstablished 恢复"。**草案文本**(tasks 阶段定稿前可微调): |

```markdown
### 7.5 健康检查机制(2026-06 更新)

`OpenCodeSSEConsumer` (per-project, `listeners/OpenCodeSSEConsumer.kt`) 提供四类回调支撑 tool window 状态同步,
不再维护独立的 5s 轮询线程:

- `onOpen()`: SSE 建连成功,触发 `onConnectionEstablished`(`MyToolWindow` 调 `loadProjectPage()` 恢复 web UI);
  1.5s debounce 防网络抖动闪烁(对齐 LaunchDarkly `alwaysContinue` 首次重试约 1s + jitter)
- `onMessage()`: 任何事件到达(含 `server.heartbeat` 10s 一次),刷新 `lastEventAt` 并分发业务逻辑
- `onError()`(wasConnected=true): SSE 连接断,触发 `onConnectionLost`(`MyToolWindow` 调 `showServerNotRunning()` 切空状态)
- `SSE_WATCHDOG` 30s idle 强制 reconnect: 触发 `reconnect()`,内部主动调 `onConnectionLost` 避免新 consumer onError
  走 `wasConnected=false` 分支导致的死循环(zombie 立即回收,功能正确)
- `stop()`(生命周期结束): 主动调 `onConnectionLost` 避免 LaunchDarkly onError 因 `wasConnected=false` 旁路
```

| `SPEC.md` §3.1 数据一致性 | 验证 `subagentSessionIds` / `sessionIdleFired` / `idleLastFired` 三个不变量不受影响(tasks 阶段验证) |
| `DESIGN.md` §3.3 关闭时序 | 更新时序图,标注快速通道在 stop 内主动触发 + F1 自杀时序图 |
| `DESIGN.md` §4 核心机制 | **重大更新**: 删除 HealthMonitor 架构图,补充"SSE 回调驱动 + reconnect 主动通知 + onConnectionEstablished 恢复 + F5 lambda 候选 2 时序" |
| `README.md` | 更新 "Crash Detection" 段(15s 不再准确,实际是 < 5s / ~30s 不同场景) |

---

## Open Questions

- **OQ1**: 1-2s debounce on `onConnectionLost`(Decision 6 暂不实施)是否真的该放 follow-up?如果用户反馈强烈,可能在 tasks 阶段重新决策
- **OQ2**: 端到端测试框架(Part E 标注 [NEEDS INVESTIGATION]): 4 个场景的集成测试是否需要新建测试基础设施?tasks 阶段需调研 `runIdeForUiTests` + robot-server 现状
- **OQ3**: `sse-streaming-reader` change 文档同步: 本 change 修改 `server.heartbeat` 间接相关行为(`lastEventAt` 持续更新路径不变),但 sse-streaming-reader 文档刚加了"实施时调整" note。tasks 阶段需 grep `lastHeartbeatAt` 所有引用,确认无矛盾
- **OQ4**: `MyToolWindow` 实际调用点: `getOrCreateConsumer` 当前有 3 个调用点(grep tasks 阶段确认),lambda 候选 2 方案需逐一适配
- **OQ5**: 自杀时序是否需要在 `OpenCodeServerManager.gracefulShutdown` 也加 `wasConnected` 守卫?目前 stop() 内的守卫已足够覆盖所有路径,但 design 阶段需 grep 确认无遗漏
