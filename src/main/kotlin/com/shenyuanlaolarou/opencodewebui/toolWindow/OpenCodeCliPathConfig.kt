package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * OpenCode 插件统一配置文件（应用级全局）：`~/.config/opencode-web-ui/config.json`。
 *
 * 与 Edge profile `edge-profiles/` 同目录（插件自身配置目录，跨项目共享）。
 * 当前承载一个配置项：
 * - `cliPath`：opencode CLI 绝对路径。非空 → server 启动时直接执行该二进制
 *   （见 [ServerProcessLauncher.getOpenCodeCommand]）；为空 → 走默认 PATH 查找。
 *
 * 写采用「读-改-写」：仅更新 `cliPath` key，保留配置文件中其他配置项。
 * 无任何静态可变状态（`gson` 为不可变 val），符合 AGENTS.md「禁止静态全局可变状态」。
 */
object OpenCodeCliPathConfig {

    /** 统一配置文件（JSON） */
    private const val FILE_NAME = "config.json"

    /** cliPath 配置项的 key */
    private const val KEY_CLI_PATH = "cliPath"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** 默认配置目录：~/.config/opencode-web-ui */
    fun defaultDir(): File =
        File(System.getProperty("user.home"), ".config/opencode-web-ui")

    /**
     * 读取已配置的 CLI 绝对路径；未配置 / 文件不存在 / 解析失败返回空串。
     * 空串语义 = 未配置 = 启动走默认 PATH 查找。
     */
    fun get(dir: File = defaultDir()): String {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return ""
        val root = runCatching {
            gson.fromJson(file.readText(StandardCharsets.UTF_8), JsonObject::class.java)
        }.getOrNull() ?: return ""
        return (root.get(KEY_CLI_PATH)?.takeIf { it.isJsonPrimitive }?.asString ?: "").trim()
    }

    /**
     * 保存 CLI 绝对路径；传空串 / 纯空白则移除该配置项（恢复默认 PATH 查找）。
     * 读-改-写：保留配置文件中的其他配置项；所有配置项都被移除后删除文件。
     * 目录不存在时自动创建（mkdirs）。
     */
    fun set(path: String, dir: File = defaultDir()) {
        val file = File(dir, FILE_NAME)
        val trimmed = path.trim()
        val root = if (file.exists()) {
            runCatching {
                gson.fromJson(file.readText(StandardCharsets.UTF_8), JsonObject::class.java)
            }.getOrNull() ?: JsonObject()
        } else {
            JsonObject()
        }
        if (trimmed.isEmpty()) {
            root.remove(KEY_CLI_PATH)
        } else {
            root.addProperty(KEY_CLI_PATH, trimmed)
        }
        if (root.size() == 0) {
            file.delete()
            return
        }
        dir.mkdirs()
        file.writeText(gson.toJson(root), StandardCharsets.UTF_8)
    }

    /**
     * 校验 CLI 路径是否符合 macOS / Linux 绝对路径规范。
     *
     * @return null 表示合法；否则返回错误提示（英文，与 Dashboard 现有 UI 文案一致）
     *
     * 规则：
     * - 空 / 纯空白：合法（语义 = 清除配置，恢复默认）
     * - 必须以 `/` 开头（绝对路径，`hello` 这类相对名直接拒绝）
     * - 不含 NUL / 控制字符
     * - 文件必须存在且可执行（阻止保存 — 用户已确认：不存在/不可执行直接拒绝）
     */
    fun validate(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("/")) {
            return "Must be an absolute path starting with / (e.g. /usr/local/bin/opencode)"
        }
        if (trimmed.contains('\u0000') || trimmed.any { it.isISOControl() }) {
            return "Path contains illegal control characters"
        }
        val file = File(trimmed)
        if (!file.exists()) {
            return "File does not exist: $trimmed"
        }
        if (!file.canExecute()) {
            return "File is not executable: $trimmed"
        }
        return null
    }
}
