package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractSessionIdTest {

    /**
     * 构造 ParsedSSEEvent 辅助方法（新 wire 格式：root = {id, type, properties}）。
     * parsedMap 直接是 root 对象，properties 在 parsedMap["properties"] 内。
     */
    private fun parsedSSEEvent(
        parsedMap: Map<*, *>? = null
    ): ParsedSSEEvent {
        return ParsedSSEEvent(
            eventType = "test",
            parsedMap = parsedMap
        )
    }

    @Test
    fun extractSessionID_propsFirst() {
        val event = parsedSSEEvent(
            parsedMap = mapOf(
                "type" to "session.idle",
                "properties" to mapOf("sessionID" to "sess-props")
            )
        )
        assertEquals("sess-props", event.extractSessionID())
    }

    @Test
    fun extractSessionID_infoSessionID() {
        val event = parsedSSEEvent(
            parsedMap = mapOf(
                "type" to "session.idle",
                "properties" to mapOf(
                    "info" to mapOf("sessionID" to "sess-info-sid")
                )
            )
        )
        assertEquals("sess-info-sid", event.extractSessionID())
    }

    @Test
    fun extractSessionID_infoId() {
        val event = parsedSSEEvent(
            parsedMap = mapOf(
                "type" to "session.idle",
                "properties" to mapOf(
                    "info" to mapOf("id" to "sess-info-id")
                )
            )
        )
        assertEquals("sess-info-id", event.extractSessionID())
    }

    @Test
    fun extractSessionID_allMissing() {
        val event = parsedSSEEvent()
        assertNull(event.extractSessionID())
    }

    @Test
    fun extractSessionID_priorityOrder() {
        val event = parsedSSEEvent(
            parsedMap = mapOf(
                "type" to "session.idle",
                "properties" to mapOf(
                    "sessionID" to "props-wins",
                    "info" to mapOf(
                        "sessionID" to "info-sid-loses",
                        "id" to "info-id-loses"
                    )
                )
            )
        )
        assertEquals("props-wins", event.extractSessionID())
    }

    @Test
    fun extractParentID_twoLevelFallback() {
        val event1 = parsedSSEEvent(
            parsedMap = mapOf(
                "type" to "session.created",
                "properties" to mapOf("parentID" to "parent-props")
            )
        )
        assertEquals("parent-props", event1.extractParentID())

        val event2 = parsedSSEEvent(
            parsedMap = mapOf(
                "type" to "session.created",
                "properties" to mapOf(
                    "info" to mapOf("parentID" to "parent-info")
                )
            )
        )
        assertEquals("parent-info", event2.extractParentID())
    }

    @Test
    fun extractParentID_nullForTopSession() {
        val event = parsedSSEEvent(
            parsedMap = mapOf(
                "type" to "session.created",
                "properties" to mapOf("id" to "sess-top")
            )
        )
        assertNull(event.extractParentID())
    }

    @Test
    fun type_fromParsedMap() {
        val event = parsedSSEEvent(
            parsedMap = mapOf("type" to "session.idle", "id" to "evt-1")
        )
        assertEquals("session.idle", event.type)
    }

    @Test
    fun type_nullWhenNoParsedMap() {
        val event = parsedSSEEvent()
        assertNull(event.type)
    }

    @Test
    fun parse_whitelistedEvent_returnsParsedMap() {
        val json = """{"id":"evt-1","type":"session.idle","properties":{"sessionID":"sess-1"}}"""
        val event = SSEEventParser.parse("message", json.reader())
        assertNotNull(event.parsedMap)
        assertEquals("session.idle", event.type)
        assertEquals("sess-1", event.extractSessionID())
    }

    @Test
    fun parse_nonWhitelistedEvent_returnsNullParsedMap() {
        val json = """{"id":"evt-2","type":"message.part.delta","properties":{}}"""
        val event = SSEEventParser.parse("message", json.reader())
        assertNull(event.parsedMap)
        assertNull(event.type)
    }

    @Test
    fun parse_sessionCreated_extractsParentID() {
        val json = """{"id":"evt-3","type":"session.created","properties":{"sessionID":"ses-subagent-123","parentID":"ses-parent-456"}}"""
        val event = SSEEventParser.parse("message", json.reader())
        assertEquals("ses-subagent-123", event.extractSessionID())
        assertEquals("ses-parent-456", event.extractParentID())
    }

    @Test
    fun parse_invalidJson_returnsNullParsedMap() {
        val event = SSEEventParser.parse("message", "not-json".reader())
        assertNull(event.parsedMap)
    }
}
