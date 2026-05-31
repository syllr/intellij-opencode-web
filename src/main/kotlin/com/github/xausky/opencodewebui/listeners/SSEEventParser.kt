package com.github.xausky.opencodewebui.listeners

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.thisLogger
import java.util.Collections
import java.util.LinkedHashMap

data class ParsedSSEEvent(
    val eventType: String,
    val directory: String?,
    val file: String?,
    val payloadType: String?,
    val syncEventType: String? = null,
    val syncEventData: Map<*, *>? = null,
    val parsedMap: Map<*, *>? = null
)

object SSEEventParser {
    private val gson = Gson()

    // LRU 去重缓存：按 payload.id 去重，最大 1000 条
    private val dedupCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(1000, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
                return size > 1000
            }
        }
    )

    /**
     * 检查 eventID 是否已处理过。已处理返回 true，否则记录并返回 false。
     */
    fun isEventProcessed(eventID: String): Boolean {
        if (eventID.isEmpty()) return false
        return dedupCache.put(eventID, System.currentTimeMillis()) != null
    }

    /**
     * SSE 重连时清空缓存
     */
    fun clearCache() {
        dedupCache.clear()
    }

    fun parse(eventType: String, message: String): ParsedSSEEvent {
        val logger = thisLogger()
        var parsedMap: Map<*, *>? = null
        var payloadType: String? = null
        var eventDir: String? = null
        var fileProperty: String? = null
        var syncEventType: String? = null
        var syncEventData: Map<*, *>? = null

        try {
            parsedMap = gson.fromJson(message, Map::class.java)
            eventDir = parsedMap?.get("directory") as? String
            val payload = parsedMap?.get("payload") as? Map<*, *>
            payloadType = payload?.get("type") as? String

            // SyncEvent (V2) 格式检测：payload.type == "sync"
            if (payloadType == "sync") {
                val syncEvent = payload?.get("syncEvent") as? Map<*, *>
                syncEventType = syncEvent?.get("type") as? String
                // 去掉版本号后缀 ".N"（如 "session.created.1" → "session.created"）
                syncEventType = syncEventType?.replace(Regex("\\.\\d+$"), "")
                syncEventData = syncEvent?.get("data") as? Map<*, *>
            }

            // 非 SyncEvent 时，尝试提取 properties（文件事件、权限事件等）
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
            syncEventType = syncEventType,
            syncEventData = syncEventData,
            parsedMap = parsedMap
        )
    }
}
