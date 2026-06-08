package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
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
            browserPanel.disposeBrowser()
            // [Fix #3] 停止该项目的 SSE consumer，断开 OpenCodeServerManager → SSEConsumer → Project 引用链
            OpenCodeServerManager.disposeForProject(project)
        }
    }

    private var isShowingStartButton = false
    @Volatile
    private var hasLoaded = false

    /**
     * 防 onStarted 5 次回调导致 onServerReady 被调 5 次(单飞在 OpenCodeServerManager
     * 端已做 5→1 进程启动,这里防止 5 个 onStarted 都进 startConsumerAndMonitor
     * 重建 consumer 5 次)。
     *
     * 每次用户点 Start(onStartClicked 入口)重置回 false;
     * onServerReady 入口 compareAndSet(false, true) 拦截后续 4 次。
     */
    private val isServerReadyHandled = AtomicBoolean(false)

    /**
     * 工具窗口内容加载入口(用户首次打开 toolWindow 时调一次)。
     *
     * 手动模式:不做 TCP 探测,永远显示 Start 按钮。server 启停完全由用户点 Start 按钮控制。
     */
    fun checkAndLoadContent() {
        showServerNotRunning()
    }

    /**
     * 创建/复用 SSE consumer。startServer() 成功后也会调。
     * onConnectionLost 快速通道:server 主动 shutdown 时绕过 15s debounce 立即显示 Start 按钮。
     * onConnectionEstablished 通道(Part C):SSE 重建 1.5s debounce 后自动调 loadProjectPage 恢复 UI。
     *
     * [Fix 线程违规] onConnectionLost/onConnectionEstablished 在 LaunchDarkly 后台线程被调,
     * showServerNotRunning/loadProjectPage 内部含 Swing UI 操作(disposeBrowser/showStartButton),
     * 必须 wrap 进 invokeLater 切到 EDT,否则违反 Swing 线程模型 → UI 损坏/死锁。
     */
    private fun startConsumerAndMonitor() {
        OpenCodeServerManager.getOrCreateConsumer(
            project = project,
            onConnectionLost = {
                ApplicationManager.getApplication().invokeLater {
                    showServerNotRunning()
                }
            },
            onConnectionEstablished = {
                ApplicationManager.getApplication().invokeLater {
                    loadProjectPage(force = true)
                }
            }
        )
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
        browserPanel.showStartButton { onStartClicked() }
        // [Fix 启动时序] 停止后台线程,避免 server 未运行时线程空转:
        // - SSE consumer.watchdog(30s 后每 5s reconnect,连接风暴)
        // - FullRefreshCoordinator(ScheduledExecutorService 常驻)
        // - EventSource 内部 LaunchDarkly 线程(指数退避重试)
        // disposeForProject 幂等(remove 返回 null 时 no-op),用户点 Start 后 startServer() 会重新创建
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

    /**
     * 用户点 Start 按钮的回调。每次点击都重置 CAS 守卫,
     * 允许上一次启动失败后重试时 onServerReady 能正常进入。
     */
    fun onStartClicked() {
        isServerReadyHandled.set(false)
        OpenCodeServerManager.startServer(
            project = project,
            onStarted = { ApplicationManager.getApplication().invokeLater { onServerReady() } },
            onFailed  = { e -> ApplicationManager.getApplication().invokeLater { onServerFailed(e) } }
        )
    }

    /**
     * server 启动成功的回调(经 OpenCodeServerManager 单飞后,5 个 onStarted 都会触发)。
     * CAS 守卫拦截后续 4 次,只让第一个真跑 startConsumerAndMonitor + loadProjectPage。
     *
     * 注意:失败时 try-catch 只 log,不重置 isServerReadyHandled——下次用户点 Start
     * 时 onStartClicked 入口会重置,语义最清晰。
     */
    private fun onServerReady() {
        if (!isServerReadyHandled.compareAndSet(false, true)) return
        try {
            startConsumerAndMonitor()
            loadProjectPage(force = true)
        } catch (e: Exception) {
            thisLogger().warn("[MyToolWindow] onServerReady failed: ${e.message}", e)
        }
    }

    /**
     * server 启动失败的回调(单飞后 5 个 onFailed 都会触发,但幂等只 log)。
     * UI 回按钮态由 onConnectionLost 兜底(单飞层已完成进程清理,server 进程退出触发 SSE onClosed)。
     */
    private fun onServerFailed(e: Exception) {
        thisLogger().error("[MyToolWindow] Server start failed: ${e.message}", e)
        // 不主动 reset isServerReadyHandled——等用户下次点 Start 时 onStartClicked 入口重置
    }

    private fun loadProjectPage(force: Boolean = false) {
        if (project.isDisposed) {
            thisLogger().info("[loadProjectPage] skipped: project already disposed")
            return
        }
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
}
