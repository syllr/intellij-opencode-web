package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.SERVER_START_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.shenyuanlaolarou.opencodewebui.utils.dataOrNull
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
 *
 * **端口固定 12396,不支持升级** — Edge extension(`background.js` / `manifest.json`)
 * + `MyToolWindow.buildOpenUrl()` 均硬编码 `http://localhost:12396`(URL path 通配符),
 * 端口升级会引入 notification 预置 allow 错位 + Edge 连不上 server 的新失败模式。
 * M2-T1 health gate 改为只检测 + 报错(冲突时让用户先关其他 IDE 实例),不递归升级。详见 SPEC §6.2。
 */
internal fun OpenCodeServerManager.startServerSingleflight(
    project: Project,
    onStarted: () -> Unit,
    onFailed: (Exception) -> Unit,
    port: Int = PORT
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

                val process = mgr.startOpenCodeProcess(project, port)
                mgr.serverProcess.set(process)

                val healthy = OpenCodeApi.waitForServerHealthy(SERVER_START_TIMEOUT_MS)

                if (healthy) {
                    // M2-T1 health gate: 端口被占且服务于不同项目时直接 onFailed(不升级端口)
                    val dirResult = OpenCodeApi.getHealthDirectory(port)
                    val serverDir = dirResult.dataOrNull()
                    if (serverDir != null && serverDir != project.basePath) {
                        mgr.killProcessTreeByHandle(process)
                        mgr.serverProcess.set(null)
                        val e = Exception(
                            "OpenCode server on port $port is serving '$serverDir'," +
                                " not '${project.basePath}'." +
                                " Port 12396 is fixed (Edge extension hardcodes localhost:12396);" +
                                " close the other OpenCode instance or stop its server first."
                        )
                        thisLogger().warn("[OpenCodeServerManager] $e")
                        onFailed(e)
                        future.completeExceptionally(e)
                        return
                    }
                    thisLogger().info(
                        "[OpenCodeServerManager] Server started," +
                            " PID=${process.toHandle().pid()}," +
                            " port=$port," +
                            " project=${project.name}"
                    )
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

internal fun OpenCodeServerManager.createProcessBuilder(project: Project, port: Int = PORT): ProcessBuilder {
    val command = getOpenCodeCommand(port)
    val wd = java.io.File(project.basePath)
    val processBuilder = ProcessBuilder(command)
    processBuilder.directory(wd)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
    thisLogger().debug("[createProcessBuilder] Working directory: $wd, command: $command")
    return processBuilder
}

internal fun OpenCodeServerManager.startOpenCodeProcess(project: Project, port: Int = PORT): Process {
    val processBuilder = createProcessBuilder(project, port)
    thisLogger().debug("[startOpenCodeProcess] Working directory: ${processBuilder.directory()}, command: ${processBuilder.command()}")
    val process = processBuilder.start()
    pipeToLogger(process, "[opencode]")
    return process
}

internal fun OpenCodeServerManager.pipeToLogger(process: Process, prefix: String) {
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

internal fun OpenCodeServerManager.getOpenCodeCommand(port: Int = PORT): List<String> {
    // 使用 zsh login mode (-l) 启动 opencode
    // -l 会加载用户的 .zshrc，确保 PATH 包含 Homebrew/NVM 等路径
    // 这样 opencode 命令才能被正确找到
    // source ~/.zshrc 确保 $PATH 包含 nvm/volta 等环境管理器的路径
    // macOS app 环境下 zsh -l 加载的 PATH 可能不完整
    return listOf("/bin/zsh", "-l", "-c", "source ~/.zshrc && opencode serve --hostname $HOST --port $port")
}
