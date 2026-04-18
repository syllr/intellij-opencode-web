# Plan: 添加 OpenCode 启动调试日志

## 目标

在 OpenCode 启动过程中添加详细日志，帮助定位启动失败的根本原因。
**注意**：这些日志是临时的，完成排查后需要删除。

## 需要修改的文件

1. `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`
2. `src/main/kotlin/com/github/xausky/opencodewebui/utils/OpenCodeApi.kt`

## 日志标记格式

所有调试日志使用以下格式，方便后续批量删除：
```
// [DEBUG-STARTUP-001] 日志内容 // [DEBUG-STARTUP-END]
```

## 修改点

### 1. MyToolWindowFactory.kt - `startOpenCodeServer()` 方法

**位置**：约第 427 行

```kotlin
// [DEBUG-STARTUP-001] startOpenCodeServer 开始 // [DEBUG-STARTUP-END]
println("[DEBUG-STARTUP-001] startOpenCodeServer called at ${System.currentTimeMillis()}")
thisLogger().info("[DEBUG-STARTUP-001] startOpenCodeServer called")

val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
    override fun run(indicator: ProgressIndicator) {
        try {
            // [DEBUG-STARTUP-002] // [DEBUG-STARTUP-END]
            println("[DEBUG-STARTUP-002] About to call startOpenCodeProcess at ${System.currentTimeMillis()}")
            thisLogger().info("[DEBUG-STARTUP-002] About to call startOpenCodeProcess")

            val handler = startOpenCodeProcess()
            serverProcess.set(handler)

            // [DEBUG-STARTUP-003] // [DEBUG-STARTUP-END]
            println("[DEBUG-STARTUP-003] startOpenCodeProcess returned: $handler at ${System.currentTimeMillis()}")
            thisLogger().info("[DEBUG-STARTUP-003] startOpenCodeProcess returned: $handler")

            // [DEBUG-STARTUP-004] // [DEBUG-STARTUP-END]
            println("[DEBUG-STARTUP-004] Waiting for server healthy, timeout: 30000ms at ${System.currentTimeMillis()}")
            thisLogger().info("[DEBUG-STARTUP-004] Waiting for server to be healthy (timeout: 30000ms)")

            val healthy = OpenCodeApi.waitForServerHealthy(30000)

            // [DEBUG-STARTUP-005] // [DEBUG-STARTUP-END]
            println("[DEBUG-STARTUP-005] waitForServerHealthy result: $healthy at ${System.currentTimeMillis()}")
            thisLogger().info("[DEBUG-STARTUP-005] waitForServerHealthy result: $healthy")

            if (healthy) {
                // [DEBUG-STARTUP-006] // [DEBUG-STARTUP-END]
                println("[DEBUG-STARTUP-006] Server healthy, calling onServerStarted at ${System.currentTimeMillis()}")
                thisLogger().info("[DEBUG-STARTUP-006] Server is healthy, calling onServerStarted")
                onServerStarted()
            } else {
                // [DEBUG-STARTUP-007] // [DEBUG-STARTUP-END]
                println("[DEBUG-STARTUP-007] Server NOT healthy but proceeding anyway at ${System.currentTimeMillis()}")
                thisLogger().warn("[DEBUG-STARTUP-007] Server may not be ready, proceeding anyway")
                onServerStarted()
            }
        } catch (e: Exception) {
            // [DEBUG-STARTUP-008] // [DEBUG-STARTUP-END]
            println("[DEBUG-STARTUP-008] Exception in startOpenCodeServer: ${e.message} at ${System.currentTimeMillis()}")
            e.printStackTrace()
            thisLogger().error("[DEBUG-STARTUP-008] Exception in startOpenCodeServer: ${e.message}", e)
            onServerStartFailed(e)
        }
    }
}
ProgressManager.getInstance().run(task)
```

### 2. MyToolWindowFactory.kt - `startOpenCodeProcess()` 方法

**位置**：约第 448 行

```kotlin
// [DEBUG-STARTUP-010] startOpenCodeProcess 开始 // [DEBUG-STARTUP-END]
println("[DEBUG-STARTUP-010] startOpenCodeProcess called at ${System.currentTimeMillis()}")
thisLogger().info("[DEBUG-STARTUP-010] startOpenCodeProcess called")

private fun startOpenCodeProcess(): ProcessHandler {
    val command = getOpenCodeCommand()

    // [DEBUG-STARTUP-011] // [DEBUG-STARTUP-END]
    println("[DEBUG-STARTUP-011] OpenCode command: $command at ${System.currentTimeMillis()}")
    thisLogger().info("[DEBUG-STARTUP-011] OpenCode command: $command")

    val commandLine = GeneralCommandLine(command)
    val fullEnv = getFullEnvironment()

    // [DEBUG-STARTUP-012] // [DEBUG-STARTUP-END]
    println("[DEBUG-STARTUP-012] Environment keys: ${fullEnv.keys.joinToString(", ")} at ${System.currentTimeMillis()}")
    thisLogger().info("[DEBUG-STARTUP-012] Environment keys: ${fullEnv.keys.joinToString(", ")}")

    // 打印关键环境变量
    // [DEBUG-STARTUP-013] // [DEBUG-STARTUP-END]
    println("[DEBUG-STARTUP-013] PATH: ${fullEnv["PATH"]?.take(200)}... at ${System.currentTimeMillis()}")
    thisLogger().info("[DEBUG-STARTUP-013] PATH: ${fullEnv["PATH"]?.take(200)}...")
    println("[DEBUG-STARTUP-013] HOME: ${fullEnv["HOME"]} at ${System.currentTimeMillis()}")
    thisLogger().info("[DEBUG-STARTUP-013] HOME: ${fullEnv["HOME"]}")

    commandLine.environment.clear()
    commandLine.environment.putAll(fullEnv)

    // [DEBUG-STARTUP-014] // [DEBUG-STARTUP-END]
    println("[DEBUG-STARTUP-014] About to create process handler at ${System.currentTimeMillis()}")
    thisLogger().info("[DEBUG-STARTUP-014] About to create process handler")

    val handler = ProcessHandlerFactory.getInstance().createProcessHandler(commandLine)

    // [DEBUG-STARTUP-015] // [DEBUG-STARTUP-END]
    println("[DEBUG-STARTUP-015] Process handler created: $handler at ${System.currentTimeMillis()}")
    thisLogger().info("[DEBUG-STARTUP-015] Process handler created: $handler")

    return handler
}
```

### 3. OpenCodeApi.kt - `waitForServerHealthy()` 方法

**位置**：约第 182 行

```kotlin
// [DEBUG-STARTUP-020] waitForServerHealthy 开始 // [DEBUG-STARTUP-END]
println("[DEBUG-STARTUP-020] waitForServerHealthy called, timeout: $timeoutMs at ${System.currentTimeMillis()}")
thisLogger().info("[DEBUG-STARTUP-020] waitForServerHealthy called (timeout: $timeoutMs)")

fun waitForServerHealthy(timeoutMs: Long): Boolean {
    // [DEBUG-STARTUP-021] // [DEBUG-STARTUP-END]
    println("[DEBUG-STARTUP-021] Sleeping 5000ms for server startup at ${System.currentTimeMillis()}")
    thisLogger().info("[DEBUG-STARTUP-021] Sleeping 5000ms for server startup")
    Thread.sleep(5000)

    val startTime = System.currentTimeMillis()
    val interval = 2000L
    var checkCount = 0

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        checkCount++
        val healthy = isServerHealthySync()

        // [DEBUG-STARTUP-022] // [DEBUG-STARTUP-END]
        println("[DEBUG-STARTUP-022] Health check #$checkCount at ${System.currentTimeMillis() - startTime}ms: $healthy at ${System.currentTimeMillis()}")
        thisLogger().info("[DEBUG-STARTUP-022] Health check #$checkCount at ${System.currentTimeMillis() - startTime}ms: $healthy")

        if (healthy) {
            // [DEBUG-STARTUP-023] // [DEBUG-STARTUP-END]
            println("[DEBUG-STARTUP-023] Server healthy after ${System.currentTimeMillis() - startTime}ms at ${System.currentTimeMillis()}")
            thisLogger().info("[DEBUG-STARTUP-023] Server is healthy after ${System.currentTimeMillis() - startTime}ms")
            return true
        }
        Thread.sleep(interval)
    }

    // [DEBUG-STARTUP-024] // [DEBUG-STARTUP-END]
    println("[DEBUG-STARTUP-024] waitForServerHealthy timed out after $timeoutMs ms, checks: $checkCount at ${System.currentTimeMillis()}")
    thisLogger().warn("[DEBUG-STARTUP-024] waitForServerHealthy timed out after $timeoutMs ms, checks: $checkCount")
    return false
}
```

### 4. OpenCodeApi.kt - `isServerHealthySync()` 方法

**位置**：约第 166 行

```kotlin
// [DEBUG-STARTUP-025] isServerHealthySync 开始 // [DEBUG-STARTUP-END]
fun isServerHealthySync(): Boolean {
    var connection: HttpURLConnection? = null
    return try {
        val url = URL("http://$HOST:$PORT/global/health")
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000

        // [DEBUG-STARTUP-026] // [DEBUG-STARTUP-END]
        val responseCode = connection.responseCode
        println("[DEBUG-STARTUP-026] Health check response code: $responseCode at ${System.currentTimeMillis()}")
        thisLogger().info("[DEBUG-STARTUP-026] Health check response code: $responseCode")

        responseCode == 200
    } catch (e: Exception) {
        // [DEBUG-STARTUP-027] // [DEBUG-STARTUP-END]
        println("[DEBUG-STARTUP-027] Health check failed: ${e.javaClass.simpleName}: ${e.message} at ${System.currentTimeMillis()}")
        thisLogger().info("[DEBUG-STARTUP-027] Health check failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    } finally {
        connection?.disconnect()
    }
}
```

## 日志删除方法

完成排查后，使用以下命令批量删除所有调试日志：

```bash
# 删除 MyToolWindowFactory.kt 中的调试日志
sed -i '' '/\/\/ \[DEBUG-STARTUP/d' src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt

# 删除 OpenCodeApi.kt 中的调试日志
sed -i '' '/\/\/ \[DEBUG-STARTUP/d' src/main/kotlin/com/github/xausky/opencodewebui/utils/OpenCodeApi.kt
```

## 验证步骤

1. 编译：`./gradlew buildPlugin`
2. 运行：`./gradlew runIde`
3. 复现问题：打开/关闭 OpenCode 工具窗口多次
4. 查看日志：
   ```bash
   # 查看所有调试日志
   grep "\[DEBUG-STARTUP" build/idea-sandbox/IU-2025.3.4/log/idea.log | tail -200

   # 只看错误相关
   grep "\[DEBUG-STARTUP-008\|Exception" build/idea-sandbox/IU-2025.3.4/log/idea.log | tail -50
   ```

## 日志文件位置

```
/Users/yutao/IdeaProjects/intellij-opencode-web/build/idea-sandbox/IU-2025.3.4/log/idea.log
```