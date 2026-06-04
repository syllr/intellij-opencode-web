package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.openapi.project.Project

object OpenCodeNotificationRouter {

    fun notify(eventType: String, properties: Map<*, *>?, project: Project) {
        OpenCodeNotificationService.send(eventType, properties, project)
    }
}
