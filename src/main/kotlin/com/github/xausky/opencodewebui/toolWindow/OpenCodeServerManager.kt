package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object OpenCodeServerManager {
    private const val HOST = OPENCODE_HOST
    private const val PORT = OPENCODE_PORT

    private val serverRunning = AtomicBoolean(false)
    private val serverProcess = AtomicReference<ProcessHandler?>(null)
    private var opencodeBinDir: String? = null

    fun isServerRunning(): Boolean = serverRunning.get()

    fun startServer(
        project: com.intellij.openapi.project.Project,
        onStarted: () -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        if (OpenCodeApi.isServerHealthySync()) {
            serverRunning.set(true)
            onStarted()
            return
        }

        val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val handler = startOpenCodeProcess()
                    serverProcess.set(handler)

                    val healthy = OpenCodeApi.waitForServerHealthy(30000)

                    if (healthy) {
                        serverRunning.set(true)
                        onStarted()
                    } else {
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
            serverProcess.get()?.let { handler ->
                if (!handler.isProcessTerminated) {
                    handler.destroyProcess()
                }
            }
            serverProcess.set(null)
            serverRunning.set(false)
        } catch (e: Exception) {
            thisLogger().error("Error stopping OpenCode server: ${e.message}")
        }
    }

    fun killProcessByPort(port: Int) {
        try {
            val os = System.getProperty("os.name").lowercase()
            val command = when {
                os.contains("mac") || os.contains("nix") || os.contains("nux") ->
                    listOf("sh", "-c", "lsof -i :$port -t | xargs kill -9 2>/dev/null || true")

                os.contains("win") ->
                    listOf(
                        "cmd",
                        "/c",
                        "for /f \"tokens=5\" %a in ('netstat -ano ^| findstr :$port ^| findstr LISTENING') do taskkill /F /PID %a"
                    )

                else -> {
                    thisLogger().warn("Unsupported OS for killProcessByPort: $os")
                    return
                }
            }

            val process = ProcessBuilder(command).start()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            thisLogger().info("Attempted to kill process on port $port")
        } catch (e: Exception) {
            thisLogger().error("Error killing process by port: ${e.message}")
        }
    }

    private fun startOpenCodeProcess(): ProcessHandler {
        val command = getOpenCodeCommand()
        val homeDir = System.getProperty("user.home", System.getenv("HOME") ?: "/tmp")
        val commandLine = GeneralCommandLine(command)
        commandLine.setWorkDirectory(homeDir)
        val fullEnv = OpenCodePathFinder.getFullEnvironment(opencodeBinDir)

        commandLine.environment.clear()
        commandLine.environment.putAll(fullEnv)

        thisLogger().info("[startOpenCodeProcess] Working directory: $homeDir")
        return ProcessHandlerFactory.getInstance().createProcessHandler(commandLine)
    }

    // 原来的代码,我注释了
//    private fun getOpenCodeCommand(): List<String> {
//        val path = findOpenCodePath()
//        return listOf(path, "serve", "--hostname", HOST, "--port", PORT.toString())
//    }

    private fun getOpenCodeCommand(): List<String> {
        return listOf("/bin/zsh", "-c", "opencode")
    }

    private fun findOpenCodePath(): String {
        thisLogger().info("[findOpenCodePath] 开始查找 opencode 路径...")
        val candidatePaths = OpenCodePathFinder.getCandidatePaths()
        thisLogger().info("[findOpenCodePath] 候选路径: $candidatePaths")
        return try {
            val result = OpenCodePathFinder.findOpenCodePath(candidatePaths)
            appendNvmBinPath(result)
            thisLogger().info("[findOpenCodePath] 找到 opencode 路径: $result")
            result
        } catch (e: IllegalStateException) {
            thisLogger().error("[findOpenCodePath] 未找到 opencode: ${e.message}")
            throw e
        }
    }

    private fun appendNvmBinPath(opencodePath: String) {
        if (OpenCodePathFinder.isNvmPath(opencodePath)) {
            opencodeBinDir = OpenCodePathFinder.extractBinDir(opencodePath)
            thisLogger().info("[appendNvmBinPath] 设置 opencode bin 目录: $opencodeBinDir")
        }
    }
}
