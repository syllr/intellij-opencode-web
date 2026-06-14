package com.shenyuanlaolarou.opencodewebui.utils

import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_INITIAL_DELAY_MS
import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_POLL_INTERVAL_MS
import com.shenyuanlaolarou.opencodewebui.HTTP_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class SessionInfo(
    val id: String,
    val title: String?,
    val parentID: String?,
    val timeCreated: Long?
)

object OpenCodeApi {
    private val sharedHttpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS.toLong()))
            .build()
    }

    private fun healthCheckUrl(): String = "http://$OPENCODE_HOST:$OPENCODE_PORT/global/health"

    /**
     * 纯 TCP 端口探测(200ms 超时),用于初始化时序:
     * 工具窗口打开时先快速判断 server 是否在监听,避免无意义创建 SSE consumer
     * 触发连接风暴。HTTP 探测在 isServerHealthySync() 里,代价 8s 太重。
     */
    fun isServerPortOpen(timeoutMs: Int = 200): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(OPENCODE_HOST, OPENCODE_PORT), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // 保留 Boolean 返回:health check 是 yes/no 二元判断,调用方只关心健康状态。
    // HTTP 检查失败仍降级为 true 的"反模式"(P0-1)按用户决策保留。
    fun isServerHealthySync(): Boolean {
        val portOk = try {
            val socket = java.net.Socket()
            try {
                socket.connect(java.net.InetSocketAddress(OPENCODE_HOST, OPENCODE_PORT), HTTP_TIMEOUT_MS)
                socket.soTimeout = HTTP_TIMEOUT_MS
                true
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            false
        }

        if (!portOk) return false

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(healthCheckUrl()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS.toLong()))
                .build()
            val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.discarding())
            response.statusCode() == 200
        } catch (e: Exception) {
            true
        }
    }

    fun waitForServerHealthy(timeoutMs: Long): Boolean {
        for (interval in pollIntervals(timeoutMs)) {
            Thread.sleep(interval)
            if (isServerHealthySync()) return true
        }
        return false
    }

    internal fun pollIntervals(
        timeoutMs: Long,
        initialDelay: Long = HEALTH_CHECK_INITIAL_DELAY_MS,
        pollInterval: Long = HEALTH_CHECK_POLL_INTERVAL_MS,
    ): List<Long> {
        if (timeoutMs <= 0) return emptyList()
        if (timeoutMs <= initialDelay) return listOf(timeoutMs)
        val result = mutableListOf(initialDelay)
        var remaining = timeoutMs - initialDelay
        while (remaining > 0) {
            val step = minOf(pollInterval, remaining)
            result.add(step)
            remaining -= step
        }
        return result
    }

    fun getSession(sessionID: String): OpenCodeApiResult<SessionInfo> {
        val url = "http://$OPENCODE_HOST:$OPENCODE_PORT/session/$sessionID"
        return httpGet(url) { body ->
            val obj = JsonParser.parseString(body).asJsonObject
            val time = obj.getAsJsonObject("time")
            SessionInfo(
                id = obj.get("id")?.asString ?: sessionID,
                title = obj.get("title")?.asString,
                parentID = obj.get("parentID")?.asString,
                timeCreated = time?.get("created")?.asLong,
            )
        }
    }

    private const val DISPOSE_TIMEOUT_MS = 2000L

    fun disposeServer(): OpenCodeApiResult<Unit> =
        httpPost("http://$OPENCODE_HOST:$OPENCODE_PORT/global/dispose", timeoutMs = DISPOSE_TIMEOUT_MS)

    // 统一 GET 入口:走 sharedHttpClient,自动 keep-alive;失败/超时映射为 Result 状态。
    private fun <T> httpGet(url: String, parse: (String) -> T): OpenCodeApiResult<T> {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS.toLong()))
                .GET()
                .build()
            val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                in 200..299 -> try {
                    OpenCodeApiResult.Success(parse(response.body()))
                } catch (e: Exception) {
                    thisLogger().warn("[OpenCodeApi] parse failed for $url: ${e.message}")
                    OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_PARSE_ERROR, "parse error: ${e.message}")
                }
                401 -> OpenCodeApiResult.Unauthorized
                else -> OpenCodeApiResult.Failure(response.statusCode(), response.body().take(200))
            }
        } catch (e: java.io.IOException) {
            // ConnectException / HttpTimeoutException / SocketException 等网络层错误
            OpenCodeApiResult.Unavailable
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeApi] HTTP GET failed: ${e.message}")
            OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_UNKNOWN_ERROR, e.message ?: "Unknown error")
        }
    }

    private fun httpPost(url: String, timeoutMs: Long = HTTP_TIMEOUT_MS.toLong()): OpenCodeApiResult<Unit> {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.discarding())
            when (response.statusCode()) {
                in 200..299 -> OpenCodeApiResult.Success(Unit)
                401 -> OpenCodeApiResult.Unauthorized
                else -> OpenCodeApiResult.Failure(response.statusCode(), "<body discarded>")
            }
        } catch (e: java.io.IOException) {
            OpenCodeApiResult.Unavailable
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeApi] HTTP POST failed: ${e.message}")
            OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_UNKNOWN_ERROR, e.message ?: "Unknown error")
        }
    }
}
