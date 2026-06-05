package com.shenyuanlaolarou.opencodewebui.listeners

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import java.io.Reader
import java.util.Collections
import java.util.LinkedHashMap

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
    /** 新 wire 格式 root 级别的 type 字段（如 "session.idle"、"file.edited"）。 */
    val type: String?
        get() = parsedMap?.get("type") as? String
}

/**
 * 从 ParsedSSEEvent 提取 sessionID，使用三级 fallback 适配新 wire 格式。
 *
 * 新 wire 格式：`{id, type, properties: {sessionID?, info?: {sessionID?, id?}}}`
 *
 * 提取优先级（高到低）:
 *  1. `properties.sessionID` —— 直接属性
 *  2. `properties.info.sessionID` —— 嵌套在 info 内
 *  3. `properties.info.id` —— 部分事件用 id 而非 sessionID
 */
fun ParsedSSEEvent.extractSessionID(): String? {
    val props = parsedMap?.get("properties") as? Map<*, *>
    val info = props?.get("info") as? Map<*, *>
    return props?.get("sessionID") as? String
        ?: info?.get("sessionID") as? String
        ?: info?.get("id") as? String
}

/**
 * 从 ParsedSSEEvent 提取 parentID（两级 fallback）。
 * 返回 `null` 表示该 session 不是 subagent（顶层 session 没有 parentID）。
 */
fun ParsedSSEEvent.extractParentID(): String? {
    val props = parsedMap?.get("properties") as? Map<*, *>
    val info = props?.get("info") as? Map<*, *>
    return props?.get("parentID") as? String
        ?: info?.get("parentID") as? String
}

fun ParsedSSEEvent.extractTitle(): String? {
    val info = (parsedMap?.get("properties") as? Map<*, *>)?.get("info") as? Map<*, *>
    return info?.get("title") as? String
}

object SSEEventParser {
    private val gson = Gson()

    // LRU 去重缓存：按 event id 去重，最大 1000 条
    private val dedupCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(1000, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
                return size > 1000
            }
        }
    )

    /** 9 个白名单事件类型：只有这些事件会被完整 Gson 解析，其他直接 close Reader 早退。 */
    private val ALLOW_PARSE_EVENT_TYPES = setOf(
        // 4 通知
        "session.idle",
        "session.status",
        "permission.asked",
        "question.asked",
        // 5 业务
        "session.created",
        "message.part.updated",
        "file.edited",
        "file.watcher.updated",
        "session.diff",
    )

    fun isEventProcessed(eventID: String): Boolean {
        if (eventID.isEmpty()) return false
        return dedupCache.put(eventID, true) != null
    }

    fun clearCache() {
        dedupCache.clear()
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

            // 白名单过滤：不在白名单内 → 不完整解析，节省 80% 临时对象
            if (type == null || type !in ALLOW_PARSE_EVENT_TYPES) {
                return ParsedSSEEvent(eventType = eventType, parsedMap = null)
            }

            // 在白名单内：完整 Gson 解析
            val parsedMap: Map<*, *> = gson.fromJson(root, Map::class.java)
            return ParsedSSEEvent(eventType = eventType, parsedMap = parsedMap)
        } catch (e: Exception) {
            logger.warn("[SSEEventParser] Failed to parse JSON: ${e.message}")
            return ParsedSSEEvent(eventType = eventType, parsedMap = null)
        }
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
