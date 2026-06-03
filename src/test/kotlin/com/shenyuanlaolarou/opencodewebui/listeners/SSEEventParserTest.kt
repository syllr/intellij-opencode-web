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
    fun highFrequencyMessagePartDelta() {
        val event = """{"directory":"/p","payload":{"type":"message.part.delta","id":"evt1"}}"""
        val parsed = SSEEventParser.parse("message", event)
        assertEquals("message.part.delta", parsed.payloadType)
        assertEquals("/p", parsed.directory)
        // 高频事件走快速路径,不构建 Map
        assertNull(parsed.file)
    }

    @Test
    fun fileEditedEvent() {
        val event = """{"directory":"/p","payload":{"type":"file.edited","properties":{"file":"/p/Foo.kt","id":"e1"}}}"""
        val parsed = SSEEventParser.parse("message", event)
        assertEquals("file.edited", parsed.payloadType)
        assertEquals("/p/Foo.kt", parsed.file)
    }

    @Test
    fun fileWatcherUpdatedUsesFilePathFallback() {
        val event = """{"directory":"/p","payload":{"type":"file.watcher.updated","properties":{"filePath":"/p/Bar.kt"}}}"""
        val parsed = SSEEventParser.parse("message", event)
        assertEquals("file.watcher.updated", parsed.payloadType)
        assertEquals("/p/Bar.kt", parsed.file)
    }

    @Test
    fun sessionDiffEvent() {
        val event = """{"directory":"/p","payload":{"type":"session.diff","properties":{"file":"/p/Diff.kt"}}}"""
        val parsed = SSEEventParser.parse("message", event)
        assertEquals("session.diff", parsed.payloadType)
        assertEquals("/p/Diff.kt", parsed.file)
        assertEquals("/p", parsed.directory)
    }

    @Test
    fun syncEventStripsVersionSuffix() {
        val event = """{"directory":"/p","payload":{"type":"sync","syncEvent":{"type":"session.idle.0","data":{"id":"sess1"}}}}"""
        val parsed = SSEEventParser.parse("message", event)
        assertEquals("sync", parsed.payloadType)
        assertEquals("session.idle", parsed.syncEventType)
        assertNotNull(parsed.syncEventData)
        assertEquals("sess1", parsed.syncEventData!!["id"])
    }

    @Test
    fun malformedJsonReturnsDefaults() {
        val parsed = SSEEventParser.parse("message", "not json at all")
        assertEquals("message", parsed.eventType)
        assertNull(parsed.directory)
        assertNull(parsed.payloadType)
        assertNull(parsed.file)
    }

    @Test
    fun emptyJsonObject() {
        val parsed = SSEEventParser.parse("message", "{}")
        assertEquals("message", parsed.eventType)
        assertNull(parsed.directory)
        assertNull(parsed.payloadType)
    }

    @Test
    fun nonObjectJsonArrayRoot() {
        val parsed = SSEEventParser.parse("message", "[1,2,3]")
        assertEquals("message", parsed.eventType)
        assertNull(parsed.directory)
        assertNull(parsed.payloadType)
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
