package com.shenyuanlaolarou.opencodewebui.toolWindow

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 覆盖 [Singleflight] 的 5 个核心行为 contract。
 * 纯 JDK 并发原语,无 IntelliJ 平台依赖。
 */
class SingleflightTest {

    private val executor = Executors.newFixedThreadPool(8)
    private val sf = Singleflight<String>()
    private val KEY = "test"

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    // ── Case 1: 5 concurrent calls → only 1 fn execution ───────────

    @Test
    fun `5 concurrent calls execute work only once`() {
        val counter = AtomicInteger(0)
        val workStarted = CountDownLatch(1)
        val workDone = CountDownLatch(1)

        val futures = (1..5).map {
            executor.submit(Callable {
                sf.doWork(KEY) {
                    counter.incrementAndGet()
                    workStarted.countDown()
                    workDone.await()
                    "done"
                }
            })
        }

        // 等待 leader 进入 fn 并自增 counter
        assertTrue("leader should start work", workStarted.await(3, TimeUnit.SECONDS))
        // 给 follower 一点时间到达 future.get() 阻塞点
        Thread.sleep(200)

        // 核心断言:5 个并发调用只触发了 1 次 fn 执行
        assertEquals("counter should be 1 — only leader executed fn", 1, counter.get())

        // 放行 leader,所有 follower 获得结果
        workDone.countDown()
        for (f in futures) {
            assertEquals("done", f.get(3, TimeUnit.SECONDS))
        }
    }

    // ── Case 2: all callbacks fire with same result ─────────────────

    @Test
    fun `all callers receive exactly the same result`() {
        val latch = CountDownLatch(1)

        val futures = (1..5).map { i ->
            executor.submit(Callable {
                sf.doWork(KEY) {
                    latch.await()
                    "result-from-leader" // leader 产出的值,所有 follower 都应该拿到
                }
            })
        }

        // 确保所有线程都已经到达 singleflight
        Thread.sleep(300)

        // 放行
        latch.countDown()

        val results = futures.map { it.get(3, TimeUnit.SECONDS) }
        assertEquals(5, results.size)

        // 核心断言:所有 caller 拿到同一个结果(leader 产出的值)
        val distinct = results.toSet()
        assertEquals("all 5 callers must receive the same result", 1, distinct.size)
        assertEquals("result-from-leader", distinct.first())
    }

    // ── Case 3: leader fail → all callbacks receive onFailed ───────

    @Test
    fun `leader failure propagates same error to all callers`() {
        val errorMessages = mutableListOf<String>()

        val futures = (1..5).map {
            executor.submit(Callable {
                try {
                    sf.doWork(KEY) {
                        throw RuntimeException("leader-failed")
                    }
                    fail("should not reach here")
                } catch (e: RuntimeException) {
                    synchronized(errorMessages) { errorMessages.add(e.message!!) }
                    throw e
                }
            })
        }

        // 等待所有线程完成(无论成功或失败)
        for (f in futures) {
            try {
                f.get(3, TimeUnit.SECONDS)
                fail("should have thrown")
            } catch (_: Exception) {
                // expected
            }
        }

        // 核心断言:5 个 caller 都收到了同一个 error
        assertEquals("all 5 callers should receive the error", 5, errorMessages.size)
        assertTrue("all errors should be 'leader-failed'",
            errorMessages.all { it == "leader-failed" })
    }

    // ── Case 4: leader completes early (port open during scheduling) ─

    @Test
    fun `leader can complete early and followers get result`() {
        // 模拟 OpenCodeServerManager 的 "Backgroundable 排队期间端口被占" 场景:
        // leader acquire 槽位后做预检,发现端口已开,直接 complete future 而不启进程。
        // followers 等待 future,得到 leader 的快速完成结果。

        val (future, isLeader) = sf.acquire(KEY)
        assertTrue("first caller should be leader", isLeader)

        // 4 个 follower 并发进入,应该拿到同一个 future
        val followersAcquired = CountDownLatch(4)
        val followerResults = mutableListOf<String>()
        val followerFutures = (1..4).map {
            executor.submit(Callable {
                val (f, leader) = sf.acquire(KEY)
                assertFalse("follower should not be leader", leader)
                followersAcquired.countDown()
                val result = f.get(5, TimeUnit.SECONDS)
                synchronized(followerResults) { followerResults.add(result) }
                result
            })
        }

        // 等待所有 follower 都 acquire 完毕,阻塞在 future.get()
        assertTrue("all followers should have acquired", followersAcquired.await(3, TimeUnit.SECONDS))

        // leader: 模拟 "端口已开,跳过启进程,走 consumer 复用路径"
        future.complete("reused-port")
        sf.release(future)

        // follower 应该全部拿到 leader 的结果
        val results = followerFutures.map { it.get(3, TimeUnit.SECONDS) }
        assertEquals(listOf("reused-port", "reused-port", "reused-port", "reused-port"), results)

        // 核心断言:release 后槽位已清空,新调用不受旧 future 影响
        val (_, isLeader2) = sf.acquire(KEY)
        assertTrue("slot should be released — new call is leader", isLeader2)
    }

    // ── Case 5: second call after first completes → new startup ────

    @Test
    fun `subsequent call after completion starts a fresh execution`() {
        // 第一次:正常完成
        val first = sf.doWork(KEY) { "first" }
        assertEquals("first", first)

        // 第二次:应该执行全新的 fn,不被第一次的 future 黏住
        val second = sf.doWork(KEY) { "second" }
        assertEquals("second", second)

        // 再跑一轮并发,确保旧 future 清理干净
        val done = CountDownLatch(1)
        val futures = (1..3).map {
            executor.submit(Callable {
                sf.doWork(KEY) {
                    done.await()
                    "third"
                }
            })
        }

        Thread.sleep(100)
        done.countDown()

        val results = futures.map { it.get(3, TimeUnit.SECONDS) }
        assertEquals(listOf("third", "third", "third"), results)
    }
}
