package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.HEALTH_CHECK_INITIAL_DELAY_MS
import com.github.xausky.opencodewebui.HEALTH_CHECK_POLL_INTERVAL_MS
import com.github.xausky.opencodewebui.HTTP_TIMEOUT_MS
import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
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
    // [O5] 共享 HttpClient 实例，复用 TCP 连接（keep-alive），避免每次健康检查都新建连接
    private val sharedHttpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS.toLong()))
            .build()
    }

    private val healthCheckUrl = "http://$OPENCODE_HOST:$OPENCODE_PORT/global/health"

    /**
     * 同步健康检查（保持向后兼容）。
     * 内部使用共享 HttpClient 复用连接，替代原来每次新建 HttpURLConnection 的方式。
     */
    fun isServerHealthySync(): Boolean {
        // 先检查端口连通性（比 HTTP 请求更轻量）
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

        if (!portOk) {
            return false
        }

        // 端口正常，使用共享 HttpClient 做 HEAD 请求（比 GET 更轻量）
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(healthCheckUrl))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS.toLong()))
                .build()
            val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.discarding())
            response.statusCode() == 200
        } catch (e: Exception) {
            // HTTP 检查失败但端口正常，仍认为健康（可能只是 /global/health 有问题）
            true
        }
    }

    fun waitForServerHealthy(timeoutMs: Long): Boolean {
        Thread.sleep(HEALTH_CHECK_INITIAL_DELAY_MS)

        val startTime = System.currentTimeMillis()
        val interval = HEALTH_CHECK_POLL_INTERVAL_MS

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val healthy = isServerHealthySync()
            if (healthy) {
                return true
            }
            Thread.sleep(interval)
        }

        return false
    }

    /**
     * 获取指定 session 的详细信息（title、parentID 等）。
     * 调用 GET /session/:sessionID 获取单个 session 的信息。
     */
    fun getSession(sessionID: String): SessionInfo? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/session/$sessionID").toURL()
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            val body = conn.inputStream.bufferedReader().readText()
            val obj = JsonParser.parseString(body).asJsonObject
            val time = obj.getAsJsonObject("time")
            SessionInfo(
                id = obj.get("id")?.asString ?: sessionID,
                title = obj.get("title")?.asString,
                parentID = obj.get("parentID")?.asString,
                timeCreated = time?.get("created")?.asLong
            )
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeApi] Failed to get session $sessionID: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 获取指定项目目录的最新 session ID。
     * 调用 GET /session?directory=xxx 获取该项目的 session 列表（服务端按 time_updated DESC 排序），
     * 取最新的 session ID。可用于构建带 session 的 URL 以复用已有 session。
     */
    fun getLatestSessionId(directory: String): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val encodedDir = java.net.URLEncoder.encode(directory, "UTF-8")
            val url = java.net.URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/session?directory=$encodedDir").toURL()
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            val body = conn.inputStream.bufferedReader().readText()
            val array = com.google.gson.JsonParser.parseString(body).asJsonArray

            // 记录第一个遇到的空 session 作为 fallback
            var firstEmptyId: String? = null

            for (i in 0 until array.size()) {
                val session = array[i].asJsonObject
                val time = session.getAsJsonObject("time")
                if (time == null) continue
                if (time.has("archived")) continue
                val created = time.get("created")?.asLong
                val updated = time.get("updated")?.asLong
                if (created != null && created == updated) {
                    // 空 session：记录为 fallback，但不立即返回
                    if (firstEmptyId == null) {
                        firstEmptyId = session.get("id")?.asString
                    }
                    continue
                }
                // 找到有内容的 session，直接返回
                val id = session.get("id")?.asString
                if (id != null) return id
            }

            // 没有找到有内容的 session，返回最近的空 session（避免前端创建新的）
            if (firstEmptyId != null) {
                thisLogger().info("[OpenCodeApi] No active session with content for $directory, returning empty session: $firstEmptyId")
                return firstEmptyId
            }

            thisLogger().info("[OpenCodeApi] No active session for $directory")
            null
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeApi] Failed to get latest session for $directory: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}