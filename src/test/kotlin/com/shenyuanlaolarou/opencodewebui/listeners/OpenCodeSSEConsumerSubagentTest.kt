package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for isSubagentTitle() (P1 TDD).
 *
 * 锁住"subagent title 抑制"的核心判定逻辑(纯函数,可测)。
 * 改实现:从 handleSessionIdle 内联 regex 改为顶层可测函数 + 在 onMessage
 * 写入 title 时同步算 isSubagent 进 cache,idle 路径只查 cache 不调 regex。
 */
class OpenCodeSSEConsumerSubagentTest {

    @Test
    fun `plain title is not subagent`() {
        assertFalse(isSubagentTitle("hello world"))
    }

    @Test
    fun `explore subagent title detected`() {
        assertTrue(isSubagentTitle("(@explore subagent)"))
    }

    @Test
    fun `coder subagent with suffix detected`() {
        assertTrue(isSubagentTitle("(@coder subagent) 任务完成"))
    }

    @Test
    fun `subagent without at prefix is not detected`() {
        assertFalse(isSubagentTitle("subagent 任务完成"))
    }

    @Test
    fun `at sign alone is not detected`() {
        assertFalse(isSubagentTitle("@"))
    }

    @Test
    fun `empty title is not subagent`() {
        assertFalse(isSubagentTitle(""))
    }

    @Test
    fun `at and subagent without space not detected`() {
        assertFalse(isSubagentTitle("(@subagent)"))
    }
}
