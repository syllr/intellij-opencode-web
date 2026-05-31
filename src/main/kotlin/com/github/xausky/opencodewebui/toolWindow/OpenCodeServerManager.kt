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
    // [Fix #3] 追踪 SSE consumer 所属 project（WeakRef），防止多项目场景下旧 consumer 泄漏
    private val consumerProjectRef = AtomicReference<java.lang.ref.WeakReference<com.intellij.openapi.project.Project>?>(null)

    fun ensureSSEConsumer(project: com.intellij.openapi.project.Project): OpenCodeSSEConsumer {
        // [Fix #3] 检查当前 consumer 是否属于同一 project，不同则停止旧的
        val existingConsumer = sseConsumer
        val existingProject = consumerProjectRef.get()?.get()
        if (existingConsumer != null && existingProject != null && existingProject !== project) {
            thisLogger().warn("[OpenCodeServerManager] SSE consumer belongs to different project (${existingProject.name}), stopping old consumer")
            existingConsumer.stop()
            sseConsumer = null
        }
        if (sseConsumer == null) {
            synchronized(this) {
                if (sseConsumer == null) {
                    thisLogger().info("[OpenCodeServerManager] Creating SSE consumer via ensureSSEConsumer, project=${project.name}")
                    sseConsumer = SSEConsumerFactory.create(project).also { it.start() }
                    consumerProjectRef.set(java.lang.ref.WeakReference(project))
                }
            }
        }
        return sseConsumer!!
    }

    /**
     * [Fix #3] 项目关闭时调用：停止该项目的 SSE consumer，断开引用链。
     * 仅当 consumer 仍属于该 project 时才停止，避免误停其他 project 的 consumer。
     */
    fun disposeForProject(project: com.intellij.openapi.project.Project) {
        val existingProject = consumerProjectRef.get()?.get()
        if (existingProject === project) {
            thisLogger().info("[OpenCodeServerManager] disposeForProject: stopping SSE consumer for project=${project.name}")
            sseConsumer?.stop()
            sseConsumer = null
            consumerProjectRef.set(null)
        }
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
            consumerProjectRef.set(java.lang.ref.WeakReference(project))
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
                        consumerProjectRef.set(java.lang.ref.WeakReference(project))
                        onStarted()
                    } else {
                        // 健康检查超时，清理已启动的进程树（含 MCP 子进程）
                        killProcessTreeByHandle(process)
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
     * 停止本 IDE 实例启动的 opencode 进程（IDE 退出时调用）。
     * 只杀 serverProcess 引用的进程树，不 fallback 到端口杀进程，
     * 避免误杀其他 IDE 实例或用户手动启动的 opencode server。
     */
    fun stopServer() {
        try {
            thisLogger().info("[OpenCodeServerManager] Stopping SSE consumer")
            sseConsumer?.stop()
            sseConsumer = null
            consumerProjectRef.set(null)

            val process = serverProcess.getAndSet(null)
            if (process != null && process.isAlive) {
                thisLogger().info("[OpenCodeServerManager] Killing process tree (this IDE started), PID=${process.toHandle().pid()}")
                killProcessTreeByHandle(process)
            } else {
                thisLogger().info("[OpenCodeServerManager] No process reference to stop (server was externally started)")
            }
        } catch (e: Exception) {
            thisLogger().error("Error stopping OpenCode server: ${e.message}")
        }
    }

    /**
     * 关闭端口上的 opencode server（用户手动 Shutdown Server 时调用）。
     * 通过端口查找并杀死进程树，不管是谁启动的。
     *
     * kill 策略：lsof -sTCP:LISTEN 找到监听进程，再用 pgrep 递归杀子进程。
     * 关键：lsof 必须加 -sTCP:LISTEN 过滤，否则会误杀与端口有 ESTABLISHED 连接的
     * JCEF Chromium 子进程，导致 IDE 崩溃退出。
     */
    fun shutdownServer() {
        try {
            thisLogger().info("[OpenCodeServerManager] Stopping SSE consumer")
            sseConsumer?.stop()
            sseConsumer = null
            consumerProjectRef.set(null)

            thisLogger().info("[OpenCodeServerManager] Shutting down server on port $PORT")
            killProcessTreeByPort()
        } catch (e: Exception) {
            thisLogger().error("Error shutting down OpenCode server: ${e.message}")
        }
    }

    /**
     * 使用 ProcessHandle API 递归杀死进程树（从叶子到根）。
     * 只杀目标进程的后代，不会误杀同 PGID 的无关进程。
     */
    private fun killProcessTreeByHandle(process: Process) {
        try {
            val handle = process.toHandle()
            val pid = handle.pid()
            thisLogger().info("[OpenCodeServerManager] Killing process tree via ProcessHandle, PID=$pid")

            val descendants = handle.descendants().toList()
            descendants.reversed().forEach { it.destroyForcibly() }
            handle.destroyForcibly()

            // 等待根进程确认终止，防止 onExit() 非阻塞导致健康检查误判
            try {
                handle.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS)
                thisLogger().info("[OpenCodeServerManager] Process $pid confirmed terminated after SIGKILL")
            } catch (_: java.util.concurrent.TimeoutException) {
                thisLogger().warn("[OpenCodeServerManager] Process $pid did not terminate within 3s after destroyForcibly")
            }

            thisLogger().info("[OpenCodeServerManager] Killed $pid and ${descendants.size} descendants via ProcessHandle")
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeServerManager] ProcessHandle kill failed: ${e.message}. Falling back to port-based kill.")
            killProcessTreeByPort()
        }
    }

    /**
     * 通过端口查找主进程，递归杀死所有子进程（从叶子到根）。
     * 后备方案：serverProcess 不可用时使用。
     */
    private fun killProcessTreeByPort() {
        try {
            thisLogger().info("[OpenCodeServerManager] Killing process tree via port lookup (PORT=$PORT)")
            val script = """
                |_kill_tree() {
                |    local pid=${'$'}1
                |    for child in ${'$'}(pgrep -P ${'$'}pid 2>/dev/null); do
                |        _kill_tree ${'$'}child
                |    done
                |    kill -9 ${'$'}pid 2>/dev/null || true
                |}
                |PID=${'$'}(lsof -tiTCP:${PORT} -sTCP:LISTEN | head -1)
                |if [ -n "${'$'}PID" ]; then
                |    _kill_tree ${'$'}PID
                |fi
            """.trimMargin()

            val proc = Runtime.getRuntime().exec(arrayOf("/bin/sh", "-c", script))
            val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                thisLogger().warn("[OpenCodeServerManager] Port-based process tree kill timed out")
            }
            thisLogger().info("[OpenCodeServerManager] Process tree killed via port-based lookup")
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeServerManager] Port-based process tree kill failed: ${e.message}")
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
