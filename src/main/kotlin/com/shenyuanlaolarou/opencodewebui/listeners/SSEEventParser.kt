package com.shenyuanlaolarou.opencodewebui.listeners

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
    val syncEvent: Map<*, *>? = null,
    val parsedMap: Map<*, *>? = null
)

/**
 * 从 ParsedSSEEvent 提取 sessionID,使用六级 fallback 兼容 opencode server 不同版本的字段位置。
 *
 * 提取优先级(高到低):
 *  1. Direct BusEvent 的 `payload.properties.sessionID` —— 老版本/特定事件类型
 *  2. SyncEvent V2 的顶层 `data.sessionID` —— 大多数 SyncEvent 事件
 *  3. SyncEvent V2 的顶层 `data.id` —— session 创建/删除事件用 id 而非 sessionID
 *  4. SyncEvent V2 嵌套 `data.info.sessionID` —— 部分事件把 sessionID 嵌在 info 里
 *  5. SyncEvent V2 嵌套 `data.info.id` —— 同上,但用 id 字段
 *  6. SyncEvent V2 顶层 `syncEvent.aggregateID` —— 实际 server 常用此字段标记 session 实体
 *
 * 返回 `null` 表示事件不携带 sessionID(可能是全局事件如 server.connected)。
 *
 * 任何修改请同步检查 OpenCodeSSEConsumer 中所有 sessionID 提取点(原本有 4 处重复,现已统一到此处)。
 */
fun ParsedSSEEvent.extractSessionID(): String? {
    val payload = parsedMap?.get("payload") as? Map<*, *>
    val props = payload?.get("properties") as? Map<*, *>
    val data = syncEventData
    return props?.get("sessionID") as? String
        ?: data?.get("sessionID") as? String
        ?: data?.get("id") as? String
        ?: (data?.get("info") as? Map<*, *>)?.get("sessionID") as? String
        ?: (data?.get("info") as? Map<*, *>)?.get("id") as? String
        ?: syncEvent?.get("aggregateID") as? String
}

/**
 * 从 ParsedSSEEvent 提取 parentID(三级 fallback,字段位置比 sessionID 稳定)。
 * 返回 `null` 表示该 session 不是 subagent(顶层 session 没有 parentID)。
 */
fun ParsedSSEEvent.extractParentID(): String? {
    val payload = parsedMap?.get("payload") as? Map<*, *>
    val props = payload?.get("properties") as? Map<*, *>
    val data = syncEventData
    return props?.get("parentID") as? String
        ?: data?.get("parentID") as? String
        ?: (data?.get("info") as? Map<*, *>)?.get("parentID") as? String
}

object SSEEventParser {
    private val gson = Gson()

    // LRU 去重缓存：按 payload.id 去重，最大 1000 条。值用 Boolean 占位（时间戳从未被读取）
    // 而非 Long：避免每事件 Long 装箱,~200-500 obj/s。
    private val dedupCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(1000, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
                return size > 1000
            }
        }
    )

    // 静态化避免每事件重新 Pattern.compile（剥离 syncEventType 的版本号后缀,如 .idle.0 → .idle）。
    private val SYNC_EVENT_TYPE_VERSION_REGEX = Regex("\\.\\d+$")

    // [O7] 高频事件类型集合，这些事件在 onMessage 中会被立即跳过，无需完整 Map 转换
    private val SKIP_PARSE_EVENT_TYPES = setOf("message.part.delta")

    fun isEventProcessed(eventID: String): Boolean {
        if (eventID.isEmpty()) return false
        return dedupCache.put(eventID, true) != null
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
            var syncEventMap: Map<*, *>? = null

            if (payloadType == "sync") {
                val syncEvent = payloadMap?.get("syncEvent") as? Map<*, *>
                syncEventType = syncEvent?.get("type") as? String
                syncEventType = syncEventType?.replace(SYNC_EVENT_TYPE_VERSION_REGEX, "")
                syncEventData = syncEvent?.get("data") as? Map<*, *>
                syncEventMap = syncEvent
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
                syncEvent = syncEventMap,
                parsedMap = parsedMap
            )
        } catch (e: Exception) {
            logger.warn("[SSEEventParser] Failed to parse JSON: ${e.message}")
            return ParsedSSEEvent(eventType = eventType, directory = null, file = null, payloadType = null)
        }
    }
}
