# 简化架构 - 全局健康检查状态

## TL;DR
健康检查结果存储在全局变量中，工具窗口读取该变量决定显示启动面板还是加载页面

## 架构

```
IDEA 启动
    │
    ▼
MyStartupActivity
    │
    ▼
健康检查线程 (startGlobalHealthCheck)
    │
    ▼
while (true) {
    isServerHealthy = checkHealth()  // 写入全局变量 serverHealthy
    Thread.sleep(5s)
}

工具窗口 (toolWindow)
    │
    ▼
定时读取 serverHealthy
    │
    ├─ false → showStartButton()
    └─ true  → loadOpenCodeWebPage()
```

## 全局变量

```kotlin
// companion object 中
private val serverHealthy = AtomicBoolean(false)  // 全局健康状态

// 健康检查线程写入
fun startGlobalHealthCheck() {
    thread {
        while (true) {
            val healthy = OpenCodeApi.isServerHealthySync()
            serverHealthy.set(healthy)
            Thread.sleep(5000)
        }
    }
}

// 工具窗口读取（只读）
fun isServerHealthy() = serverHealthy.get()
```

## 修改清单

### 1. MyToolWindowFactory.kt - 添加全局健康检查

```kotlin
companion object {
    // 现有的
    private val serverRunning = AtomicBoolean(false)

    // 新增：全局健康状态
    private val serverHealthy = AtomicBoolean(false)

    // 新增：启动全局健康检查
    fun startGlobalHealthCheck() {
        thread {
            while (true) {
                val healthy = OpenCodeApi.isServerHealthySync()
                serverHealthy.set(healthy)
                thisLogger().info("Global health check: $healthy")
                Thread.sleep(5000)
            }
        }
    }

    // 供工具窗口读取
    fun isServerHealthy() = serverHealthy.get()
}
```

### 2. MyStartupActivity.kt

```kotlin
class MyStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        // 启动全局健康检查
        MyToolWindowFactory.startGlobalHealthCheck()

        // 注册 disposable
        val applicationDisposable = ApplicationManager.getApplication()
        val disposable = MyApplicationDisposable()
        Disposer.register(applicationDisposable, disposable)
    }
}
```

### 3. MyToolWindowFactory.kt - 简化 checkAndLoadContent

```kotlin
fun checkAndLoadContent() {
    // 直接读取全局健康状态
    if (isServerHealthy()) {
        loadProjectPage()
    } else {
        showServerNotRunning()
    }
}
```

### 4. 删除

- 删除 `startPeriodicCheck()` 函数
- 删除 `onServerBecameUnhealthy()` 函数
- 删除 `createToolWindowContent` 中的 `startPeriodicCheck()` 调用
- 删除 `myToolWindow.startPeriodicCheck()`

## 文件修改

| 文件 | 修改 |
|------|------|
| MyToolWindowFactory.kt | 添加全局健康检查，简化 checkAndLoadContent |
| MyStartupActivity.kt | 启动全局健康检查 |
| 删除死代码 | startPeriodicCheck, onServerBecameUnhealthy |

## TODO

- [x] 1. MyToolWindowFactory 添加全局健康状态和函数
- [x] 2. MyStartupActivity 调用 startGlobalHealthCheck
- [x] 3. 简化 checkAndLoadContent 直接读取全局状态
- [x] 4. 删除 startPeriodicCheck 和 onServerBecameUnhealthy
- [x] 5. 删除 createToolWindowContent 中的 startPeriodicCheck 调用
- [x] 6. 构建验证

## Commit Strategy
- Commit: YES
