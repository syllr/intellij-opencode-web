package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.openapi.diagnostic.Logger
import com.shenyuanlaolarou.opencodewebui.HTTP_TIMEOUT_MS
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private val httpHelperLog = Logger.getInstance("com.shenyuanlaolarou.opencodewebui.utils.HttpHelper")

internal fun <T> httpGet(url: String, parse: (String) -> T): OpenCodeApiResult<T> {
    return try {
        val conn = httpConn(url, "GET", connectTimeoutMs = HTTP_TIMEOUT_MS.toLong(), readTimeoutMs = HTTP_TIMEOUT_MS.toLong())
        when (val code = conn.responseCode) {
            in 200..299 -> try {
                OpenCodeApiResult.Success(parse(conn.readBodySafe()))
            } catch (e: Exception) {
                httpHelperLog.warn("[OpenCodeApi] parse failed for $url: ${e.message}")
                OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_PARSE_ERROR, "parse error: ${e.message}")
            }
            401 -> OpenCodeApiResult.Unauthorized
            else -> OpenCodeApiResult.Failure(code, conn.readBodySafe().take(200))
        }
    } catch (e: IOException) {
        OpenCodeApiResult.Unavailable
    } catch (e: Exception) {
        httpHelperLog.warn("[OpenCodeApi] HTTP GET failed: ${e.message}")
        OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_UNKNOWN_ERROR, e.message ?: "Unknown error")
    }
}

internal fun httpRequest(
    method: String,
    url: String,
    timeoutMs: Long = HTTP_TIMEOUT_MS.toLong(),
    includeBodyOnFailure: Boolean = true,
): OpenCodeApiResult<Unit> {
    return try {
        val conn = httpConn(url, method, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs)
        when (val code = conn.responseCode) {
            in 200..299 -> OpenCodeApiResult.Success(Unit)
            401 -> OpenCodeApiResult.Unauthorized
            else -> OpenCodeApiResult.Failure(
                code,
                if (includeBodyOnFailure) conn.readBodySafe().take(200) else "<body discarded>"
            )
        }
    } catch (e: IOException) {
        OpenCodeApiResult.Unavailable
    } catch (e: Exception) {
        httpHelperLog.warn("[OpenCodeApi] HTTP $method failed: ${e.message}")
        OpenCodeApiResult.Failure(OpenCodeApiResult.CODE_UNKNOWN_ERROR, e.message ?: "Unknown error")
    }
}

internal fun httpConn(
    url: String,
    method: String,
    body: String? = null,
    contentType: String? = null,
    connectTimeoutMs: Long,
    readTimeoutMs: Long,
): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = connectTimeoutMs.toInt()
    conn.readTimeout = readTimeoutMs.toInt()
    if (body != null) {
        conn.doOutput = true
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
    }
    return conn
}

internal fun HttpURLConnection.readBodySafe(): String {
    return try {
        inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    } catch (_: Exception) {
        try {
            errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
