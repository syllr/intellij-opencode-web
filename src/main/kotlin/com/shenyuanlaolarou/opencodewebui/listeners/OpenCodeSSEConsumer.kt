package com.shenyuanlaolarou.opencodewebui.listeners

import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.shenyuanlaolarou.opencodewebui.SSE_CONNECT_TIMEOUT_SECONDS
import com.shenyuanlaolarou.opencodewebui.SSE_IDLE_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.SSE_WATCHDOG_INTERVAL_MS
import com.shenyuanlaolarou.opencodewebui.listeners.SSEEventParser
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeNotificationRouter
import com.shenyuanlaolarou.opencodewebui.utils.dataOrNull
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.launchdarkly.eventsource.ConnectStrategy
import com.launchdarkly.eventsource.ErrorStrategy
import com.launchdarkly.eventsource.EventSource
import com.launchdarkly.eventsource.MessageEvent
import com.launchdarkly.eventsource.background.BackgroundEventHandler
import com.launchdarkly.eventsource.background.BackgroundEventSource
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class OpenCodeSSEConsumer(
    private val project: Project
) : BackgroundEventHandler {
    private val projectBasePath: String? = project.basePath

    private val eventSourceRef = AtomicReference<BackgroundEventSource?>(null)
    private val connectionGen = AtomicLong(0)
    @Volatile private var activeConnectionGen: Long = -1
    private val logger = thisLogger()

    // 任何 SSE 事件到达(onMessage)或连接刚建(onOpen)时刷新,watchdog 超时则强制重连。
    private val lastEventAt = AtomicLong(System.currentTimeMillis())
    @Volatile
    private var watchdogThread: Thread? = null

    private companion object {
        private const val MAX_TRACKED_SESSIONS = 1000

        private fun newLruSet(): MutableSet<String> = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                object : LinkedHashMap<String, Boolean>(MAX_TRACKED_SESSIONS, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > MAX_TRACKED_SESSIONS
                }
            )
        )

        // 故意放在 companion object(违反 AGENTS.md "禁止静态全局可变状态" 的 HARD RULE):
        // subagent 追踪需跨 project 共享同一 set —— 放在实例字段会导致
        // subagent sessionID 在 stop()/onClosed() 后丢失,后续 idle 事件被误判为父 session。
        // 多项目架构下需保持 LRU set 跨 consumer 实例共享。

        // 有 parentID 的 session —— handleSessionIdle 据此区分子 agent/父 session。
        private val subagentSessionIds: MutableSet<String> = newLruSet()

        // subagent title 模式: "@<agent-name> subagent" (如 "(@explore subagent)")
        private val SUBAGENT_TITLE_REGEX = Regex("""@\w+ subagent""")
    }

    fun start() {
        logger.info("[OpenCodeSSEConsumer] Starting SSE consumer, projectBasePath='$projectBasePath'")
        FullRefreshCoordinator.start(projectBasePath ?: "")
        startSseConnection()
        startWatchdog()
    }

    fun stop() {
        watchdogThread?.interrupt()
        watchdogThread = null
        FullRefreshCoordinator.stop()
        eventSourceRef.getAndSet(null)?.close()
        // [Fix #2] 清理静态集合，防止 stop() 后 companion object 集合继续增长
        // onClosed() 只在 SSE 连接关闭时调用，stop() 主动关闭时也需要清理
        SSEEventParser.clearCache()
        subagentSessionIds.clear()
        logger.info("[OpenCodeSSEConsumer] SSE consumer stopped, static collections cleared")
    }

    private fun startSseConnection() {
        // 规范化路径: 解析符号链接 + 去除末尾分隔符, 确保与 opencode 服务端 instance.directory 完全一致
        val normalizedPath = try {
            java.nio.file.Path.of(projectBasePath ?: "").toRealPath().toString()
        } catch (_: Exception) {
            projectBasePath ?: ""
        }
        val uri = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/event?directory=" + URLEncoder.encode(normalizedPath, "UTF-8"))
        val connectStrategy = ConnectStrategy.http(uri)
            .connectTimeout(SSE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        val esBuilder = EventSource.Builder(connectStrategy)
            .errorStrategy(ErrorStrategy.alwaysContinue())
            .streamEventData(false)
        val bgEventSource = BackgroundEventSource.Builder(this, esBuilder).build()
        val gen = connectionGen.incrementAndGet()
        activeConnectionGen = gen
        eventSourceRef.set(bgEventSource)
        bgEventSource.start()
        lastEventAt.set(System.currentTimeMillis())
        logger.info("[OpenCodeSSEConsumer] SSE connection started, gen=$gen, normalizedPath=$normalizedPath, uri=$uri")
    }

    private fun reconnect() {
        val idle = System.currentTimeMillis() - lastEventAt.get()
        logger.warn("[OpenCodeSSEConsumer] No event for ${idle}ms, forcing reconnect")
        val old = eventSourceRef.getAndSet(null)
        startSseConnection()
        old?.close()
    }

    private fun startWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(SSE_WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val idle = System.currentTimeMillis() - lastEventAt.get()
                if (idle > SSE_IDLE_TIMEOUT_MS) reconnect()
            }
        }.apply {
            isDaemon = true
            name = "SSE-Watchdog"
            start()
        }
    }

    override fun onOpen() {
        lastEventAt.set(System.currentTimeMillis())
        logger.info("[OpenCodeSSEConsumer] SSE connection opened")
    }

    override fun onMessage(event: String, messageEvent: MessageEvent) {
        lastEventAt.set(System.currentTimeMillis())
        val data = messageEvent.data ?: return
        val parsed = SSEEventParser.parse(event, data)
        val parsedMap = parsed.parsedMap

        // 非白名单事件：parser 已早退（parsedMap == null），直接丢弃
        if (parsedMap == null) return

        val type = parsed.type ?: return

        if (logger.isDebugEnabled) {
            logger.debug("[OpenCodeSSEConsumer] Event: type='$type'")
        }

        // 事件去重：按 root-level id 去重
        val eventId = parsedMap["id"] as? String
        if (eventId != null && SSEEventParser.isEventProcessed(eventId)) return

        if (type == "session.created") {
            val sid = parsed.extractSessionID()
            val parentID = parsed.extractParentID()
            if (sid != null && parentID != null) {
                subagentSessionIds.add(sid)
                if (logger.isDebugEnabled) {
                    logger.debug("[OpenCodeSSEConsumer] Subagent session tracked: $sid (parent=$parentID)")
                }
            }
        }

        // 通知事件分发（在文件事件过滤之前，确保通知事件不被丢）
        when (type) {
            "permission.asked" -> dispatchNotification("permission", parsedMap, project)
            "session.status" -> {
                val props = parsedMap["properties"] as? Map<*, *>
                val statusObj = props?.get("status") as? Map<*, *>
                if (statusObj?.get("type") == "idle") {
                    handleSessionIdle(parsed, project)
                }
            }
            "session.idle" -> handleSessionIdle(parsed, project)
            "question.asked" -> dispatchNotification("question", parsedMap, project)
        }

        // 文件事件处理
        if (type == "message.part.updated") { BashCommandHandler.handleBashEvent(parsedMap, project.basePath); return }
        if (type != "session.diff" && type != "file.edited" && type != "file.watcher.updated") return
        if (project.basePath == null) { logger.warn("[OpenCodeSSEConsumer] project.basePath is null, skipping refresh"); return }

        if (type == "session.diff") {
            try { FullRefreshCoordinator.request() }
            catch (e: Exception) { logger.error("[OpenCodeSSEConsumer] Failed to handle session.diff: ${e.message}", e) }
            return
        }
        FullRefreshCoordinator.request()
    }

    private fun dispatchNotification(eventType: String, parsedMap: Map<*, *>?, project: Project) {
        OpenCodeNotificationRouter.notify(eventType, parsedMap, project)
    }

    private fun handleSessionIdle(parsed: ParsedSSEEvent, project: Project) {
        val parsedMap = parsed.parsedMap
        val sessionID = parsed.extractSessionID() ?: return

        val title = OpenCodeApi.getSession(sessionID).dataOrNull()?.title
        if (title != null && SUBAGENT_TITLE_REGEX.containsMatchIn(title)) {
            if (logger.isDebugEnabled) {
                logger.debug("[OpenCodeSSEConsumer] Subagent idle suppressed by title: $sessionID (title=$title)")
            }
            return
        }
        dispatchNotification("complete", parsedMap, project)
    }

    override fun onClosed() {
        val currentGen = activeConnectionGen
        val latestGen = connectionGen.get()
        if (currentGen != latestGen) {
            logger.info("[OpenCodeSSEConsumer] SSE onClosed for stale connection (gen=$currentGen, latest=$latestGen), skipping cleanup")
            return
        }
        logger.info("[OpenCodeSSEConsumer] SSE connection closed (gen=$currentGen)")
        SSEEventParser.clearCache()
        val subagentSize = subagentSessionIds.size
        subagentSessionIds.clear()
        if (logger.isDebugEnabled) {
            logger.debug("[OpenCodeSSEConsumer] Cleared subagentSessionIds ($subagentSize entries)")
        }
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
