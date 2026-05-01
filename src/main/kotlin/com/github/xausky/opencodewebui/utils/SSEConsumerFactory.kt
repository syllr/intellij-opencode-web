package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.listeners.OpenCodeSSEConsumer
import com.intellij.openapi.project.Project

/**
 * OpenCodeSSEConsumer 工厂。
 * 解耦 toolWindow 包对 listeners 包的直接构造依赖。
 */
object SSEConsumerFactory {
    fun create(project: Project): OpenCodeSSEConsumer {
        return OpenCodeSSEConsumer(project)
    }
}
