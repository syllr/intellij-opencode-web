package com.github.xausky.opencodewebui.listeners

import com.github.xausky.opencodewebui.FULL_REFRESH_POLL_MS
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 兜底全量刷新协调器。
 *
 * 架构：生产者-消费者模式
 * - 生产者（SSE 事件、bash 命令）调用 request() 递增计数器
 * - 消费者（Worker 线程）每 500ms 检查计数器
 *   - 如果新请求 > 已处理计数 → 做一次全量项目刷新
 *   - 成功后同步已处理计数
 *   - 失败则下次重试（计数不变）
 *
 * 这样 500ms 内收到 N 个请求也只刷新一次。
 */
object FullRefreshCoordinator {
    private val logger = thisLogger()
    private val pendingRequests = AtomicInteger(0)
    private val processedCount = AtomicInteger(0)
    private var scheduler: ScheduledExecutorService? = null
    private var projectRoot: String? = null

    fun start(projectPath: String) {
        projectRoot = projectPath
        if (scheduler != null) {
            logger.warn("[FullRefresh] Already started, skipping")
            return
        }
        val s = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "FullRefreshWorker")
        }
        s.scheduleWithFixedDelay(::tick, FULL_REFRESH_POLL_MS, FULL_REFRESH_POLL_MS, TimeUnit.MILLISECONDS)
        scheduler = s
        logger.info("[FullRefresh] Started, polling every 500ms, root=$projectPath")
    }

    /** 生产者：通知 worker 需要全量刷新 */
    fun request() {
        pendingRequests.incrementAndGet()
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        logger.info("[FullRefresh] Stopped")
    }

    private fun tick() {
        val current = pendingRequests.get()
        if (current == processedCount.get()) return  // nothing new

        val root = projectRoot ?: return
        try {
            val dir = File(root)
            if (!dir.exists()) {
                logger.warn("[FullRefresh] Project root not found: $root")
                return
            }

            logger.debug("[FullRefresh] Pending=${current - processedCount.get()}, doing full refresh")
            LocalFileSystem.getInstance().refreshIoFiles(
                listOf(dir),
                /* async */ false,   // 同步！确保刷新完成再更新计数
                /* recursive */ true,
                null
            )
            processedCount.set(current)
            logger.debug("[FullRefresh] Full refresh completed (processed=${processedCount.get()})")
        } catch (e: Exception) {
            logger.error("[FullRefresh] Full refresh failed: ${e.message}, will retry", e)
            // processedCount 不变，下次 tick 会重试
        }
    }
}