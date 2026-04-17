# 重构 OpenCode Server 健康检查逻辑

## TL;DR
将端口检测改为健康检查接口，每个函数不超过 30 行

## 问题分析

### 当前问题
1. `checkPortOpen()` 只检查 TCP 端口，不能判断服务器真正可用
2. 多个地方重复检查端口，逻辑分散
3. `waitForServerReady()` 复杂且重复

### 重构目标
1. 统一使用 `OpenCodeApi.checkHealth()` 健康检查
2. 每个函数不超过 30 行
3. 逻辑清晰，职责单一

---

## 重构后的代码结构

### 1. OpenCodeApi.kt 扩展

```kotlin
// 新增：同步健康检查（5行）
fun isServerHealthySync(): Boolean {
    return try {
        val url = URL("http://$HOST:$PORT/global/health")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        connection.responseCode == 200
    } catch (e: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

// 新增：等待服务器健康（15行）
fun waitForServerHealthy(timeoutMs: Long): Boolean {
    val startTime = System.currentTimeMillis()
    val interval = 500L
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        if (isServerHealthySync()) return true
        Thread.sleep(interval)
    }
    return false
}
```

### 2. MyToolWindowFactory.kt 重构

#### 新增常量与状态
```kotlin
companion object {
    private const val PORT = 12396
    private const val HOST = "127.0.0.1"

    private val browserInstance = AtomicReference<JBCefBrowser?>(null)
    private val serverRunning = AtomicBoolean(false)
    private val serverProcess = AtomicReference<Process?>(null)
    private val isRestarting = AtomicBoolean(false)
    private var myToolWindowInstance: MyToolWindow? = null
    private var checkScheduledFuture: ScheduledFuture<*>? = null
    private var updateScheduledFuture: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "OpenCode-Server-Checker")
    }
}
```

#### 状态判断（3个函数，每个<15行）
```kotlin
// 【核心】判断服务器是否健康（5行）
private fun isServerHealthy(): Boolean = OpenCodeApi.isServerHealthySync()

// 判断浏览器是否需要重建（8行）
private fun needNewBrowser(): Boolean {
    return browserInstance.get() == null || mainBrowser == null
}

// 判断服务器是否运行（10行）
private fun isServerRunning(): Boolean {
    return if (isServerHealthy()) {
        serverRunning.set(true)
        true
    } else {
        serverRunning.set(false)
        false
    }
}
```

#### 健康检查与加载（3个函数，每个<20行）
```kotlin
// 【主入口】检查并加载内容（18行）
fun checkAndLoadContent() {
    val projectPath = project.basePath ?: return

    if (isServerRunning()) {
        loadProjectPage()
    } else {
        showServerNotRunning()
    }
}

// 显示服务器未运行面板（8行）
private fun showServerNotRunning() {
    serverRunning.set(false)
    mainBrowser = null
    browserPanel.disposeBrowser()
    browserPanel.showStartButton { startOpenCodeServer() }
}

// 加载项目页面（12行）
private fun loadProjectPage() {
    val projectPath = project.basePath ?: return
    val sessionId = SessionHelper.getLatestSessionId(projectPath)
    val url = buildProjectUrl(projectPath, sessionId)
    thisLogger().info("Loading page: $url")

    browserPanel.hideStartButton()
    if (mainBrowser == null) {
        mainBrowser = browserPanel.createMainTab(url, projectPath)
        browserInstance.set(mainBrowser)
        setupBrowserComponent(mainBrowser!!)
    } else {
        mainBrowser?.loadURL(url)
    }
}
```

#### 启动服务器（2个函数，每个<25行）
```kotlin
// 启动服务器（22行）
private fun startOpenCodeServer() {
    val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val logFile = createLogFile()
                val process = startOpenCodeProcess(logFile)
                serverProcess.set(process)

                if (OpenCodeApi.waitForServerHealthy(30000)) {
                    onServerStarted()
                } else {
                    thisLogger().warn("Server may not be ready, proceeding anyway")
                    onServerStarted()
                }
            } catch (e: Exception) {
                onServerStartFailed(e)
            }
        }
    }
    ProgressManager.getInstance().run(task)
}

// 辅助：创建日志文件（6行）
private fun createLogFile(): File {
    val logDir = File(System.getProperty("user.home"), "Desktop/tmp")
    logDir.mkdirs()
    return File(logDir, "opencode.log")
}

// 辅助：启动进程（10行）
private fun startOpenCodeProcess(logFile: File): Process {
    val processBuilder = ProcessBuilder()
        .command(getOpenCodeCommand())
        .redirectOutput(logFile)
        .redirectErrorStream(true)
    processBuilder.environment().putAll(getEnvironment())
    return processBuilder.start()
}

// 辅助：服务器启动成功回调（6行）
private fun onServerStarted() {
    serverRunning.set(true)
    ApplicationManager.getApplication().invokeLater { loadProjectPage() }
}

// 辅助：服务器启动失败回调（6行）
private fun onServerStartFailed(e: Exception) {
    thisLogger().error("Error starting OpenCode server: ${e.message}")
    serverRunning.set(false)
    ApplicationManager.getApplication().invokeLater { showErrorInBrowser() }
}
```

#### 定期检查（2个函数，每个<20行）
```kotlin
// 【每5秒】健康检查（16行）
fun startPeriodicCheck() {
    if (checkScheduledFuture != null) return

    checkScheduledFuture = scheduler.scheduleAtFixedRate({
        ApplicationManager.getApplication().invokeLater {
            if (!isServerHealthy()) {
                onServerBecameUnhealthy()
            }
        }
    }, 5L, 5L, TimeUnit.SECONDS)
}

// 服务器变得不健康时的处理（8行）
private fun onServerBecameUnhealthy() {
    thisLogger().info("Server became unhealthy, showing start button")
    browserPanel.disposeBrowser()
    mainBrowser = null
    browserInstance.set(null)
    showServerNotRunning()
}
```

#### 工具函数
```kotlin
// 构建项目 URL（8行）
private fun buildProjectUrl(projectPath: String, sessionId: String?): String {
    val encodedPath = Base64.getEncoder()
        .encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
    return if (sessionId != null) {
        "http://$HOST:$PORT/$encodedPath/session/$sessionId"
    } else {
        "http://$HOST:$PORT/$encodedPath"
    }
}

// 获取 opencode 命令（8行）
private fun getOpenCodeCommand(): List<String> {
    val path = findOpenCodePath()
    return listOf(path, "serve", "--hostname", HOST, "--port", PORT.toString())
}

// 查找 opencode 路径（10行）
private fun findOpenCodePath(): String {
    listOf("/opt/homebrew/bin/opencode", "/usr/local/bin/opencode")
        .forEach { if (File(it).exists()) return it }
    return "opencode"
}

// 获取环境变量（8行）
private fun getEnvironment(): Map<String, String> {
    val env = mutableMapOf<String, String>()
    env.putAll(System.getenv())
    val additionalPaths = listOf("/usr/local/bin", "/opt/homebrew/bin")
        .filterNot { env["PATH"]?.contains(it) == true }
    env["PATH"] = (additionalPaths + (env["PATH"] ?: "")).joinToString(":")
    return env
}
```

#### 停止服务器（12行）
```kotlin
fun stopServer() {
    checkScheduledFuture?.cancel(true)
    updateScheduledFuture?.cancel(true)
    serverProcess.get()?.let {
        if (it.isAlive) it.destroy()
    }
    killProcessByPort(PORT)
    serverRunning.set(false)
    serverProcess.set(null)
}
```

---

## 文件修改清单

### OpenCodeApi.kt
| 修改 | 说明 |
|------|------|
| + `isServerHealthySync()` | 同步健康检查 |
| + `waitForServerHealthy(timeoutMs)` | 等待健康检查通过 |

### MyToolWindowFactory.kt
| 函数 | 行数 | 说明 |
|------|------|------|
| `isServerHealthy()` | 5 | 封装健康检查 |
| `needNewBrowser()` | 8 | 判断是否需要重建浏览器 |
| `isServerRunning()` | 10 | 统一状态判断 |
| `checkAndLoadContent()` | 18 | 主入口，简化逻辑 |
| `showServerNotRunning()` | 8 | 显示启动按钮 |
| `loadProjectPage()` | 12 | 加载页面 |
| `startOpenCodeServer()` | 22 | 启动服务器 |
| `createLogFile()` | 6 | 辅助函数 |
| `startOpenCodeProcess()` | 10 | 辅助函数 |
| `onServerStarted()` | 6 | 成功回调 |
| `onServerStartFailed()` | 6 | 失败回调 |
| `startPeriodicCheck()` | 16 | 定期检查 |
| `onServerBecameUnhealthy()` | 8 | 不健康处理 |
| `buildProjectUrl()` | 8 | 工具函数 |
| `getOpenCodeCommand()` | 8 | 工具函数 |
| `findOpenCodePath()` | 10 | 工具函数 |
| `getEnvironment()` | 8 | 工具函数 |
| `stopServer()` | 12 | 停止服务器 |

**删除的旧函数**：
- `checkPortOpen()` - 用 `isServerHealthy()` 替代
- `checkPortOpenInternal()` - 用 `isServerHealthy()` 替代
- `waitForServerReady()` - 用 `OpenCodeApi.waitForServerHealthy()` 替代
- `checkServerHealth()` - 合并到 `isServerRunning()`
- `restartServer()` - 简化逻辑

---

## TODO

- [x] 1. OpenCodeApi 添加 `isServerHealthySync()` 和 `waitForServerHealthy()`
- [x] 2. MyToolWindowFactory 重构 companion object 状态管理
- [x] 3. MyToolWindow 重构健康检查与加载逻辑
- [x] 4. MyToolWindow 重构服务器启动逻辑
- [x] 5. MyToolWindow 重构定期检查逻辑
- [x] 6. MyToolWindow 添加工具函数
- [x] 7. 删除废弃的旧函数
- [x] 8. 测试完整流程

---

## Commit Strategy
- Commit: YES（待用户确认）
- Message: `refactor: 使用健康检查接口，代码模块化，每个函数<30行`
