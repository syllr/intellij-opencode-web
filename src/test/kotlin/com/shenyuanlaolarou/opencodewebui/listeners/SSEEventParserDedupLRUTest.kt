package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for SSEEventParser.isEventProcessed 事件去重 LRU (P1-7).
 *
 * 测 SSEEventParser 内置的 dedupCache (1000 容量 LinkedHashMap) 行为.
 * 与 NotificationDedupTest 不同: 那个测 1s Session 维度防抖,
 * 这里测事件 id 维度的去重 (同一事件 id 不被处理 2 次).
 */
class SSEEventParserDedupLRUTest {

    @org.junit.Before
    fun resetCache() {
        SSEEventParser.clearCache()
    }

    @Test
    fun `first occurrence of eventID is not processed`() {
        // 首次出现 → 返回 false (未处理过)
        assertFalse(SSEEventParser.isEventProcessed("evt-001"))
    }

    @Test
    fun `second occurrence of same eventID is processed`() {
        // 首次出现 → false (未处理)
        assertFalse(SSEEventParser.isEventProcessed("evt-001"))
        // 第二次出现 → true (已处理, 应去重)
        assertTrue(SSEEventParser.isEventProcessed("evt-001"))
    }

    @Test
    fun `different eventIDs are independent`() {
        assertFalse(SSEEventParser.isEventProcessed("evt-A"))
        assertFalse(SSEEventParser.isEventProcessed("evt-B"))
        assertFalse(SSEEventParser.isEventProcessed("evt-C"))
    }

    @Test
    fun `empty eventID is ignored (returns false)`() {
        // 空串 ID 应被忽略 (防止 false positive 去重)
        assertFalse(SSEEventParser.isEventProcessed(""))
        // 再次空串应仍然返回 false (没记录)
        assertFalse(SSEEventParser.isEventProcessed(""))
    }

    @Test
    fun `clearCache resets the dedup state`() {
        // 记录一些
        assertFalse(SSEEventParser.isEventProcessed("evt-X"))
        assertTrue(SSEEventParser.isEventProcessed("evt-X"))
        // 清空
        SSEEventParser.clearCache()
        // 重新出现应返回 false (因为缓存被清空)
        assertFalse(SSEEventParser.isEventProcessed("evt-X"))
    }

    @Test
    fun `cache respects maximum size of 1000`() {
        for (i in 0 until 2000) {
            assertFalse(SSEEventParser.isEventProcessed("evt-$i"))
        }
        SSEEventParser.dedupCache.cleanUp()
        val size = SSEEventParser.dedupCache.estimatedSize()
        assertTrue("cache 大小应 <= 1000 (实际=$size)", size <= 1000)
    }
}
