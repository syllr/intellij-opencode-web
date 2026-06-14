package com.shenyuanlaolarou.opencodewebui.listeners

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import java.io.StringReader

/**
 * 验证 SSE 解析只 parse 一次 (P0 性能修复 TDD).
 *
 * 当前实现问题:parse() 内 `JsonParser.parseReader(reader)` 后又 `gson.fromJson(root, Map::class.java)`,
 * 同一 JSON 被解析两遍——高频 SSE 事件下累积开销.
 *
 * 修复方向:第一次 parse 拿到的 JsonObject 直接用 entrySet 提取,不再二次 parse.
 *
 * 包含两类测试:
 * 1. 性能结构 (P0) — 验证白名单事件 parse 时 gson.fromJson(JsonElement, Map) **不被调用**
 * 2. Characterization — 验证白名单事件返回 Map 内容正确(锁定行为,改实现后必须仍绿)
 */
class SSEEventParserParseOnceTest {

    @After
    fun cleanup() {
        SSEEventParser.clearCache()
    }

    // ─── 性能结构测试 (P0) ─────────────────────────────────────────────

    @Test
    fun `whitelisted event parse should not call gson_fromJson_on_JsonElement_Map`() {
        // 用 spy 替换内部 gson,监控 fromJson(JsonElement, Class) 类型的调用
        val realGson = SSEEventParser.gson
        val spyGson = spy(realGson)
        SSEEventParser.gson = spyGson
        try {
            val event = """{"id":"e1","type":"file.edited","properties":{"file":"/p/Foo.kt"}}"""
            val parsed = SSEEventParser.parse("message", StringReader(event))

            // 白名单事件必须正确返回 parsedMap
            assertNotNull("白名单事件应有 parsedMap", parsed.parsedMap)
            assertEquals("file.edited", parsed.type)

            // 关键断言:gson.fromJson(JsonElement, Class) **不应被调用**
            // 当前实现会调 1 次(JsonObject → Map),改实现后应为 0 次
            verify(spyGson, Mockito.never()).fromJson(any<JsonElement>(), eq(Map::class.java))
        } finally {
            SSEEventParser.gson = realGson
        }
    }

    @Test
    fun `non-whitelisted event parse should not call gson_fromJson_at_all`() {
        val realGson = SSEEventParser.gson
        val spyGson = spy(realGson)
        SSEEventParser.gson = spyGson
        try {
            val event = """{"id":"e1","type":"message.part.delta","properties":{}}"""
            val parsed = SSEEventParser.parse("message", StringReader(event))

            // 非白名单 → parsedMap 为 null
            assertNull(parsed.parsedMap)

            // 非白名单早退,**任何 fromJson 都不该被调用**
            verify(spyGson, Mockito.never()).fromJson(any<JsonElement>(), eq(Map::class.java))
        } finally {
            SSEEventParser.gson = realGson
        }
    }

    // ─── Characterization 测试 (锁定行为) ──────────────────────────────

    @Test
    fun `whitelisted event with nested object preserves full structure`() {
        val event = """
            {"id":"e-deep","type":"session.idle",
             "properties":{"sessionID":"sess1","info":{"id":"i1","sessionID":"nested-sess","title":"Subagent"}}}
        """.trimIndent()
        val parsed = SSEEventParser.parse("message", StringReader(event))
        assertNotNull(parsed.parsedMap)
        assertEquals("session.idle", parsed.type)
        // extractSessionID() 三级 fallback: properties.sessionID 优先
        assertEquals("sess1", parsed.extractSessionID())
        // extractParentID() 两级 fallback
        val info = (parsed.parsedMap!!["properties"] as Map<*, *>)["info"] as Map<*, *>
        assertEquals("Subagent", info["title"])
    }

    @Test
    fun `whitelisted event with array field preserves array type`() {
        val event = """{"id":"e-arr","type":"session.created","properties":{"tags":["a","b","c"]}}"""
        val parsed = SSEEventParser.parse("message", StringReader(event))
        assertNotNull(parsed.parsedMap)
        val props = parsed.parsedMap!!["properties"] as Map<*, *>
        val tags = props["tags"]
        assertTrue("数组字段应保留为 List", tags is List<*>)
        assertEquals(listOf("a", "b", "c"), tags)
    }

    @Test
    fun `whitelisted event with null field preserves null value`() {
        val event = """{"id":"e-null","type":"session.idle","properties":{"sessionID":null,"info":null}}"""
        val parsed = SSEEventParser.parse("message", StringReader(event))
        assertNotNull(parsed.parsedMap)
        val props = parsed.parsedMap!!["properties"] as Map<*, *>
        assertTrue("null 字段应保留为 null", props.containsKey("sessionID"))
        assertNull(props["sessionID"])
        assertNull(props["info"])
    }

    @Test
    fun `whitelisted event with number and boolean fields preserves types`() {
        val event = """
            {"id":"e-types","type":"session.updated",
             "properties":{"count":42,"ratio":3.14,"active":true,"disabled":false}}
        """.trimIndent()
        val parsed = SSEEventParser.parse("message", StringReader(event))
        assertNotNull(parsed.parsedMap)
        val props = parsed.parsedMap!!["properties"] as Map<*, *>
        assertTrue(props["count"] is Number)
        assertEquals(42L, (props["count"] as Number).toLong())
        assertEquals(3.14, (props["ratio"] as Number).toDouble(), 0.0001)
        assertTrue(props["active"] as Boolean)
        assertFalse(props["disabled"] as Boolean)
    }

    @Test
    fun `all 12 whitelisted types are returned with non-null parsedMap`() {
        val whitelisted = listOf(
            "session.idle", "session.status", "permission.asked", "question.asked",
            "session.created", "session.updated", "message.updated", "message.part.updated",
            "file.edited", "file.watcher.updated", "session.diff", "server.heartbeat"
        )
        for (type in whitelisted) {
            val event = """{"id":"e-$type","type":"$type","properties":{}}"""
            val parsed = SSEEventParser.parse("message", StringReader(event))
            assertEquals(type, parsed.type)
            assertNotNull("$type 应有 parsedMap", parsed.parsedMap)
        }
    }
}
