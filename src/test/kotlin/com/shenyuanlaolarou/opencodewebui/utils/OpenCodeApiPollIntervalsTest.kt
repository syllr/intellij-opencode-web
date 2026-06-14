package com.shenyuanlaolarou.opencodewebui.utils

import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_INITIAL_DELAY_MS
import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_POLL_INTERVAL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for OpenCodeApi.pollIntervals() (P1 TDD).
 *
 * 锁住 health-check 轮询时间表:
 * 1. 初始延迟(1s,给 server 启动时间)+ 步进(500ms) 直到 timeout
 * 2. 最后一个 interval 不超过剩余 timeout
 * 3. intervals 总和 ≈ timeout
 * 4. 步进 ≤ pollInterval 常量(避免长 sleep 卡 EDT)
 */
class OpenCodeApiPollIntervalsTest {

    @Test
    fun `intervals start with initial delay`() {
        val intervals = OpenCodeApi.pollIntervals(timeoutMs = 5000L)
        assertEquals(HEALTH_CHECK_INITIAL_DELAY_MS, intervals.first())
    }

    @Test
    fun `intervals are at most poll interval in length`() {
        val intervals = OpenCodeApi.pollIntervals(timeoutMs = 10_000L)
        assertTrue("除 initialDelay 外,所有步进应 <= $HEALTH_CHECK_POLL_INTERVAL_MS",
            intervals.drop(1).all { it <= HEALTH_CHECK_POLL_INTERVAL_MS })
    }

    @Test
    fun `sum of intervals approximately equals timeout`() {
        val timeout = 5000L
        val sum = OpenCodeApi.pollIntervals(timeoutMs = timeout).sum()
        assertTrue("sum 应 <= timeout (sum=$sum, timeout=$timeout)", sum <= timeout)
        assertTrue("sum 应 >= timeout - 2*pollInterval",
            sum >= timeout - 2 * HEALTH_CHECK_POLL_INTERVAL_MS)
    }

    @Test
    fun `timeout less than initial delay returns single interval equal to timeout`() {
        val intervals = OpenCodeApi.pollIntervals(timeoutMs = 500L)
        assertEquals(listOf(500L), intervals)
    }

    @Test
    fun `timeout exactly equals initial delay returns single initial interval`() {
        val intervals = OpenCodeApi.pollIntervals(timeoutMs = HEALTH_CHECK_INITIAL_DELAY_MS)
        assertEquals(listOf(HEALTH_CHECK_INITIAL_DELAY_MS), intervals)
    }

    @Test
    fun `poll interval is reduced from 2000 to 500 ms for faster health detection`() {
        assertTrue("POLL_INTERVAL 应 <= 500ms (实际=$HEALTH_CHECK_POLL_INTERVAL_MS)",
            HEALTH_CHECK_POLL_INTERVAL_MS <= 500L)
    }
}
