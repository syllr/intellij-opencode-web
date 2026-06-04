package com.shenyuanlaolarou.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger

object BashCommandHandler {

    @Suppress("UNCHECKED_CAST")
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
                val input = state.get("input") as? Map<*, *>
                val command = input?.get("command") as? String ?: ""

                if (command.isNotBlank()) {
                    val exitCode = try {
                        val metadata = state.get("metadata") as? Map<*, *>
                        (metadata?.get("exit") as? Double)?.toInt() ?: -1
                    } catch (e: Exception) { -1 }

                    if (exitCode == 0 && projectDir != null) {
                        // bash 语法切分(非 JSON 解析,不违反 AGENTS.md Regex 规则)。
                        // 边界 case:引号内的 &&/|/; 会被误切,但方向保守(假阳性触发刷新是幂等的)。
                        val segments = command.split(BASH_SPLIT_REGEX)
                        val allReadOnly = segments.all { segment ->
                            val base = segment.trimStart().split(WHITESPACE_REGEX).firstOrNull()?.trim() ?: ""
                            base.isEmpty() || base in READ_ONLY_COMMANDS
                        }

                        if (!allReadOnly) {
                            if (logger.isDebugEnabled) {
                                logger.debug("[BashCommandHandler] Bash requires refresh: '${command.take(100)}'")
                            }
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

    // bash 操作符切分(非 JSON 解析):静态化避免每事件 Pattern.compile。
    private val BASH_SPLIT_REGEX = Regex("&&|;|\n|\\|")
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    val READ_ONLY_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "less", "more", "tree", "stat", "file", "du", "df",
        "grep", "find", "wc", "sort", "uniq", "cut", "diff", "awk",
        "pwd", "which", "type", "echo", "printf", "env", "printenv",
        "whoami", "id", "date", "uname", "hostname", "uptime",
        "ps", "pgrep", "pidof", "lsof",
        "basename", "dirname", "realpath", "readlink",
        "true", "false", "sleep",
    )
}

