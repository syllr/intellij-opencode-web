package com.shenyuanlaolarou.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.launchdarkly.eventsource.ConnectStrategy
import com.launchdarkly.eventsource.ErrorStrategy
import com.launchdarkly.eventsource.EventSource
import com.launchdarkly.eventsource.MessageEvent
import com.launchdarkly.eventsource.background.BackgroundEventHandler
import com.launchdarkly.eventsource.background.BackgroundEventSource
import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.shenyuanlaolarou.opencodewebui.SSE_CONNECT_TIMEOUT_SECONDS
import com.shenyuanlaolarou.opencodewebui.SSE_IDLE_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.SSE_WATCHDOG_INTERVAL_MS
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SSE 消费者。
 *
 * v2.0.0+ 职责窄化为:**文件刷新**(Bash 工具完成 + 文件 watcher 事件)+ **心跳健康信号**。
 * in-IDE 通知已砍掉(由 OpenCode Web UI / server 端接管),`permission.asked` /
 * `question.asked` / `session.idle` / `session.status` 等通知事件在 SSEEventParser 白名单外,
 * parser 早退不走全量 Gson 反序列化,见 [SSEEventParser.ALLOW_PARSE_EVENT_TYPES]。
 */
class OpenCodeSSEConsumer(
    private val project: Project,
    /**
     * SSE 连接被服务端关闭时的回调(快速通道,绕过 15s 轮询 debounce)。
     * 通常是 server 主动 shutdown 的信号:showServerNotRunning() 应立即被调,
     * 用户不应等待 15s 后才看到 Start 按钮。
     */
    @Volatile var onConnectionLost: () -> Unit = {},
    /**
     * SSE 连接建立后的回调(替代 HealthMonitor.onRecovered,Part C 修法)。
     * 在 onOpen() 末尾触发,1.5s debounce 防网络抖动误触。
     * 字段为 var + @Volatile 支持 getOrCreateConsumer 的 computeIfPresent 重注册机制。
     */
    @Volatile var onConnectionEstablished: () -> Unit = {},
) : BackgroundEventHandler {
    private val projectBasePath: String? = project.basePath

    private val eventSourceRef = AtomicReference<BackgroundEventSource?>(null)
    private val connectionGen = AtomicLong(0)
    @Volatile private var activeConnectionGen: Long = -1
    private val logger = thisLogger()

    // 任何 SSE 事件到达(onMessage)或连接刚建(onOpen)时刷新,watchdog 超时则强制重连。
    private val lastEventAt = AtomicLong(0L)
    private val lastEstablishedAt = AtomicLong(0L)
    // 最近一次收到 server.heartbeat 的时间戳,仅作诊断用(无业务消费者)。
    // 0 表示从未收到过心跳。
    private val lastHeartbeatAt = AtomicLong(0L)
    // SSE 连接已通过 onOpen 确认建立。仅 true 时 isHealthy() 才返回 true,
    // 防止 startSseConnection 后 bgEventSource.start() 异步未完成时假阳性。
    @Volatile
    private var connected = false
    @Volatile
    private var watchdogScheduler: java.util.concurrent.ScheduledExecutorService? = null

    /**
     * SSE 连接健康度:连接已确认建立(connected=true) 或 最近收到过 heartbeat,
     * 且 lastEventAt/heartbeat 在 N 秒内更新过。替代 HTTP 探活,避免 EDT 阻塞。
     */
    fun isHealthy(maxIdleMs: Long = SSE_IDLE_TIMEOUT_MS): Boolean {
        if (!connected && lastHeartbeatAt.get() == 0L) return false
        val lastEvent = maxOf(lastEventAt.get(), lastHeartbeatAt.get())
        if (lastEvent == 0L) return false
        return System.currentTimeMillis() - lastEvent <= maxIdleMs
    }

    fun start() {
        logger.info("[OpenCodeSSEConsumer] Starting SSE consumer, projectBasePath='$projectBasePath'")
        FullRefreshCoordinator.start(projectBasePath ?: "")
        startSseConnection()
        startWatchdog()
    }

    fun stop() {
        val wasConnected = connected
        connected = false
        if (wasConnected) {
            onConnectionLost()
        }
        watchdogScheduler?.shutdownNow()
        watchdogScheduler?.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
        watchdogScheduler = null
        FullRefreshCoordinator.stop()
        eventSourceRef.getAndSet(null)?.close()
        // dedupCache 跨 instance 共享(stop() 全局清),与重连不耦合。
        SSEEventParser.clearCache()
        logger.info("[OpenCodeSSEConsumer] SSE consumer stopped")
    }

    private fun startSseConnection() {
        // 规范化路径: 解析符号链接 + 去除末尾分隔符, 确保与 opencode 服务端 instance.directory 完全一致
        val normalizedPath = try {
            java.nio.file.Path.of(projectBasePath ?: "").toRealPath().toString()
        } catch (_: Exception) {
            projectBasePath ?: ""
        }
        val uri = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/event?directory=" + URLEncoder.encode(normalizedPath, "UTF-8"))
        val connectStrategy = ConnectStrategy.http(uri)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(SSE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        val esBuilder = EventSource.Builder(connectStrategy)
            .errorStrategy(ErrorStrategy.alwaysContinue())
            .streamEventData(false)
        val bgEventSource = BackgroundEventSource.Builder(this, esBuilder).build()
        val gen = connectionGen.incrementAndGet()
        activeConnectionGen = gen
        eventSourceRef.set(bgEventSource)
        bgEventSource.start()
        // 不再预设 lastEventAt —— bgEventSource.start() 异步,onOpen() 回调确认连接建立后才置 lastEventAt + connected=true。
        // 否则 checkAndLoadContent 立即调 isHealthy() 会假阳性返回 true,绕过 Start 按钮流程。
        logger.info("[OpenCodeSSEConsumer] SSE connection started, gen=$gen, normalizedPath=$normalizedPath, uri=$uri")
    }

    private fun reconnect() {
        val idle = System.currentTimeMillis() - lastEventAt.get()
        logger.warn("[OpenCodeSSEConsumer] No event for ${idle}ms, forcing reconnect")

        // [WARNING 自杀时序] onConnectionLost() 必须在 startSseConnection() 之前调:
        // 1. 早触发 invokeLater → UI 切回 Start 按钮更早(LaunchDarkly 后台线程 → EDT 排队)
        // 2. 避免 startSseConnection 立即建连触发 onOpen → onConnectionEstablished → loadProjectPage,
        //    与即将调 onConnectionLost → showServerNotRunning 产生 race(用户看到 UI 闪一下)
        // 3. connected=false 标记后再建连,New source 由 onOpen(1.5s debounce) 接管恢复路径
        // zombie 行为(2 个 EventSource 共存)两种顺序完全等价,放前面只为 UX 优化。
        if (connected) {
            onConnectionLost()
        }

        val old = eventSourceRef.getAndSet(null)
        startSseConnection()
        old?.close()
    }

    internal fun startWatchdog() {
        watchdogScheduler?.shutdownNow()
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "SSE-Watchdog").apply { isDaemon = true }
        }
        watchdogScheduler = scheduler
        scheduler.scheduleWithFixedDelay(
            {
                try {
                    val idle = System.currentTimeMillis() - lastEventAt.get()
                    if (idle > SSE_IDLE_TIMEOUT_MS) reconnect()
                } catch (t: Throwable) {
                    logger.error("watchdog task failed: ${t.message}", t)
                }
            },
            SSE_WATCHDOG_INTERVAL_MS, SSE_WATCHDOG_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    override fun onOpen() {
        connected = true
        lastEventAt.set(System.currentTimeMillis())
        logger.info("[OpenCodeSSEConsumer] SSE onOpen() called (background thread=${Thread.currentThread().name})")
        // 1.5s debounce 触发 onConnectionEstablished。
        // 对齐 LaunchDarkly 首次重试 ~1s + jitter(不放 1.0s 踩边缘,不放 2.0s 延迟感知)。
        // 防御 SSE 瞬时握手成功(被 server 立刻关闭),避免 UI 闪一下 Start 按钮。
        val now = System.currentTimeMillis()
        if (now - lastEstablishedAt.get() >= 1500L) {
            lastEstablishedAt.set(now)
            logger.info("[OpenCodeSSEConsumer] SSE 1.5s debounce passed, firing onConnectionEstablished (wasConnected=$connected)")
            onConnectionEstablished()
        } else {
            logger.info("[OpenCodeSSEConsumer] SSE 1.5s debounce NOT passed (${now - lastEstablishedAt.get()}ms since last), suppressing onConnectionEstablished")
        }
    }

    override fun onMessage(event: String, messageEvent: MessageEvent) {
        lastEventAt.set(System.currentTimeMillis())
        val data = messageEvent.data ?: return
        val parsed = SSEEventParser.parse(event, data)
        val parsedMap = parsed.parsedMap

        // 非白名单事件：parser 已早退(parsedMap == null),直接丢弃。
        // v2.0.0+ 白名单见 SSEEventParser.ALLOW_PARSE_EVENT_TYPES 的 KDoc。
        if (parsedMap == null) return

        val type = parsed.type ?: return

        // server.heartbeat: 仅作健康信号记录,无业务逻辑,直接 return
        if (type == "server.heartbeat") {
            lastHeartbeatAt.set(System.currentTimeMillis())
            return
        }

        if (logger.isDebugEnabled) {
            logger.debug("[OpenCodeSSEConsumer] Event: type='$type'")
        }

        // 事件去重：按 root-level id 去重
        val eventId = parsedMap["id"] as? String
        if (eventId != null && SSEEventParser.isEventProcessed(eventId)) return

        // 文件事件处理
        // - message.part.updated: BashCommandHandler 通过解析消息内容检测 bash 完成 → FullRefreshCoordinator
        // - file.edited / file.watcher.updated: SSE server 直推 → FullRefreshCoordinator 主路径
        // - session.diff: 服务器 ack diff 接受 → FullRefreshCoordinator(绕过 debounce)
        if (type == "message.part.updated") {
            BashCommandHandler.handleBashEvent(parsedMap, project.basePath)
            return
        }
        if (type != "session.diff" && type != "file.edited" && type != "file.watcher.updated") return
        if (project.basePath == null) {
            logger.warn("[OpenCodeSSEConsumer] project.basePath is null, skipping refresh")
            return
        }

        if (type == "session.diff") {
            try { FullRefreshCoordinator.request() }
            catch (e: Exception) { logger.error("[OpenCodeSSEConsumer] Failed to handle session.diff: ${e.message}", e) }
            return
        }
        FullRefreshCoordinator.request()
    }

    override fun onClosed() {
        connected = false
        val currentGen = activeConnectionGen
        val latestGen = connectionGen.get()
        if (currentGen != latestGen) {
            logger.debug("[OpenCodeSSEConsumer] SSE onClosed for stale connection (gen=$currentGen, latest=$latestGen), skipping cleanup")
            return
        }
        logger.info("[OpenCodeSSEConsumer] SSE onClosed() called (gen=$currentGen), connected set to false")
        SSEEventParser.clearCache()
    }

    override fun onComment(comment: String) {
        // BackgroundEventHandler 接口强制实现。
        // SSE 注释事件（以 : 开头的行）在此插件中无意义，故意留空。
        // 请勿删除此方法——没有它编译会失败，它是接口契约的一部分。
    }

    override fun onError(error: Throwable) {
        val wasConnected = connected
        connected = false
        // [Fix 启动时序 + 日志降噪] 快速通道判断用"之前是否连上过"状态,而不是异常类型:
        // - wasConnected=true + 任何 error(EOFException / StreamClosedByServerException / ConnectException 等)
        //   → server 之前在跑,现在挂了 → 立即调 onConnectionLost 跳过 15s debounce
        // - wasConnected=false + error → 从未连上(server 未启动/初次连接),不调 onConnectionLost
        // 这样无论是 web UI 干净关闭(StreamClosedByServerException)还是 kill -9(EOFException)都能覆盖。
        val ctxLog = "[OpenCodeSSEConsumer] projectBasePath=$projectBasePath uri=http://$OPENCODE_HOST:$OPENCODE_PORT/event?directory=${projectBasePath?.let { java.net.URLEncoder.encode(it, "UTF-8") }}"
        if (wasConnected) {
            // 第二参数传 error 让日志框架附加堆栈(诊断 SSE 连接问题根因)
            logger.info("$ctxLog SSE connection lost (was connected): ${error.javaClass.simpleName}: ${error.message}", error)
            onConnectionLost()
        } else {
            logger.warn("$ctxLog SSE error (never connected): ${error.javaClass.simpleName}: ${error.message}", error)
        }
    }
}
