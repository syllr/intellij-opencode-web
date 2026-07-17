package com.shenyuanlaolarou.opencodewebui.utils

import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_INITIAL_DELAY_MS
import com.shenyuanlaolarou.opencodewebui.HEALTH_CHECK_POLL_INTERVAL_MS
import com.shenyuanlaolarou.opencodewebui.HEALTH_VERIFY_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.HTTP_TIMEOUT_MS
import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SessionInfo(
    val id: String,
    val title: String?,
    val parentID: String?,
    val timeCreated: Long?
)

object OpenCodeApi {

    private fun healthCheckUrl(): String = "http://$OPENCODE_HOST:$OPENCODE_PORT/global/health"

    fun isServerPortOpen(timeoutMs: Int = 200): Boolean {
        val portOk = try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(OPENCODE_HOST, OPENCODE_PORT), timeoutMs)
                true
            }
        } catch (_: Exception) {
            return false
        }
        if (!portOk) return false
        return try {
            val conn = httpConn(healthCheckUrl(), "GET", connectTimeoutMs = HEALTH_VERIFY_TIMEOUT_MS, readTimeoutMs = HEALTH_VERIFY_TIMEOUT_MS)
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    fun getHealthDirectory(port: Int = OPENCODE_PORT): OpenCodeApiResult<String?> {
        val url = "http://$OPENCODE_HOST:$port/global/health"
        return try {
            val conn = httpConn(url, "GET", connectTimeoutMs = HEALTH_VERIFY_TIMEOUT_MS, readTimeoutMs = HEALTH_VERIFY_TIMEOUT_MS)
            if (conn.responseCode != 200) {
                return OpenCodeApiResult.Failure(conn.responseCode, conn.readBodySafe().take(200))
            }
            val obj = JsonParser.parseString(conn.readBodySafe()).asJsonObject
            OpenCodeApiResult.Success(obj.get("directory")?.asString)
        } catch (e: IOException) {
            OpenCodeApiResult.Unavailable
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeApi] getHealthDirectory failed: ${e.message}")
            OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_UNKNOWN_ERROR, e.message ?: "Unknown error")
        }
    }

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
        } catch (_: Exception) {
            return false
        }
        if (!portOk) return false
        return try {
            val conn = httpConn(healthCheckUrl(), "GET", connectTimeoutMs = HTTP_TIMEOUT_MS.toLong(), readTimeoutMs = HTTP_TIMEOUT_MS.toLong())
            conn.responseCode == 200
        } catch (_: Exception) {
            false
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

    private fun buildSessionsUrl(directory: String?): String = buildString {
        append("http://$OPENCODE_HOST:$OPENCODE_PORT/session")
        if (directory != null) {
            append("?directory=").append(URLEncoder.encode(directory, StandardCharsets.UTF_8))
        }
    }

    fun getSessions(directory: String? = null): OpenCodeApiResult<List<SessionInfo>> {
        val url = buildSessionsUrl(directory)
        return httpGet(url) { body ->
            val array = JsonParser.parseString(body).asJsonArray
            array.mapNotNull { element ->
                val obj = element.asJsonObject
                if (obj.has("parentID") && !obj.get("parentID").isJsonNull) return@mapNotNull null
                val title = obj.get("title")?.asString ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                if (title.startsWith("New session - ", ignoreCase = true)) return@mapNotNull null
                val timeObj = obj.getAsJsonObject("time")
                SessionInfo(
                    id = obj.get("id")?.asString ?: "",
                    title = title,
                    parentID = null,
                    timeCreated = timeObj?.get("created")?.asLong,
                )
            }
        }
    }

    fun createSession(directory: String): OpenCodeApiResult<String> {
        val url = buildString {
            append("http://$OPENCODE_HOST:$OPENCODE_PORT/session?directory=")
            append(URLEncoder.encode(directory, StandardCharsets.UTF_8))
        }
        return try {
            val conn = httpConn(
                url, "POST",
                body = "{}",
                contentType = "application/json",
                connectTimeoutMs = HTTP_TIMEOUT_MS.toLong(),
                readTimeoutMs = HTTP_TIMEOUT_MS.toLong()
            )
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.readBodySafe()
                val id = runCatching {
                    JsonParser.parseString(body).asJsonObject.get("id")?.asString
                }.getOrNull()
                if (id.isNullOrBlank()) {
                    OpenCodeApiResult.Failure(code, "createSession: response has no id (${body.take(200)})")
                } else {
                    OpenCodeApiResult.Success(id)
                }
            } else {
                OpenCodeApiResult.Failure(code, conn.readBodySafe().take(200))
            }
        } catch (e: IOException) {
            OpenCodeApiResult.Unavailable
        } catch (e: Exception) {
            thisLogger().warn("[OpenCodeApi] createSession failed: ${e.message}")
            OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_UNKNOWN_ERROR, e.message ?: "Unknown error")
        }
    }

    fun deleteSession(sessionID: String): OpenCodeApiResult<Unit> =
        httpDelete("http://$OPENCODE_HOST:$OPENCODE_PORT/session/$sessionID")

    /**
     * 不带显示过滤的 session 列表(不过滤掉 "New session -" 空 session),供清理/审计用。
     * 仍过滤掉顶层 parentID != null 的子 session 和 title 为空的 session(显示无意义)。
     */
    fun getRawSessions(directory: String? = null): OpenCodeApiResult<List<SessionInfo>> {
        val url = buildSessionsUrl(directory)
        return httpGet(url) { body ->
            val array = JsonParser.parseString(body).asJsonArray
            array.mapNotNull { element ->
                val obj = element.asJsonObject
                if (obj.has("parentID") && !obj.get("parentID").isJsonNull) return@mapNotNull null
                val title = obj.get("title")?.asString ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val timeObj = obj.getAsJsonObject("time")
                SessionInfo(
                    id = obj.get("id")?.asString ?: "",
                    title = title,
                    parentID = null,
                    timeCreated = timeObj?.get("created")?.asLong,
                )
            }
        }
    }

    private const val DISPOSE_TIMEOUT_MS = 2000L

    fun disposeServer(): OpenCodeApiResult<Unit> =
        httpPost("http://$OPENCODE_HOST:$OPENCODE_PORT/global/dispose", timeoutMs = DISPOSE_TIMEOUT_MS)

    private fun httpPost(url: String, timeoutMs: Long = HTTP_TIMEOUT_MS.toLong()): OpenCodeApiResult<Unit> =
        httpRequest("POST", url, timeoutMs, includeBodyOnFailure = false)

    private fun httpDelete(url: String, timeoutMs: Long = HTTP_TIMEOUT_MS.toLong()): OpenCodeApiResult<Unit> =
        httpRequest("DELETE", url, timeoutMs, includeBodyOnFailure = true)
}
