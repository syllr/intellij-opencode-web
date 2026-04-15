package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OpenCodeApi {
    private const val HOST = "127.0.0.1"
    private const val PORT = 12396

    fun getLatestSessionId(projectPath: String, callback: (String?) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = fetchSessionId(projectPath)
            ApplicationManager.getApplication().invokeLater {
                callback(result)
            }
        }
    }

    private fun fetchSessionId(projectPath: String): String? {
        return try {
            val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
            val url = "http://$HOST:$PORT/session?directory=$encodedPath"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                parseSessionId(response)
            } else {
                null
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to get session from API: ${e.message}")
            null
        }
    }

    private fun parseSessionId(json: String): String? {
        val pattern = Regex(""""id"\s*:\s*"([^"]+)"""")
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }
}