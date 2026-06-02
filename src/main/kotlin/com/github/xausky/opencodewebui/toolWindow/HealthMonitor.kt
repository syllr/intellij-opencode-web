package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.HEALTH_CHECK_INTERVAL_MS
import com.github.xausky.opencodewebui.HEALTH_CHECK_START_DELAY_MS
import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.intellij.openapi.application.ApplicationManager

/**
 * OpenCode 服务器健康监控器。
 * 定期轮询服务器健康状态，在状态变化时通过回调通知调用方。
 */
class HealthMonitor(
    private val onUnhealthy: () -> Unit,
    private val onRecovered: () -> Unit
) {
    @Volatile
    var lastHealthState: Boolean? = null

    @Volatile
    private var started = false

    @Volatile
    private var running = false

    private var monitorThread: Thread? = null

    fun start() {
        if (started) return
        started = true
        running = true
        monitorThread = Thread {
            // 如果上次状态是健康的，等 10 秒再开始检查，给服务器稳定窗口期
            if (lastHealthState == true) {
                try {
                    Thread.sleep(HEALTH_CHECK_START_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
            while (running) {
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                // [O5] 健康检查（HealthMonitor 已在后台线程，直接调用同步方法，避免 CompletableFuture 包装的额外线程切换）
                val healthy = OpenCodeApi.isServerHealthySync()
                if (healthy != lastHealthState) {
                    lastHealthState = healthy
                    if (!healthy) {
                        ApplicationManager.getApplication().invokeLater { onUnhealthy() }
                    } else {
                        ApplicationManager.getApplication().invokeLater { onRecovered() }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "HealthMonitor"
            start()
        }
    }

    fun stop() {
        running = false
        started = false
        monitorThread?.interrupt()
        monitorThread = null
    }
}
