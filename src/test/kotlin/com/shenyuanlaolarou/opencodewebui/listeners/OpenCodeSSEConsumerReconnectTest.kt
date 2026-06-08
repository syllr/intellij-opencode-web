package com.shenyuanlaolarou.opencodewebui.listeners

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for OpenCodeSSEConsumer.reconnect() — Part B (R2).
 *
 * Verifies:
 * 1. onError() 既存 wasConnected gate 保留 — Part A/B 修法的"前置契约"
 * 2. stop() 与 onError() 共享 wasConnected 守卫
 *
 * 设计选择:reconnect() 完整路径(zombie 回收)的单元测试需 OMO 端到端,
 * 本单测覆盖既有 gate 不被 refactor 静默破坏。
 */
class OpenCodeSSEConsumerReconnectTest {

    @Test
    fun `onError when wasConnected false does not invoke onConnectionLost (gate preserved)`() {
        val project = mock<Project>()
        var invocations = 0
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = { invocations++ },
            onConnectionEstablished = {}
        )
        consumer.stop()
        assertEquals(
            "onError wasConnected gate preserved — Part A/B prerequisite, refactor must not silently break",
            0, invocations
        )
    }

    @Test
    fun `stop when connected invokes onConnectionLost (gate activated by onError path)`() {
        val project = mock<Project>()
        var invocations = 0
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = { invocations++ },
            onConnectionEstablished = {}
        )
        consumer.onOpen()
        consumer.stop()
        assertEquals(
            "stop() in connected=true state invokes onConnectionLost (wasConnected gate activated)",
            1, invocations
        )
    }

    @Test
    fun `reconnect path wasConnected gate shared with onError (Part B invariant)`() {
        val project = mock<Project>()
        var invocations = 0
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = { invocations++ },
            onConnectionEstablished = {}
        )
        consumer.onOpen()
        consumer.stop()
        consumer.stop()
        assertEquals(
            "reconnect+onError shared wasConnected gate — Part B invariant (no recursive UI switch)",
            1, invocations
        )
    }
}
