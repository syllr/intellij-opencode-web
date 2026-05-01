package com.github.xausky.opencodewebui.listeners

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.listeners.FullRefreshCoordinator
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
    // 去重：最近刷新的文件路径 → 时间戳（毫秒）
    private val lastRefreshTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start() {
        val uri = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/global/event")
        logger.info("[OpenCodeSSEConsumer] Starting SSE consumer, project.basePath='${project.basePath}', uri=$uri")

        FullRefreshCoordinator.start(project.basePath ?: "")

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

        // 从 JSON body 解析 payload type、顶层 directory 和 file 相关字段
        var parsedMap: Map<*, *>? = null
        var payloadType: String? = null
        var eventDir: String? = null
        var fileProperty: String? = null
        try {
            parsedMap = gson.fromJson(message, Map::class.java)
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
        val isFileWatcherUpdated = payloadType == "file.watcher.updated"

        // === Handle bash tool file operations (rm, mv, cp, etc.) ===
        if (payloadType == "message.part.updated") {
            try {
                // reuse parsedMap from top-level parse
                val payload = parsedMap?.get("payload") as? Map<*, *>
                val props = payload?.get("properties") as? Map<*, *>
                val part = props?.get("part") as? Map<*, *>
                val partType = part?.get("type") as? String
                val toolName = part?.get("tool") as? String
                val state = part?.get("state") as? Map<*, *>
                val status = state?.get("status") as? String
                
                if (partType == "tool" && toolName == "bash" && (status == "completed" || status == "running")) {
                    val input = state?.get("input") as? Map<*, *>
                    val command = input?.get("command") as? String ?: ""
                    
                    if (command.isNotBlank()) {
                        logger.info("[OpenCodeSSEConsumer] Bash tool event: status=$status, command='${command.take(200)}'")
                        
                        if (status == "completed") {
                            val exitCode = try {
                                val metadata = state?.get("metadata") as? Map<*, *>
                                (metadata?.get("exit") as? Double)?.toInt() ?: -1
                            } catch (_: Exception) { -1 }
                            
                            if (exitCode == 0) {
                                val projectPath = projectDir
                                if (projectPath != null && command.contains(projectPath)) {
                                    val segments = command.split(Regex("&&|;|\n"))
                                    val allReadOnly = segments.all { segment ->
                                        val base = extractBaseBashCommand(segment)
                                        base.isEmpty() || base in READ_ONLY_COMMANDS
                                    }
                                    
                                    if (allReadOnly) {
                                        logger.info("[OpenCodeSSEConsumer] All bash segments are read-only, skipping refresh")
                                    } else {
                                        logger.info("[OpenCodeSSEConsumer] Bash has non-read-only segments, refreshing project root")
                                        OpenCodeDiffRefresher.refreshProjectRoot(projectPath)
                                        FullRefreshCoordinator.request()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("[OpenCodeSSEConsumer] Failed to parse bash tool event: ${e.message}")
            }
        }

        if (!isSessionDiff && !isFileEdited && !isFileWatcherUpdated) {
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
                val now = System.currentTimeMillis()
                val lastRefresh = lastRefreshTimes.get(absolutePath)
                if (lastRefresh != null && now - lastRefresh < 1000) {
                    logger.info("[OpenCodeSSEConsumer] Skipping duplicate refresh for $absolutePath (${now - lastRefresh}ms ago)")
                    return
                }
                lastRefreshTimes[absolutePath] = now

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

    private fun extractBaseBashCommand(cmd: String): String {
        val skipPrefixes = listOf("sudo", "command", "time", "nohup")
        var trimmed = cmd.trimStart()
        while (true) {
            val stripped = skipPrefixes.fold(trimmed) { acc, prefix ->
                acc.removePrefix("$prefix ")
            }
            if (stripped == trimmed) break
            trimmed = stripped.trimStart()
        }
        return trimmed.split("\\s+".toRegex()).firstOrNull()?.trim() ?: ""
    }

    /**
     * 从 bash 命令中提取项目目录下的文件路径（绝对路径）
     * 使用正则匹配所有绝对路径，不限定具体命令
     */
    companion object {
        /** bash 只读命令白名单——不修改文件的命令，不触发刷新 */
        private val READ_ONLY_COMMANDS = setOf(
            "cd", "ls", "cat", "grep", "head", "tail", "echo", "pwd", "which", "type",
            "dir", "less", "more", "printf", "find", "wc", "sort", "uniq", "cut",
            "diff", "tree", "stat", "file", "du", "df", "env", "printenv", "read",
            "test", "[", "declare", "typeset", "local", "export", "alias", "unalias",
            "hash", "help", "man", "info", "whatis", "apropos", "whereis",
            "source", "exit", "return", "continue", "break", "shopt",
            "history", "fc", "bind", "complete", "compgen", "compopt",
            "id", "whoami", "who", "w", "users", "last", "date",
            "uname", "hostname", "arch", "nproc", "uptime",
            "basename", "dirname", "realpath", "readlink",
            "sleep", "true", "false",
            "xargs", "pgrep", "pidof", "ps", "lsof",
            "unset", "shift", "getopts", "trap", "wait",
            "type", "command", "builtin", "eval",
        )
    }}
