package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.shenyuanlaolarou.opencodewebui.listeners.OpenCodeSSEConsumer
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApiResult
import com.shenyuanlaolarou.opencodewebui.utils.SSEConsumerFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object OpenCodeServerManager {
    internal const val HOST = OPENCODE_HOST
    internal const val PORT = OPENCODE_PORT

    internal val serverProcess = AtomicReference<Process?>(null)
    private val consumers = ConcurrentHashMap<Project, OpenCodeSSEConsumer>()
    // 防 stopServer/shutdownServer 重入:用户连续点 Shutdown 按钮时,第二次以后短路返回
    private val shutdownInProgress = AtomicBoolean(false)
    // 防 startServer 并发:Go-style singleflight,同一时刻仅一个 Backgroundable 在飞
    internal val singleflight = Singleflight<Unit>()

    fun getOrCreateConsumer(
        project: Project,
        onConnectionLost: () -> Unit = {},
        onConnectionEstablished: () -> Unit = {},
    ): OpenCodeSSEConsumer {
        thisLogger().info("[OpenCodeServerManager] getOrCreateConsumer called for project=${project.name} (consumers.size before=${consumers.size})")
        return consumers.computeIfAbsent(project) { p ->
            thisLogger().info("[OpenCodeServerManager] getOrCreateConsumer: no existing consumer, creating new one for project=${p.name}")
            SSEConsumerFactory.create(p, onConnectionLost, onConnectionEstablished).also { it.start() }
        }.also { existing ->
            thisLogger().info("[OpenCodeServerManager] getOrCreateConsumer: existing consumer for project=${project.name}, updating callbacks (isHealthy=${existing.isHealthy()})")
            // 原子更新回调(支持 getOrCreateConsumer 重入时新 caller 覆盖旧 callback)
            existing.onConnectionLost = onConnectionLost
            existing.onConnectionEstablished = onConnectionEstablished
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
        // [Fix EDT freeze + 启动时序 P5] 不再调 isServerHealthySync() (8s 同步 HTTP)。
        // 检查顺序:
        // 1. 本 IDE 实例之前启动的进程仍存活 → 复用,直接创建 consumer
        // 2. 端口已被监听(外部 server 或其他 IDE 启动) → 200ms TCP 探测,跳过进程启动
        // 3. 否则 → 后台启动新进程
        // 三条路径都在 onStarted() 前 register project → 让上游 web app /project 列表能显示当前目录
        val existing = serverProcess.get()
        if (existing != null && existing.isAlive) {
            thisLogger().debug("[OpenCodeServerManager] Reusing existing server process, project=${project.name}")
            registerProjectInBackground(project)
            onStarted()
            return
        }

        if (OpenCodeApi.isServerPortOpen()) {
            thisLogger().debug("[OpenCodeServerManager] Server already listening on port (external), skipping process start, project=${project.name}")
            registerProjectInBackground(project)
            onStarted()
            return
        }

        startServerSingleflight(project, {
            registerProjectInBackground(project)
            onStarted()
        }, onFailed)
    }

    private fun registerProjectInBackground(project: com.intellij.openapi.project.Project) {
        val directory = project.basePath ?: return
        Thread({
            val result = OpenCodeApi.createSession(directory)
            when (result) {
                is OpenCodeApiResult.Success -> thisLogger().info("[OpenCodeServerManager] Created session ${result.data} for project $directory")
                is OpenCodeApiResult.Failure -> thisLogger().warn("[OpenCodeServerManager] createSession failed (${result.code}): ${result.message}")
                OpenCodeApiResult.Unavailable -> thisLogger().debug("[OpenCodeServerManager] createSession unavailable (server still starting?)")
                OpenCodeApiResult.Unauthorized -> thisLogger().warn("[OpenCodeServerManager] createSession unauthorized (auth required?)")
            }
        }, "OpenCode-createSession-${project.name}").start()
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
        val process = serverProcess.getAndSet(null) ?: return null
        if (!process.isAlive) return null
        return process.toHandle()
    }

    private fun acquirePortProcessHandle(): ProcessHandle? {
        thisLogger().debug("[OpenCodeServerManager] Graceful shutdown on port $PORT")
        val handle = findProcessByPort()
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
            thisLogger().debug("[OpenCodeServerManager] Shutdown already in progress, skipping")
            return
        }
        try {
            synchronized(this) {
                consumers.values.forEach { it.stop() }
                consumers.clear()
            }

            // [Fix #2 端口误杀] 必须先 acquireHandle() 拿到本 IDE 启的进程,再发 /global/dispose。
            // 旧顺序: startDisposeThread() 在前 → 无条件发 POST /global/dispose → 再检查 handle
            //   后果: 用户手动 `opencode serve` 启动的 server(不在 serverProcess 引用里)收到
            //         dispose 指令后自我退出,即便 handle 检查为 null 提前 return 也晚了。
            // 新顺序: 先 acquireHandle() 验证进程归属 → 是自家的才发 dispose → 不存在则直接 return。
            //   并发性不受影响: startDisposeThread() 是 daemon fire-and-forget,与后续
            //   handle.onExit().get() 依然并行。
            val handle = acquireHandle() ?: return
            thisLogger().info("[OpenCodeServerManager] Waiting for process exit, PID=${handle.pid()}")

            // fire-and-forget:dispose HTTP 异步发,不让用户等 HTTP 响应
            startDisposeThread()

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
}
