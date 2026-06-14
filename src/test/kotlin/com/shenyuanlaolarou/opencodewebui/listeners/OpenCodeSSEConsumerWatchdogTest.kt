package com.shenyuanlaolarou.opencodewebui.listeners

import com.intellij.openapi.project.Project
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for OpenCodeSSEConsumer.startWatchdog() lifecycle (P1 TDD).
 *
 * 锁住 watchdog 行为不变量:
 * 1. startWatchdog 后有名字为 "SSE-Watchdog" 的 daemon 线程在跑
 * 2. stop() 后该线程终止
 * 3. startWatchdog 可多次重启(无副作用累积)
 *
 * 这些不变量在 Thread + sleep 实现和 ScheduledExecutorService 实现下都应保持。
 */
class OpenCodeSSEConsumerWatchdogTest {

    private val consumers = mutableListOf<OpenCodeSSEConsumer>()

    @After
    fun cleanup() {
        consumers.forEach { it.stop() }
        consumers.clear()
        Thread.sleep(50)  // 等线程退出
    }

    private fun newConsumer(): OpenCodeSSEConsumer {
        val project = mock<Project>()
        return OpenCodeSSEConsumer(
            project = project,
            onConnectionLost = {},
            onConnectionEstablished = {},
        ).also { consumers.add(it) }
    }

    @Test
    fun `startWatchdog spawns SSE-Watchdog daemon thread`() {
        val consumer = newConsumer()
        consumer.startWatchdog()
        Thread.sleep(100)  // 等线程起来
        val watchdog = findWatchdogThread()
        assertTrue("SSE-Watchdog 线程应在跑", watchdog != null && watchdog.isAlive)
        assertTrue("watchdog 应是 daemon", watchdog?.isDaemon == true)
    }

    @Test
    fun `stop halts the SSE-Watchdog thread`() {
        val consumer = newConsumer()
        consumer.startWatchdog()
        Thread.sleep(100)
        assertTrue("前置: watchdog 在跑", findWatchdogThread()?.isAlive == true)
        consumer.stop()
        Thread.sleep(100)  // 等线程退出
        val alive = findWatchdogThread()?.isAlive == true
        assertFalse("stop() 后 SSE-Watchdog 不应存活", alive)
    }

    @Test
    fun `startWatchdog can be restarted multiple times without error`() {
        val consumer = newConsumer()
        consumer.startWatchdog()
        Thread.sleep(50)
        consumer.stop()
        Thread.sleep(50)
        consumer.startWatchdog()
        Thread.sleep(50)
        consumer.stop()
        Thread.sleep(50)
        assertFalse("多次重启后 watchdog 应能干净停止", findWatchdogThread()?.isAlive == true)
    }

    private fun findWatchdogThread(): Thread? =
        Thread.getAllStackTraces().keys.firstOrNull { it.name == "SSE-Watchdog" }
}
