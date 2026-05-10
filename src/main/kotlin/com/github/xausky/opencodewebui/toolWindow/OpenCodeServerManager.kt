package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.SERVER_START_TIMEOUT_MS
import com.github.xausky.opencodewebui.listeners.OpenCodeSSEConsumer
import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.github.xausky.opencodewebui.utils.SSEConsumerFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

object OpenCodeServerManager {
    private const val HOST = OPENCODE_HOST
    private const val PORT = OPENCODE_PORT

    private val serverProcess = AtomicReference<Process?>(null)
    @Volatile
    private var sseConsumer: OpenCodeSSEConsumer? = null

    fun ensureSSEConsumer(project: com.intellij.openapi.project.Project): OpenCodeSSEConsumer {
        if (sseConsumer == null) {
            synchronized(this) {
                if (sseConsumer == null) {
                    thisLogger().info("[OpenCodeServerManager] Creating SSE consumer via ensureSSEConsumer")
                    sseConsumer = SSEConsumerFactory.create(project).also { it.start() }
                }
            }
        }
        return sseConsumer!!
    }

    fun startServer(
        project: com.intellij.openapi.project.Project,
        onStarted: () -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        if (OpenCodeApi.isServerHealthySync()) {
            thisLogger().info("[OpenCodeServerManager] Creating SSE consumer (server already healthy), project=${project.name}")
            sseConsumer?.stop()
            sseConsumer = SSEConsumerFactory.create(project).also { it.start() }
            onStarted()
            return
        }

        val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logDiagnosticEnvironment()

                    val process = startOpenCodeProcess()
                    serverProcess.set(process)

                    val healthy = OpenCodeApi.waitForServerHealthy(SERVER_START_TIMEOUT_MS)

                    if (healthy) {
                        thisLogger().info("[OpenCodeServerManager] Creating SSE consumer (server started healthily), project=${project.name}")
                        sseConsumer?.stop()
                        sseConsumer = SSEConsumerFactory.create(project).also { it.start() }
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

    /**
     * 关闭 OpenCode 服务器。
     *
     * 注意：这是用户通过右键菜单主动触发的操作，而非 IDE 退出时的自动清理。
     * IDE 退出时不会调用此方法，opencode 服务进程会独立继续运行。
     *
     * kill 策略：通过 lsof 查找端口号，暴力 kill (SIGKILL)，不做优雅关闭。
     * 不依赖 process.destroy() 是因为 zsh -lc 会 exec 优化，启动后 zsh 进程已被替换。
     *
     * 关键：必须加 -sTCP:LISTEN 过滤，否则 lsof -ti :PORT 会返回所有与该端口
     * 有 TCP 连接（包括 ESTABLISHED 客户端连接）的进程。JCEF Chromium 子进程通过
     * HTTP 连接到此端口会被 lsof 误列，kill -9 它们会导致 IDE 崩溃退出。
     */
    fun stopServer() {
        try {
            thisLogger().info("[OpenCodeServerManager] Stopping SSE consumer")
            sseConsumer?.stop()
            sseConsumer = null

            thisLogger().info("[OpenCodeServerManager] Killing process on port $PORT")
            try {
                val killCmd = listOf("/bin/sh", "-c", "lsof -tiTCP:$PORT -sTCP:LISTEN | xargs kill -9")
                Runtime.getRuntime().exec(killCmd.toTypedArray()).waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                thisLogger().warn("[OpenCodeServerManager] Port kill failed: ${e.message}")
            }

            serverProcess.set(null)
        } catch (e: Exception) {
            thisLogger().error("Error stopping OpenCode server: ${e.message}")
        }
    }

    private fun logDiagnosticEnvironment() {
        try {
            val cmd = listOf("/bin/zsh", "-l", "-c", "echo '---WHICH opencode---' && which opencode 2>&1 || echo 'NOT FOUND' && echo '---PATH---' && echo \$PATH && echo '---NODE---' && (which node 2>&1 || echo 'NOT FOUND') && echo '---NVM---' && (which nvm 2>&1 || echo 'NOT FOUND') && echo '---HOME---' && echo \$HOME && echo '---SHELL---' && echo \$SHELL && echo '---ENV---' && env | sort")
            val proc = Runtime.getRuntime().exec(cmd.toTypedArray())
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            thisLogger().info("[OpenCodeServerManager] Diagnostic environment (exit=$exitCode):\n$output")
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeServerManager] Diagnostic failed: ${e.message}")
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
        // source ~/.zshrc 确保 $PATH 包含 nvm/volta 等环境管理器的路径
        // macOS app 环境下 zsh -l 加载的 PATH 可能不完整
        return listOf("/bin/zsh", "-l", "-c", "source ~/.zshrc && opencode serve --hostname $HOST --port $PORT")
    }
}
