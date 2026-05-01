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

        // 打印每个收到的 SSE 事件
        logger.info("[OpenCodeSSEConsumer] RAW SSE event: type='$event', data=${message.take(1000)}")

        // 解析 SSE 事件
        val parsed = SSEEventParser.parse(event, message)
        val parsedMap = parsed.parsedMap
        val payloadType = parsed.payloadType
        val eventDir = parsed.directory
        val fileProperty = parsed.file

        val projectDir = project.basePath

        logger.info("[OpenCodeSSEConsumer] Event: type='${parsed.payloadType}', eventDir=$eventDir, projectDir=$projectDir, file=$fileProperty")

        if (fileProperty != null) {
            logger.info("[OpenCodeSSEConsumer] *** FILE CHANGE DETECTED *** payloadType=${parsed.payloadType}, file=$fileProperty")
        }

        val isSessionDiff = parsed.eventType == "session.diff" || parsed.payloadType == "session.diff"
        val isFileEdited = parsed.payloadType == "file.edited"
        val isFileWatcherUpdated = parsed.payloadType == "file.watcher.updated"

        // === Handle bash tool file operations ===
        if (payloadType == "message.part.updated") {
            BashCommandHandler.handleBashEvent(parsedMap, projectDir)
            return
        }

        if (!isSessionDiff && !isFileEdited && !isFileWatcherUpdated) {
            // 非文件事件只打日志，不处理
            return
        }

        if (projectDir == null) {
            logger.warn("[OpenCodeSSEConsumer] project.basePath is null, skipping refresh")
            return
        }

        if (eventDir != null && projectDir != null) {
            try {
                if (java.io.File(eventDir).canonicalPath != java.io.File(projectDir).canonicalPath) {
                    logger.info("[OpenCodeSSEConsumer] Directory MISMATCH: event='$eventDir' vs project='$projectDir'")
                    return
                }
            } catch (_: Exception) {
                if (eventDir != projectDir) {
                    logger.info("[OpenCodeSSEConsumer] Directory MISMATCH (fallback): event='$eventDir' vs project='$projectDir'")
                    return
                }
            }
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
                    try {
                        java.io.File(eventDir).toPath().relativize(java.io.File(absolutePath).toPath()).toString()
                    } catch (_: Exception) {
                        absolutePath.removePrefix(eventDir).removePrefix("/")
                    }
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
                FullRefreshCoordinator.request()
            } catch (e: Exception) {
                logger.error("[OpenCodeSSEConsumer] Failed to parse file.edited: ${e.message}", e)
            }
            return
        }

        // === Handle file.watcher.updated (filesystem events from ParcelWatcher) ===
        if (isFileWatcherUpdated) {
            try {
                // reuse parsedMap from top-level parse
                val payload = parsedMap?.get("payload") as? Map<*, *>
                val properties = payload?.get("properties") as? Map<*, *>
                val absolutePath = properties?.get("file") as? String
                val eventType = properties?.get("event") as? String  // "add", "change", "unlink"

                if (absolutePath == null) {
                    logger.warn("[OpenCodeSSEConsumer] file.watcher.updated missing file property")
                    return
                }

                val relativePath = if (eventDir != null && absolutePath.startsWith(eventDir)) {
                    try {
                        java.io.File(eventDir).toPath().relativize(java.io.File(absolutePath).toPath()).toString()
                    } catch (_: Exception) {
                        absolutePath.removePrefix(eventDir).removePrefix("/")
                    }
                } else {
                    absolutePath
                }

                logger.info("[OpenCodeSSEConsumer] file.watcher.updated: event=$eventType, absolute='$absolutePath', relative='$relativePath'")

                // 注意：file.edited 和 file.watcher.updated 对 create/edit 是双发的
                // 这里用文件路径去重：记录最近刷新的文件，1秒内不重复刷新同一个文件
                if (!refreshDeduplicator.shouldRefresh(absolutePath, DEDUP_WINDOW_MS)) {
                    logger.info("[OpenCodeSSEConsumer] Skipping duplicate refresh for $absolutePath")
                    return
                }

                val diffFiles = listOf(
                    DiffFile(
                        file = relativePath,
                        additions = 0,
                        deletions = 0,
                        status = when (eventType) {
                            "add" -> "created"
                            "unlink" -> "deleted"
                            else -> "modified"
                        }
                    )
                )
                OpenCodeDiffRefresher.refreshFiles(projectDir, diffFiles)
                FullRefreshCoordinator.request()
            } catch (e: Exception) {
                logger.error("[OpenCodeSSEConsumer] Failed to parse file.watcher.updated: ${e.message}", e)
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
