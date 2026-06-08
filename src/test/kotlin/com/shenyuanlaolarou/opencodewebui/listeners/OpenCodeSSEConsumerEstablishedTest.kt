package com.shenyuanlaolarou.opencodewebui.listeners

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for OpenCodeSSEConsumer.onConnectionEstablished (Part C, R3).
 *
 * Verifies 1.5s debounce in onOpen():
 * 1. First onOpen() triggers onConnectionEstablished exactly once
 * 2. Second onOpen() within 1.5s does NOT re-trigger (debounce)
 * 3. onOpen() after 1.5s cooldown re-triggers
 * 4. Callback can be re-registered (lambda lock-in candidate 2 invariant)
 */
class OpenCodeSSEConsumerEstablishedTest {

    @Test
    fun `onOpen first time triggers onConnectionEstablished exactly once`() {
        val project = mock<Project>()
        var invocations = 0
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = {},
            onConnectionEstablished = { invocations++ }
        )
        consumer.onOpen()
        assertEquals(
            "first onOpen() must trigger onConnectionEstablished once",
            1, invocations
        )
    }

    @Test
    fun `onOpen within 1_5s cooldown does not re-trigger (debounce)`() {
        val project = mock<Project>()
        var invocations = 0
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = {},
            onConnectionEstablished = { invocations++ }
        )
        consumer.onOpen()
        consumer.onOpen()  // 立即二次握手,被 1.5s debounce 拦截
        assertEquals(
            "second onOpen() within 1.5s cooldown must NOT re-trigger (defends against transient handshake)",
            1, invocations
        )
    }

    @Test
    fun `onOpen after 1_5s cooldown re-triggers (network recovered)`() {
        val project = mock<Project>()
        var invocations = 0
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = {},
            onConnectionEstablished = { invocations++ }
        )
        consumer.onOpen()
        Thread.sleep(1600)  // 越过 1.5s debounce
        consumer.onOpen()
        assertEquals(
            "onOpen() after 1.5s cooldown re-triggers (recovery from server transient unavailability)",
            2, invocations
        )
    }

    @Test
    fun `callback re-registration works (lambda lock-in candidate 2)`() {
        val project = mock<Project>()
        var firstCount = 0
        var secondCount = 0
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = {},
            onConnectionEstablished = { firstCount++ }
        )
        consumer.onOpen()  // first onOpen → firstCount=1
        assertEquals(
            "first onOpen invokes original callback once",
            1, firstCount
        )
        // 模拟 getOrCreateConsumer 重入:第二个 caller 覆盖回调
        consumer.onConnectionEstablished = { secondCount++ }
        Thread.sleep(1600)  // 越过 1.5s debounce
        consumer.onOpen()  // 第二个 onOpen → secondCount=1,firstCount 仍 1
        assertEquals(
            "replaced callback: original must NOT receive event after re-registration",
            1, firstCount
        )
        assertEquals(
            "re-registered callback receives event after cooldown",
            1, secondCount
        )
    }

    @Test
    fun `onOpen does not throw when onConnectionEstablished is empty default`() {
        val project = mock<Project>()
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = {}
        )
        // 默认 onConnectionEstablished = {},onOpen 不应抛 NPE
        consumer.onOpen()
        // 仅验证不抛
    }
}
