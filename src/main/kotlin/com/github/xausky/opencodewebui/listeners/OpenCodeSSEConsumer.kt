package com.github.xausky.opencodewebui.listeners

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
    project: Project
) : BackgroundEventHandler {
    private val projectBasePath: String? = project.basePath

    private val gson = Gson()
    private val eventSourceRef = AtomicReference<BackgroundEventSource?>(null)
    private val logger = thisLogger()

    // subagent 会话追踪：记录所有 subagent sessionID（有 parentID 的 session）
    companion object {
        private val subagentSessionIds = ConcurrentHashMap.newKeySet<String>()
        // 已发送过 complete 通知的父 session 集合
        // 用于抑制 agent 循环中的重复 idle 通知
        private val sessionIdleFired = ConcurrentHashMap.newKeySet<String>()
    }

    fun start() {
        val uri = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/global/event")
        logger.info("[OpenCodeSSEConsumer] Starting SSE consumer, projectBasePath='$projectBasePath', uri=$uri")

        FullRefreshCoordinator.start(projectBasePath ?: "")

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
        // [Fix #2] 清理静态集合，防止 stop() 后 companion object 集合继续增长
        // onClosed() 只在 SSE 连接关闭时调用，stop() 主动关闭时也需要清理
        SSEEventParser.clearCache()
        subagentSessionIds.clear()
        sessionIdleFired.clear()
        idleLastFired.clear()
        logger.info("[OpenCodeSSEConsumer] SSE consumer stopped, static collections cleared")
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
        val projectDir = projectBasePath

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

        if (eventType == "session.created") {
            // Direct BusEvent: parsedMap.payload.properties.{sessionID,parentID}
            // SyncEvent V2:   parsed.syncEventData.{id,parentID}
            val payload = parsedMap?.get("payload") as? Map<*, *>
            val props = payload?.get("properties") as? Map<*, *>
            val data = parsed.syncEventData
            val sid = props?.get("sessionID") as? String
                ?: data?.get("sessionID") as? String
                ?: data?.get("id") as? String
                ?: (data?.get("info") as? Map<*, *>)?.get("sessionID") as? String
                ?: (data?.get("info") as? Map<*, *>)?.get("id") as? String
            val parentID = props?.get("parentID") as? String
                ?: data?.get("parentID") as? String
                ?: (data?.get("info") as? Map<*, *>)?.get("parentID") as? String
            if (sid != null && parentID != null) {
                subagentSessionIds.add(sid)
                logger.debug("[OpenCodeSSEConsumer] Subagent session tracked: $sid (parent=$parentID)")
            } else if (sid != null && parentID == null) {
                dispatchNotification("session_started", parsedMap, eventDir)
            }
        }
        if (eventType == "session.deleted") {
            val payload = parsedMap?.get("payload") as? Map<*, *>
            val props = payload?.get("properties") as? Map<*, *>
            val data = parsed.syncEventData
            val sid = props?.get("sessionID") as? String
                ?: data?.get("sessionID") as? String
                ?: data?.get("id") as? String
                ?: (data?.get("info") as? Map<*, *>)?.get("id") as? String
            if (sid != null) {
                // 不移除 subagentSessionIds：防止 session.deleted 先于 session.status(idle) 到达时
                // 子 agent 的 idle 事件被误判为 complete。集合仅在 SSE 重连时通过 onClosed() 清空。
                logger.debug("[OpenCodeSSEConsumer] Subagent session removed (tracking preserved for idle detection): $sid")
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
                    handleSessionIdle(parsed, eventDir)
                }
            }
            "session.idle" -> handleSessionIdle(parsed, eventDir)
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
                val data = parsed.syncEventData
                val info = props?.get("info") as? Map<*, *>
                    ?: (data as? Map<*, *>)?.get("info") as? Map<*, *>
                if (info?.get("role") == "user") {
                    val sessionID = props?.get("sessionID") as? String
                        ?: (data as? Map<*, *>)?.get("sessionID") as? String
                        ?: (data as? Map<*, *>)?.get("id") as? String
                        ?: (data?.get("info") as? Map<*, *>)?.get("sessionID") as? String
                        ?: (data?.get("info") as? Map<*, *>)?.get("id") as? String
                    if (sessionID != null) {
                        sessionIdleFired.remove(sessionID)
                    }
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
            try { FullRefreshCoordinator.request() }
            catch (e: Exception) { logger.error("[OpenCodeSSEConsumer] Failed to handle session.diff: ${e.message}", e) }
            return
        }
        if (isFileEdited) { FullRefreshCoordinator.request(); return }
        if (isFileWatcherUpdated) { FullRefreshCoordinator.request(); return }
    }

    private fun dispatchNotification(eventType: String, parsedMap: Map<*, *>?, eventDir: String?) {
        OpenCodeNotificationRouter.notify(eventType, parsedMap, eventDir)
    }

    // session idle 去重：同一 session 在时间窗口内只发一次 complete 通知
    // 防止 session.status(idle) 和 session.idle 两个事件重复触发
    // [Fix #7] 使用有界 LRU Map 防止无界增长
    private val idleLastFired = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > 500
        }
    )
    private val idleDedupWindowMs = 2000L

    private fun handleSessionIdle(parsed: ParsedSSEEvent, eventDir: String?) {
        val parsedMap = parsed.parsedMap
        val props = parsedMap?.get("payload") as? Map<*, *>
        val properties = props?.get("properties") as? Map<*, *>
        val data = parsed.syncEventData
        val sessionID = properties?.get("sessionID") as? String
            ?: data?.get("sessionID") as? String
            ?: data?.get("id") as? String
            ?: (data?.get("info") as? Map<*, *>)?.get("sessionID") as? String
            ?: (data?.get("info") as? Map<*, *>)?.get("id") as? String
            ?: return

        // 子 agent idle → subagent_complete（走配置开关，默认关闭）
        if (sessionID in subagentSessionIds) {
            dispatchNotification("subagent_complete", parsedMap, eventDir)
            return
        }

        // 父 session 抑制：已发过 complete 则跳过
        // 防止 agent 循环中的重复通知，按用户发消息（message.updated）重置
        if (sessionID in sessionIdleFired) {
            return
        }

        val key = "$sessionID:complete"

        // 同一 session 在时间窗口内只发一次（防止 session.status(idle) 和 session.idle 双发）
        val now = System.currentTimeMillis()
        val last = idleLastFired.put(key, now)
        if (last != null && now - last < idleDedupWindowMs) {
            return
        }

        dispatchNotification("complete", parsedMap, eventDir)
        sessionIdleFired.add(sessionID)
    }

    override fun onClosed() {
        logger.info("[OpenCodeSSEConsumer] SSE connection closed")
        SSEEventParser.clearCache()
        subagentSessionIds.clear()
        idleLastFired.clear()
        logger.debug("[OpenCodeSSEConsumer] Cleared sessionIdleFired (${sessionIdleFired.size} entries)")
        sessionIdleFired.clear()
    }

    override fun onComment(comment: String) {
        // BackgroundEventHandler 接口强制实现。
        // SSE 注释事件（以 : 开头的行）在此插件中无意义，故意留空。
        // 请勿删除此方法——没有它编译会失败，它是接口契约的一部分。
    }

    override fun onError(error: Throwable) {
        logger.error("[OpenCodeSSEConsumer] SSE error: ${error.message}", error)
    }

}
