package com.shenyuanlaolarou.opencodewebui.utils

import com.shenyuanlaolarou.opencodewebui.utils.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit test for SessionInfo 字段构造 (P1-5 简化版).
 *
 * SessionInfoCache 是 OpenCodeNotificationService 的 private object,
 * 但其行为通过 OpenCodeNotificationService.send 的 minDuration 路径间接影响通知.
 * 直接测试 LRU + TTL 逻辑需要反射或重构成可见 API,
 * 这里改为测试 SessionInfo 类本身的字段构造 (防止未来重构破坏字段名).
 */
class SessionInfoCacheTest {

    @Test
    fun `SessionInfo can be constructed with timeCreated`() {
        // 防 SessionInfo.timeCreated 字段重命名(API 兼容测试)
        val info = SessionInfo(id = "ses-1", timeCreated = 1_000_000L, title = "test", parentID = null)
        assertEquals(1_000_000L, info.timeCreated)
        assertEquals("test", info.title)
    }

    @Test
    fun `SessionInfo timeCreated can be null`() {
        val info = SessionInfo(id = "ses-1", timeCreated = null, title = "test", parentID = null)
        assertNull(info.timeCreated)
    }
}
