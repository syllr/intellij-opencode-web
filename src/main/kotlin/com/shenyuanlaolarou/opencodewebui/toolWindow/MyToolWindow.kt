package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.shenyuanlaolarou.opencodewebui.utils.dataOrNull
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.JComponent
import javax.swing.SwingUtilities

class MyToolWindow(toolWindow: ToolWindow) {

    private val project = toolWindow.project
    private val logger = thisLogger()
    private val browserPanel = BrowserPanel(OPENCODE_HOST, OPENCODE_PORT, MyToolWindowFactory.sharedJBCefClient)
    private var mainBrowser: JBCefBrowser? = null

    init {
        setupWindowFocusListener(toolWindow)
        MyToolWindowFactory.myToolWindowInstances[project] = this

        // 项目关闭时清理：移除 map 引用并释放资源，防止内存泄漏
        Disposer.register(project) {
            logger.info("[Lifecycle] Cleaning up MyToolWindow for project=${project.name}")
            MyToolWindowFactory.myToolWindowInstances.remove(project)
            healthMonitor?.stop()
            browserPanel.disposeBrowser()
            // [Fix #3] 停止该项目的 SSE consumer，断开 OpenCodeServerManager → SSEConsumer → Project 引用链
            OpenCodeServerManager.disposeForProject(project)
        }
    }

    private var isShowingStartButton = false
    @Volatile
    private var hasLoaded = false

    @Volatile
    private var healthMonitor: HealthMonitor? = null

    fun checkAndLoadContent() {
        // [Fix EDT freeze + 初始化时序] 端口预探测(200ms)先判断 server 是否在监听:
        // - 端口通: server 已运行 → 创建 consumer + 启 HealthMonitor + 加载页面
        // - 端口关: server 未运行 → 仅显示 Start 按钮,零后台线程(避免连接风暴)
        if (OpenCodeApi.isServerPortOpen()) {
            logger.info("[checkAndLoadContent] Server port open, starting consumer + health monitor")
            startConsumerAndMonitor()
            loadProjectPage()
        } else {
            logger.info("[checkAndLoadContent] Server port closed, showing start button (no consumer created)")
            showServerNotRunning()
        }
    }

    /**
     * 创建/复用 SSE consumer + 启动 HealthMonitor。startServer() 成功后也会调。
     * HealthMonitor 负责监测 server crash→onUnhealthy→showServerNotRunning 停止 consumer。
     * onConnectionLost 快速通道:server 主动 shutdown 时绕过 15s debounce 立即显示 Start 按钮。
     *
     * [Fix 线程违规] onConnectionLost 在 LaunchDarkly 后台线程被调,
     * showServerNotRunning 内部含 Swing UI 操作(disposeBrowser/showStartButton),
     * 必须 wrap 进 invokeLater 切到 EDT,否则违反 Swing 线程模型 → UI 损坏/死锁。
     */
    private fun startConsumerAndMonitor() {
        val consumer = OpenCodeServerManager.getOrCreateConsumer(
            project = project,
            onConnectionLost = {
                ApplicationManager.getApplication().invokeLater {
                    showServerNotRunning()
                }
            }
        )
        // 替换旧 HealthMonitor(如果有)。stop() 幂等,安全覆盖。
        healthMonitor?.stop()
        val monitor = HealthMonitor(
            consumer = consumer,
            onUnhealthy = { showServerNotRunning() },
            onRecovered = { loadProjectPage() }
        )
        monitor.lastHealthState = consumer.isHealthy()
        healthMonitor = monitor
        monitor.start()
    }

    private fun showServerNotRunning() {
        // [Fix 重入守卫] LaunchDarkly 库 alwaysContinue 策略 + server 已 down
        // → 库内部 1s/2s/4s... 指数退避重试,每次失败都触发 onError → onConnectionLost → showServerNotRunning。
        // 如果没有守卫,会反复 disposeBrowser + showStartButton → UI 闪烁 + 组件泄漏。
        // isShowingStartButton 守卫(已存在但此前从未被读)实现幂等。
        if (isShowingStartButton) {
            thisLogger().info("showServerNotRunning skipped: already showing start button (幂等)")
            return
        }
        // [Fix #4 资源泄漏] onConnectionLost 回调在 LaunchDarkly 后台线程触发(onClosed/onError 异步),
        // 可能晚于 Disposer.register(project) 的清理回调。如果 project 已 dispose,
        // 访问 browserPanel/mainBrowser 等 Swing 组件会触发 "Already disposed" 异常。
        if (project.isDisposed) {
            thisLogger().info("showServerNotRunning skipped: project already disposed")
            return
        }
        thisLogger().info("showServerNotRunning called, EDT thread=${Thread.currentThread().name}")
        hasLoaded = false
        mainBrowser = null
        isShowingStartButton = true
        browserPanel.disposeBrowser()
        browserPanel.showStartButton { startOpenCodeServer() }
        // [Fix 启动时序] 停止后台线程,避免 server 未运行时 5 个线程空转:
        // - HealthMonitor(5s 轮询 isHealthy,永远 false,无意义)
        // - SSE consumer.watchdog(30s 后每 5s reconnect,连接风暴)
        // - FullRefreshCoordinator(ScheduledExecutorService 常驻)
        // - EventSource 内部 LaunchDarkly 线程(指数退避重试)
        // disposeForProject 幂等(remove 返回 null 时 no-op),用户点 Start 后 startServer() 会重新创建
        healthMonitor?.stop()
        healthMonitor = null
        OpenCodeServerManager.disposeForProject(project)
    }

    // [Fix #4] 追踪已注册 listener 的 window，防止 hierarchy 变化时无限累积
    private var focusListenerWindow: java.awt.Window? = null
    private var focusListener: java.awt.event.WindowFocusListener? = null

    private fun setupWindowFocusListener(toolWindow: ToolWindow) {
        browserPanel.addHierarchyListener {
            val newWindow = SwingUtilities.getWindowAncestor(browserPanel)
            val oldWindow = focusListenerWindow
            // 仅当 window 发生变化时才重新注册
            if (newWindow !== oldWindow) {
                // 移除旧 window 上的 listener
                oldWindow?.let { w -> focusListener?.let { l -> w.removeWindowFocusListener(l) } }
                // 在新 window 上注册
                newWindow?.let { window ->
                    val listener = object : WindowAdapter() {
                        override fun windowGainedFocus(e: WindowEvent?) {
                            if (toolWindow.isVisible) {
                                requestBrowserFocus()
                            }
                        }
                    }
                    window.addWindowFocusListener(listener)
                    focusListenerWindow = window
                    focusListener = listener
                }
            }
        }
    }

    fun getContent() = browserPanel

    fun getBrowser() = browserPanel.getBrowser()

    fun isPageLoaded() = browserPanel.isPageLoaded()

    private fun setupBrowserComponent(browser: JBCefBrowser) {
        val osrComponent = browser.cefBrowser.uiComponent

        osrComponent?.let { comp ->
            JcefKeyboardInterceptor.interceptKeys(comp)
            EmacsKeyHandler.addEmacsKeyMapping(comp)
        }
    }

    fun requestBrowserFocus() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val browser = browserPanel.getBrowser() ?: return@invokeLater
                val osrComponent = browser.cefBrowser.uiComponent
                val browserComponent = browser.component

                osrComponent?.let { comp ->
                    if (comp.isFocusable) {
                        comp.requestFocus()
                    }
                }

                if (browserComponent.isFocusable) {
                    browserComponent.requestFocus()
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to request browser focus: ${e.message}")
            }
        }
    }

    private fun startOpenCodeServer() {
        OpenCodeServerManager.startServer(
            project = project,
            onStarted = { onServerStarted() },
            onFailed = { e -> onServerStartFailed(e) }
        )
    }

    private fun onServerStarted() {
        // [Fix hasLoaded lock + 启动时序] startServer() 成功后:
        // 1. 启动 HealthMonitor 监听 crash 恢复(server 已 healthy,SSE 会连上)
        // 2. force=true 强制重新加载(覆盖之前 showServerNotRunning 设的 hasLoaded=false 后又被 loadProjectPage 设 true 的状态)
        ApplicationManager.getApplication().invokeLater {
            startConsumerAndMonitor()
            loadProjectPage(force = true)
        }
    }

    private fun onServerStartFailed(e: Exception) {
        thisLogger().error("Error starting OpenCode server: ${e.message}")
        ApplicationManager.getApplication().invokeLater { showErrorInBrowser() }
    }

    private fun loadProjectPage(force: Boolean = false) {
        if (hasLoaded && !force) {
            logger.info("[loadProjectPage] Already loaded, skipping duplicate call")
            return
        }
        hasLoaded = true
        isShowingStartButton = false
        val projectPath = project.basePath ?: return
        val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))

        // [决策:不自动恢复 session] 加载项目页到 DirectoryLayout(显示侧栏 + recent sessions),
        // 由用户在 web UI 内手动点 session。opencode 桌面版/web app 启动后默认停在 HomePage,
        // 不提供"启动时自动打开 last session"能力;`opencode run --continue`/`--session` 只对 CLI
        // run/attach 生效,我们走 serve 模式拿不到。历史曾实现过 `getLatestSessionId` 自动恢复
        // (c5091e5 移除),如需恢复能力见 git history。
        val url = "http://$OPENCODE_HOST:$OPENCODE_PORT/$encodedPath"
        thisLogger().info("loadProjectPage: url=$url force=$force (manual session selection)")
        browserPanel.hideStartButton()
        if (mainBrowser == null) {
            thisLogger().info("[Lifecycle] loadProjectPage: mainBrowser is null, creating new browser")
            mainBrowser = browserPanel.createMainTab(url, projectPath)
            setupBrowserComponent(mainBrowser!!)
        } else {
            mainBrowser?.loadURL(url)
        }
    }

    private fun showErrorInBrowser() {
        val html = """
            <html>
            <body style="background-color: #2B2B2B; color: #A9B7C6; font-family: sans-serif; padding: 20px;">
                <h2>Failed to start OpenCode server</h2>
                <p>Please make sure 'opencode' is installed and available in your PATH.</p>
                <p>Run the following command to start the server manually:</p>
                <pre style="background: #3C3F41; padding: 10px; border-radius: 4px;">opencode serve --hostname $OPENCODE_HOST --port $OPENCODE_PORT</pre>
            </body>
            </html>
        """.trimIndent()
        mainBrowser?.loadHTML(html)
    }
}
