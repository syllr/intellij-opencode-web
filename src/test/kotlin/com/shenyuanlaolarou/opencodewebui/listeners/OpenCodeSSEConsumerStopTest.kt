package com.shenyuanlaolarou.opencodewebui.listeners

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for OpenCodeSSEConsumer.stop() — Part A (R1).
 *
 * Verifies:
 * 1. stop() in connected=true state invokes onConnectionLost synchronously
 * 2. stop() in connected=false state does NOT invoke onConnectionLost
 * 3. stop() called twice (re-entrant) does NOT invoke onConnectionLost twice
 *
 * Project mocked via mockito-kotlin (no real IntelliJ Platform testFramework needed).
 */
class OpenCodeSSEConsumerStopTest {

    private fun newConsumer(
        onConnectionLost: () -> Unit = {},
        connected: Boolean = false,
    ): OpenCodeSSEConsumer {
        val project = mock<Project>()
        val consumer = OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = onConnectionLost,
            onConnectionEstablished = {},
        )
        if (connected) {
            consumer.onOpen()
        }
        return consumer
    }

    @Test
    fun `stop when connected invokes onConnectionLost synchronously`() {
        var invocations = 0
        val consumer = newConsumer(
            onConnectionLost = { invocations++ },
            connected = true,
        )
        consumer.stop()
        assertEquals("stop() should invoke onConnectionLost exactly once when previously connected", 1, invocations)
    }

    @Test
    fun `stop when not connected does not invoke onConnectionLost`() {
        var invocations = 0
        val consumer = newConsumer(
            onConnectionLost = { invocations++ },
            connected = false,
        )
        consumer.stop()
        assertEquals("stop() should NOT invoke onConnectionLost when never connected", 0, invocations)
    }

    @Test
    fun `stop called twice does not invoke onConnectionLost twice (re-entrant guard)`() {
        var invocations = 0
        val consumer = newConsumer(
            onConnectionLost = { invocations++ },
            connected = true,
        )
        // First stop: wasConnected=true,invoke onConnectionLost (1st call)
        consumer.stop()
        // Second stop(re-entrant, e.g., from disposeForProject after first onConnectionLost triggers showServerNotRunning):
        // wasConnected=false,skip onConnectionLost (no recursive UI switch storm)
        consumer.stop()
        assertEquals(
            "stop() called twice should invoke onConnectionLost only once (wasConnected gate)",
            1,
            invocations,
        )
    }
}
