package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object OpenCodeNotificationRouter {

    private val projectRegistry = ConcurrentHashMap<String, Project>()
    private val logger = thisLogger()

    fun register(project: Project) {
        val path = normalize(project.basePath) ?: return
        projectRegistry[path] = project
        logger.debug("[OpenCodeNotificationRouter] Registered project: ${project.name} ($path)")
    }

    fun unregister(project: Project) {
        val path = normalize(project.basePath)
        if (path != null) {
            projectRegistry.remove(path)
            logger.debug("[OpenCodeNotificationRouter] Unregistered project: ${project.name} ($path)")
        }
    }

    fun notify(eventType: String, properties: Map<*, *>?, eventDir: String?) {
        val dir = normalize(eventDir) ?: return
        val project = projectRegistry[dir] ?: return
        OpenCodeNotificationService.send(eventType, properties, project)
    }

    private fun normalize(path: String?): String? {
        if (path == null) return null
        return try {
            File(path).canonicalPath
        } catch (e: Exception) {
            logger.warn("[OpenCodeNotificationRouter] Failed to canonicalize path: $path")
            path
        }
    }
}
