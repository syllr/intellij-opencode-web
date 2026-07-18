package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
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
     * `verifyOpenCodeOnPath()` 故意不调用 — opencode CLI 可用性由 `OpenCodeServerManager.startServer`
     * 在启动 server 时独立验证(若 CLI 缺失会直接报错),浏览器 launch 是独立的"开窗口"操作,
     * 不应耦合 CLI 检查。否则 launch 会被一个本可由 server manager 处理的预检阻塞。
     *
     * @param url OpenCode Web UI URL(来自 [buildUrl])
     * @param extensionDir (可选) unpacked Edge extension 目录;为 null 时不加载 extension
     *   (侧栏 add project 功能降级,但 Edge 仍能打开)。`null` 不会 throw,只是 no-op。
     * @return 使用回退路径时返回 Edge 进程 PID;主路径 `open` 不可追踪 PID 返回 null
     * @throws IllegalStateException Edge 未安装
     */
    fun launch(url: String, extensionDir: File? = null, userDataDir: File? = null): Long? {
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

    /**
     * 检查指定 Edge --app 主进程是否有 renderer 子进程。
     *
     * 这是区分"窗口活跃"vs"zombie 残留"的关键信号:
     * - Chromium 在 window 关闭时会销毁 WebContents → 销毁 renderer 进程
     * - 但主进程残留( macOS app lifecycle 不跟随 window 生命周期)
     * - 所以 renderer 子进程数 > 0 = 真有可见窗口;= 0 = 窗口已关(僵尸)
     *
     * 设计决策(fail-safe):
     * - 检测异常 → 返回 true(宁可"误报活跃"也不"漏报活跃"——漏报会导致开重复窗口)
     *
     * @return true 有 renderer 子进程(窗口活跃)或检测异常;false 无 renderer(僵尸)
     */
    private fun hasRendererChild(pid: Long): Boolean {
        return try {
            // 拿 Edge 主进程的所有子 PID
            val pg = ProcessBuilder("pgrep", "-P", pid.toString()).start()
            val childPids = pg.inputStream.bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (!pg.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                pg.destroyForcibly()
                log.warn("[OpenCodeBrowserLauncher] hasRendererChild: pgrep -P $pid timeout")
                return true  // fail-safe
            }
            if (childPids.isEmpty()) return false  // 无子进程 → 无 renderer → zombie

            // 批量 ps -p 一次拿所有子进程的 args(O(1) 进程启动 vs O(N))
            val ps = ProcessBuilder("ps", "-p", childPids.joinToString(","), "-o", "pid,args=").start()
            val output = ps.inputStream.bufferedReader().readText()
            if (!ps.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                ps.destroyForcibly()
                log.warn("[OpenCodeBrowserLauncher] hasRendererChild: ps -p batch timeout")
                return true  // fail-safe
            }
            output.contains("--type=renderer")
        } catch (e: Exception) {
            log.warn("[OpenCodeBrowserLauncher] hasRendererChild failed: ${e.message}")
            true  // fail-safe: 宁可误报活跃,不开重复窗口
        }
    }

    /**
     * 检查 Edge 是否已开过指定项目路径的活跃窗口。
     *
     * 实现链路:
     * 1. `pgrep -f "MacOS/Microsoft Edge .*--app=http"` → 找所有 Edge --app 主进程
     * 2. `ps -p PID -o args=` → 提取 `--app=<URL>` → base64 decode 路径段 → 匹配 projectPath
     * 3. [P5 zombie fix] `hasRendererChild()` 二次验证 → 区分活跃窗口 vs zombie 残留
     *   - 没有 renderer 子进程 → zombie(用户关过窗口但主进程残留) → 跳过,允许重新打开
     *   - 有 renderer 子进程 → 活跃窗口 → return true
     *
     * 设计决策(fail-safe):
     * - 任何检测异常 → return false(宁可"误报无窗口"也不"误报有窗口"——
     *   Edge 自己能处理重复 launch,用户体验是"多开一个窗口",比"永远开不了窗口"好)
     *
     * @return true 找到该项目活跃窗口;false 无活跃窗口或检测异常
     */
    internal fun hasEdgeWindowOpen(projectPath: String): Boolean {
        if (projectPath.isBlank()) return false
        return try {
            val projectPathB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))

            // 1. 找所有 Edge --app 主进程
            val pg = ProcessBuilder("pgrep", "-f", "MacOS/Microsoft Edge .*--app=http").start()
            val pids = pg.inputStream.bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (!pg.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                pg.destroyForcibly()
                log.warn("[OpenCodeBrowserLauncher] hasEdgeWindowOpen: pgrep timeout")
                return false
            }
            if (pids.isEmpty()) return false

            // 2. 对每个 PID 解析 --app URL,匹配 projectPath
            for (pid in pids) {
                val ps = ProcessBuilder("ps", "-p", pid, "-o", "args=").start()
                val argsLine = ps.inputStream.bufferedReader().readText()
                if (!ps.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    ps.destroyForcibly()
                    log.warn("[OpenCodeBrowserLauncher] hasEdgeWindowOpen: ps -p $pid timeout")
                    continue
                }

                // 提取 --app=<URL>(到下一个空格)
                val match = Regex("""--app=(\S+)""").find(argsLine) ?: continue
                val url = match.groupValues[1]

                // URL 格式:http://localhost:12396/<base64dir>[/session/<id>]
                // <base64dir> 是 URL path 第二段(取到第一个 / 或字符串末尾)
                val afterHost = url.substringAfter("localhost:$OPENCODE_PORT/")
                val pathSegment = afterHost.substringBefore("/")
                if (pathSegment != projectPathB64) continue

                // 3. [P5 zombie fix] 二次验证 renderer 子进程
                // pgrep 拿到的是字符串,需要转 Long
                val pidLong = pid.toLongOrNull() ?: continue
                if (!hasRendererChild(pidLong)) {
                    log.info("[OpenCodeBrowserLauncher] hasEdgeWindowOpen: PID=$pid matches project but is zombie (no renderer), skipping")
                    continue
                }
                return true
            }
            false
        } catch (e: Exception) {
            log.warn("[OpenCodeBrowserLauncher] hasEdgeWindowOpen failed: ${e.message}")
            false  // fail-safe: 让用户尝试 launch
        }
    }
}
