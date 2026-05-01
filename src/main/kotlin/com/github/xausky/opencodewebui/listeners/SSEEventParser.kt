package com.github.xausky.opencodewebui.listeners

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.thisLogger

/**
 * 解析后的 SSE 事件数据
 */
data class ParsedSSEEvent(
    val eventType: String,          // "session.diff", "file.edited", etc.
    val directory: String?,          // 顶层 directory 字段
    val file: String?,               // properties 中的 file/filePath
    val payloadType: String?,        // payload 中的 type
    val parsedMap: Map<*, *>?        // 原始解析的 JSON map（供重用于后续解析）
)

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

object SSEEventParser {
    private val gson = Gson()
    
    fun parse(eventType: String, message: String): ParsedSSEEvent {
        val logger = thisLogger()
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
            fileProperty = properties?.get("file") as? String
            if (fileProperty == null) {
                fileProperty = properties?.get("filePath") as? String
            }
            if (fileProperty != null) {
                logger.info("[SSEEventParser] *** FILE EVENT *** type=$payloadType, file=$fileProperty, eventDir=$eventDir")
            }
        } catch (e: Exception) {
            logger.warn("[SSEEventParser] Failed to parse JSON: ${e.message}")
        }
        return ParsedSSEEvent(
            eventType = eventType,
            directory = eventDir,
            file = fileProperty,
            payloadType = payloadType,
            parsedMap = parsedMap
        )
    }
}
