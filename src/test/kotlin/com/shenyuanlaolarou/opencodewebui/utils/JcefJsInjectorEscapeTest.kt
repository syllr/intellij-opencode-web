package com.shenyuanlaolarou.opencodewebui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 JcefJsInjector 中 JS 字符串构造为可测纯函数 (P1 性能优化 TDD).
 *
 * 原 executeAppend 内部:
 * - 5 次链式 .replace() 转义 (单次调用创建 5 个临时 String)
 * - 完整 JS 模板用 """...""".trimIndent() 每次调用重新构造
 *
 * 修复方向:抽成 internal 纯函数 + StringBuilder 单次遍历 escape + JS 模板拆 const val。
 */
class JcefJsInjectorEscapeTest {

    // ─── escapeForJsTemplate (TDD #2+#3 P1) ───────────────────────────

    @Test
    fun `escape plain text returns unchanged`() {
        assertEquals("hello", escapeForJsTemplate("hello"))
    }

    @Test
    fun `escape backslash doubles it`() {
        assertEquals("he\\\\\\\\lo", escapeForJsTemplate("he\\\\lo"))
    }

    @Test
    fun `escape single quote is escaped with backslash`() {
        assertEquals("it\\'s", escapeForJsTemplate("it's"))
    }

    @Test
    fun `escape backtick is escaped with backslash`() {
        assertEquals("\\`tick\\`", escapeForJsTemplate("`tick`"))
    }

    @Test
    fun `escape newline becomes escaped n`() {
        assertEquals("line1\\nline2", escapeForJsTemplate("line1\nline2"))
    }

    @Test
    fun `escape carriage return is stripped (then newline may be escaped)`() {
        // "\r\n" 顺序:先处理 \r 去掉,再处理 \n 加 \n
        assertEquals("a\\nb", escapeForJsTemplate("a\r\nb"))
    }

    @Test
    fun `escape complex mixed input produces safe output`() {
        assertEquals("a\\\\b\\'c\\`d\\ne", escapeForJsTemplate("a\\b'c`d\ne"))
    }

    @Test
    fun `escape empty string returns empty`() {
        assertEquals("", escapeForJsTemplate(""))
    }

    @Test
    fun `escape does not modify forward slashes or double quotes`() {
        // 仅 5 个模式: \\, ', `, \n, \r
        assertEquals("path/to/file\"", escapeForJsTemplate("path/to/file\""))
    }

    // ─── buildAppendJs (TDD #2+#3 P1) ────────────────────────────────

    @Test
    fun `buildAppendJs contains the escaped text inside single-quoted var assignment`() {
        val js = buildAppendJs("hello")
        assertTrue("JS 必须包含 var text = 'hello';", js.contains("var text = 'hello';"))
    }

    @Test
    fun `buildAppendJs escapes the text before insertion`() {
        val js = buildAppendJs(escapeForJsTemplate("it's"))
        assertTrue("JS 必须转义单引号", js.contains("var text = 'it\\'s';"))
        assertFalse("JS 不应包含未转义单引号", js.contains("var text = 'it's';"))
    }

    @Test
    fun `buildAppendJs contains editor query selector`() {
        val js = buildAppendJs("x")
        assertTrue("JS 必须查询 [role=textbox][contenteditable=true]",
            js.contains("""[role="textbox"][contenteditable="true"]"""))
    }

    @Test
    fun `buildAppendJs dispatches input change keyup keydown events`() {
        val js = buildAppendJs("x")
        for (event in listOf("input", "change", "keyup", "keydown")) {
            assertTrue("JS 必须 dispatch $event event", js.contains("'$event'"))
        }
    }
}
