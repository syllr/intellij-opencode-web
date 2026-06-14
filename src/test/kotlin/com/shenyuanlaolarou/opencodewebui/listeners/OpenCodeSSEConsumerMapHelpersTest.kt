package com.shenyuanlaolarou.opencodewebui.listeners

import com.shenyuanlaolarou.opencodewebui.utils.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for SSE event Map<*, *> cast helpers (P1 TDD).
 *
 * 抽 propertiesAsMap / infoAsMap / getAsMap 顶层 helpers,
 * 替代 onMessage 内多处 inline `as? Map<*, *>` cast,锁住 helper 行为。
 */
class OpenCodeSSEConsumerMapHelpersTest {

    @Test
    fun `propertiesAsMap returns null for null parsedMap`() {
        val parsed = ParsedSSEEvent(eventType = "x", parsedMap = null)
        assertNull(parsed.propertiesAsMap())
    }

    @Test
    fun `propertiesAsMap returns inner map`() {
        val inner = mapOf<String, Any?>("sessionID" to "s-1")
        val parsed = ParsedSSEEvent(eventType = "x", parsedMap = mapOf("properties" to inner))
        assertEquals(inner, parsed.propertiesAsMap())
    }

    @Test
    fun `propertiesAsMap returns null when properties is missing`() {
        val parsed = ParsedSSEEvent(eventType = "x", parsedMap = mapOf<String, Any?>("id" to "e1"))
        assertNull(parsed.propertiesAsMap())
    }

    @Test
    fun `propertiesAsMap returns null when properties is wrong type`() {
        val parsed = ParsedSSEEvent(eventType = "x", parsedMap = mapOf<String, Any?>("properties" to "not-a-map"))
        assertNull(parsed.propertiesAsMap())
    }

    @Test
    fun `getAsMap returns nested map`() {
        val info = mapOf<String, Any?>("sessionID" to "s-2")
        val props = mapOf<String, Any?>("info" to info)
        assertEquals(info, props.getAsMap("info"))
    }

    @Test
    fun `getAsMap returns null for missing key`() {
        val props = mapOf<String, Any?>("a" to 1)
        assertNull(props.getAsMap("missing"))
    }

    @Test
    fun `getAsMap returns null for wrong type`() {
        val props = mapOf<String, Any?>("info" to "not-a-map")
        assertNull(props.getAsMap("info"))
    }
}
