package com.github.xausky.opencodewebui.toolWindow

/**
 * OpenCode 路径查找辅助类
 * 封装 findOpenCodePath 的核心逻辑用于单元测试
 */
object OpenCodePathFinder {

    private const val NVM_HOMEBREW_PATH = "/opt/homebrew/opt/nvm/versions/node"
    private const val NVM_SCRIPT_PATH = ".nvm/versions/node"

    fun isNvmPath(path: String): Boolean {
        return path.contains(NVM_HOMEBREW_PATH) || path.contains(NVM_SCRIPT_PATH)
    }

    /**
     * 从 opencode 路径提取 bin 目录
     */
    fun extractBinDir(opencodePath: String): String {
        return java.io.File(opencodePath).parentFile.absolutePath
    }

    /**
     * 进程执行结果
     */
    data class ProcessResult(val exitCode: Int, val output: String)

    /**
     * 收集候选路径列表（包含 NVM 扫描）
     * @return 候选路径列表
     */
    fun getCandidatePaths(): List<String> {
        val candidatePaths = mutableListOf<String>()
        val home = System.getProperty("user.home")

        candidatePaths.addAll(listOf(
            "/opt/homebrew/bin/opencode",
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        ))

        val nvmDirs = listOf(
            java.io.File(NVM_HOMEBREW_PATH),
            java.io.File(home, ".nvm/versions/node")
        )

        for (nvmDir in nvmDirs) {
            println("[OpenCodePathFinder] 检查 NVM 目录: ${nvmDir.absolutePath}, exists=${nvmDir.exists()}")
            if (nvmDir.exists()) {
                nvmDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("v") }
                    ?.forEach { versionDir ->
                        val opencodePath = java.io.File(versionDir, "bin/opencode")
                        println("[OpenCodePathFinder] 检查版本目录: ${versionDir.absolutePath}, opencode exists=${opencodePath.exists()}")
                        if (opencodePath.exists()) {
                            candidatePaths.add(opencodePath.absolutePath)
                            println("[OpenCodePathFinder] 添加 NVM opencode 路径: ${opencodePath.absolutePath}")
                        }
                    }
            }
        }
        println("[OpenCodePathFinder] 最终候选路径: $candidatePaths")
        return candidatePaths
    }

    /**
     * 查找 OpenCode 路径
     * @param candidatePaths 候选路径列表
     * @param existingPathsFilter 仅保留存在的路径的过滤器
     * @param executor 进程执行器
     * @return 找到的 OpenCode 路径
     */
    fun findOpenCodePath(
        candidatePaths: List<String>,
        existingPathsFilter: (List<String>) -> List<String> = { paths -> paths.filter { java.io.File(it).exists() } },
        executor: (String) -> ProcessResult? = { path ->
            try {
                val process = Runtime.getRuntime().exec(arrayOf(path, "--version"))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText().trim()
                    ProcessResult(exitCode, output)
                } else null
            } catch (e: Exception) {
                null
            }
        }
    ): String {
        println("[OpenCodePathFinder] findOpenCodePath 开始")
        val existingPaths = existingPathsFilter(candidatePaths)
        println("[OpenCodePathFinder] 存在的路径: $existingPaths")

        if (existingPaths.isEmpty()) {
            println("[OpenCodePathFinder] 没有找到任何 opencode 路径，抛出异常")
            throw IllegalStateException("OpenCode not found")
        }

        if (existingPaths.size == 1) {
            println("[OpenCodePathFinder] 只有一个路径，直接返回: ${existingPaths[0]}")
            return existingPaths[0]
        }

        println("[OpenCodePathFinder] 多路径，执行 --version 获取版本...")
        val versionedPaths = existingPaths.mapNotNull { path ->
            println("[OpenCodePathFinder] 执行: $path --version")
            val result = executor(path)
            if (result != null) {
                println("[OpenCodePathFinder] $path 版本: ${result.output}")
                Pair(result.output, path)
            } else {
                println("[OpenCodePathFinder] $path 执行失败")
                null
            }
        }

        val selected = versionedPaths.maxByOrNull { parseOpenCodeVersion(it.first) }?.second
        println("[OpenCodePathFinder] 选择版本最高的路径: $selected")
        return selected ?: existingPaths.first()
    }

    /**
     * 解析 OpenCode 版本号
     * 使用整数比较，不是字符串比较
     */
    fun parseOpenCodeVersion(version: String): ComparableVersion {
        val parts = version.split(".").map { it.toIntOrNull() ?: 0 }
        return ComparableVersion(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 }
        )
    }

    /**
     * 版本号比较类
     */
    data class ComparableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ComparableVersion> {
        override fun compareTo(other: ComparableVersion): Int {
            return when {
                major != other.major -> major - other.major
                minor != other.minor -> minor - other.minor
                else -> patch - other.patch
            }
        }
    }

    private const val MAX_CONFIG_PARSE_DEPTH = 5

    fun getFullEnvironment(opencodeBinDir: String?): Map<String, String> {
        val env = System.getenv().toMutableMap()
        val home = env["HOME"] ?: System.getProperty("user.home")
        val currentPath = env["PATH"] ?: ""

        val allPaths = mutableListOf<String>()

        val defaultPaths = listOf(
            "/usr/bin", "/bin", "/usr/sbin", "/sbin",
            "/usr/local/bin",
            "/opt/homebrew/bin", "/opt/homebrew/sbin",
            "/opt/local/bin", "/opt/local/sbin"
        )
        allPaths.addAll(defaultPaths)

        val detectedPaths = detectToolPaths(home)
        allPaths.addAll(detectedPaths)

        val shellPaths = parseShellConfigPaths(home)
        allPaths.addAll(shellPaths)

        val uniquePaths = allPaths.distinct().filter { java.io.File(it).exists() }

        val existingPaths = currentPath.split(":").filter { path ->
            !defaultPaths.contains(path) && !uniquePaths.contains(path)
        }

        val allPathsWithOpencodeBin = if (opencodeBinDir != null && !uniquePaths.contains(opencodeBinDir)) {
            listOf(opencodeBinDir) + uniquePaths
        } else {
            uniquePaths
        }

        val newPath = (allPathsWithOpencodeBin + existingPaths).joinToString(":")
        env["PATH"] = newPath

        return env
    }

    private fun detectToolPaths(home: String): List<String> {
        val paths = mutableListOf<String>()

        val nvmDirs = listOf(
            java.io.File(NVM_HOMEBREW_PATH),
            java.io.File("$home/.nvm/versions/node")
        )
        for (nvmVersionsDir in nvmDirs) {
            if (nvmVersionsDir.exists()) {
                nvmVersionsDir.listFiles()?.forEach { versionDir ->
                    val nvmBin = "${versionDir.absolutePath}/bin"
                    if (java.io.File(nvmBin).exists()) paths.add(nvmBin)
                }
            }
        }

        return paths
    }

    private fun parseShellConfigPaths(home: String): List<String> {
        val paths = mutableSetOf<String>()
        val visitedFiles = mutableSetOf<String>()

        val configFiles = mutableListOf<String>()

        val shell = System.getenv("SHELL") ?: ""
        when {
            shell.contains("zsh") -> {
                configFiles.add("$home/.zshrc")
                configFiles.add("$home/.zprofile")
                configFiles.add("$home/.zshenv")
            }
            shell.contains("bash") -> {
                configFiles.add("$home/.bashrc")
                configFiles.add("$home/.bash_profile")
            }
            shell.contains("fish") -> {
                configFiles.add("$home/.config/fish/config.fish")
            }
            shell.contains("csh") -> {
                configFiles.add("$home/.cshrc")
            }
            shell.contains("ksh") -> {
                configFiles.add("$home/.kshrc")
            }
            else -> {
                configFiles.add("$home/.bashrc")
                configFiles.add("$home/.zshrc")
            }
        }

        for (configFile in configFiles) {
            parseConfigFile(configFile, home, paths, visitedFiles, 0)
        }

        return paths.toList()
    }

    private fun parseConfigFile(
        filePath: String,
        home: String,
        paths: MutableSet<String>,
        visitedFiles: MutableSet<String>,
        depth: Int
    ) {
        if (depth >= MAX_CONFIG_PARSE_DEPTH) return

        val file = java.io.File(filePath)
        if (!file.exists() || !file.isFile || !file.canRead()) return

        val canonicalPath = try {
            file.canonicalPath
        } catch (e: Exception) {
            return
        }
        if (visitedFiles.contains(canonicalPath)) return
        visitedFiles.add(canonicalPath)

        try {
            file.readLines().forEach { line ->
                val trimmedLine = line.trim()

                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) return@forEach

                if (trimmedLine.startsWith("export PATH=") || trimmedLine.startsWith("PATH=")) {
                    val pathMatch = Regex("""PATH=["']?([^"'`\n]+)["']?""").find(trimmedLine)
                    if (pathMatch != null) {
                        parsePathString(pathMatch.groupValues[1], home, paths)
                    }
                }

                val sourceMatch = Regex("""^\s*(?:source|\.)\s+["']?([^"'\n]+)["']?""").find(trimmedLine)
                if (sourceMatch != null) {
                    val sourcedFile = sourceMatch.groupValues[1]
                    val expandedFile = sourcedFile.replace("~", home)
                    parseConfigFile(expandedFile, home, paths, visitedFiles, depth + 1)
                }

                if (trimmedLine.contains("eval \"\$(") || trimmedLine.contains("eval $(")) {
                    val evalMatch = Regex("""eval\s+\$?\("([^)]+)"\)""").find(trimmedLine)
                    if (evalMatch != null) {
                        val cmd = evalMatch.groupValues[1]
                        when {
                            cmd.contains("pyenv init") -> {
                                val pyenvBin = "$home/.pyenv/bin"
                                if (java.io.File(pyenvBin).exists()) paths.add(pyenvBin)
                            }
                            cmd.contains("rbenv init") -> {
                                val rbenvBin = "$home/.rbenv/bin"
                                if (java.io.File(rbenvBin).exists()) paths.add(rbenvBin)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun parsePathString(pathStr: String, home: String, paths: MutableSet<String>) {
        val parts = pathStr.split(":")
        for (part in parts) {
            var expandedPart = part
                .replace("~", home)
                .replace("\$HOME", home)
                .replace("\${HOME}", home)

            expandedPart = expandedPart.trim('"', '\'', '`')

            if (expandedPart.contains("\$") && !expandedPart.startsWith("/") && !expandedPart.startsWith("~")) {
                continue
            }

            if (expandedPart.isNotEmpty() && !expandedPart.contains("\$")) {
                val file = java.io.File(expandedPart)
                if (file.exists() && file.isDirectory) {
                    paths.add(expandedPart)
                }
            }
        }
    }
}
