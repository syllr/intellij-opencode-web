package com.github.xausky.opencodewebui.listeners

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
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

/**
 * SSE event wrapper.
 * @param directory The project directory from the SSE event (top-level field in JSON).
 * @param payload The SSE payload containing session diff information.
 */
data class SSEEventWrapper(
    val directory: String? = null,
    val payload: SSESessionDiffPayload
)

data class SSESessionDiffPayload(
    val type: String,
    val properties: SSESessionDiffProperties
)

data class SSESessionDiffProperties(
    val sessionID: String,
    val diff: List<DiffFile>
)

data class DiffFile(
    val file: String,
    val additions: Int,
    val deletions: Int,
    val status: String
)

class OpenCodeSSEConsumer(
    private val project: Project
) : BackgroundEventHandler {

    private val gson = Gson()
    private val eventSourceRef = AtomicReference<BackgroundEventSource?>(null)
    private val logger = thisLogger()

    fun start() {
        val uri = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/global/event")
        logger.info("[OpenCodeSSEConsumer] Starting SSE consumer, project.basePath='${project.basePath}', uri=$uri")
        val connectStrategy = ConnectStrategy.http(uri)
            .connectTimeout(5, TimeUnit.SECONDS)
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
        eventSourceRef.getAndSet(null)?.close()
        logger.info("[OpenCodeSSEConsumer] SSE consumer stopped")
    }

    override fun onOpen() {
        logger.info("[OpenCodeSSEConsumer] SSE connection opened")
    }

    override fun onMessage(event: String, messageEvent: MessageEvent) {
        val message = messageEvent.data

        // 【关键日志】打印每个收到的 SSE 事件，不论类型
        logger.info("[OpenCodeSSEConsumer] RAW SSE event: type='$event', data=${message.take(500)}")

        // 尝试从 JSON body 中解析 payload type（OpenCode 可能不在 event header 中传类型）
        var payloadType: String? = null
        try {
            val parsed = gson.fromJson(message, Map::class.java) as? Map<*, *>
            val payload = parsed?.get("payload") as? Map<*, *>
            payloadType = payload?.get("type") as? String
        } catch (e: Exception) {
            logger.warn("[OpenCodeSSEConsumer] Failed to parse JSON payload type: ${e.message}")
        }

        // 双重判断：SSE event header OR JSON body payload.type
        val isSessionDiff = event == "session.diff" || payloadType == "session.diff"

        if (!isSessionDiff) {
            logger.info("[OpenCodeSSEConsumer] Ignored event: event='$event', payloadType=$payloadType")
            return
        }

        logger.info("[OpenCodeSSEConsumer] MESSAGE session.diff FOUND! event='$event', payloadType=$payloadType")
        logger.info("[OpenCodeSSEConsumer] session.diff raw data: $message")

        try {
            val wrapper = gson.fromJson(message, SSEEventWrapper::class.java)
            val props = wrapper.payload.properties
            val eventDir = wrapper.directory
            val projectDir = project.basePath
            logger.info("[OpenCodeSSEConsumer] event.directory='$eventDir', project.basePath='$projectDir'")
            logger.info("[OpenCodeSSEConsumer] Files to refresh: ${props.diff.size} files")
            props.diff.forEachIndexed { i, f ->
                logger.info("[OpenCodeSSEConsumer]   diff[$i]: file='${f.file}', status=${f.status}, +${f.additions}/-${f.deletions}")
            }
            if (projectDir != null && eventDir == projectDir && props.diff.isNotEmpty()) {
                logger.info("[OpenCodeSSEConsumer] Directory MATCHED with ${props.diff.size} files, invoking DiffRefresher via invokeLater")
                ApplicationManager.getApplication().invokeLater {
                    OpenCodeDiffRefresher.refreshFiles(eventDir, props.diff)
                }
            } else if (projectDir != null && eventDir != projectDir) {
                logger.info("[OpenCodeSSEConsumer] Directory MISMATCH: event='$eventDir' vs project='$projectDir'")
            } else {
                logger.info("[OpenCodeSSEConsumer] Skipping: match=${eventDir == projectDir}, files=${props.diff.size}")
            }
        } catch (e: Exception) {
            logger.error("[OpenCodeSSEConsumer] Failed to parse session.diff JSON: ${e.message}", e)
        }
    }

    override fun onComment(comment: String) {
        // 忽略 SSE 注释
    }

    override fun onClosed() {
        logger.info("[OpenCodeSSEConsumer] SSE connection closed")
    }

    override fun onError(error: Throwable) {
        logger.error("[OpenCodeSSEConsumer] SSE error: ${error.message}", error)
    }
}
