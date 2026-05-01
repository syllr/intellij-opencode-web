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

data class SSEFileEditedEvent(
    val directory: String? = null,
    val payload: SSEFileEditedPayload
)

data class SSEFileEditedPayload(
    val type: String,
    val properties: SSEFileEditedProperties
)

data class SSEFileEditedProperties(
    val file: String
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

        // 打印每个收到的 SSE 事件
        logger.info("[OpenCodeSSEConsumer] RAW SSE event: type='$event', data=${message.take(1000)}")

        // 从 JSON body 解析 payload type、顶层 directory 和 file 相关字段
        var payloadType: String? = null
        var eventDir: String? = null
        var fileProperty: String? = null
        try {
            val parsedMap = gson.fromJson(message, Map::class.java)
            eventDir = parsedMap?.get("directory") as? String
            val payload = parsedMap?.get("payload") as? Map<*, *>
            payloadType = payload?.get("type") as? String
            val properties = payload?.get("properties") as? Map<*, *>
            // 检查 payload.properties 中是否包含 file/filePath 字段
            fileProperty = properties?.get("file") as? String
            if (fileProperty == null) {
                fileProperty = properties?.get("filePath") as? String
            }
            if (fileProperty != null) {
                logger.info("[OpenCodeSSEConsumer] *** FILE EVENT *** type=$payloadType, file=$fileProperty, eventDir=$eventDir")
            }
        } catch (e: Exception) {
            logger.warn("[OpenCodeSSEConsumer] Failed to parse JSON: ${e.message}")
        }

        val projectDir = project.basePath

        // 所有事件都打印诊断信息
        logger.info("[OpenCodeSSEConsumer] Event: type='$payloadType', eventDir=$eventDir, projectDir=$projectDir, file=$fileProperty")

        // 有 file 属性的额外标记
        if (fileProperty != null) {
            logger.info("[OpenCodeSSEConsumer] *** FILE CHANGE DETECTED *** payloadType=$payloadType, file=$fileProperty")
        }

        val isSessionDiff = event == "session.diff" || payloadType == "session.diff"
        val isFileEdited = payloadType == "file.edited"

        if (!isSessionDiff && !isFileEdited) {
            // 非文件事件只打日志，不处理
            return
        }

        if (projectDir == null) {
            logger.warn("[OpenCodeSSEConsumer] project.basePath is null, skipping refresh")
            return
        }

        if (eventDir != null && eventDir != projectDir) {
            logger.info("[OpenCodeSSEConsumer] Directory MISMATCH: event='$eventDir' vs project='$projectDir'")
            return
        }

        // === Handle session.diff (batch refresh via diff list) ===
        if (isSessionDiff) {
            try {
                val wrapper = gson.fromJson(message, SSEEventWrapper::class.java)
                val props = wrapper.payload.properties
                logger.info("[OpenCodeSSEConsumer] session.diff: ${props.diff.size} files")
                props.diff.forEachIndexed { i, f ->
                    logger.info("[OpenCodeSSEConsumer]   diff[$i]: file='${f.file}', status=${f.status}")
                }
                if (props.diff.isNotEmpty()) {
                    logger.info("[OpenCodeSSEConsumer] Refreshing ${props.diff.size} files from session.diff")
                    OpenCodeDiffRefresher.refreshFiles(projectDir, props.diff)
                } else {
                    logger.info("[OpenCodeSSEConsumer] session.diff has 0 files, skipping")
                }
            } catch (e: Exception) {
                logger.error("[OpenCodeSSEConsumer] Failed to parse session.diff: ${e.message}", e)
            }
            return
        }

        // === Handle file.edited (single file from OpenCode edit) ===
        if (isFileEdited) {
            try {
                val fe = gson.fromJson(message, SSEFileEditedEvent::class.java)
                val absolutePath = fe.payload.properties.file
                val relativePath = if (eventDir != null && absolutePath.startsWith(eventDir)) {
                    absolutePath.removePrefix(eventDir).removePrefix("/")
                } else {
                    logger.warn("[OpenCodeSSEConsumer] Cannot determine relative path: file='$absolutePath', dir='$eventDir'")
                    absolutePath
                }
                logger.info("[OpenCodeSSEConsumer] file.edited: absolute='$absolutePath', relative='$relativePath'")
                val diffFiles = listOf(
                    DiffFile(
                        file = relativePath,
                        additions = 0,
                        deletions = 0,
                        status = "modified"
                    )
                )
                OpenCodeDiffRefresher.refreshFiles(projectDir, diffFiles)
            } catch (e: Exception) {
                logger.error("[OpenCodeSSEConsumer] Failed to parse file.edited: ${e.message}", e)
            }
            return
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
