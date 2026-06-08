package com.shenyuanlaolarou.opencodewebui.utils

import com.shenyuanlaolarou.opencodewebui.listeners.OpenCodeSSEConsumer
import com.intellij.openapi.project.Project

/**
 * OpenCodeSSEConsumer 工厂。
 * 解耦 toolWindow 包对 listeners 包的直接构造依赖。
 */
object SSEConsumerFactory {
    fun create(
        project: Project,
        onConnectionLost: () -> Unit = {},
        onConnectionEstablished: () -> Unit = {},
    ): OpenCodeSSEConsumer {
        return OpenCodeSSEConsumer(project, onConnectionLost, onConnectionEstablished)
    }
}
