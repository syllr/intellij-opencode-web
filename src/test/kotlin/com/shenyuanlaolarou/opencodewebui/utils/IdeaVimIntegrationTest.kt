package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.openapi.editor.Editor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for IdeaVimIntegration 第四轮 C5 反射缓存重构 (行为不变回归).
 *
 * 测试环境无 IdeaVim 插件,只能验证降级路径:
 * - 缓存重建后 isEnabled/getInstance/getInjector/getVimState/getMode/exitVisualMode
 *   任意一环拿不到 method 时,都应安全返回 null/false, 不抛异常
 * - editor=null 的早退路径仍生效
 * - 多次调用结果一致(缓存命中,不破坏行为)
 */
class IdeaVimIntegrationTest {

    @Test
    fun `isIdeaVimInstalled returns false when IdeaVim not on classpath`() {
        assertFalse(IdeaVimIntegration.isIdeaVimInstalled())
    }

    @Test
    fun `getVisualSelection returns null when editor is null`() {
        assertNull(IdeaVimIntegration.getVisualSelection(null))
    }

    @Test
    fun `getVisualSelection returns null when IdeaVim not installed (no exception)`() {
        val editor: Editor = mock()
        assertNull(IdeaVimIntegration.getVisualSelection(editor))
    }

    @Test
    fun `getVisualSelection multiple calls all return null when IdeaVim not installed`() {
        val editor: Editor = mock()
        repeat(5) {
            assertNull(IdeaVimIntegration.getVisualSelection(editor))
        }
    }

    @Test
    fun `isInVisualMode returns false when editor is null`() {
        assertFalse(IdeaVimIntegration.isInVisualMode(null))
    }

    @Test
    fun `isInVisualMode returns false when IdeaVim not installed (no exception)`() {
        val editor: Editor = mock()
        assertFalse(IdeaVimIntegration.isInVisualMode(editor))
    }

    @Test
    fun `isInVisualMode multiple calls all return false when IdeaVim not installed`() {
        val editor: Editor = mock()
        repeat(5) {
            assertFalse(IdeaVimIntegration.isInVisualMode(editor))
        }
    }

    @Test
    fun `exitVisualMode is no-op when editor is null (no exception)`() {
        IdeaVimIntegration.exitVisualMode(null)
    }

    @Test
    fun `exitVisualMode does not throw when IdeaVim not installed`() {
        val editor: Editor = mock()
        IdeaVimIntegration.exitVisualMode(editor)
    }

    @Test
    fun `isIdeaVimInstalled returns same result across multiple calls`() {
        val first = IdeaVimIntegration.isIdeaVimInstalled()
        repeat(10) {
            assertEquals(first, IdeaVimIntegration.isIdeaVimInstalled())
        }
    }
}
