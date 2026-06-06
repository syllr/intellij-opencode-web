package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit test for SSEEventParser.ALLOW_PARSE_EVENT_TYPES 9 个白名单 type 完整性.
 *
 * 与 SSEEventParserTest 互补 — 那个只测了 5 个 type, 这里补全剩下 4 个:
 * session.status, permission.asked, question.asked, session.created.
 * 还测非白名单 type + 缺 type 字段的场景.
 *
 * 通过 reader 构造测试数据, 直接调 SSEEventParser.parse(eventType, reader).
 */
class AllowParseEventTypesTest {

    private fun wireEvent(type: String, properties: Map<String, Any?> = emptyMap()): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":\"evt-test\",")
        sb.append("\"type\":${quote(type)},")
        if (properties.isNotEmpty()) {
            sb.append("\"properties\":")
            sb.append(toJson(properties))
        }
        sb.append("}")
        return sb.toString()
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun toJson(map: Map<String, Any?>): String {
        val parts = map.entries.joinToString(",") { (k, v) ->
            "\"$k\":" + jsonValue(v)
        }
        return "{$parts}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Number -> v.toString()
        is Map<*, *> -> toJson((v as Map<String, Any?>))
        else -> quote(v.toString())
    }

    private fun parse(type: String, properties: Map<String, Any?> = emptyMap()): ParsedSSEEvent {
        val wire = wireEvent(type, properties)
        return wire.byteInputStream().reader().use { reader ->
            SSEEventParser.parse(type, reader)
        }
    }

    // === 9 白名单 type 逐一验证 ===

    @Test
    fun `session_idle is whitelisted`() {
        val r = parse("session.idle", mapOf("sessionID" to "s-1"))
        assertNotNull(r.parsedMap)
        assertEquals("session.idle", r.type)
    }

    @Test
    fun `session_status is whitelisted`() {
        val r = parse("session.status", mapOf("status" to mapOf("type" to "idle")))
        assertNotNull(r.parsedMap)
        assertEquals("session.status", r.type)
    }

    @Test
    fun `permission_asked is whitelisted`() {
        val r = parse("permission.asked", mapOf("sessionID" to "s-1"))
        assertNotNull(r.parsedMap)
        assertEquals("permission.asked", r.type)
    }

    @Test
    fun `question_asked is whitelisted`() {
        val r = parse("question.asked", mapOf("sessionID" to "s-1"))
        assertNotNull(r.parsedMap)
        assertEquals("question.asked", r.type)
    }

    @Test
    fun `session_created is whitelisted`() {
        val r = parse("session.created", mapOf("info" to mapOf("id" to "ses-1")))
        assertNotNull(r.parsedMap)
        assertEquals("session.created", r.type)
    }

    @Test
    fun `message_part_updated is whitelisted`() {
        val r = parse("message.part.updated", mapOf(
            "part" to mapOf("type" to "tool", "tool" to "bash", "state" to mapOf("status" to "completed"))
        ))
        assertNotNull(r.parsedMap)
        assertEquals("message.part.updated", r.type)
    }

    @Test
    fun `file_edited is whitelisted`() {
        val r = parse("file.edited", mapOf("file" to "/tmp/test.kt"))
        assertNotNull(r.parsedMap)
        assertEquals("file.edited", r.type)
    }

    @Test
    fun `file_watcher_updated is whitelisted`() {
        val r = parse("file.watcher.updated", mapOf("file" to "/tmp/test.kt", "event" to "change"))
        assertNotNull(r.parsedMap)
        assertEquals("file.watcher.updated", r.type)
    }

    @Test
    fun `session_diff is whitelisted`() {
        val r = parse("session.diff", mapOf("sessionID" to "s-1", "diff" to emptyList<Any>()))
        assertNotNull(r.parsedMap)
        assertEquals("session.diff", r.type)
    }

    // === 非白名单 type 早退 ===

    @Test
    fun `message_part_delta is not whitelisted (skipped at reader)`() {
        val r = parse("message.part.delta", mapOf("sessionID" to "s-1"))
        // 非白名单 → parse() 返回 parsedMap=null (100Hz 高频事件被早退)
        assertNull(r.parsedMap)
    }

    @Test
    fun `server_heartbeat is whitelisted (HealthMonitor 健康信号,替代 EDT HTTP 探活)`() {
        val r = parse("server.heartbeat", mapOf("ts" to 12345))
        assertNotNull(r.parsedMap)
        assertEquals("server.heartbeat", r.type)
    }

    @Test
    fun `session_updated is whitelisted (cached for subagent title check)`() {
        val r = parse("session.updated", mapOf("sessionID" to "s-1", "info" to mapOf("title" to "t")))
        assertNotNull(r.parsedMap)
        assertEquals("session.updated", r.type)
    }

    @Test
    fun `message_updated is whitelisted (resets idle suppression on user msg)`() {
        val r = parse("message.updated", mapOf("sessionID" to "s-1", "info" to mapOf("role" to "user")))
        assertNotNull(r.parsedMap)
        assertEquals("message.updated", r.type)
    }

    @Test
    fun `session_error is not whitelisted (砍 case 后无通知)`() {
        val r = parse("session.error", mapOf("error" to mapOf("name" to "MessageAbortedError")))
        assertNull(r.parsedMap)
    }

    @Test
    fun `session_next_tool_called is not whitelisted (砍 case 后无通知)`() {
        val r = parse("session.next.tool.called", mapOf("tool" to "question"))
        assertNull(r.parsedMap)
    }

    // === 缺 type 字段 ===

    @Test
    fun `missing type field returns parsedMap null`() {
        val wire = "{\"id\":\"evt-1\",\"properties\":{\"sessionID\":\"s-1\"}}"
        val r = wire.byteInputStream().reader().use { reader ->
            SSEEventParser.parse("unknown_type", reader)
        }
        assertNull(r.parsedMap)
    }

    @Test
    fun `empty type string returns parsedMap null`() {
        val wire = "{\"id\":\"evt-1\",\"type\":\"\",\"properties\":{}}"
        val r = wire.byteInputStream().reader().use { reader ->
            SSEEventParser.parse("session.idle", reader)
        }
        // type 为空串 → 不在 ALLOW_PARSE_EVENT_TYPES 内
        assertNull(r.parsedMap)
    }
}
