# 插件性能退化分析：内存泄漏与资源清理

**日期**: 2026-05-30
**症状**: 插件运行久了 IDE 变卡，重启后立即恢复
**怀疑方向**: 内存泄漏、线程泄漏、连接泄漏
**关联**: 无（2026-06 清理：原"与 jcef-focus-ime 研究密切相关"的关联已删除，jcef-focus-ime 调研整体废弃）

> ⚠️ **2026-06 状态更新**（archive 留存说明）
>
> 本调研中部分"待修复"问题在后续清理中**通过删除代码**而非"修好"的方式解决：
>
> | 原问题                                      | 调研建议                     | 2026-06 实际处置                                                                                                                      |
> | ------------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
> | CRITICAL #1 WindowFocusListener 无限累积    | 修复 listener 引用追踪防累积 | **`requestBrowserFocus()` + `setupWindowFocusListener()` + 整个 WindowFocusListener 机制已删除**（jcef-focus-ime 调研方向被实测无效） |
> | CRITICAL #3 ContentManagerListener 从不移除 | 改成命名 listener + 反向清理 | **整个 ContentManagerListener 已删除**（只服务 `requestBrowserFocus()`，随 #1 一起删）                                                |
> | jcef-focus-ime 关联                         | 实施 FocusAdapter            | **`research/jcef-focus-ime/` 整个目录已删除**（实测无效）                                                                             |
>
> **当前代码基线不包含上述被删的代码**。下文保留为历史分析记录，不应再作为"待办"参考。**修复优先级表**和**实施建议**章节**已不适用**。

---

## 一、泄漏点总览

| 严重度          | 问题                                | 文件:行号                         | 类型          |
| --------------- | ----------------------------------- | --------------------------------- | ------------- |
| 🔴 **CRITICAL** | sharedClient Handler 累积           | `BrowserPanel.kt:109-146`         | 资源泄漏      |
| 🟡 **HIGH**     | SSE Consumer 不随项目关闭停止       | `MyToolWindow.kt:31-36`           | 线程/连接泄漏 |
| 🟡 **HIGH**     | FullRefreshCoordinator 调度器泄漏   | `FullRefreshCoordinator.kt:39-43` | 线程泄漏      |
| 🟡 **HIGH**     | MessageBusConnection 不断开         | `MyToolWindowFactory.kt:25`       | 连接泄漏      |
| 🟠 **MEDIUM**   | EmacsKeyHandler KeyListener 不移除  | `EmacsKeyHandler.kt:20-24`        | 监听器泄漏    |
| 🟠 **MEDIUM**   | HealthMonitor 多次 start 不重入保护 | `HealthMonitor.kt:28-29`          | 线程泄漏      |

---

## 二、逐项深度分析

---

### 🔴 CRITICAL #2: sharedClient Handler 累积

**文件**: `BrowserPanel.kt:103-151`

```kotlin
fun createMainTab(url: String, projectPath: String): JBCefBrowser {
    if (browser == null) {
        val createdBrowser = JBCefBrowserBuilder().setClient(sharedClient).setUrl(url).build()
        // 每次创建浏览器，都向 sharedClient 添加 3 个 handler：
        sharedClient.addContextMenuHandler(LinkContextMenuHandler(), createdBrowser.cefBrowser)  // 109
        sharedClient.addLoadHandler(..., createdBrowser.cefBrowser)  // 110
        sharedClient.addDisplayHandler(..., createdBrowser.cefBrowser)  // 129
    }
}
```

**泄漏机制**：

- `sharedClient` 是全局单例（`MyToolWindowFactory.sharedJBCefClient`）
- 每次浏览器重建（服务器重启后），向 sharedClient 添加新 handler
- `disposeBrowser()` 调用 `browser?.dispose()` 清理浏览器
- 但 `sharedClient` 上注册的 handler **不会被自动清理**
- 每次服务器重启，handler 数量增加

**影响**：

- 每次 console 消息，所有 DisplayHandler 都被回调
- 每次页面加载，所有 LoadHandler 都被回调
- 内存中持有对旧浏览器和面板的引用，阻止 GC

**修复**：在 `disposeBrowser()` 中移除 handler，或使用 per-browser client。

---

---

### 🟡 HIGH #4: SSE Consumer 不随项目关闭停止

**文件**: `MyToolWindow.kt:31-36`

```kotlin
Disposer.register(project) {
    logger.info("[Lifecycle] Cleaning up MyToolWindow for project=${project.name}")
    MyToolWindowFactory.myToolWindowInstances.remove(project)
    healthMonitor.stop()
    browserPanel.disposeBrowser()
    // ⚠️ 缺少：sseConsumer?.stop()
}
```

**泄漏机制**：

- `OpenCodeServerManager.sseConsumer` 是 `object`（全局单例）上的 `@Volatile var`
- `MyToolWindow` 关闭时停止了 `healthMonitor` 和 `browser`
- 但**没有停止 SSE consumer**
- `BackgroundEventSource` 持续运行，维持 HTTP 连接和后台线程
- SSE 事件持续处理，触发文件刷新（`FullRefreshCoordinator.request()`）

**影响**：

- 后台线程持续运行，消耗 CPU
- HTTP 连接池占用内存
- 文件刷新操作持续执行

---

### 🟡 HIGH #5: FullRefreshCoordinator 调度器泄漏

**文件**: `FullRefreshCoordinator.kt:33-44`

```kotlin
object FullRefreshCoordinator {
    private var scheduler: ScheduledExecutorService? = null

    fun start(projectPath: String) {
        if (scheduler != null) return  // 只检查非 null，不检查是否 shutdown
        val s = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "FullRefreshWorker") }
        s.scheduleWithFixedDelay(::tick, ...)
        scheduler = s
    }
}
```

**泄漏机制**：

- `FullRefreshCoordinator` 是 `object` 单例
- `start()` 创建 `ScheduledExecutorService`，`stop()` 调用 `shutdownNow()`
- 但如果 `stop()` 从未被调用（SSE consumer 未正确停止），调度器永远运行
- 调度器每 500ms 执行 `tick()`，即使没有待处理请求
- `tick()` 中 `LocalFileSystem.getInstance().refreshIoFiles(...)` 每次都遍历项目文件树

---

### 🟡 HIGH #6: MessageBusConnection 不断开

**文件**: `MyToolWindowFactory.kt:25`

```kotlin
init {
    val connection: MessageBusConnection = ApplicationManager.getApplication().messageBus.connect()
    connection.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
        override fun projectOpened(project: Project) { ... }
        override fun projectClosing(project: Project) { ... }
    })
    // ⚠️ connection 是局部变量，没有被存储，也没有 disconnect()
}
```

**泄漏机制**：

- 连接到应用级 MessageBus 的连接没有被存储
- 无法在插件卸载时断开
- 订阅的 `ProjectManagerListener` 永久存活

---

### 🟠 MEDIUM #7: EmacsKeyHandler KeyListener 不移除

**文件**: `EmacsKeyHandler.kt:20-24`

```kotlin
component.addKeyListener(object : java.awt.event.KeyAdapter() {
    override fun keyPressed(e: KeyEvent) {
        handleEmacsKey(e, emacsMappings, component)
    }
})
```

**影响**：每次 `setupBrowserComponent()` 调用都添加一个新的 KeyListener。虽然旧浏览器组件会被 GC，但如果同一组件被多次设置，会累积。

---

### 🟠 MEDIUM #8: HealthMonitor 重入问题

**文件**: `HealthMonitor.kt:27-63`

```kotlin
fun start() {
    if (started) return  // 防重入，但 stop() 重置 started=false 后可再 start
    started = true
    running = true
    monitorThread = Thread { ... }.apply { start() }
}

fun stop() {
    running = false
    started = false
    monitorThread?.interrupt()
    monitorThread = null
}
```

**风险**：如果 `stop()` 被调用后立即 `start()`，线程切换可能有竞态。但当前代码用 `@Volatile` 保护，问题不大。

---

## 四、验证方法

### 4.1 线程转储

```bash
# 获取 IDE 进程 PID
jps -l | grep idea

# 线程转储（查找 FullRefreshWorker、HealthMonitor 线程）
jstack -l <PID> | grep -A 5 "FullRefreshWorker\|HealthMonitor\|BackgroundEventSource"
```

### 4.3 内存分析

```bash
# 堆转储
jmap -dump:format=b,file=heap.bin <PID>

# 或使用 jcmd
jcmd <PID> GC.heap_dump heap.bin
```

---

## 五、修复优先级

| 优先级 | 问题                          | 修复复杂度 | 预期效果           |
| ------ | ----------------------------- | ---------- | ------------------ |
| 🔴 P0  | sharedClient Handler 累积     | 中         | 减少 Chromium 负担 |
| 🟡 P1  | SSE Consumer 不停止           | 中         | 减少后台线程       |
| 🟡 P1  | FullRefreshCoordinator 调度器 | 低         | 停止无用刷新       |
| 🟡 P1  | MessageBusConnection 不断开   | 低         | 规范化生命周期     |

---

## 六、实施建议

### 第一步：修复 sharedClient Handler 累积

在 `BrowserPanel.disposeBrowser()` 中清理：

```kotlin
fun disposeBrowser() {
    browser?.cefBrowser?.stopLoad()
    // 移除 sharedClient 上的 handler
    // 注意：JBCefClient API 可能需要按 browser 参数移除
    browser?.dispose()
    removeAll()
    browser = null
}
```

### 第三步：在 MyToolWindow 关闭时停止 SSE Consumer

```kotlin
Disposer.register(project) {
    MyToolWindowFactory.myToolWindowInstances.remove(project)
    healthMonitor.stop()
    browserPanel.disposeBrowser()
    OpenCodeServerManager.stopSSEConsumerOnly()  // 新方法，只停 SSE
}
```

---

## 七、调研文档索引

| 文件                                               | 内容                           |
| -------------------------------------------------- | ------------------------------ |
| 本文件                                             | 性能退化/泄漏分析（当前）      |
| `../jcef-focus-ime/README.md`                      | **已删除**（2026-06 整体废弃） |
| `../jcef-focus-ime/21-jcef-osr-focus-deep-dive.md` | **已删除**（2026-06 整体废弃） |
| `../idea-plugin-integration/README.md`             | IDEA 插件功能集成              |
