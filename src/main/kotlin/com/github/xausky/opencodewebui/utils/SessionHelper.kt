package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

object SessionHelper {
    private const val HOST = "127.0.0.1"
    private const val PORT = 12396

    fun getLatestSessionId(projectPath: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
            val url = URI.create("http://$HOST:$PORT/session?directory=$encodedPath").toURL()
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    Regex(""""id"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to query OpenCode session via HTTP: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
