package com.github.xausky.opencodewebui.utils

import com.intellij.ide.util.PropertiesComponent

object OpenCodeConfig {

    private fun props() = PropertiesComponent.getInstance()

    var notificationEnabled: Boolean
        get() = props().getBoolean("opencode.settings.notification", true)
        set(value) = props().setValue("opencode.settings.notification", value)

    var showProjectName: Boolean
        get() = props().getBoolean("opencode.settings.showProjectName", true)
        set(value) = props().setValue("opencode.settings.showProjectName", value)

    var showSessionTitle: Boolean
        get() = props().getBoolean("opencode.settings.showSessionTitle", true)
        set(value) = props().setValue("opencode.settings.showSessionTitle", value)

    var minDuration: Int
        get() = props().getInt("opencode.settings.minDuration", 0)
        set(value) = props().setValue("opencode.settings.minDuration", value.toString())

    fun isEventEnabled(eventType: String): Boolean {
        return props().getBoolean("opencode.event.$eventType.enabled", defaultEvents()[eventType] ?: false)
    }

    fun setEventEnabled(eventType: String, enabled: Boolean) {
        props().setValue("opencode.event.$eventType.enabled", enabled)
    }

    fun getMessageTemplate(eventType: String): String {
        return props().getValue("opencode.message.$eventType") ?: defaultMessages()[eventType] ?: eventType
    }

    fun setMessageTemplate(eventType: String, template: String) {
        props().setValue("opencode.message.$eventType", template)
    }
}

val ALL_EVENT_TYPES = listOf(
    "permission", "complete", "subagent_complete", "error", "question",
    "interrupted", "user_cancelled", "plan_exit", "session_started",
    "user_message", "client_connected"
)

fun defaultEvents(): Map<String, Boolean> = mapOf(
    "permission" to true,
    "complete" to true,
    "error" to true,
    "client_connected" to false,
    "subagent_complete" to false,
    "question" to true,
    "interrupted" to false,
    "user_cancelled" to false,
    "plan_exit" to true,
    "session_started" to false,
    "user_message" to false,
)

fun defaultMessages(): Map<String, String> = mapOf(
    "permission" to "权限申请: {sessionTitle}",
    "complete" to "回答完成: {sessionTitle}",
    "subagent_complete" to "Subagent 任务完成: {sessionTitle}",
    "error" to "执行错误: {sessionTitle}",
    "question" to "询问用户: {sessionTitle}",
    "interrupted" to "会话中断: {sessionTitle}",
    "user_cancelled" to "会话取消: {sessionTitle}",
    "plan_exit" to "Plan 制定完成: {sessionTitle}",
    "session_started" to "新会话: {sessionTitle}",
    "user_message" to "用户已发送消息",
    "client_connected" to "OpenCode 已连接",
)
