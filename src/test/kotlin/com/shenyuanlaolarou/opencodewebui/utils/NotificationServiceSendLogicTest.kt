package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.notification.NotificationType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for OpenCodeNotificationService.resolveType 纯函数 (P1-6 简化版).
 *
 * 注意: send() 整体依赖 IDE 服务,这里**只**测纯函数 resolveType (4 case).
 * extractSessionID 由 NotificationServiceExtractSessionIdTest 覆盖.
 */
class NotificationServiceSendLogicTest {

    @Test
    fun `resolveType returns WARNING for permission`() {
        assertEquals(NotificationType.WARNING, OpenCodeNotificationService.resolveType("permission"))
    }

    @Test
    fun `resolveType returns WARNING for question`() {
        assertEquals(NotificationType.WARNING, OpenCodeNotificationService.resolveType("question"))
    }

    @Test
    fun `resolveType returns INFORMATION for complete`() {
        assertEquals(NotificationType.INFORMATION, OpenCodeNotificationService.resolveType("complete"))
    }

    @Test
    fun `resolveType returns INFORMATION for unknown eventType (dead code paths removed)`() {
        // 原 "error"/"user_cancelled" dead code 已删 (2 个 🟡 警告修完)
        // 现在任何未知 eventType 都应返回 INFORMATION
        assertEquals(NotificationType.INFORMATION, OpenCodeNotificationService.resolveType("any_unknown_type"))
    }
}
