package com.shenyuanlaolarou.opencodewebui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit test for OpenCodeApiResult sealed class 扩展函数 (P2-10).
 *
 * 测 dataOrNull() 在 4 种变体下的行为.
 * 注: Success<T> 是泛型,T 可空 (Success<String?>) 时 dataOrNull() 返回 null.
 */
class OpenCodeApiResultTest {

    @Test
    fun `Success dataOrNull returns data`() {
        val result = OpenCodeApiResult.Success("hello")
        assertEquals("hello", result.dataOrNull())
    }

    @Test
    fun `Success with nullable data type returns null data`() {
        val result = OpenCodeApiResult.Success<String?>(null)
        assertNull(result.dataOrNull())
    }

    @Test
    fun `Failure dataOrNull returns null`() {
        val result = OpenCodeApiResult.Failure(500, "internal error")
        assertNull(result.dataOrNull())
    }

    @Test
    fun `Unavailable dataOrNull returns null`() {
        val result = OpenCodeApiResult.Unavailable
        assertNull(result.dataOrNull())
    }

    @Test
    fun `Unauthorized dataOrNull returns null`() {
        val result = OpenCodeApiResult.Unauthorized
        assertNull(result.dataOrNull())
    }
}
