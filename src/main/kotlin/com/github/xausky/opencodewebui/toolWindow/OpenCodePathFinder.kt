package com.github.xausky.opencodewebui.toolWindow

/**
 * OpenCode 路径查找辅助类
 * 封装 findOpenCodePath 的核心逻辑用于单元测试
 */
object OpenCodePathFinder {

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
        // 硬编码路径
        candidatePaths.addAll(listOf(
            "/opt/homebrew/bin/opencode",
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        ))
        // NVM 多版本路径
        val nvmBase = "/opt/homebrew/opt/nvm/versions/node"
        val nvmDir = java.io.File(nvmBase)
        if (nvmDir.exists()) {
            nvmDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("v") }
                ?.forEach { versionDir ->
                    val opencodePath = java.io.File(versionDir, "bin/opencode")
                    if (opencodePath.exists()) {
                        candidatePaths.add(opencodePath.absolutePath)
                    }
                }
        }
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
        // 1. 过滤存在的路径
        val existingPaths = existingPathsFilter(candidatePaths)

        if (existingPaths.isEmpty()) {
            throw IllegalStateException("OpenCode not found")
        }

        if (existingPaths.size == 1) {
            return existingPaths[0]
        }

        // 2. 多路径时选择版本最高的
        val versionedPaths = existingPaths.mapNotNull { path ->
            val result = executor(path)
            if (result != null) {
                Pair(result.output, path)
            } else null
        }

        return versionedPaths.maxByOrNull { parseOpenCodeVersion(it.first) }?.second
            ?: existingPaths.first()
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
}
