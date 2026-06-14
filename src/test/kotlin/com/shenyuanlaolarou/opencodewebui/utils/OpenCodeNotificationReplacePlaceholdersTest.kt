package com.shenyuanlaolarou.opencodewebui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for OpenCodeNotificationService.replacePlaceholders (P1 TDD).
 *
 * 锁住"单次遍历替换多个占位符"行为:
 * - 已知占位符 {key} → 用对应 value 替换
 * - 未知占位符保留字面
 * - 多占位符一次遍历完成
 */
class OpenCodeNotificationReplacePlaceholdersTest {

    @Test
    fun `plain string returns unchanged`() {
        assertEquals("hello", OpenCodeNotificationService.replacePlaceholders("hello", emptyMap()))
    }

    @Test
    fun `single known placeholder replaced`() {
        assertEquals("OpenCode (foo)",
            OpenCodeNotificationService.replacePlaceholders("OpenCode ({projectName})", mapOf("projectName" to "foo")))
    }

    @Test
    fun `unknown placeholder preserved literally`() {
        assertEquals("OpenCode ({unknown})",
            OpenCodeNotificationService.replacePlaceholders("OpenCode ({unknown})", mapOf("projectName" to "foo")))
    }

    @Test
    fun `multiple placeholders replaced in single pass`() {
        val result = OpenCodeNotificationService.replacePlaceholders(
            "[{sessionTitle}] {timestamp}",
            mapOf("sessionTitle" to "S", "timestamp" to "T")
        )
        assertEquals("[S] T", result)
    }

    @Test
    fun `null value for known key keeps placeholder literal`() {
        assertEquals("[{projectName}]",
            OpenCodeNotificationService.replacePlaceholders("[{projectName}]", mapOf<String, String?>("projectName" to null)))
    }

    @Test
    fun `unclosed brace preserved`() {
        assertEquals("a {b",
            OpenCodeNotificationService.replacePlaceholders("a {b", mapOf("b" to "X")))
    }

    @Test
    fun `empty brace pair preserved`() {
        assertEquals("a {} b",
            OpenCodeNotificationService.replacePlaceholders("a {} b", mapOf("" to "X")))
    }

    @Test
    fun `placeholder at end of string`() {
        assertEquals("end y", OpenCodeNotificationService.replacePlaceholders("end {x}", mapOf("x" to "y")))
    }
}
