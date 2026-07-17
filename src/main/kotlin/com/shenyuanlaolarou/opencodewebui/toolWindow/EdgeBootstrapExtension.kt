package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 把 browser extension 资源(`src/main/resources/edge-extension/`) 拷贝到 $TMPDIR
 * 并替换占位符,产出可被 Edge `--load-extension=` 直接加载的 unpacked extension 目录。
 *
 * 设计:
 * - 资源文件跟随 plugin jar 走,build 后 classpath 里始终可读
 * - 每个 project 一个 ext 目录,按 project.basePath 哈希命名,避免同 project 重复 launch 时 Edge 看到不同 path 产生不同 ext id 引发"stale"提示
 * - macOS $TMPDIR 默认 /var/folders/.../T/,系统定期清理(3-7 天),plugin 不主动 dispose 时清理
 *
 * 写盘的 ext 内容是 plugin resource 的快照(纯静态),跟 plugin jar 同步;不会被运行时状态污染。
 */
object EdgeBootstrapExtension {
    private val log = thisLogger()

    private const val RESOURCE_DIR = "edge-extension"
    private const val MANIFEST = "manifest.json"
    private const val CONTENT_JS = "content.js"
    private const val PLACEHOLDER = "__PROJECT_BASE_PATH__"
    private const val TMP_PREFIX = "opencode-web-ext-"

    /**
     * 为指定 project 准备 ext 目录;返回已写好 manifest.json + content.js 的目录路径。
     *
     * 幂等:同一 project 多次调用覆盖同一 ext 目录,不会累积 stale ext。
     *
     * @param projectBasePath IDE 项目的绝对路径
     * @return ext 目录(File),失败返回 null
     */
    fun prepare(projectBasePath: String): File? {
        if (projectBasePath.isBlank()) {
            log.warn("[EdgeBootstrapExtension] empty projectBasePath, skip")
            return null
        }

        val targetDir = File(tmpRoot(), "$TMP_PREFIX${hash(projectBasePath)}-${System.currentTimeMillis()}")
        try {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            if (!targetDir.mkdirs()) {
                log.warn("[EdgeBootstrapExtension] mkdirs failed: ${targetDir.absolutePath}")
                return null
            }
        } catch (e: Exception) {
            log.warn("[EdgeBootstrapExtension] cannot create ext dir: ${e.message}")
            return null
        }

        val manifestSrc = readResource("$RESOURCE_DIR/$MANIFEST") ?: run {
            log.warn("[EdgeBootstrapExtension] missing resource $RESOURCE_DIR/$MANIFEST")
            return null
        }
        val contentSrc = readResource("$RESOURCE_DIR/$CONTENT_JS") ?: run {
            log.warn("[EdgeBootstrapExtension] missing resource $RESOURCE_DIR/$CONTENT_JS")
            return null
        }

        try {
            File(targetDir, MANIFEST).writeText(manifestSrc, StandardCharsets.UTF_8)
            val replaced = contentSrc.replace(PLACEHOLDER, escapeForJsStringLiteral(projectBasePath))
            File(targetDir, CONTENT_JS).writeText(replaced, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            log.warn("[EdgeBootstrapExtension] write ext files failed: ${e.message}")
            return null
        }

        log.info("[EdgeBootstrapExtension] prepared ext dir: ${targetDir.absolutePath} (project=$projectBasePath)")
        return targetDir
    }

    /**
     * 显式清理 ext 目录(plugin dispose 或用户主动清理时调用)。
     * 平时不调用 — macOS 会自动清理 $TMPDIR。
     */
    fun cleanup(projectBasePath: String) {
        val targetDir = File(tmpRoot(), "$TMP_PREFIX${hash(projectBasePath)}")
        if (targetDir.exists()) {
            runCatching { targetDir.deleteRecursively() }
        }
    }

    private fun readResource(path: String): String? {
        val stream = EdgeBootstrapExtension::class.java.classLoader.getResourceAsStream(path)
            ?: return null
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private fun tmpRoot(): String =
        System.getProperty("java.io.tmpdir") ?: "/tmp"

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 4) {
            sb.append(String.format("%02x", bytes[i]))
        }
        return sb.toString()
    }

    /**
     * 把 path 字符串安全嵌入 JS 单引号字符串字面量。
     * - 反斜杠 -> \\
     * - 单引号 -> \\'
     * - 换行 -> \\n (path 不该有但防意外)
     * - 控制字符 -> \\xHH
     */
    private fun escapeForJsStringLiteral(input: String): String {
        val sb = StringBuilder(input.length + 8)
        for (ch in input) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '\'' -> sb.append("\\'")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append(String.format("\\x%02x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
