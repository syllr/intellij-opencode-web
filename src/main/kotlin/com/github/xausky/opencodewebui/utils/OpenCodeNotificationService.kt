package com.github.xausky.opencodewebui.utils

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications

object OpenCodeNotificationService {

    private const val NOTIFICATION_GROUP = "OpenCodeWeb.notifications"
    private val logger = thisLogger()

    fun send(eventType: String, properties: Map<*, *>?, project: Project) {
        if (!OpenCodeConfig.notificationEnabled) return
        if (!OpenCodeConfig.isEventEnabled(eventType)) return

        val tw = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")
        if (tw?.isVisible == true && tw.isActive) return

        // minDuration 过滤
        if ((eventType == "complete" || eventType == "subagent_complete") && OpenCodeConfig.minDuration > 0) {
            val sessionID = extractSessionID(properties)
            if (sessionID != null) {
                val info = OpenCodeApi.getSession(sessionID)
                if (info != null) {
                    val now = System.currentTimeMillis()
                    val duration = if (info.timeCreated != null) (now - info.timeCreated) / 1000 else 0L
                    if (duration < OpenCodeConfig.minDuration) return
                }
            }
        }

        val projectName = if (OpenCodeConfig.showProjectName)
            project.name.ifEmpty { null } else null

        val title = buildString {
            append("OpenCode")
            if (projectName != null) append(" ($projectName)")
        }

        val body = formatMessage(eventType, properties, project)

        val frame = WindowManager.getInstance().getFrame(project)
        val projectWindowActive = frame != null && frame.isActive

        ApplicationManager.getApplication().invokeLater {
            try {
                if (projectWindowActive && !project.isDisposed) {
                    val notification = Notification(NOTIFICATION_GROUP, title, body, resolveType(eventType))
                    addClickAction(notification, eventType, project)
                    notification.notify(project)
                } else if (!ApplicationManager.getApplication().isActive()) {
                    SystemNotifications.getInstance().notify(NOTIFICATION_GROUP, title, body)
                }
            } catch (e: Exception) {
                logger.warn("[OpenCodeNotificationService] Failed to send notification: ${e.message}")
            }
        }
    }

    private fun addClickAction(notification: Notification, eventType: String, project: Project) {
        when (eventType) {
            "permission", "question", "error" -> {
                notification.addAction(NotificationAction.createSimpleExpiring("打开") {
                    ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")?.activate(null)
                })
            }
            else -> { /* 无操作 */ }
        }
    }

    private fun resolveType(eventType: String): NotificationType = when (eventType) {
        "error", "user_cancelled" -> NotificationType.ERROR
        "permission", "question" -> NotificationType.WARNING
        else -> NotificationType.INFORMATION
    }

    private fun formatMessage(eventType: String, properties: Map<*, *>?, project: Project): String {
        val template = OpenCodeConfig.getMessageTemplate(eventType)
        var result = template

        if (result.contains("{sessionTitle}")) {
            val sessionTitle = lookupSessionTitle(properties)
            result = result.replace("{sessionTitle}", sessionTitle ?: "")
        }
        if (result.contains("{projectName}")) {
            result = result.replace("{projectName}", project.name.ifEmpty { "未知项目" })
        }
        if (result.contains("{timestamp}")) {
            val now = java.time.LocalTime.now()
            val ts = String.format("%02d:%02d:%02d", now.hour, now.minute, now.second)
            result = result.replace("{timestamp}", ts)
        }
        if (result.contains("{agentName}")) {
            val agentName = lookupAgentName(properties)
            result = result.replace("{agentName}", agentName ?: "")
        }
        return result
    }

    private fun extractSessionID(properties: Map<*, *>?): String? {
        val payload = properties?.get("payload") as? Map<*, *>
        val props = payload?.get("properties") as? Map<*, *>
        return props?.get("sessionID") as? String
    }

    private fun lookupSessionTitle(properties: Map<*, *>?): String? {
        if (!OpenCodeConfig.showSessionTitle) return null
        val sessionID = extractSessionID(properties) ?: return null
        // 重试 1 次
        for (attempt in 0..1) {
            try {
                val info = OpenCodeApi.getSession(sessionID)
                if (info != null) return info.title
            } catch (_: Exception) {
                if (attempt < 1) Thread.sleep(200L)
            }
        }
        return null
    }

    private fun lookupAgentName(properties: Map<*, *>?): String? {
        val sessionID = extractSessionID(properties) ?: return null
        try {
            val info = OpenCodeApi.getSession(sessionID)
            if (info != null && info.title != null) {
                val match = Regex("""\s*\(@([^\s)]+)\s+subagent\)\s*$""").find(info.title!!)
                if (match != null) return match.groupValues[1]
            }
        } catch (_: Exception) { }
        return null
    }
}
