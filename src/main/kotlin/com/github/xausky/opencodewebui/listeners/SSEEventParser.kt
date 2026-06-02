package com.github.xausky.opencodewebui.listeners

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    // [O7] 高频事件类型集合，这些事件在 onMessage 中会被立即跳过，无需完整 Map 转换
    private val SKIP_PARSE_EVENT_TYPES = setOf("message.part.delta")

    fun isEventProcessed(eventID: String): Boolean {
        if (eventID.isEmpty()) return false
        return dedupCache.put(eventID, System.currentTimeMillis()) != null
    }

    fun clearCache() {
        dedupCache.clear()
    }

    fun parse(eventType: String, message: String): ParsedSSEEvent {
        val logger = thisLogger()

        try {
            // [O7] 先解析为 JsonObject（树模型），提取轻量字段
            val jsonElement = JsonParser.parseString(message)
            if (jsonElement !is JsonObject) {
                return ParsedSSEEvent(eventType = eventType, directory = null, file = null, payloadType = null)
            }
            val root = jsonElement
            val eventDir = root.get("directory")?.asString

            val payload = root.getAsJsonObject("payload")
            val payloadType = payload?.get("type")?.asString

            // [O7] 高频事件快速路径：只提取必要字段，跳过 Map<*, *> 转换
            if (payloadType in SKIP_PARSE_EVENT_TYPES) {
                return ParsedSSEEvent(
                    eventType = eventType,
                    directory = eventDir,
                    file = null,
                    payloadType = payloadType
                )
            }

            // 非高频事件：转换为 Map<*, *> 供下游使用
            val parsedMap: Map<*, *> = gson.fromJson(root, Map::class.java)
            val payloadMap = parsedMap["payload"] as? Map<*, *>

            var syncEventType: String? = null
            var syncEventData: Map<*, *>? = null

            if (payloadType == "sync") {
                val syncEvent = payloadMap?.get("syncEvent") as? Map<*, *>
                syncEventType = syncEvent?.get("type") as? String
                syncEventType = syncEventType?.replace(Regex("\\.\\d+$"), "")
                syncEventData = syncEvent?.get("data") as? Map<*, *>
            }

            val properties = payloadMap?.get("properties") as? Map<*, *>
            var fileProperty = properties?.get("file") as? String
            if (fileProperty == null) {
                fileProperty = properties?.get("filePath") as? String
            }
            if (fileProperty != null) {
                logger.info("[SSEEventParser] *** FILE EVENT *** type=$payloadType, file=$fileProperty, eventDir=$eventDir")
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
        } catch (e: Exception) {
            logger.warn("[SSEEventParser] Failed to parse JSON: ${e.message}")
            return ParsedSSEEvent(eventType = eventType, directory = null, file = null, payloadType = null)
        }
    }
}
