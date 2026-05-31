package com.github.xausky.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object FullRefreshCoordinator {
    private val logger = thisLogger()
    @Volatile
    private var scheduler: ScheduledExecutorService? = null
    @Volatile
    private var projectRoot: String? = null

    private val refreshInProgress = AtomicBoolean(false)
    @Volatile
    private var pendingTask: ScheduledFuture<*>? = null
    @Volatile
    private var refreshRequestedWhileBusy = false
    private const val DEBOUNCE_MS = 2000L

    fun start(projectPath: String) {
        projectRoot = projectPath
        if (scheduler != null) return
        val s = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "FullRefreshWorker")
        }
        scheduler = s
        logger.info("[FullRefresh] Started, debounce=${DEBOUNCE_MS}ms, root=$projectPath")
    }

    fun request() {
        if (refreshInProgress.get()) {
            refreshRequestedWhileBusy = true
            return
        }
        pendingTask?.cancel(false)
        pendingTask = scheduler?.schedule(::executeRefresh, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        pendingTask?.cancel(false)
        pendingTask = null
        scheduler?.shutdownNow()
        scheduler = null
        projectRoot = null
        refreshInProgress.set(false)
        logger.info("[FullRefresh] Stopped")
    }

    private fun executeRefresh() {
        if (!refreshInProgress.compareAndSet(false, true)) return
        val root = projectRoot
        if (root == null) {
            refreshInProgress.set(false)
            return
        }
        val dir = File(root)
        if (!dir.exists()) {
            logger.warn("[FullRefresh] Project root not found: $root")
            refreshInProgress.set(false)
            return
        }
        logger.debug("[FullRefresh] Executing full refresh for $root")
        LocalFileSystem.getInstance().refreshIoFiles(
            listOf(dir),
            true,
            true,
            Runnable {
        refreshInProgress.set(false)
        refreshRequestedWhileBusy = false
                if (refreshRequestedWhileBusy) {
                    refreshRequestedWhileBusy = false
                    request()
                }
                logger.debug("[FullRefresh] Refresh completed")
            }
        )
    }
}
