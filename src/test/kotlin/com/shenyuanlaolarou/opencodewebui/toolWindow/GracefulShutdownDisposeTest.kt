package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApiResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回归测试：[Fix #2 端口误杀] — gracefulShutdown 必须先 acquireHandle 验证进程归属,
 * 拿不到 handle 时**不**应发送 POST /global/dispose。
 *
 * 历史 bug: 旧版先 startDisposeThread() 再 acquireHandle() ?: return,
 *   用户手动 `opencode serve` 启动的 server (不在 serverProcess 引用里) 收到
 *   dispose 指令后自我退出。
 *
 * 修复后: handle 为 null 直接 return,dispose 线程永远不启动。
 *
 * 用 [disposeSender] 注入点观测:测试替换为计数 lambda,断言调用次数。
 */
class GracefulShutdownDisposeTest {

    @After
    fun tearDown() {
        // 还原默认 sender + 清空 serverProcess,避免污染其他测试
        disposeSender = { OpenCodeApiResult.Unavailable }
        OpenCodeServerManager.serverProcess.set(null)
        // shutdownInProgress 是 private,但 gracefulShutdown finally 会重置为 false。
        // 同步 stopServer() 在 handle=null 时也会走 finally,无需手工重置。
    }

    // ── Case 1: 核心 bug 回归 ───────────────────────────────────────
    //
    // serverProcess = null 模拟 "用户手动 `opencode serve` 启动的 server,
    //                       本 IDE 引用不到它" 的场景。
    // 旧版 (bug): disposeSender() 被调用 1 次 (误杀)
    // 修复后:    disposeSender() 被调用 0 次 (返回 null 直接退出)

    @Test
    fun `stopServer when serverProcess is null does NOT call disposeSender`() {
        val callCount = AtomicInteger(0)
        // 用 latch 让 dispose 线程跑完再断言,防止 race
        val disposeCallLatch = CountDownLatch(1)
        val trackingSender = {
            callCount.incrementAndGet()
            disposeCallLatch.countDown()
            OpenCodeApiResult.Unavailable
        }
        disposeSender = trackingSender

        // 前置: serverProcess 为 null
        OpenCodeServerManager.serverProcess.set(null)

        // 触发 stopServer — 修复后会在 handle 为 null 时直接 return,不调 startDisposeThread
        OpenCodeServerManager.stopServer()

        // 等最多 1 秒确认 dispose 线程(如果启动的话)已跑完
        // 修复后线程根本不会启动,countDownLatch.await() 立即超时返回 false
        val called = disposeCallLatch.await(1, TimeUnit.SECONDS)

        assertEquals(
            "[Fix #2 端口误杀] serverProcess 为 null 时 disposeSender 永远不调",
            0, callCount.get()
        )
        assertTrue(
            "disposeSender 不应被异步调用 — 修复后 stopServer 在 handle 为 null 时" +
                " 直接 return,根本不启动 dispose 线程",
            !called
        )
    }

    // ── Case 2: 保护现有正向路径 ───────────────────────────────────
    //
    // serverProcess 持有 mock 进程 (isAlive=true) 时,正常走 wait-exit 流程。
    // 断言 "disposeSender 被调用了",**不**等进程退出 (避免测试卡 5s onExit 等待)。

    @Test
    fun `stopServer when serverProcess is alive DOES call disposeSender (positive path preserved)`() {
        val callCount = AtomicInteger(0)
        val disposeCallLatch = CountDownLatch(1)
        disposeSender = {
            callCount.incrementAndGet()
            disposeCallLatch.countDown()
            OpenCodeApiResult.Unavailable
        }

        // 用真实 ProcessBuilder 启一个 sleep 长进程 — 真实 alive + 真实 PID
        val process = ProcessBuilder("sleep", "30").start()
        try {
            OpenCodeServerManager.serverProcess.set(process)
            assertTrue("test process should be alive", process.isAlive)

            // 在后台线程跑 stopServer,避免主线程被 handle.onExit().get(5s) 阻塞
            val stopThread = Thread { OpenCodeServerManager.stopServer() }
            stopThread.isDaemon = true
            stopThread.start()

            // 等 dispose 线程跑完 — daemon 线程启动后同步调 sender,几百 ms 内必然完成
            val called = disposeCallLatch.await(2, TimeUnit.SECONDS)
            assertTrue(
                "正向路径: serverProcess 持有 alive 进程时 disposeSender 应被调用," +
                    " 实际 callCount=${callCount.get()}, called=$called",
                called && callCount.get() >= 1
            )
        } finally {
            // 清理: 杀掉测试进程
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            OpenCodeServerManager.serverProcess.set(null)
        }
    }
}
