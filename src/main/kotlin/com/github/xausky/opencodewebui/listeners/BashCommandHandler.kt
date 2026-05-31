package com.github.xausky.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger

object BashCommandHandler {

    @Suppress("UNCHECKED_CAST")
    fun handleBashEvent(parsedMap: Map<*, *>?, projectDir: String?): Boolean {
        val logger = thisLogger()

        try {
            val payload = parsedMap?.get("payload") as? Map<*, *>
            val props = payload?.get("properties") as? Map<*, *>
            val part = props?.get("part") as? Map<*, *>
            val partType = part?.get("type") as? String
            val toolName = part?.get("tool") as? String
            val state = part?.get("state") as? Map<*, *>
            val status = state?.get("status") as? String

            if (partType == "tool" && toolName == "bash" && status == "completed") {
                val input = state?.get("input") as? Map<*, *>
                val command = input?.get("command") as? String ?: ""

                if (command.isNotBlank()) {
                    val exitCode = try {
                        val metadata = state?.get("metadata") as? Map<*, *>
                        (metadata?.get("exit") as? Double)?.toInt() ?: -1
                    } catch (e: Exception) { -1 }

                    if (exitCode == 0 && projectDir != null) {
                        val segments = command.split(Regex("&&|;|\n"))
                        val allReadOnly = segments.all { segment ->
                            val base = segment.trimStart().split(WHITESPACE_REGEX).firstOrNull()?.trim() ?: ""
                            base.isEmpty() || base in READ_ONLY_COMMANDS
                        }

                        if (!allReadOnly) {
                            logger.debug("[BashCommandHandler] Bash requires refresh: '${command.take(100)}'")
                            FullRefreshCoordinator.request()
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("[BashCommandHandler] Failed to parse bash event: ${e.message}")
        }
        return false
    }

    private val WHITESPACE_REGEX = "\\s+".toRegex()

    val READ_ONLY_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "less", "more", "tree", "stat", "file", "du", "df",
        "grep", "find", "wc", "sort", "uniq", "cut", "diff", "awk", "sed",
        "pwd", "which", "type", "echo", "printf", "env", "printenv",
        "whoami", "id", "date", "uname", "hostname", "uptime",
        "ps", "pgrep", "pidof", "lsof",
        "basename", "dirname", "realpath", "readlink",
        "true", "false", "sleep",
    )
}
