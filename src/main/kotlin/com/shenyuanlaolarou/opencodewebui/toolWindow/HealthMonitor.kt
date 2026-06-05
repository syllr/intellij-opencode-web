package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_INTERVAL_MS
import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_START_DELAY_MS
import com.shenyuanlaolarou.opencodewebui.listeners.OpenCodeSSEConsumer
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 健康监控:用 SSE server.heartbeat 替代 HTTP 探活,避免 EDT 阻塞。
 * 任何 SSE 事件(含 heartbeat)都证明 server 存活。
 */
class HealthMonitor(
    private val consumer: OpenCodeSSEConsumer,
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
                val healthy = consumer.isHealthy()
                if (healthy) {
                    healthyStreak.incrementAndGet()
                    unhealthyStreak.set(0)
                    if (lastHealthState != true) {
                        // false → true 跳过 debounce 立即恢复:
                        // 首次 healthy 检测 = heartbeat/connected 已建立,是真实状态转变;
                        // 不应延迟 15s+ 否则用户卡在 Start 按钮。
                        // (true → false 仍需 debounce,防 SSE 抖动误报 server down)
                        lastHealthState = true
                        ApplicationManager.getApplication().invokeLater { onRecovered() }
                    }
                } else {
                    unhealthyStreak.incrementAndGet()
                    healthyStreak.set(0)
                    // lastHealthState 在 checkAndLoadContent 已显式赋值,不会为 null。
                    // 保留 null 守卫仅为防御性,首次 null 时静默设 false 不触发回调。
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

