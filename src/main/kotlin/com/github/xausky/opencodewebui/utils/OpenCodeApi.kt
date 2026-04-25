package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.intellij.openapi.application.ApplicationManager
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
                socket.connect(java.net.InetSocketAddress(OPENCODE_HOST, OPENCODE_PORT), 8000)
                socket.soTimeout = 8000
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
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
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
        Thread.sleep(1000)

        val startTime = System.currentTimeMillis()
        val interval = 2000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val healthy = isServerHealthySync()
            if (healthy) {
                return true
            }
            Thread.sleep(interval)
        }

        return false
    }
}