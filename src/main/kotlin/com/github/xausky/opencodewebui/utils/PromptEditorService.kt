package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.intellij.openapi.diagnostic.thisLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Prompt 编辑器服务
 * 封装 HTTP 通信和会话管理
 */
object PromptEditorService {

    private val gson = Gson()

    /**
     * 会话数据类
     */
    data class Session(
        val id: String,
        val directory: String,
        val name: String,
        val createdAt: String? = null,
        val archivedAt: String? = null
    ) {
        val isArchived: Boolean = archivedAt != null
        override fun toString(): String = name
    }

    /**
     * 根据目录获取活跃会话列表
     * @param directory 项目目录
     * @return 活跃会话列表（排除已归档的）
     */
    fun getSessions(directory: String): List<Session> {
        var connection: HttpURLConnection? = null
        return try {
            val encodedPath = URLEncoder.encode(directory, "UTF-8")
            val url = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/session?directory=$encodedPath").toURL()
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            val responseCode = connection.responseCode
            thisLogger().info("[PromptEditorService] 请求会话列表: URL=$url, HTTP=$responseCode")

            if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    thisLogger().info("[PromptEditorService] 响应内容: $response")
                    val sessions = parseSessions(response)
                    thisLogger().info("[PromptEditorService] 解析出 ${sessions.size} 个会话")
                    sessions
                }
            } else {
                thisLogger().warn("[PromptEditorService] 获取会话失败: HTTP $responseCode")
                emptyList()
            }
        } catch (e: Exception) {
            thisLogger().warn("[PromptEditorService] 获取会话异常: ${e.message}")
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 创建新会话
     * @param directory 项目目录
     * @return 新创建的 Session，失败返回 null
     */
    fun createSession(directory: String): Session? {
        var connection: HttpURLConnection? = null
        return try {
            val encodedPath = URLEncoder.encode(directory, "UTF-8")
            val url = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/session?directory=$encodedPath").toURL()
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "POST"

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    parseSessions(response).firstOrNull()
                }
            } else {
                thisLogger().warn("[PromptEditorService] 创建会话失败: HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            thisLogger().warn("[PromptEditorService] 创建会话异常: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 发送消息到会话
     * @param sessionId 会话 ID
     * @param text 消息文本
     * @return 是否发送成功
     */
    fun sendMessage(sessionId: String, text: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/session/$sessionId/prompt_async").toURL()
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
            val requestBody = """{"parts": [{"type": "text", "text": "$escapedText"}]}"""

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 204) {
                thisLogger().info("[PromptEditorService] 消息发送成功 (promptAsync)")
                true
            } else {
                connection.errorStream?.bufferedReader()?.use { reader ->
                    val error = reader.readText()
                    thisLogger().warn("[PromptEditorService] 发送消息失败: HTTP $responseCode, $error")
                }
                false
            }
        } catch (e: Exception) {
            thisLogger().warn("[PromptEditorService] 发送消息异常: ${e.message}", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 获取或创建会话
     * @param projectPath 项目路径
     * @return 会话 ID，失败返回 null
     */
    fun getOrCreateSession(projectPath: String): String? {
        val existingSessions = getSessions(projectPath)
        if (existingSessions.isNotEmpty()) {
            thisLogger().info("[PromptEditorService] 找到已有会话: ${existingSessions.first().id}")
            return existingSessions.first().id
        }

        thisLogger().info("[PromptEditorService] 未找到会话，创建新会话")
        return createSession(projectPath)?.id
    }

    /**
     * 解析会话列表响应
     */
    private fun parseSessions(response: String): List<Session> {
        return try {
            val type = object : TypeToken<List<SessionResponse>>() {}.type
            val sessions: List<SessionResponse> = gson.fromJson(response, type)
            sessions.map { it.toSession() }
        } catch (e: Exception) {
            thisLogger().warn("[PromptEditorService] 解析会话响应失败: ${e.message}")
            emptyList()
        }
    }

    private data class SessionResponse(
        val id: String,
        val directory: String,
        val title: String? = null,
        val name: String? = null,
        val time: TimeData
    ) {
        data class TimeData(
            val created: Long,
            val archived: Long? = null
        )

        fun toSession() = Session(
            id = id,
            directory = directory,
            name = title ?: name ?: "Session ${id.take(8)}",
            createdAt = time.created.toString(),
            archivedAt = time.archived?.toString()
        )
    }
}