package com.github.xausky.opencodewebui.actions

import org.junit.Assert.assertTrue
import org.junit.Test

class FormatAsPromptTest {

    @Test
    fun testFormat() {
        val result = formatAsPrompt("/test.kt", 10, 15, "fun hello()")
        assertTrue(result.startsWith("location:/test.kt:10-15"))
        assertTrue(result.contains("content:"))
        assertTrue(result.contains("```"))
        assertTrue(result.contains("fun hello()"))
    }

    @Test
    fun testNewlineInText() {
        val text = "line1\nline2"
        val result = formatAsPrompt("/test.kt", 1, 2, text)
        // 验证 \n 是真正的换行符，不是转义
        assertTrue(result.contains("line1"))
        assertTrue(result.contains("line2"))
        assertTrue(result.count { it == '\n' } >= 2)
    }

    @Test
    fun testBackslash() {
        val text = "path\\to\\file"
        val result = formatAsPrompt("/test.kt", 1, 1, text)
        assertTrue(result.contains("path\\to\\file"))
    }

    @Test
    fun testQuote() {
        val text = "println(\"hello\")"
        val result = formatAsPrompt("/test.kt", 1, 1, text)
        assertTrue(result.contains("\"hello\""))
    }
}
