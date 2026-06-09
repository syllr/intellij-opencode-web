package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import java.util.concurrent.TimeUnit

/**
 * 通过 lsof 查找监听 PORT 的进程,返回 ProcessHandle;找不到返 null。
 */
internal fun OpenCodeServerManager.findProcessByPort(): ProcessHandle? {
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
internal fun OpenCodeServerManager.killProcessTreeByHandle(process: Process) {
    try {
        val handle = process.toHandle()
        val pid = handle.pid()

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
internal fun OpenCodeServerManager.killProcessTreeByPort() {
    try {
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
