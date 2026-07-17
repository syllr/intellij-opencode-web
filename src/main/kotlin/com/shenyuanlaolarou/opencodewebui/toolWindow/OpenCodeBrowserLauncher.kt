package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 启动 Microsoft Edge 在 **App Mode** (`--app=<url>`) 下显示 OpenCode Web UI。
 *
 * 为什么是 Edge 而不是 Chrome:
 * - Chrome 150+ stable 静默忽略 `--load-extension=`,导致 plugin 端侧栏 add project 失效
 *   (`WARNING:chrome/browser/extensions/extension_service.cc:420] --load-extension is not allowed in Google Chrome, ignoring`)
 * - Edge 150+ (Chromium 内核) 接受 `--load-extension=`,ext content script 在 `document_start` 阶段
 *   写 `localStorage["opencode.global.dat:server"]` 把当前 IntelliJ 项目塞进 OpenCode Web UI
 *   侧栏的 client store,web app mount 时从 localStorage 还原,侧栏立即显示
 *
 * App Mode 特性:Edge 窗口**无 tab bar / address bar / menu**,只渲染 page content,
 * 跟系统 Edge 主窗口视觉/行为完全隔离。
 *
 * 设计:
 * - 主路径:`open -na "Microsoft Edge" --args --app=<url> [--load-extension=<extDir>]`
 *   - `-n` 强制新实例(避免开已有 Edge 的普通 tab)
 *   - `-a "Microsoft Edge"` 通过 LaunchServices 启动 Edge
 *   - `--args` 把 `--app` / `--load-extension` 透传 Edge
 * - 回退路径:直接 fork Edge binary 带 `--app=<url>`
 * - 两条路径都**仅当 userDataDir != null 时传 --user-data-dir**;默认(null)复用用户日常 Edge profile,非默认(传入时)实现多项目 profile 隔离(SPEC §4.3)
 *
 * macOS-only;跨平台 TODO 已删除(SPEC §2 硬规则)。
 */
object OpenCodeBrowserLauncher {
    private val log = thisLogger()

    /** Microsoft Edge 在 macOS 上的固定安装位置(only supported browser)。 */
    private val EDGE_BINARY = "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"

    /**
     * 检测 Microsoft Edge 是否安装。
     *
     * @return Edge binary 绝对路径,未安装返回 null
     */
    fun pickBrowser(): String? {
        return EDGE_BINARY.takeIf { File(it).canExecute() }
    }

    /**
     * 验证 `opencode` CLI 在 PATH 中。
     *
     * @return 找到 opencode 返回 true,否则返回 false
     */
    fun verifyOpenCodeOnPath(): Boolean {
        return try {
            val proc = ProcessBuilder(listOf("which", "opencode"))
                .redirectErrorStream(true)
                .start()
            proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 构建 OpenCode Web UI URL:`http://localhost:$port/<base64dir>` 或
     * `http://localhost:$port/<base64dir>/session/<id>`(传 sessionId 时)。
     *
     * 目录必须用 base64 编码(URL-safe,无 padding)作为路径段;query 参数形式
     * `?directory=...&session=...` 已被 Web UI 路由器忽略,会回退到默认首页。
     *
     * @param projectBasePath 绝对文件系统路径(例如 /Users/yutao/IdeaProjects/intellij-opencode-web)
     * @param port opencode server HTTP 端口(例如 12396)
     * @param sessionId opencode session ID(例如 ses_xxx),空串则不带 /session/ 段
     * @return 完整的 OpenCode Web UI URL
     */
    fun buildUrl(projectBasePath: String, port: Int, sessionId: String): String {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(projectBasePath.toByteArray(StandardCharsets.UTF_8))
        val base = "http://localhost:$port/$encoded"
        return if (sessionId.isEmpty()) base else "$base/session/$sessionId"
    }

    /**
     * 主路径命令:`open -na "Microsoft Edge" --args --app=<url> [--load-extension=<extDir>]`。
     *
     * - `-n` 强制新 Edge 实例(避免打开已有 Edge 的普通 tab)
     * - `-a "Microsoft Edge"` 通过 LaunchServices 启动 Edge
     * - `--args --app=<url>` 把 `--app` 透传给 Edge 启动 **App Mode**(无 tab bar / address bar / menu)
     * - `--load-extension=<extDir>` (可选) 加载 plugin 写的 unpacked browser extension (Edge 接受的 MV3 格式),ext content script
     *   在 `document_start` 阶段写 `localStorage["opencode.global.dat:server"]` 把当前 IntelliJ 项目塞进
     *   OpenCode Web UI 侧栏的 client store,web app mount 时从 localStorage 还原,侧栏立即显示
     * - `--user-data-dir=<dir>`(可选)项目隔离的 Edge profile 目录;为 null 时复用日常 profile
     */
    internal fun buildOpenCommand(url: String, extensionDir: File? = null, userDataDir: File? = null): List<String> {
        // --no-first-run 跳过新 profile 的 Edge 欢迎引导(否则新 project 首次启动会弹欢迎)
        // --disable-sync 禁用 Chrome/Edge Sync 服务,阻止新 --user-data-dir profile 从云端
        //   同步登录状态。SPEC §4.3 身份层隔离约束;若实测账号又被同步进来,这是唯一可动点。勿删。
        val args = mutableListOf(
            "open", "-na", "Microsoft Edge", "--args",
            "--app=$url",
            "--no-first-run",
            "--disable-sync",
        )
        if (userDataDir != null) {
            args += "--user-data-dir=${userDataDir.absolutePath}"
        }
        if (extensionDir != null) {
            args += "--load-extension=${extensionDir.absolutePath}"
        }
        return args
    }

    /**
     * 回退路径命令:直接 fork Edge binary 带 `--app=<url>`。
     *
     * @param edge Edge binary 完整路径(来自 [pickBrowser])
     * @param url OpenCode Web UI URL
     * @param extensionDir (可选) 加载 unpacked browser extension,把当前项目塞进 Web UI 侧栏
     */
    internal fun buildProcessBuilderCommand(edge: String, url: String, extensionDir: File? = null, userDataDir: File? = null): List<String> {
        // --disable-sync 同 buildOpenCommand: 阻止新 profile 同步登录状态。勿删(见 buildOpenCommand 注释)。
        val args = mutableListOf(
            edge,
            "--app=$url",
            "--no-default-browser-check",
            "--no-first-run",
            "--disable-sync",
        )
        if (userDataDir != null) {
            args += "--user-data-dir=${userDataDir.absolutePath}"
        }
        if (extensionDir != null) {
            args += "--load-extension=${extensionDir.absolutePath}"
        }
        return args
    }

    /**
     * 检查 Edge 是否安装,用于 plugin UI 在用户点击 launch 前给出清晰提示。
     *
     * @return 已安装返回 null;未安装返回明确的安装指引 message(给 dialog 显示)
     */
    fun checkEdgeInstalled(): String? {
        if (pickBrowser() != null) return null
        return "Microsoft Edge is required but not found at:\n$EDGE_BINARY\n\n" +
                "Plugin uses Edge's App Mode (`--app=<url>`) to render the OpenCode Web UI as " +
                "a standalone window that opens OpenCode Web UI in Edge's --app mode.\n\n" +
                "Download from: https://www.microsoft.com/edge"
    }

    /**
     * 用 Microsoft Edge App Mode (`--app=<url>`) 启动 OpenCode Web UI,复用用户日常 profile。
     *
     * @param url OpenCode Web UI URL(来自 [buildUrl])
     * @param extensionDir (可选) unpacked Edge extension 目录;为 null 时不加载 extension
     *   (侧栏 add project 功能降级,但 Edge 仍能打开)。`null` 不会 throw,只是 no-op。
     * @return 使用回退路径时返回 Edge 进程 PID;主路径 `open` 不可追踪 PID 返回 null
     * @throws IllegalStateException Edge 未安装或 opencode CLI 缺失
     */
    fun launch(url: String, extensionDir: File? = null, userDataDir: File? = null): Long? {
        require(verifyOpenCodeOnPath()) {
            "opencode CLI not on PATH; install via `brew install opencode-ai`"
        }
        val edge = pickBrowser()
            ?: throw IllegalStateException(
                "Microsoft Edge not found at $EDGE_BINARY. " +
                "Plugin requires Edge for App Mode rendering of OpenCode Web UI. " +
                "Download from https://www.microsoft.com/edge"
            )

        if (extensionDir != null) {
            if (!extensionDir.isDirectory) {
                log.warn("[OpenCodeBrowserLauncher] extensionDir is not a directory, skipping --load-extension: ${extensionDir.absolutePath}")
            } else {
                val manifest = File(extensionDir, "manifest.json")
                if (!manifest.isFile) {
                    log.warn("[OpenCodeBrowserLauncher] extensionDir missing manifest.json, skipping --load-extension: ${extensionDir.absolutePath}")
                    return launchWithoutExtension(url, edge, userDataDir)
                }
            }
        }

        return try {
            val openProc = ProcessBuilder(buildOpenCommand(url, extensionDir, userDataDir)).start()
            openProc.waitFor(2, TimeUnit.SECONDS)
            log.info("[OpenCodeBrowserLauncher] Launched Edge in --app mode via `open -na` (PID untrackable, daily profile reused, extension=${extensionDir?.absolutePath ?: "none"}, userDataDir=${userDataDir?.absolutePath ?: "none"}): $url")
            null
        } catch (_: Exception) {
            log.warn("[OpenCodeBrowserLauncher] `open -na Edge --app` failed, falling back to direct fork --app mode")
            val proc = ProcessBuilder(buildProcessBuilderCommand(edge, url, extensionDir, userDataDir)).start()
            log.info("[OpenCodeBrowserLauncher] Launched Edge via --app direct fork (PID=${proc.pid()}, daily profile, extension=${extensionDir?.absolutePath ?: "none"}, userDataDir=${userDataDir?.absolutePath ?: "none"}): $url")
            proc.pid()
        }
    }

    /**
     * ext 不可用时的降级路径(直接调 launch 但传 null)。内部 helper 避免重复校验逻辑。
     */
    private fun launchWithoutExtension(url: String, edge: String, userDataDir: File? = null): Long? {
        return try {
            val openProc = ProcessBuilder(buildOpenCommand(url, null, userDataDir)).start()
            openProc.waitFor(2, TimeUnit.SECONDS)
            log.info("[OpenCodeBrowserLauncher] Launched Edge without extension (manifest missing): $url")
            null
        } catch (_: Exception) {
            val proc = ProcessBuilder(buildProcessBuilderCommand(edge, url, null, userDataDir)).start()
            log.info("[OpenCodeBrowserLauncher] Launched Edge without extension (manifest missing, fallback): $url")
            proc.pid()
        }
    }
}
