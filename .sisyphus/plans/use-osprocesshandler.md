# 使用 OSProcessHandler 替换 ProcessBuilder

## TL;DR
将 `ProcessBuilder` 启动进程的方式改为 IntelliJ Platform 官方的 `OSProcessHandler`，解决性能问题

## Context
用户发现 `ProcessBuilder` 启动的 OpenCode server 性能不如直接在 terminal 启动的好。需要使用 IntelliJ 官方的 `OSProcessHandler` 来改进。

## 问题分析

### 当前实现（ProcessBuilder）
```kotlin
private fun startOpenCodeProcess(logFile: java.io.File): Process {
    val processBuilder = ProcessBuilder()
        .command(getOpenCodeCommand())
        .redirectOutput(logFile)      // 问题：同步写入文件，阻塞进程
        .redirectErrorStream(true)
    processBuilder.environment().putAll(getEnvironment())
    return processBuilder.start()
}
```

### 问题
1. `.redirectOutput(logFile)` - 所有输出同步写入文件，阻塞进程
2. 没有进程生命周期管理
3. 与 IntelliJ 平台集成差

---

## 修改方案

### 1. 添加必要的 import

```kotlin
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessHandler
```

### 2. 修改 `serverProcess` 类型

从：
```kotlin
private val serverProcess = AtomicReference<Process?>(null)
```

改为：
```kotlin
private val serverProcess = AtomicReference<ProcessHandler?>(null)
```

### 3. 重写 `startOpenCodeProcess` 函数

```kotlin
private fun startOpenCodeProcess(): ProcessHandler {
    val commandLine = GeneralCommandLine(getOpenCodeCommand())
    commandLine.environment.clear()
    commandLine.environment.putAll(getEnvironment())

    val handler = ProcessHandlerFactory.getInstance()
        .createProcessHandler(commandLine)

    handler.startProcess()
    return handler
}
```

### 4. 修改 `stopServer` 函数

```kotlin
fun stopServer() {
    checkScheduledFuture?.cancel(true)
    checkScheduledFuture = null
    updateScheduledFuture?.cancel(true)
    updateScheduledFuture = null

    serverProcess.get()?.let { handler ->
        if (!handler.isProcessTerminated) {
            handler.destroyProcess()
            thisLogger().info("OpenCode server stopped (via ProcessHandler)")
        }
    }

    serverRunning.set(false)
    serverProcess.set(null)
}
```

### 5. 修改 `startOpenCodeServer` 调用

移除 `logFile` 参数：
```kotlin
private fun startOpenCodeServer() {
    val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val handler = startOpenCodeProcess()
                serverProcess.set(handler)

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
```

---

## 文件修改清单

| 位置 | 修改 |
|------|------|
| MyToolWindowFactory.kt imports | 添加 4 个 import |
| `serverProcess` 类型 | `AtomicReference<Process>` → `AtomicReference<ProcessHandler>` |
| `startOpenCodeProcess()` | 重写，使用 GeneralCommandLine + OSProcessHandler |
| `startOpenCodeServer()` | 移除 logFile 参数 |
| `stopServer()` | 改用 `handler.destroyProcess()` |
| `createLogFile()` | 可删除（不再需要） |

---

## TODO

- [x] 1. 添加 OSProcessHandler 相关 import
- [x] 2. 修改 serverProcess 类型为 `AtomicReference<ProcessHandler?>`
- [x] 3. 重写 `startOpenCodeProcess()` 函数
- [x] 4. 修改 `stopServer()` 使用 `destroyProcess()`
- [x] 5. 修改 `startOpenCodeServer()` 移除 logFile 参数
- [x] 6. 删除不再需要的 `createLogFile()` 函数
- [x] 7. 构建并验证

## Commit Strategy
- Commit: YES
- Message: `refactor: 使用 OSProcessHandler 替换 ProcessBuilder 解决性能问题`
