package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.shenyuanlaolarou.opencodewebui.SERVER_START_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.listeners.OpenCodeSSEConsumer
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApiResult
import com.shenyuanlaolarou.opencodewebui.utils.SSEConsumerFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object OpenCodeServerManager {
    private const val HOST = OPENCODE_HOST
    private const val PORT = OPENCODE_PORT

    private val serverProcess = AtomicReference<Process?>(null)
    private val consumers = ConcurrentHashMap<Project, OpenCodeSSEConsumer>()
    // 防 stopServer/shutdownServer 重入:用户连续点 Shutdown 按钮时,第二次以后短路返回
    private val shutdownInProgress = AtomicBoolean(false)

    fun getOrCreateConsumer(project: Project): OpenCodeSSEConsumer {
        return consumers.computeIfAbsent(project) { p ->
            SSEConsumerFactory.create(p).also { it.start() }
        }
    }

    /**
     * 项目关闭时调用：停止并移除该项目的 SSE consumer。
     */
    fun disposeForProject(project: Project) {
        consumers.remove(project)?.let { consumer ->
            consumer.stop()
        }
    }

    fun startServer(
        project: com.intellij.openapi.project.Project,
        onStarted: () -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        if (OpenCodeApi.isServerHealthySync()) {
            thisLogger().info("[OpenCodeServerManager] Creating SSE consumer (server already healthy), project=${project.name}")
            getOrCreateConsumer(project)
            onStarted()
            return
        }

        val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val process = startOpenCodeProcess()
                    serverProcess.set(process)

                    val healthy = OpenCodeApi.waitForServerHealthy(SERVER_START_TIMEOUT_MS)

                    if (healthy) {
                        thisLogger().info("[OpenCodeServerManager] Server started, PID=${process.toHandle().pid()}, port=$PORT")
                        thisLogger().info("[OpenCodeServerManager] Creating SSE consumer (server started healthily), project=${project.name}")
                        getOrCreateConsumer(project)
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
     *
     * 关闭策略由 [gracefulShutdown] 统一实现。
     */
    fun stopServer() {
        gracefulShutdown(
            acquireHandle = ::acquireServerProcessHandle,
            killFallback = { killProcessTreeByPort() },
            errorTag = "stopping"
        )
    }

    /**
     * 关闭端口上的 opencode server（用户手动 Shutdown Server 时调用）。
     * 通过端口查找并杀死进程树，不管是谁启动的。
     *
     * 关闭策略由 [gracefulShutdown] 统一实现。
     */
    fun shutdownServer() {
        gracefulShutdown(
            acquireHandle = ::acquirePortProcessHandle,
            killFallback = { killProcessTreeByPort() },
            errorTag = "shutting down"
        )
    }

    private fun acquireServerProcessHandle(): ProcessHandle? {
        val process = serverProcess.getAndSet(null) ?: run {
            thisLogger().info("[OpenCodeServerManager] No process reference to stop (server was externally started)")
            return null
        }
        if (!process.isAlive) {
            thisLogger().info("[OpenCodeServerManager] Process already exited")
            return null
        }
        return process.toHandle()
    }

    private fun acquirePortProcessHandle(): ProcessHandle? {
        thisLogger().info("[OpenCodeServerManager] Graceful shutdown on port $PORT")
        val handle = findProcessByPort()
        if (handle == null) {
            thisLogger().info("[OpenCodeServerManager] Port $PORT already free, no process to wait for")
        }
        return handle
    }

    /**
     * 优雅关闭 server 的统一流程。被 [stopServer] 与 [shutdownServer] 共享。
     *
     * 关闭策略(fire-and-forget dispose + 等 process 退出):
     * 1. 停所有 SSE consumer
     * 2. 后台线程 POST /global/dispose → server 内部清理 MCP/PTY/SSE/LSP(不阻塞用户)
     * 3. 主线程通过 [acquireHandle] 拿 ProcessHandle,等 onExit(最多 5s)
     * 4. 兜底 SIGTERM(handle.destroy)+ 再等 2s
     * 5. 最后兜底调 [killFallback](杀进程树)
     *
     * 与早期版本的差异:不再串行等 /global/dispose HTTP 响应(2s 超时常不够),
     * 改为并行 dispose + wait process exit,user-perceived time = 真实退出时间。
     */
    private fun gracefulShutdown(
        acquireHandle: () -> ProcessHandle?,
        killFallback: () -> Unit,
        errorTag: String
    ) {
        if (!shutdownInProgress.compareAndSet(false, true)) {
            thisLogger().info("[OpenCodeServerManager] Shutdown already in progress, skipping")
            return
        }
        try {
            thisLogger().info("[OpenCodeServerManager] Stopping all SSE consumers")
            synchronized(this) {
                consumers.values.forEach { it.stop() }
                consumers.clear()
            }

            // fire-and-forget:dispose HTTP 异步发,不让用户等 HTTP 响应
            startDisposeThread()

            val handle = acquireHandle() ?: return
            thisLogger().info("[OpenCodeServerManager] Waiting for process exit, PID=${handle.pid()}")

            val startMs = System.currentTimeMillis()
            try {
                handle.onExit().get(5, TimeUnit.SECONDS)
                thisLogger().info("[OpenCodeServerManager] Server exited after ${System.currentTimeMillis() - startMs}ms, PID=${handle.pid()}")
                return
            } catch (_: TimeoutException) {
                thisLogger().info("[OpenCodeServerManager] Server still alive 5s, sending SIGTERM, PID=${handle.pid()}")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                thisLogger().info("[OpenCodeServerManager] Shutdown interrupted, sending SIGTERM, PID=${handle.pid()}")
            }

            val sigtermStartMs = System.currentTimeMillis()
            handle.destroy()
            try {
                handle.onExit().get(2, TimeUnit.SECONDS)
                thisLogger().info("[OpenCodeServerManager] Server exited after SIGTERM in ${System.currentTimeMillis() - sigtermStartMs}ms, PID=${handle.pid()}")
                return
            } catch (_: TimeoutException) {
                thisLogger().info("[OpenCodeServerManager] Server did not exit 2s after SIGTERM, force killing, PID=${handle.pid()}")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                thisLogger().info("[OpenCodeServerManager] Shutdown interrupted, force killing, PID=${handle.pid()}")
            }

            killFallback()
        } catch (e: Exception) {
            thisLogger().error("Error $errorTag OpenCode server: ${e.message}")
        } finally {
            shutdownInProgress.set(false)
        }
    }

    /**
     * 在后台线程发起 POST /global/dispose,记录结果但不阻塞当前调用者。
     * 与关闭策略解耦:dispose 成功/失败都不影响主线程等待 process 退出;
     * process 退出由 SIGTERM 兜底保证。
     */
    private fun startDisposeThread() {
        Thread({ requestGracefulDispose() }, "opencode-dispose").apply {
            isDaemon = true
            start()
        }
    }

    private fun requestGracefulDispose() {
        when (val result = OpenCodeApi.disposeServer()) {
            is OpenCodeApiResult.Success -> {
                thisLogger().info("[OpenCodeServerManager] /global/dispose succeeded, server will exit shortly")
            }
            is OpenCodeApiResult.Failure -> {
                if (result.code == 404) {
                    // 老版 opencode 可能没这个端点;降级为 debug 避免每次 shutdown 都刷警告
                    thisLogger().debug("[OpenCodeServerManager] /global/dispose not available (404, old opencode?)")
                } else {
                    thisLogger().warn("[OpenCodeServerManager] /global/dispose failed: code=${result.code}")
                }
            }
            is OpenCodeApiResult.Unavailable -> {
                // 2s 客户端超时或连接拒绝。server 仍可能在清理中,主线程继续等 process 退出。
                thisLogger().info("[OpenCodeServerManager] /global/dispose unavailable (timeout/connection refused), process exit wait continues")
            }
            is OpenCodeApiResult.Unauthorized -> {
                thisLogger().warn("[OpenCodeServerManager] /global/dispose 401 unauthorized")
            }
        }
    }

    /**
     * 通过 lsof 查找监听 PORT 的进程,返回 ProcessHandle;找不到返 null。
     */
    private fun findProcessByPort(): ProcessHandle? {
        return try {
            val script = "lsof -tiTCP:$PORT -sTCP:LISTEN | head -1"
            val proc = Runtime.getRuntime().exec(arrayOf("/bin/sh", "-c", script))
            try {
                val pidStr = proc.inputStream.bufferedReader().readText().trim()
                if (pidStr.isEmpty()) return null
                ProcessHandle.of(pidStr.toLong()).orElse(null)
            } finally {
                proc.waitFor(3, TimeUnit.SECONDS)
                if (proc.isAlive) proc.destroyForcibly()
            }
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeServerManager] findProcessByPort failed: ${e.message}")
            null
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

    private fun startOpenCodeProcess(): Process {
        val command = getOpenCodeCommand()
        val homeDir = System.getProperty("user.home", System.getenv("HOME") ?: "/tmp")
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(java.io.File(homeDir))
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
        thisLogger().info("[startOpenCodeProcess] Working directory: $homeDir, command: $command")
        val process = processBuilder.start()
        pipeToLogger(process, "[opencode]")
        return process
    }

    private fun pipeToLogger(process: Process, prefix: String) {
        val logger = thisLogger()
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { logger.info("$prefix $it") }
            } catch (_: java.io.IOException) {
                // 进程退出后 stream 关闭,预期行为,静默退出
            } catch (e: Exception) {
                logger.warn("$prefix stdout relay unexpected error: ${e.message}")
            }
        }, "opencode-stdout-relay").apply { isDaemon = true }.start()
        Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { logger.warn("$prefix $it") }
            } catch (_: java.io.IOException) {
                // 进程退出后 stream 关闭,预期行为,静默退出
            } catch (e: Exception) {
                logger.warn("$prefix stderr relay unexpected error: ${e.message}")
            }
        }, "opencode-stderr-relay").apply { isDaemon = true }.start()
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
