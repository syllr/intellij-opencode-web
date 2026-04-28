package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger

object SessionHelper {

    fun getLatestSessionId(projectPath: String): String? {
        return try {
            val sessions = PromptEditorService.getSessions(projectPath)
            val latest = sessions.firstOrNull()
            thisLogger().info("[SessionHelper] 获取到最新会话: ${latest?.id}")
            latest?.id
        } catch (e: Exception) {
            thisLogger().warn("[SessionHelper] 获取会话失败: ${e.message}")
            null
        }
    }
}
