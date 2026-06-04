package com.shenyuanlaolarou.opencodewebui.utils

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for OpenCodeNotificationService 1s Session-dimension deduplication.
 *
 * Covers:
 * - (sessionID, eventType) Key: 同 session 同事件 1s 内抑制
 * - 不同 session / eventType 互不干扰
 * - LRU 1000 容量驱逐
 * - 1s 后同 key 重新触发
 */
class NotificationDedupTest {

    @After
    fun tearDown() {
        OpenCodeNotificationService.clearDedupForTesting()
    }

    @Test
    fun `same key within 1s is suppressed`() {
        val now = 1_000_000L
        // 首次调用: 之前无记录, 不抑制
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now))
        // 500ms 后同 key: 在 1s 窗口内, 抑制
        assertTrue(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now + 500))
    }

    @Test
    fun `same key after 1s is not suppressed`() {
        val now = 1_000_000L
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now))
        // 1500ms 后同 key: 超过 1s 窗口, 不抑制
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now + 1_500))
    }

    @Test
    fun `different eventTypes for same session are independent`() {
        val now = 1_000_000L
        // 同 session 不同 eventType → 独立 Key
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now))
        // 500ms 内: complete 抑制, 但 permission 不抑制
        assertTrue(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now + 100))
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "permission", now + 200))
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "question", now + 300))
    }

    @Test
    fun `different sessions for same eventType are independent`() {
        val now = 1_000_000L
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now))
        // 不同 session 同 eventType → 独立 Key
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sB", "complete", now + 100))
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("sC", "complete", now + 200))
        // 回到 sA 仍抑制 (因为 last 为 sA, 500ms 内)
        assertTrue(OpenCodeNotificationService.tryRecordAndCheckDedup("sA", "complete", now + 300))
    }

    @Test
    fun `LRU evicts oldest entry after 1000 entries`() {
        // 插入 1000 条
        for (i in 0 until 1000) {
            OpenCodeNotificationService.tryRecordAndCheckDedup("session-$i", "complete", i.toLong())
        }
        // 第 1001 条触发 LRU 驱逐: session-0 被驱逐
        OpenCodeNotificationService.tryRecordAndCheckDedup("session-1000", "complete", 1000L)
        // session-0 (最旧) 现在重新触发不抑制 (因为它已被驱逐)
        assertFalse(OpenCodeNotificationService.tryRecordAndCheckDedup("session-0", "complete", 1001L))
    }
}
