package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications
import java.util.concurrent.ConcurrentHashMap

object OpenCodeNotificationService {

    private const val NOTIFICATION_GROUP = "OpenCodeWeb.notifications"
    private val logger = thisLogger()

    /**
     * SessionInfo LRU 缓存(30s TTL),消除通知路径上的同步 HTTP 调用。
     * 只缓存 timeCreated(不变)和 title(stale 30s 可接受),避免阻塞 SSE 事件线程。
     */
    private object SessionInfoCache {
        private const val TTL_MS = 30_000L
        private data class Entry(val info: SessionInfo, val cachedAt: Long)
        private val cache = ConcurrentHashMap<String, Entry>()

        fun get(sessionID: String): SessionInfo? {
            val entry = cache[sessionID] ?: return null
            if (System.currentTimeMillis() - entry.cachedAt > TTL_MS) {
                cache.remove(sessionID)
                return null
            }
            return entry.info
        }

        fun getOrFetch(sessionID: String): SessionInfo? {
            get(sessionID)?.let { return it }
            val info = OpenCodeApi.getSession(sessionID).dataOrNull() ?: return null
            cache[sessionID] = Entry(info, System.currentTimeMillis())
            return info
        }

        fun clear() = cache.clear()
    }

    fun send(eventType: String, properties: Map<*, *>?, project: Project) {
        if (!OpenCodeConfig.notificationEnabled) return
        if (!OpenCodeConfig.isEventEnabled(eventType)) return

        val tw = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")
        if (tw?.isVisible == true && tw.isActive) return

        // minDuration 过滤
        if ((eventType == "complete" || eventType == "subagent_complete") && OpenCodeConfig.minDuration > 0) {
            val sessionID = extractSessionID(properties)
            if (sessionID != null) {
                val info = SessionInfoCache.getOrFetch(sessionID)
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
        return SessionInfoCache.getOrFetch(sessionID)?.title
    }

    private val SUBAGENT_REGEX = Regex("""\s*\(@([^\s)]+)\s+subagent\)\s*$""")

    private fun lookupAgentName(properties: Map<*, *>?): String? {
        val sessionID = extractSessionID(properties) ?: return null
        try {
            val info = SessionInfoCache.getOrFetch(sessionID)
            if (info != null && info.title != null) {
                val match = SUBAGENT_REGEX.find(info.title)
                if (match != null) return match.groupValues[1]
            }
        } catch (e: Exception) {
            logger.debug("[OpenCodeNotificationService] Agent name lookup failed: ${e.message}")
        }
        return null
    }
}
