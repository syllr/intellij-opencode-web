package com.shenyuanlaolarou.opencodewebui.utils

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications
import com.shenyuanlaolarou.opencodewebui.LRU_MAX_ENTRIES
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object OpenCodeNotificationService {

    private const val NOTIFICATION_GROUP = "OpenCodeWeb.notifications"
    private const val NOTIFICATION_DEDUP_WINDOW_MS = 1000L
    private val logger = thisLogger()

    private val lastNotificationFired: Cache<Pair<String, String>, Long> = Caffeine.newBuilder()
        .maximumSize(LRU_MAX_ENTRIES)
        .expireAfterWrite(NOTIFICATION_DEDUP_WINDOW_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * SessionInfo LRU 缓存(1 hour TTL),消除通知路径上的同步 HTTP 调用。
     * 只缓存 timeCreated(不变)和 title(stale 1 hour 可接受),避免阻塞 SSE 事件线程。
     */
    private object SessionInfoCache {
        private const val TTL_MS = 3_600_000L
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
        val notificationEnabled = OpenCodeConfig.notificationEnabled
        if (!notificationEnabled) {
            return
        }
        val showProjectName = OpenCodeConfig.showProjectName
        val minDuration = OpenCodeConfig.minDuration
        val showSessionTitle = OpenCodeConfig.showSessionTitle

        val propertiesMap = properties?.get("properties") as? Map<*, *>

        val sessionID = extractSessionIDFromPropsMap(propertiesMap)

        // 1s Session 维度防抖：1s 内同 session + 同事件类型抑制
        if (sessionID != null && tryRecordAndCheckDedup(sessionID, eventType)) {
            return  // 1s 内同 session 同事件抑制
        }

        val tw = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")
        if (tw?.isVisible == true && tw.isActive) {
            return
        }

        // minDuration 过滤
        if (eventType == "complete" && minDuration > 0 && sessionID != null) {
            val info = SessionInfoCache.getOrFetch(sessionID)
            if (info != null) {
                val now = System.currentTimeMillis()
                val duration = if (info.timeCreated != null) (now - info.timeCreated) / 1000 else 0L
                if (duration < minDuration) {
                    return
                }
            }
        }

        val projectName = if (showProjectName)
            project.name.ifEmpty { null } else null

        val title = buildString {
            append("OpenCode")
            if (projectName != null) append(" ($projectName)")
        }

        val body = formatMessage(eventType, propertiesMap, project, showSessionTitle)

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

    internal fun addClickAction(notification: Notification, eventType: String, project: Project) {
        when (eventType) {
            "permission", "question" -> {
                notification.addAction(NotificationAction.createSimpleExpiring("打开") {
                    ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")?.activate(null)
                })
            }
            else -> { /* 无操作 */ }
        }
    }

    internal fun resolveType(eventType: String): NotificationType = when (eventType) {
        "permission", "question" -> NotificationType.WARNING
        else -> NotificationType.INFORMATION
    }

    internal fun formatMessage(
        eventType: String,
        propertiesMap: Map<*, *>?,
        project: Project,
        showSessionTitle: Boolean,
    ): String {
        val template = when (eventType) {
            "permission" -> "权限申请: {sessionTitle}"
            "complete" -> "回答完成: {sessionTitle}"
            "question" -> "询问用户: {sessionTitle}"
            else -> eventType
        }

        val needsSessionTitle = template.contains("{sessionTitle}")
        val needsAgentName = template.contains("{agentName}")
        val needsSessionInfo = needsSessionTitle || needsAgentName
        val sessionInfo = if (needsSessionInfo) lookupSessionInfo(propertiesMap, showSessionTitle) else null

        val params: Map<String, String?> = mapOf(
            "sessionTitle" to htmlEscape(sessionInfo?.title),
            "projectName" to project.name.ifEmpty { "未知项目" },
            "timestamp" to currentTimestampString(),
            "agentName" to if (needsAgentName) extractAgentName(sessionInfo?.title) else null,
        )
        return replacePlaceholders(template, params)
    }

    private fun currentTimestampString(): String {
        val now = java.time.LocalTime.now()
        return String.format("%02d:%02d:%02d", now.hour, now.minute, now.second)
    }

    internal fun replacePlaceholders(template: String, params: Map<String, String?>): String {
        val result = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            if (template[i] == '{' && i + 1 < template.length) {
                val end = template.indexOf('}', i + 1)
                if (end > i + 1) {
                    val key = template.substring(i + 1, end)
                    val value = params[key]
                    if (value != null) {
                        result.append(value)
                        i = end + 1
                        continue
                    }
                }
            }
            result.append(template[i])
            i++
        }
        return result.toString()
    }

    private fun lookupSessionInfo(propertiesMap: Map<*, *>?, showSessionTitle: Boolean): SessionInfo? {
        if (!showSessionTitle) return null
        val sid = extractSessionIDFromPropsMap(propertiesMap) ?: return null
        return SessionInfoCache.getOrFetch(sid)
    }

    private fun extractAgentName(title: String?): String? {
        if (title == null) return null
        return SUBAGENT_REGEX.find(title)?.groupValues?.get(1)
    }

    internal fun extractSessionID(properties: Map<*, *>?): String? {
        return extractSessionIDFromPropsMap(properties?.get("properties") as? Map<*, *>)
    }

    internal fun extractSessionIDFromPropsMap(props: Map<*, *>?): String? {
        val info = props?.get("info") as? Map<*, *>
        return props?.get("sessionID") as? String
            ?: info?.get("sessionID") as? String
            ?: info?.get("id") as? String
    }

    /**
     * 1s Session 维度防抖判定: 1s 内同 session + 同事件类型 → 抑制(true)
     * 测试可见: 提取为 internal 纯函数,便于 unit test
     */
    internal fun tryRecordAndCheckDedup(
        sessionID: String,
        eventType: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val key = sessionID to eventType
        val last = lastNotificationFired.getIfPresent(key)
        if (last != null && now - last < NOTIFICATION_DEDUP_WINDOW_MS) return true
        lastNotificationFired.put(key, now)
        return false
    }

    internal fun clearDedupForTesting() {
        lastNotificationFired.invalidateAll()
    }

    private val SUBAGENT_REGEX = Regex("""\s*\(@([^\s)]+)\s+subagent\)\s*$""")

    internal fun htmlEscape(s: String?): String? {
        if (s == null) return null
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
