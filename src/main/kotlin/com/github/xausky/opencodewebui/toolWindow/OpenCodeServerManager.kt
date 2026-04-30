package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.listeners.OpenCodeSSEConsumer
import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object OpenCodeServerManager {
    private const val HOST = OPENCODE_HOST
    private const val PORT = OPENCODE_PORT

    private val serverRunning = AtomicBoolean(false)
    private val serverProcess = AtomicReference<Process?>(null)
    private val stateListeners = mutableListOf<(Boolean) -> Unit>()
    private var sseConsumer: OpenCodeSSEConsumer? = null

    fun addStateListener(listener: (Boolean) -> Unit) {
        synchronized(stateListeners) {
            stateListeners.add(listener)
        }
    }

    fun removeStateListener(listener: (Boolean) -> Unit) {
        synchronized(stateListeners) {
            stateListeners.remove(listener)
        }
    }

    private fun notifyStateListeners(running: Boolean) {
        serverRunning.set(running)
        synchronized(stateListeners) {
            stateListeners.forEach { it(running) }
        }
    }

    fun startServer(
        project: com.intellij.openapi.project.Project,
        onStarted: () -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        if (OpenCodeApi.isServerHealthySync()) {
            notifyStateListeners(true)
            sseConsumer = OpenCodeSSEConsumer(project).also { it.start() }
            onStarted()
            return
        }

        val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val process = startOpenCodeProcess()
                    serverProcess.set(process)

                    val healthy = OpenCodeApi.waitForServerHealthy(30000)

                    if (healthy) {
                        notifyStateListeners(true)
                        sseConsumer = OpenCodeSSEConsumer(project).also { it.start() }
                        onStarted()
                    } else {
                        // 健康检查超时，清理已启动的进程
                        process.destroyForcibly()
                        serverProcess.set(null)
                        onFailed(Exception("Server not healthy after 30s"))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    thisLogger().error("[startOpenCodeServer] Exception: ${e.message}", e)
                    onFailed(e)
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    fun stopServer() {
        try {
            sseConsumer?.stop()
            sseConsumer = null

            serverProcess.get()?.let { process ->
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            }
            serverProcess.set(null)
            notifyStateListeners(false)
        } catch (e: Exception) {
            thisLogger().error("Error stopping OpenCode server: ${e.message}")
        }
    }

    private fun startOpenCodeProcess(): Process {
        val command = getOpenCodeCommand()
        val homeDir = System.getProperty("user.home", System.getenv("HOME") ?: "/tmp")
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(java.io.File(homeDir))
        // 不要继承 IDE 的 stdout/stderr，否则 IDE 退出时子进程写入日志会收到 SIGPIPE
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
        thisLogger().info("[startOpenCodeProcess] Working directory: $homeDir, command: $command")
        return processBuilder.start()
    }

    private fun getOpenCodeCommand(): List<String> {
        // 使用 zsh login mode (-l) 启动 opencode
        // -l 会加载用户的 .zshrc，确保 PATH 包含 Homebrew/NVM 等路径
        // 这样 opencode 命令才能被正确找到
        return listOf("/bin/zsh", "-l", "-c", "opencode serve --hostname $HOST --port $PORT")
    }
}
