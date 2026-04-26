package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.intellij.openapi.diagnostic.thisLogger
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Prompt 编辑器服务
 * 封装 HTTP 通信和会话管理
 */
object PromptEditorService {

    /**
     * 会话数据类
     */
    data class Session(
        val id: String,
        val directory: String,
        val createdAt: String? = null,
        val archivedAt: String? = null
    ) {
        val isArchived: Boolean = archivedAt != null
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

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    parseSessions(response).filter { !it.isArchived }
                }
            } else {
                thisLogger().warn("[PromptEditorService] 获取会话失败: HTTP ${connection.responseCode}")
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
            val url = URI.create("http://$OPENCODE_HOST:$OPENCODE_PORT/session/$sessionId/message").toURL()
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val requestBody = """{"parts": [{"type": "text", "text": "$text"}]}"""

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                true
            } else {
                thisLogger().warn("[PromptEditorService] 发送消息失败: HTTP $responseCode")
                false
            }
        } catch (e: Exception) {
            thisLogger().warn("[PromptEditorService] 发送消息异常: ${e.message}")
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
            val sessionRegex = Regex("""\{"id"\s*:\s*"([^"]+)",\s*"directory"\s*:\s*"([^"]*)",\s*"time"\s*:\s*\{([^}]*)\}\}""")
            sessionRegex.findAll(response).map { match ->
                val id = match.groupValues[1]
                val directory = match.groupValues[2]
                val timeContent = match.groupValues[3]

                val createdRegex = Regex(""""created"\s*:\s*"([^"]*)"""").find(timeContent)
                val archivedRegex = Regex(""""archived"\s*:\s*"([^"]*)"""").find(timeContent)

                Session(
                    id = id,
                    directory = directory,
                    createdAt = createdRegex?.groupValues?.get(1),
                    archivedAt = archivedRegex?.groupValues?.get(1)
                )
            }.toList()
        } catch (e: Exception) {
            thisLogger().warn("[PromptEditorService] 解析会话响应失败: ${e.message}")
            emptyList()
        }
    }
}