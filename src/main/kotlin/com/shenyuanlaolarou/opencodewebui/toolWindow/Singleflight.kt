package com.shenyuanlaolarou.opencodewebui.toolWindow

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Go-style singleflight: concurrent calls with the same [key] deduplicate —
 * only one executes [fn], all wait for and share the same result/exception.
 *
 * Two APIs:
 * - [acquire]/[release] — for async callers that need to complete the future
 *   on a different thread (e.g. [com.intellij.openapi.progress.Backgroundable]).
 * - [doWork] — convenience for synchronous callers.
 *
 * ## Contract (per key)
 * - At most one [fn] execution is in-flight at any time.
 * - If [fn] succeeds, all callers get the same return value.
 * - If [fn] throws, all callers get the same exception.
 * - After completion, the next call starts a fresh execution.
 */
class Singleflight<T> {

    private val lock = Any()
    private var inflight: CompletableFuture<T>? = null

    /**
     * Attempt to start or join an in-flight operation for [key].
     *
     * @return Pair(future, isLeader)
     *   - isLeader = true : caller MUST eventually complete [future] and call [release].
     *   - isLeader = false: [future] is shared with the leader; caller should wait on it.
     */
    fun acquire(key: Any): Pair<CompletableFuture<T>, Boolean> {
        synchronized(lock) {
            val existing = inflight
            if (existing != null) {
                return Pair(existing, false)
            }
            val future = CompletableFuture<T>()
            inflight = future
            return Pair(future, true)
        }
    }

    /**
     * Release the singleflight slot so the next [acquire] starts a fresh operation.
     * Safe to call after the future is completed.  Only clears the slot if [future]
     * is still the current inflight (prevents races with a new leader).
     */
    fun release(future: CompletableFuture<T>) {
        synchronized(lock) {
            if (inflight === future) {
                inflight = null
            }
        }
    }

    /**
     * Execute [fn] once for concurrent calls with the same [key].
     * Followers block until the leader completes.  Exceptions propagate to all.
     */
    fun doWork(key: Any, fn: () -> T): T {
        val result = acquire(key)
        val future = result.first
        val isLeader = result.second

        if (!isLeader) {
            // Follower: wait for leader (may throw if leader failed)
            return safeGet(future)
        }

        // Leader: execute fn, complete future, release slot
        return try {
            val value = fn()
            future.complete(value)
            value
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            throw t
        } finally {
            release(future)
        }
    }

    /**
     * Unwrap [ExecutionException] so followers receive the leader's original
     * exception type rather than an opaque wrapper.
     */
    private fun safeGet(future: CompletableFuture<T>): T {
        return try {
            future.get()
        } catch (e: ExecutionException) {
            // Re-throw the original cause so callers see the same exception type
            @Suppress("UNCHECKED_CAST")
            throw (e.cause as Throwable)
        }
    }
}
