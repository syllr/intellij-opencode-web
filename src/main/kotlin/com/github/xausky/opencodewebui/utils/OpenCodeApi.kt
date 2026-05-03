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

object OpenCodeApi {
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

        // 端口正常，再尝试 HTTP 健康检查
        var connection: HttpURLConnection? = null
        return try {
            val url = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/global/health").toURL()
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            val responseCode = connection.responseCode
            responseCode == 200
        } catch (e: Exception) {
            // HTTP 检查失败但端口正常，仍认为健康（可能只是 /global/health 有问题）
            true
        } finally {
            connection?.disconnect()
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
            for (i in 0 until array.size()) {
                val session = array[i].asJsonObject
                val time = session.getAsJsonObject("time")
                if (time == null) continue
                if (time.has("archived")) continue
                if (time.get("created") == time.get("updated")) continue
                val id = session.get("id")?.asString
                if (id != null) return id
            }
            thisLogger().info("[OpenCodeApi] No active session with content for $directory")
            null
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeApi] Failed to get latest session for $directory: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}