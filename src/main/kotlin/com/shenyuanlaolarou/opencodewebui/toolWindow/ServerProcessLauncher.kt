package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.SERVER_START_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

/**
 * singleflight:同一时刻只允许一个 Backgroundable 任务真启进程。
 * 后续调用 follower 用 [CompletableFuture.whenComplete] 异步等待 leader 结果。
 *
 * 关键不变量:
 * - leader 一定在 [Backgroundable.run] 内部 complete future + release 槽位
 * - leader panic 也会被 try-catch 捕获走 onFailed,finally 块保证 release
 */
internal fun OpenCodeServerManager.startServerSingleflight(
    project: Project,
    onStarted: () -> Unit,
    onFailed: (Exception) -> Unit
) {
    val mgr = this
    val (future, isLeader) = mgr.singleflight.acquire("start")

    if (!isLeader) {
        // follower: 异步等待 leader 完成,共享结果
        thisLogger().debug("[OpenCodeServerManager] Joining in-flight startup, project=${project.name}")
        future.whenComplete { _, error ->
            if (error == null) {
                onStarted()
            } else {
                onFailed(error as? Exception ?: Exception(error))
            }
        }
        return
    }

    // leader: 真启进程
    val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                // 极限时序: Backgroundable 排队期间,外部可能先占了 PORT
                // 在真正 startOpenCodeProcess() 之前再探测一次
                if (OpenCodeApi.isServerPortOpen()) {
                    thisLogger().debug("[OpenCodeServerManager] Port became open during scheduling, reusing external server, project=${project.name}")
                    mgr.getOrCreateConsumer(project)
                    future.complete(Unit)
                    return
                }

                val process = mgr.startOpenCodeProcess()
                mgr.serverProcess.set(process)

                val healthy = OpenCodeApi.waitForServerHealthy(SERVER_START_TIMEOUT_MS)

                if (healthy) {
                    thisLogger().info("[OpenCodeServerManager] Server started, PID=${process.toHandle().pid()}, port=${mgr.PORT}, project=${project.name}")
                    mgr.getOrCreateConsumer(project)
                    onStarted()
                    future.complete(Unit)
                } else {
                    // 健康检查超时,清理已启动的进程树(含 MCP 子进程)
                    mgr.killProcessTreeByHandle(process)
                    mgr.serverProcess.set(null)
                    val e = Exception("Server not healthy after ${SERVER_START_TIMEOUT_MS}ms")
                    onFailed(e)
                    future.completeExceptionally(e)
                }
            } catch (e: Exception) {
                thisLogger().error("[startOpenCodeServer] Exception: ${e.message}", e)
                onFailed(e)
                future.completeExceptionally(e)
            } finally {
                mgr.singleflight.release(future)
            }
        }
    }
    ProgressManager.getInstance().run(task)
}

internal fun OpenCodeServerManager.startOpenCodeProcess(): Process {
    val command = getOpenCodeCommand()
    val homeDir = System.getProperty("user.home", System.getenv("HOME") ?: "/tmp")
    val processBuilder = ProcessBuilder(command)
    processBuilder.directory(java.io.File(homeDir))
    processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
    thisLogger().debug("[startOpenCodeProcess] Working directory: $homeDir, command: $command")
    val process = processBuilder.start()
    pipeToLogger(process, "[opencode]")
    return process
}

internal fun OpenCodeServerManager.pipeToLogger(process: Process, prefix: String) {
    val logger = thisLogger()
    Thread({
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { logger.debug("$prefix $it") }
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

internal fun OpenCodeServerManager.getOpenCodeCommand(): List<String> {
    // 使用 zsh login mode (-l) 启动 opencode
    // -l 会加载用户的 .zshrc，确保 PATH 包含 Homebrew/NVM 等路径
    // 这样 opencode 命令才能被正确找到
    // source ~/.zshrc 确保 $PATH 包含 nvm/volta 等环境管理器的路径
    // macOS app 环境下 zsh -l 加载的 PATH 可能不完整
    return listOf("/bin/zsh", "-l", "-c", "source ~/.zshrc && opencode serve --hostname $HOST --port $PORT")
}
