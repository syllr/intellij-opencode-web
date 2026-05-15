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
     * 关闭 OpenCode 服务器和所有子进程（MCP 服务等）。
     *
     * kill 策略：优先用 ProcessHandle API 从叶子杀到根；回退用 lsof+pgrep 递归遍历。
     * 为什么不能只 kill 父进程：opencode serve 启动的 MCP 子进程（open-websearch、
     * @playwright/mcp 等）通过 npm exec/npx 拉起，杀父进程后变为孤儿进程驻留。
     * 关键：lsof 必须加 -sTCP:LISTEN 过滤，否则会误杀与端口有 ESTABLISHED 连接的
     * JCEF Chromium 子进程，导致 IDE 崩溃退出。
     */
    fun stopServer() {
        try {
            thisLogger().info("[OpenCodeServerManager] Stopping SSE consumer")
            sseConsumer?.stop()
            sseConsumer = null

            thisLogger().info("[OpenCodeServerManager] Killing process tree on port $PORT")

            // getAndSet(null) 原子性地获取并清空引用，防止与 startServer() 并发时的 TOCTOU 竞态
            val process = serverProcess.getAndSet(null)
            if (process != null && process.isAlive) {
                killProcessTreeByHandle(process)
            } else {
                killProcessTreeByPort()
            }
        } catch (e: Exception) {
            thisLogger().error("Error stopping OpenCode server: ${e.message}")
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
