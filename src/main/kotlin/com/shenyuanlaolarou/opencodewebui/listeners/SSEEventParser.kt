package com.shenyuanlaolarou.opencodewebui.listeners

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import com.shenyuanlaolarou.opencodewebui.LRU_MAX_ENTRIES
import java.io.Reader

/**
 * SSE 事件解析结果。
 *
 * 新 wire 格式（`/event?directory=...` 端点）直出 `{id, type, properties}`，无外层包装。
 * - [type] 从 `parsedMap["type"]` 取，即 root 级别的事件类型。
 * - [parsedMap] 仅白名单事件有值；非白名单事件为 null（早退，无 Gson 调用）。
 */
data class ParsedSSEEvent(
    val eventType: String,
    val parsedMap: Map<*, *>? = null
) {
    /** 新 wire 格式 root 级别的 type 字段（如 "file.edited"、"session.diff"）。 */
    val type: String?
        get() = parsedMap?.get("type") as? String
}

object SSEEventParser {
    internal val dedupCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(LRU_MAX_ENTRIES)
        .build()

    /**
     * 5 个白名单事件:v2.0.0+ in-IDE 通知砍掉后,IDE 端仅消费:
     * - 1 Bash 事件(message.part.updated → BashCommandHandler)
     * - 3 文件刷新事件(session.diff 特殊 + file.edited/file.watcher.updated 主路径 → FullRefreshCoordinator)
     * - 1 健康信号(server.heartbeat → lastHeartbeatAt, 仅作诊断)
     * 其他事件 SSEEventParser 在 parse 阶段早退,不走 Gson 全量反序列化。
     */
    private val ALLOW_PARSE_EVENT_TYPES = setOf(
        "message.part.updated",
        "session.diff",
        "file.edited",
        "file.watcher.updated",
        "server.heartbeat",
    )

    fun isEventProcessed(eventID: String): Boolean {
        if (eventID.isEmpty()) return false
        if (dedupCache.getIfPresent(eventID) != null) return true
        dedupCache.put(eventID, true)
        return false
    }

    fun clearCache() {
        dedupCache.invalidateAll()
    }

    /**
     * 解析 SSE 事件。使用流式 Reader 读取 JSON，白名单外事件不走完整 Gson 反序列化。
     *
     * 新 wire 格式（`/event?directory=...` 端点）直出 `{id, type, properties}`，无外层包装。
     *
     * @param eventType SSE 事件类型（EventSource 框架传入的 event 字段）
     * @param reader JSON body 的 Reader（调用方负责 close）
     */
    fun parse(eventType: String, reader: Reader): ParsedSSEEvent {
        val logger = thisLogger()

        try {
            val jsonElement = JsonParser.parseReader(reader)
            if (jsonElement !is JsonObject) {
                return ParsedSSEEvent(eventType = eventType, parsedMap = null)
            }
            val root = jsonElement
            val type = root.get("type")?.asString

            if (type == null || type !in ALLOW_PARSE_EVENT_TYPES) {
                return ParsedSSEEvent(eventType = eventType, parsedMap = null)
            }

            return ParsedSSEEvent(eventType = eventType, parsedMap = extractFields(root))
        } catch (e: Exception) {
            logger.warn("[SSEEventParser] Failed to parse JSON: ${e.message}")
            return ParsedSSEEvent(eventType = eventType, parsedMap = null)
        }
    }

    private fun extractFields(root: JsonObject): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(root.size())
        for ((key, value) in root.entrySet()) {
            result[key] = unwrap(value)
        }
        return result
    }

    private fun unwrap(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonPrimitive -> {
            val p = element.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isString -> p.asString
                else -> p.asNumber
            }
        }
        element.isJsonArray -> element.asJsonArray.map(::unwrap)
        element.isJsonObject -> extractFields(element.asJsonObject)
        else -> null
    }

    /**
     * 解析 SSE 事件（String 重载）。
     * streamEventData(false) 模式下 messageEvent.data 是完整 JSON String，
     * 内部转 Reader 调用主 parse 方法。
     */
    fun parse(eventType: String, text: String): ParsedSSEEvent {
        return parse(eventType, text.byteInputStream(Charsets.UTF_8).bufferedReader())
    }
}
