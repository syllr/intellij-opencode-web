package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractSessionIdTest {

    private fun parsedSSEEvent(
        parsedMap: Map<*, *>? = null,
        syncEventData: Map<*, *>? = null,
        syncEventType: String? = null,
        syncEvent: Map<*, *>? = null
    ): ParsedSSEEvent {
        return ParsedSSEEvent(
            eventType = "test",
            directory = null,
            file = null,
            payloadType = if (syncEventType != null || syncEventData != null || syncEvent != null) "sync" else null,
            syncEventType = syncEventType,
            syncEventData = syncEventData,
            syncEvent = syncEvent,
            parsedMap = parsedMap
        )
    }

    @Test
    fun extractSessionID_propsFirst() {
        val event = parsedSSEEvent(
            parsedMap = mapOf(
                "payload" to mapOf(
                    "properties" to mapOf("sessionID" to "sess-props")
                )
            )
        )
        assertEquals("sess-props", event.extractSessionID())
    }

    @Test
    fun extractSessionID_dataSessionID() {
        val event = parsedSSEEvent(
            syncEventData = mapOf("sessionID" to "sess-data")
        )
        assertEquals("sess-data", event.extractSessionID())
    }

    @Test
    fun extractSessionID_dataId() {
        val event = parsedSSEEvent(
            syncEventData = mapOf("id" to "sess-id")
        )
        assertEquals("sess-id", event.extractSessionID())
    }

    @Test
    fun extractSessionID_infoSessionID() {
        val event = parsedSSEEvent(
            syncEventData = mapOf(
                "info" to mapOf("sessionID" to "sess-info-sid")
            )
        )
        assertEquals("sess-info-sid", event.extractSessionID())
    }

    @Test
    fun extractSessionID_infoId() {
        val event = parsedSSEEvent(
            syncEventData = mapOf(
                "info" to mapOf("id" to "sess-info-id")
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
                "payload" to mapOf(
                    "properties" to mapOf("sessionID" to "props-wins")
                )
            ),
            syncEventData = mapOf(
                "sessionID" to "data-loses",
                "id" to "id-loses",
                "info" to mapOf(
                    "sessionID" to "info-sid-loses",
                    "id" to "info-id-loses"
                )
            )
        )
        assertEquals("props-wins", event.extractSessionID())
    }

    @Test
    fun extractParentID_threeLevelFallback() {
        val event1 = parsedSSEEvent(
            parsedMap = mapOf(
                "payload" to mapOf(
                    "properties" to mapOf("parentID" to "parent-props")
                )
            )
        )
        assertEquals("parent-props", event1.extractParentID())

        val event2 = parsedSSEEvent(
            syncEventData = mapOf("parentID" to "parent-data")
        )
        assertEquals("parent-data", event2.extractParentID())

        val event3 = parsedSSEEvent(
            syncEventData = mapOf(
                "info" to mapOf("parentID" to "parent-info")
            )
        )
        assertEquals("parent-info", event3.extractParentID())
    }

    @Test
    fun extractParentID_nullForTopSession() {
        val event = parsedSSEEvent(
            syncEventData = mapOf("id" to "sess-top")
        )
        assertNull(event.extractParentID())
    }

    @Test
    fun parseSyncEvent_stripsVersionSuffix() {
        val event = SSEEventParser.parse(
            "message",
            """{"directory":"/p","payload":{"type":"sync","syncEvent":{"type":"session.idle.0","data":{"id":"sess1"}}}}"""
        )
        assertEquals("session.idle", event.syncEventType)
        assertNotNull(event.syncEventData)
        assertEquals("sess1", event.syncEventData!!["id"])
    }

    @Test
    fun extractSessionID_aggregateID() {
        val event = parsedSSEEvent(
            syncEvent = mapOf("aggregateID" to "ses-aggregate"),
            syncEventData = mapOf("info" to mapOf("parentID" to "ses-parent"))
        )
        assertEquals("ses-aggregate", event.extractSessionID())
    }

    @Test
    fun extractSessionID_aggregateID_lowestPriority() {
        val event = parsedSSEEvent(
            parsedMap = mapOf("payload" to mapOf("properties" to mapOf("sessionID" to "ses-props"))),
            syncEvent = mapOf("aggregateID" to "ses-aggregate-lowest"),
            syncEventData = mapOf("sessionID" to "ses-data-second")
        )
        assertEquals("ses-props", event.extractSessionID())
    }

    @Test
    fun parseSessionCreated_preservesAggregateID() {
        val event = SSEEventParser.parse(
            "message",
            """{"directory":"/p","payload":{"type":"sync","syncEvent":{"type":"session.created.1","aggregateID":"ses-subagent-123","data":{"info":{"parentID":"ses-parent-456"}}}}}"""
        )
        assertEquals("session.created", event.syncEventType)
        assertEquals("ses-subagent-123", event.extractSessionID())
        assertEquals("ses-parent-456", event.extractParentID())
        assertNotNull(event.syncEvent)
        assertEquals("ses-subagent-123", event.syncEvent!!["aggregateID"])
    }
}
