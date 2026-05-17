package com.github.xausky.opencodewebui.listeners

import com.github.xausky.opencodewebui.DEDUP_WINDOW_MS
import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.SSE_CONNECT_TIMEOUT_SECONDS
import com.github.xausky.opencodewebui.listeners.SSEEventParser
import com.github.xausky.opencodewebui.utils.OpenCodeNotificationRouter
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenCodeSSEConsumer(
    private val project: Project
) : BackgroundEventHandler {

    private val gson = Gson()
    private val eventSourceRef = AtomicReference<BackgroundEventSource?>(null)
    private val logger = thisLogger()
    private val refreshDeduplicator = RefreshDeduplicator()

    // subagent 会话追踪：记录所有 subagent sessionID（有 parentID 的 session）
    companion object {
        private val subagentSessionIds = ConcurrentHashMap.newKeySet<String>()
    }

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

        logger.debug("[OpenCodeSSEConsumer] Event: type='$payloadType', syncEventType='${parsed.syncEventType}', eventDir=$eventDir, projectDir=$projectDir, file=$fileProperty")

        // 事件去重：按 payload.id 去重
        val payloadId = (parsedMap?.get("payload") as? Map<*, *>)?.get("id") as? String
        if (payloadId != null && SSEEventParser.isEventProcessed(payloadId)) return

        // 使用 syncEventType 优先（SyncEvent V2），没有则用 payloadType（Direct BusEvent）
        val eventType = parsed.syncEventType ?: payloadType ?: return

        // subagent 追踪 + session_started 通知
        if (eventType == "session.created") {
            val info = parsed.syncEventData?.get("info") as? Map<*, *>
            val parentID = info?.get("parentID") as? String
            val sid = info?.get("id") as? String
            if (parentID != null && sid != null) {
                subagentSessionIds.add(sid)
                logger.debug("[OpenCodeSSEConsumer] Subagent session tracked: $sid (parent=$parentID)")
            } else if (parentID == null) {
                dispatchNotification("session_started", parsedMap, eventDir)
            }
        }
        if (eventType == "session.deleted") {
            val sid = parsed.syncEventData?.get("sessionID") as? String
            if (sid != null) {
                subagentSessionIds.remove(sid)
                logger.debug("[OpenCodeSSEConsumer] Subagent session removed: $sid")
            }
        }

        // 通知事件分发（在文件事件过滤之前，确保通知事件不被丢）
        when (eventType) {
            "server.connected" -> dispatchNotification("client_connected", parsedMap, eventDir)
            "permission.asked" -> dispatchNotification("permission", parsedMap, eventDir)
            "session.error" -> {
                val payload = parsedMap?.get("payload") as? Map<*, *>
                val props = payload?.get("properties") as? Map<*, *>
                val err = props?.get("error") as? Map<*, *>
                val errorName = err?.get("name") as? String
                val notifType = if (errorName == "MessageAbortedError") "user_cancelled" else "error"
                dispatchNotification(notifType, parsedMap, eventDir)
            }
            "session.status" -> {
                val payload = parsedMap?.get("payload") as? Map<*, *>
                val props = payload?.get("properties") as? Map<*, *>
                val statusObj = props?.get("status") as? Map<*, *>
                if (statusObj?.get("type") == "idle") {
                    handleSessionIdle(parsedMap, eventDir)
                }
            }
            "session.idle" -> handleSessionIdle(parsedMap, eventDir)
            "session.next.tool.called" -> {
                val payload = parsedMap?.get("payload") as? Map<*, *>
                val properties = payload?.get("properties") as? Map<*, *>
                val data = parsed.syncEventData
                val tool = properties?.get("tool") as? String
                    ?: data?.get("tool") as? String
                if (tool == "question") dispatchNotification("question", parsedMap, eventDir)
                else if (tool == "plan_exit") dispatchNotification("plan_exit", parsedMap, eventDir)
            }
            "question.asked" -> dispatchNotification("question", parsedMap, eventDir)
            "message.updated" -> {
                val payload = parsedMap?.get("payload") as? Map<*, *>
                val props = payload?.get("properties") as? Map<*, *>
                val info = props?.get("info") as? Map<*, *>
                if (info?.get("role") == "user") {
                    dispatchNotification("user_message", parsedMap, eventDir)
                }
            }
        }

        // 文件事件处理（已有逻辑）
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

    private fun dispatchNotification(eventType: String, parsedMap: Map<*, *>?, eventDir: String?) {
        OpenCodeNotificationRouter.notify(eventType, parsedMap, eventDir)
    }

    // session idle 去重：同一 session 在时间窗口内只发一次 complete 通知
    // 防止 session.status(idle) 和 session.idle 两个事件重复触发
    private val idleLastFired = ConcurrentHashMap<String, Long>()
    private val idleDedupWindowMs = 2000L

    private fun handleSessionIdle(parsedMap: Map<*, *>?, eventDir: String?) {
        val props = parsedMap?.get("payload") as? Map<*, *>
        val properties = props?.get("properties") as? Map<*, *>
        val sessionID = properties?.get("sessionID") as? String ?: return
        val eventType = if (sessionID in subagentSessionIds) "subagent_complete" else "complete"
        val key = "$sessionID:$eventType"

        // 同一 session 在时间窗口内只发一次
        val now = System.currentTimeMillis()
        val last = idleLastFired.put(key, now)
        if (last != null && now - last < idleDedupWindowMs) {
            logger.debug("[OpenCodeSSEConsumer] Skipping duplicate idle notification for $key (last fired ${now - last}ms ago)")
            return
        }

        dispatchNotification(eventType, parsedMap, eventDir)
    }

    override fun onClosed() {
        logger.info("[OpenCodeSSEConsumer] SSE connection closed")
        SSEEventParser.clearCache()
        subagentSessionIds.clear()
        idleLastFired.clear()
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

    override fun onError(error: Throwable) {
        logger.error("[OpenCodeSSEConsumer] SSE error: ${error.message}", error)
    }

}
