package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OpenCodeApi {
    private const val HOST = "127.0.0.1"
    private const val PORT = 12396
    private const val CONNECT_TIMEOUT = 30000
    private const val READ_TIMEOUT = 30000
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L

    fun getLatestSessionId(projectPath: String, callback: (String?) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = fetchSessionIdWithRetry(projectPath)
            ApplicationManager.getApplication().invokeLater {
                callback(result)
            }
        }
    }

    fun checkHealth(callback: (HealthStatus) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = fetchHealthWithRetry()
            ApplicationManager.getApplication().invokeLater {
                callback(result)
            }
        }
    }

    data class HealthStatus(val healthy: Boolean, val version: String?)

    private fun fetchHealthWithRetry(): HealthStatus {
        repeat(MAX_RETRIES) { attempt ->
            val result = fetchHealth()
            if (result.healthy || attempt == MAX_RETRIES - 1) {
                return result
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
        return HealthStatus(false, null)
    }

    private fun fetchHealth(): HealthStatus {
        var connection: HttpURLConnection? = null
        return try {
            val url = "http://$HOST:$PORT/global/health"
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                    val healthy = response.contains("\"healthy\"") && !response.contains("\"healthy\": false")
                    HealthStatus(healthy, version)
                }
            } else {
                HealthStatus(false, null)
            }
        } catch (e: Exception) {
            thisLogger().warn("Health check failed: ${e.message}")
            HealthStatus(false, null)
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchSessionIdWithRetry(projectPath: String): String? {
        repeat(MAX_RETRIES) { attempt ->
            val result = fetchSessionId(projectPath)
            if (result != null || attempt == MAX_RETRIES - 1) {
                return result
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
        return null
    }

    private fun fetchSessionId(projectPath: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
            val url = "http://$HOST:$PORT/session?directory=$encodedPath"
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }.let { response ->
                    parseSessionId(response)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to get session from API: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseSessionId(json: String): String? {
        val pattern = Regex(""""id"\s*:\s*"([^"]+)"""")
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }

    fun checkAndPerformUpgrade(callback: (UpgradeResult) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = fetchUpgrade()
            ApplicationManager.getApplication().invokeLater {
                callback(result)
            }
        }
    }

    data class UpgradeResult(val success: Boolean, val newVersion: String?, val message: String?)

    private fun fetchUpgrade(): UpgradeResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = "http://$HOST:$PORT/global/upgrade"
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { os ->
                os.write("{}".toByteArray())
            }

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    val success = response.contains("\"success\": true") || response.contains("\"success\":true")
                    val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                    val error = Regex(""""error"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                    if (success) {
                        UpgradeResult(true, version, "Updated to version $version")
                    } else {
                        UpgradeResult(false, null, error ?: "Update failed")
                    }
                }
            } else {
                UpgradeResult(false, null, "HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            thisLogger().warn("Upgrade check failed: ${e.message}")
            UpgradeResult(false, null, e.message)
        } finally {
            connection?.disconnect()
        }
    }
}