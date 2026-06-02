package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.HEALTH_CHECK_INTERVAL_MS
import com.github.xausky.opencodewebui.HEALTH_CHECK_START_DELAY_MS
import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.atomic.AtomicInteger

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

    // Debounce 连续 N 次翻转,消除网络抖动导致的"状态闪烁"。
    private val healthyStreak = AtomicInteger(0)
    private val unhealthyStreak = AtomicInteger(0)

    fun start() {
        if (started) return
        started = true
        running = true
        monitorThread = Thread {
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
                val healthy = OpenCodeApi.isServerHealthySync()
                if (healthy) {
                    healthyStreak.incrementAndGet()
                    unhealthyStreak.set(0)
                    if (lastHealthState == null) {
                        // 首次检测不回调,等 debounce 后再触发(避免启动期 false 导致 UI 闪红)
                        lastHealthState = true
                    } else if (lastHealthState != true && healthyStreak.get() >= DEBOUNCE_THRESHOLD) {
                        lastHealthState = true
                        ApplicationManager.getApplication().invokeLater { onRecovered() }
                    }
                } else {
                    unhealthyStreak.incrementAndGet()
                    healthyStreak.set(0)
                    if (lastHealthState == null) {
                        lastHealthState = false
                    } else if (lastHealthState != false && unhealthyStreak.get() >= DEBOUNCE_THRESHOLD) {
                        lastHealthState = false
                        ApplicationManager.getApplication().invokeLater { onUnhealthy() }
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

    private companion object {
        private const val DEBOUNCE_THRESHOLD = 3
    }
}

