package com.shenyuanlaolarou.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger

object BashCommandHandler {

    fun handleBashEvent(parsedMap: Map<*, *>?, projectDir: String?): Boolean {
        val logger = thisLogger()

        try {
            val props = parsedMap?.get("properties") as? Map<*, *>
            val part = props?.get("part") as? Map<*, *>
            val partType = part?.get("type") as? String
            val toolName = part?.get("tool") as? String
            val state = part?.get("state") as? Map<*, *>
            val status = state?.get("status") as? String

            if (partType == "tool" && toolName == "bash" && status == "completed") {
                if (logger.isDebugEnabled) {
                    logger.debug("[BashCommandHandler] Bash completed, request refresh")
                }
                FullRefreshCoordinator.request()
                return true
            }
        } catch (e: Exception) {
            logger.warn("[BashCommandHandler] Failed to parse bash event: ${e.message}")
        }
        return false
    }
}

