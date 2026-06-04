package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SSEEventParserTest {

    @After
    fun cleanup() {
        SSEEventParser.clearCache()
    }

    @Test
    fun nonWhitelistedEventReturnsNullParsedMap() {
        // message.part.delta 不在白名单内 → parsedMap 为 null
        val event = """{"id":"evt1","type":"message.part.delta","properties":{}}"""
        val parsed = SSEEventParser.parse("message", event.reader())
        assertEquals("message", parsed.eventType)
        assertNull(parsed.parsedMap)
        assertNull(parsed.type)
    }

    @Test
    fun fileEditedEvent() {
        val event = """{"id":"e1","type":"file.edited","properties":{"file":"/p/Foo.kt"}}"""
        val parsed = SSEEventParser.parse("message", event.reader())
        assertEquals("file.edited", parsed.type)
        assertNotNull(parsed.parsedMap)
        val props = parsed.parsedMap!!["properties"] as? Map<*, *>
        assertEquals("/p/Foo.kt", props?.get("file"))
    }

    @Test
    fun fileWatcherUpdatedEvent() {
        val event = """{"id":"e2","type":"file.watcher.updated","properties":{"file":"/p/Bar.kt"}}"""
        val parsed = SSEEventParser.parse("message", event.reader())
        assertEquals("file.watcher.updated", parsed.type)
    }

    @Test
    fun sessionDiffEvent() {
        val event = """{"id":"e3","type":"session.diff","properties":{"file":"/p/Diff.kt"}}"""
        val parsed = SSEEventParser.parse("message", event.reader())
        assertEquals("session.diff", parsed.type)
        assertNotNull(parsed.parsedMap)
    }

    @Test
    fun sessionIdleEvent() {
        val event = """{"id":"evt-4","type":"session.idle","properties":{"sessionID":"sess1"}}"""
        val parsed = SSEEventParser.parse("message", event.reader())
        assertEquals("session.idle", parsed.type)
        assertNotNull(parsed.parsedMap)
        assertEquals("sess1", parsed.extractSessionID())
    }

    @Test
    fun malformedJsonReturnsDefaults() {
        val parsed = SSEEventParser.parse("message", "not json at all".reader())
        assertEquals("message", parsed.eventType)
        assertNull(parsed.parsedMap)
        assertNull(parsed.type)
    }

    @Test
    fun emptyJsonObject() {
        val parsed = SSEEventParser.parse("message", "{}".reader())
        assertEquals("message", parsed.eventType)
        assertNull(parsed.parsedMap)
        assertNull(parsed.type)
    }

    @Test
    fun nonObjectJsonArrayRoot() {
        val parsed = SSEEventParser.parse("message", "[1,2,3]".reader())
        assertEquals("message", parsed.eventType)
        assertNull(parsed.parsedMap)
    }

    @Test
    fun dedupCacheFirstTimeFalse() {
        assertEquals(false, SSEEventParser.isEventProcessed("evt-A"))
    }

    @Test
    fun dedupCacheSecondTimeTrue() {
        assertEquals(false, SSEEventParser.isEventProcessed("evt-B"))
        assertEquals(true, SSEEventParser.isEventProcessed("evt-B"))
    }

    @Test
    fun dedupCacheEmptyIdAlwaysFalse() {
        assertEquals(false, SSEEventParser.isEventProcessed(""))
        assertEquals(false, SSEEventParser.isEventProcessed(""))
    }

    @Test
    fun clearCacheAllowsReprocessing() {
        assertEquals(false, SSEEventParser.isEventProcessed("evt-C"))
        assertEquals(true, SSEEventParser.isEventProcessed("evt-C"))
        SSEEventParser.clearCache()
        assertEquals(false, SSEEventParser.isEventProcessed("evt-C"))
    }
}
