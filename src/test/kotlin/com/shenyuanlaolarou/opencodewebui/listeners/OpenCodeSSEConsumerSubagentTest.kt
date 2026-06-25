package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for isSubagentTitle() (P1 + P2 TDD)。
 *
 * 锁住"subagent title 抑制"的核心判定逻辑(纯函数,可测)。
 * 改实现:从 handleSessionIdle 内联 regex 改为顶层可测函数 + 在 onMessage
 * 写入 title 时同步算 isSubagent 进 cache,idle 路径只查 cache 不调 regex。
 *
 * 共 16 个 case:
 *  - 7 个 P1 baseline(覆盖旧 @\w+ 形态)
 *  - 9 个 P2 加固(覆盖 OMO 新字符集 `-`/`.`/`/`/`+`/`_`/混合)
 *  触发原 @\w+ regex 在 @Sisyphus-Junior 这类连字符 agent 名下失配的现场 bug。
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

    @Test
    fun `sisyphus junior hyphenated subagent title detected (screenshot case)`() {
        assertTrue(isSubagentTitle("(@Sisyphus-Junior subagent)"))
    }

    @Test
    fun `oracle master hyphenated subagent detected`() {
        assertTrue(isSubagentTitle("(@oracle-master subagent)"))
    }

    @Test
    fun `lcx report bug multi hyphen subagent detected`() {
        assertTrue(isSubagentTitle("(@lcx-report-bug subagent)"))
    }

    @Test
    fun `agent with dot in name subagent detected`() {
        assertTrue(isSubagentTitle("(@agent.v2 subagent)"))
    }

    @Test
    fun `agent with slash in name subagent detected`() {
        assertTrue(isSubagentTitle("(@lark/base subagent)"))
    }

    @Test
    fun `agent with plus in name subagent detected`() {
        assertTrue(isSubagentTitle("(@a+b subagent)"))
    }

    @Test
    fun `agent with underscore in name subagent detected`() {
        assertTrue(isSubagentTitle("(@a_b subagent)"))
    }

    @Test
    fun `agent with mixed hyphen dot digits subagent detected`() {
        assertTrue(isSubagentTitle("(@agent-1.0 subagent)"))
    }

    @Test
    fun `at agent with closing paren before subagent keyword not detected`() {
        assertFalse(isSubagentTitle("(@agent) subagent"))
    }
}
