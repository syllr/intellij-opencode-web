package com.github.xausky.opencodewebui.listeners

import com.github.xausky.opencodewebui.DEDUP_WINDOW_MS
import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.SSE_CONNECT_TIMEOUT_SECONDS
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.launchdarkly.eventsource.ConnectStrategy
import com.launchdarkly.eventsource.ErrorStrategy
import com.launchdarkly.eventsource.EventSource
import com.launchdarkly.eventsource.MessageEvent
import com.launchdarkly.eventsource.background.BackgroundEventHandler
import com.launchdarkly.eventsource.background.BackgroundEventSource
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenCodeSSEConsumer(
    private val project: Project
) : BackgroundEventHandler {

    private val gson = Gson()
    private val eventSourceRef = AtomicReference<BackgroundEventSource?>(null)
    private val logger = thisLogger()
    private val refreshDeduplicator = RefreshDeduplicator()

    fun start() {
        val uri = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/global/event")
        logger.info("[OpenCodeSSEConsumer] Starting SSE consumer, project.basePath='${project.basePath}', uri=$uri")

        FullRefreshCoordinator.start(project.basePath ?: "")

        val connectStrategy = ConnectStrategy.http(uri)
            .connectTimeout(SSE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)

        val esBuilder = EventSource.Builder(connectStrategy)
            .errorStrategy(ErrorStrategy.alwaysContinue())

        val bgBuilder = BackgroundEventSource.Builder(this, esBuilder)
        val bgEventSource = bgBuilder.build()

        eventSourceRef.set(bgEventSource)
        bgEventSource.start()
        logger.info("[OpenCodeSSEConsumer] SSE consumer started")
    }

    fun stop() {
        FullRefreshCoordinator.stop()
        eventSourceRef.getAndSet(null)?.close()
        logger.info("[OpenCodeSSEConsumer] SSE consumer stopped")
    }

    override fun onOpen() {
        logger.info("[OpenCodeSSEConsumer] SSE connection opened")
    }

    override fun onMessage(event: String, messageEvent: MessageEvent) {
        val message = messageEvent.data
        val parsed = SSEEventParser.parse(event, message)
        val parsedMap = parsed.parsedMap
        val payloadType = parsed.payloadType
        val eventDir = parsed.directory
        val fileProperty = parsed.file
        val projectDir = project.basePath

        if (payloadType == "message.part.delta") {
            if (fileProperty != null) logger.debug("[OpenCodeSSEConsumer] *** FILE CHANGE DETECTED *** payloadType=$payloadType, file=$fileProperty")
            return
        }

        logger.debug("[OpenCodeSSEConsumer] Event: type='$payloadType', eventDir=$eventDir, projectDir=$projectDir, file=$fileProperty")

        val isSessionDiff = parsed.eventType == "session.diff" || parsed.payloadType == "session.diff"
        val isFileEdited = parsed.payloadType == "file.edited"
        val isFileWatcherUpdated = parsed.payloadType == "file.watcher.updated"

        if (payloadType == "message.part.updated") { BashCommandHandler.handleBashEvent(parsedMap, projectDir); return }
        if (!isSessionDiff && !isFileEdited && !isFileWatcherUpdated) return
        if (projectDir == null) { logger.warn("[OpenCodeSSEConsumer] project.basePath is null, skipping refresh"); return }

        if (eventDir != null) {
            try {
                if (java.io.File(eventDir).canonicalPath != java.io.File(projectDir).canonicalPath) {
                    logger.debug("[OpenCodeSSEConsumer] Directory MISMATCH: event='$eventDir' vs project='$projectDir'")
                    return
                }
            } catch (e: Exception) {
                logger.warn("[OpenCodeSSEConsumer] Directory canonical path check failed: ${e.message}")
                if (eventDir != projectDir) { logger.debug("[OpenCodeSSEConsumer] Directory MISMATCH (fallback): event='$eventDir' vs project='$projectDir'"); return }
            }
        }

        if (isSessionDiff) {
            try { handleSessionDiff(gson.fromJson(message, SSEEventWrapper::class.java).payload.properties, projectDir) }
            catch (e: Exception) { logger.error("[OpenCodeSSEConsumer] Failed to parse session.diff: ${e.message}", e) }
            return
        }
        if (isFileEdited) { handleFileEdited(eventDir, fileProperty, projectDir, parsedMap, message); return }
        if (isFileWatcherUpdated) { handleFileWatcherUpdated(eventDir, fileProperty, projectDir, parsedMap); return }
    }

    private fun handleSessionDiff(props: SSESessionDiffProperties, projectDir: String) {
        logger.debug("[OpenCodeSSEConsumer] session.diff: ${props.diff.size} files")
        props.diff.forEachIndexed { i, f -> logger.debug("[OpenCodeSSEConsumer]   diff[$i]: file='${f.file}', status=${f.status}") }
        if (props.diff.isNotEmpty()) { logger.debug("[OpenCodeSSEConsumer] Refreshing ${props.diff.size} files from session.diff"); OpenCodeDiffRefresher.refreshFiles(projectDir, props.diff) }
    }

    private fun handleFileEdited(eventDir: String?, fileProperty: String?, projectDir: String, parsedMap: Map<*, *>?, message: String) {
        try {
            val absolutePath = gson.fromJson(message, SSEFileEditedEvent::class.java).payload.properties.file
            val relativePath = computeRelativePath(eventDir, absolutePath)
            logger.debug("[OpenCodeSSEConsumer] file.edited: absolute='$absolutePath', relative='$relativePath'")
            OpenCodeDiffRefresher.refreshFiles(projectDir, listOf(DiffFile(relativePath, 0, 0, "modified")))
            FullRefreshCoordinator.request()
        } catch (e: Exception) { logger.error("[OpenCodeSSEConsumer] Failed to parse file.edited: ${e.message}", e) }
    }

    private fun handleFileWatcherUpdated(eventDir: String?, fileProperty: String?, projectDir: String, parsedMap: Map<*, *>?) {
        try {
            val props = (parsedMap?.get("payload") as? Map<*, *>)?.get("properties") as? Map<*, *>
            val absolutePath = props?.get("file") as? String
                ?: run { logger.warn("[OpenCodeSSEConsumer] file.watcher.updated missing file property"); return }
            val eventType = props.get("event") as? String
            val relativePath = computeRelativePath(eventDir, absolutePath)
            logger.debug("[OpenCodeSSEConsumer] file.watcher.updated: event=$eventType, absolute='$absolutePath', relative='$relativePath'")
            if (!refreshDeduplicator.shouldRefresh(absolutePath, DEDUP_WINDOW_MS)) {
                logger.debug("[OpenCodeSSEConsumer] Skipping duplicate refresh for $absolutePath"); return
            }
            OpenCodeDiffRefresher.refreshFiles(projectDir, listOf(DiffFile(relativePath, 0, 0,
                when (eventType) { "add" -> "created"; "unlink" -> "deleted"; else -> "modified" })))
            FullRefreshCoordinator.request()
        } catch (e: Exception) { logger.error("[OpenCodeSSEConsumer] Failed to parse file.watcher.updated: ${e.message}", e) }
    }

    private fun computeRelativePath(eventDir: String?, absolutePath: String): String =
        if (eventDir != null && absolutePath.startsWith(eventDir)) {
            try { java.io.File(eventDir).toPath().relativize(java.io.File(absolutePath).toPath()).toString() }
            catch (e: Exception) {
                logger.warn("[OpenCodeSSEConsumer] Failed to relativize path: ${e.message}")
                absolutePath.removePrefix(eventDir).removePrefix("/")
            }
        } else absolutePath

    override fun onComment(comment: String) {
        // BackgroundEventHandler 接口强制实现。
        // SSE 注释事件（以 : 开头的行）在此插件中无意义，故意留空。
        // 请勿删除此方法——没有它编译会失败，它是接口契约的一部分。
    }

    override fun onClosed() {
        logger.info("[OpenCodeSSEConsumer] SSE connection closed")
    }

    override fun onError(error: Throwable) {
        logger.error("[OpenCodeSSEConsumer] SSE error: ${error.message}", error)
    }

}
