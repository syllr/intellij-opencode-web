package com.shenyuanlaolarou.opencodewebui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit test for OpenCodeNotificationService.extractSessionID 的通知路径嵌套提取.
 *
 * 通知路径接收的 properties 是 parsedMap (整个 event {id, type, properties:{...}}),
 * 所以要走 properties["properties"]["sessionID"] 嵌套 2 层.
 * 这与 SSEEventParser.extractSessionID 路径不同 (那个从 parsedMap["properties"] 单层取).
 */
class NotificationServiceExtractSessionIdTest {

    @Test
    fun `extracts sessionID from nested properties`() {
        val parsedMap: Map<String, Any?> = mapOf(
            "id" to "evt-1",
            "type" to "session.idle",
            "properties" to mapOf(
                "sessionID" to "s-abc123"
            )
        )
        assertEquals("s-abc123", OpenCodeNotificationService.extractSessionID(parsedMap))
    }

    @Test
    fun `returns null when properties is null`() {
        assertNull(OpenCodeNotificationService.extractSessionID(null))
    }

    @Test
    fun `returns null when properties is empty`() {
        val empty = emptyMap<String, Any?>()
        assertNull(OpenCodeNotificationService.extractSessionID(empty))
    }

    @Test
    fun `returns null when nested properties missing sessionID`() {
        val parsedMap: Map<String, Any?> = mapOf(
            "id" to "evt-1",
            "type" to "session.idle",
            "properties" to mapOf(
                "otherField" to "value"
            )
        )
        assertNull(OpenCodeNotificationService.extractSessionID(parsedMap))
    }

    @Test
    fun `returns null when nested properties is not a map`() {
        val parsedMap: Map<String, Any?> = mapOf(
            "id" to "evt-1",
            "properties" to "not a map"  // wrong type
        )
        assertNull(OpenCodeNotificationService.extractSessionID(parsedMap))
    }

    @Test
    fun `returns null when sessionID is not a string`() {
        val parsedMap: Map<String, Any?> = mapOf(
            "id" to "evt-1",
            "properties" to mapOf(
                "sessionID" to 12345  // wrong type (Int instead of String)
            )
        )
        assertNull(OpenCodeNotificationService.extractSessionID(parsedMap))
    }

    @Test
    fun `handles deeply nested map structure`() {
        // 模拟 opencode server 返回的完整嵌套结构
        val parsedMap: Map<String, Any?> = mapOf(
            "id" to "evt-xyz",
            "type" to "permission.asked",
            "properties" to mapOf(
                "sessionID" to "s-xyz789",
                "info" to mapOf("role" to "user"),
                "otherStuff" to "value"
            )
        )
        assertEquals("s-xyz789", OpenCodeNotificationService.extractSessionID(parsedMap))
    }
}
