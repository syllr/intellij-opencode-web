package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for OpenCodeNotificationService 第四轮 C1+C2+C3+C4 重构 (行为不变回归).
 *
 * 覆盖纯函数部分(不依赖 server):
 * - C1: extractSessionIDFromPropsMap 提取逻辑不变
 * - C2+C3: showSessionTitle=false 时 formatMessage 不查 session, body = 模板字面值
 * - C4: 死代码删除后,extractSessionIDFromPropsMap 仍正确(由 NotificationServiceExtractSessionIdTest + ExtractSessionIdTest 已覆盖)
 */
class OpenCodeNotificationServiceOptimizationTest {

    private fun mockProject(name: String = "testProject"): Project {
        val project = mock<Project>()
        whenever(project.name).thenReturn(name)
        return project
    }

    private fun plainTitleProps(sessionID: String = "s2"): Map<String, Any?> = mapOf(
        "id" to "evt-2",
        "type" to "permission.asked",
        "properties" to mapOf(
            "sessionID" to sessionID,
            "info" to mapOf("title" to "plain session")
        )
    )

    /**
     * C3: showSessionTitle=false 时,即便模板含 {sessionTitle}, 也不查 session,
     * 行为: body = 模板字面值(无占位符被替换).
     */
    @Test
    fun `formatMessage skips session lookup when showSessionTitle is false`() {
        val project = mockProject()
        val body = OpenCodeNotificationService.formatMessage(
            eventType = "permission",
            propertiesMap = plainTitleProps(),
            project = project,
            showSessionTitle = false,
        )
        // showSessionTitle=false → sessionInfo=null → sessionTitle 占位符无法被替换, 保留原模板
        assertEquals("权限申请: {sessionTitle}", body)
    }

    /**
     * C1: extractSessionIDFromPropsMap 提取逻辑不变 (通知路径 properties["properties"]["sessionID"]).
     */
    @Test
    fun `extractSessionIDFromPropsMap returns nested sessionID for send path`() {
        val props = mapOf<String, Any?>("sessionID" to "s-direct")
        assertEquals("s-direct", OpenCodeNotificationService.extractSessionIDFromPropsMap(props))
    }

    /**
     * C1: 三个 fallback 路径全部生效 (props.sessionID / props.info.sessionID / props.info.id).
     */
    @Test
    fun `extractSessionIDFromPropsMap falls back through three levels`() {
        assertEquals(
            "from-props",
            OpenCodeNotificationService.extractSessionIDFromPropsMap(mapOf("sessionID" to "from-props"))
        )
        assertEquals(
            "from-info-sid",
            OpenCodeNotificationService.extractSessionIDFromPropsMap(
                mapOf("info" to mapOf("sessionID" to "from-info-sid"))
            )
        )
        assertEquals(
            "from-info-id",
            OpenCodeNotificationService.extractSessionIDFromPropsMap(
                mapOf("info" to mapOf("id" to "from-info-id"))
            )
        )
    }

    /**
     * P0-2: htmlEscape 转义 4 个 HTML 特殊字符 (< > & "), 防止 Notification 渲染 session title 时
     * 触发 HTML 注入 (e.g. `<img src="http://evil/...">` 外连请求)。null 输入返回 null。
     */
    @Test
    fun `htmlEscape escapes HTML special chars and preserves null`() {
        assertEquals(
            "&lt;script&gt;alert()&lt;/script&gt;",
            OpenCodeNotificationService.htmlEscape("<script>alert()</script>")
        )
        assertEquals("a &amp; b", OpenCodeNotificationService.htmlEscape("a & b"))
        assertEquals("&quot;quoted&quot;", OpenCodeNotificationService.htmlEscape("\"quoted\""))
        assertEquals("plain text", OpenCodeNotificationService.htmlEscape("plain text"))
        assertNull(OpenCodeNotificationService.htmlEscape(null))
    }
}
