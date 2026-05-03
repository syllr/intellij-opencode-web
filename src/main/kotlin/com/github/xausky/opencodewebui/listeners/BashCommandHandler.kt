package com.github.xausky.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger

object BashCommandHandler {

    /**
     * 处理 bash 工具的 SSE 事件。
     * @param parsedMap 从 SSE JSON 解析的顶层 map
     * @param projectDir 当前项目的 basePath
     * @return true 如果触发了文件刷新，否则 false
     */
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

            if (partType == "tool" && toolName == "bash" && (status == "completed" || status == "running")) {
                val input = state?.get("input") as? Map<*, *>
                val command = input?.get("command") as? String ?: ""

                if (command.isNotBlank()) {
                    logger.debug("[BashCommandHandler] Bash tool event: status=$status, command='${command.take(200)}'")

                    if (status == "completed") {
                        val exitCode = try {
                            val metadata = state?.get("metadata") as? Map<*, *>
                            (metadata?.get("exit") as? Double)?.toInt() ?: -1
                        } catch (e: Exception) {
                            logger.debug("[BashCommandHandler] Failed to parse exit code: ${e.message}")
                            -1
                        }

                        if (exitCode == 0 && projectDir != null) {
                            val projectPath = projectDir
                            val segments = command.split(Regex("&&|;|\n"))
                            val allReadOnly = segments.all { segment ->
                                val base = extractBaseBashCommand(segment)
                                base.isEmpty() || base in READ_ONLY_COMMANDS
                            }

                            if (allReadOnly) {
                                logger.debug("[BashCommandHandler] All bash segments are read-only, skipping refresh")
                            } else {
                                logger.debug("[BashCommandHandler] Bash has non-read-only segments, requesting refresh")
                                FullRefreshCoordinator.request()
                                return true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("[BashCommandHandler] Failed to parse bash tool event: ${e.message}")
        }
        return false
    }

    private fun extractBaseBashCommand(cmd: String): String {
        val skipPrefixes = listOf("sudo", "command", "time", "nohup")
        var trimmed = cmd.trimStart()
        var iterations = 0
        while (iterations < 10) {
            iterations++
            val stripped = skipPrefixes.fold(trimmed) { acc, prefix ->
                acc.removePrefix("$prefix ")
            }
            if (stripped == trimmed) break
            trimmed = stripped.trimStart()
        }
        return trimmed.split(WHITESPACE_REGEX).firstOrNull()?.trim() ?: ""
    }

    /** bash 只读命令白名单——不修改文件的命令，不触发刷新 */
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    val READ_ONLY_COMMANDS = setOf(
        "cd", "ls", "cat", "grep", "head", "tail", "echo", "pwd", "which", "type",
        "dir", "less", "more", "printf", "find", "wc", "sort", "uniq", "cut",
        "diff", "tree", "stat", "file", "du", "df", "env", "printenv", "read",
        "test", "[", "declare", "typeset", "local", "export", "alias", "unalias",
        "hash", "help", "man", "info", "whatis", "apropos", "whereis",
        "exit", "return", "continue", "break", "shopt",
        "history", "fc", "bind", "complete", "compgen", "compopt",
        "id", "whoami", "who", "w", "users", "last", "date",
        "uname", "hostname", "arch", "nproc", "uptime",
        "basename", "dirname", "realpath", "readlink",
        "sleep", "true", "false",
        "pgrep", "pidof", "ps", "lsof",
        "unset", "shift", "getopts", "trap", "wait",
        "command", "builtin",
    )
}
