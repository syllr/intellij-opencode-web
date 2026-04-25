package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.github.xausky.opencodewebui.utils.SessionHelper
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import java.awt.Component
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * OpenCode Web UI 工具窗口工厂类
 *
 * 【重要】实现 DumbAware 接口：
 * IntelliJ 在 "dumb mode"（索引更新期间）会禁用需要智能功能的组件，默认情况下
 * 所有 ToolWindow 都会受到影响，显示 "This view is not available until indexes are built"。
 *
 * 实现 DumbAware 接口可以告知 IntelliJ：这个工具窗口不依赖于项目索引，
 * 在索引更新期间也可以正常使用。OpenCode 是纯浏览器 UI，不需要索引，
 * 因此实现此接口可以避免索引期间的覆盖层提示。
 *
 * 【API 警告说明】：
 * @Suppress("DEPRECATION", "EXPERIMENTAL_API_USAGE") 用于抑制 ToolWindowFactory
 * 接口的 deprecated/experimental 方法警告。这些警告来自 IntelliJ 框架本身，
 * 不是我们代码的问题 - 官方 SDK 示例同样使用相同模式。
 */
@Suppress("DEPRECATION", "EXPERIMENTAL_API_USAGE")
class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private const val PORT = OPENCODE_PORT
        private const val HOST = OPENCODE_HOST

        private val sharedJBCefClient by lazy { JBCefApp.getInstance().createClient() }

        private var checkScheduledFuture: ScheduledFuture<*>? = null
        private var myToolWindowInstance: MyToolWindow? = null

        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenCode-Server-Checker")
        }

        fun stopServer() {
            try {
                checkScheduledFuture?.cancel(true)
                checkScheduledFuture = null
                OpenCodeServerManager.stopServer()
                OpenCodeServerManager.killProcessByPort(PORT)
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }

    }

    // 【一】插件打开工具窗口时调用（调用时机：用户首次打开 OpenCode 工具窗口）
    // 调用此函数 → 创建 MyToolWindow → setupBrowserKeyboardHandling → checkAndLoadContent → startPeriodicCheck → startPeriodicUpdateCheck
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            thisLogger().warn("JCEF is not supported in this environment, browser functionality disabled")
            val errorHtml = """
                <html>
                <body style="background-color: #2B2B2B; color: #A9B7C6; font-family: sans-serif; padding: 20px;">
                    <h2>JCEF is not supported in this environment</h2>
                    <p>Please use a supported IntelliJ-based IDE with JCEF enabled.</p>
                </body>
                </html>
            """.trimIndent()
            ApplicationManager.getApplication().invokeLater {
                val content = ContentFactory.getInstance().createContent(
                    JBCefBrowser().apply { loadHTML(errorHtml) }.component, null, false
                )
                toolWindow.contentManager.addContent(content)
            }
            return
        }
        val myToolWindow = MyToolWindow(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
            toolWindow.contentManager.addContent(content)

            toolWindow.contentManager.addContentManagerListener(object :
                com.intellij.ui.content.ContentManagerListener {
                override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                    if (event.content === content) {
                        myToolWindow.requestBrowserFocus()
                    }
                }
            })

            myToolWindow.setupBrowserKeyboardHandling()
            myToolWindow.checkAndLoadContent()
        }
    }

    override suspend fun isApplicableAsync(project: Project) = true

    inner class MyToolWindow(toolWindow: ToolWindow) {

        private val project = toolWindow.project
        private val browserPanel = BrowserPanel(HOST, PORT)
        private var mainBrowser: JBCefBrowser? = null

        init {
            setupWindowFocusListener(toolWindow)
            myToolWindowInstance = this
        }

        private var isShowingStartButton = false
        private var healthMonitoringStarted = false
        private var lastHealthState: Boolean? = null

        private fun startHealthMonitoring() {
            if (healthMonitoringStarted) return
            healthMonitoringStarted = true
            Thread({
                // 如果上次状态是健康的，等10秒再开始检查，给服务器稳定窗口期
                if (lastHealthState == true) {
                    Thread.sleep(10000)
                }
                while (true) {
                    Thread.sleep(5000)
                    val healthy = OpenCodeApi.isServerHealthySync()
                    if (healthy != lastHealthState) {
                        lastHealthState = healthy
                        if (!healthy && !isShowingStartButton && mainBrowser != null) {
                            ApplicationManager.getApplication().invokeLater {
                                showServerNotRunning()
                            }
                        } else if (healthy && isShowingStartButton) {
                            ApplicationManager.getApplication().invokeLater {
                                loadProjectPage()
                            }
                        }
                    }
                }
            }).start()
        }

        private fun isServerRunning(): Boolean {
            return OpenCodeServerManager.isServerRunning()
        }

        fun checkAndLoadContent() {
            val healthy = OpenCodeApi.isServerHealthySync()
            lastHealthState = healthy
            startHealthMonitoring()
            if (healthy) {
                loadProjectPage()
            } else {
                showServerNotRunning()
            }
        }

        private fun showServerNotRunning() {
            mainBrowser = null
            isShowingStartButton = true
            browserPanel.disposeBrowser()
            browserPanel.showStartButton { startOpenCodeServer() }
        }

        private fun setupWindowFocusListener(toolWindow: ToolWindow) {
            browserPanel.addHierarchyListener {
                SwingUtilities.getWindowAncestor(browserPanel)?.let { window ->
                    window.addWindowFocusListener(object : WindowAdapter() {
                        override fun windowGainedFocus(e: WindowEvent?) {
                            if (toolWindow.isVisible) {
                                requestBrowserFocus()
                            }
                        }
                    })
                }
            }
        }

        fun getContent() = browserPanel

        fun setupBrowserKeyboardHandling() {
            val panel = browserPanel
            setupComponent(panel)
            setupComponentHierarchy(panel)
            val osrComponent = (browserPanel.getBrowser()?.cefBrowser?.uiComponent as? JComponent)
            osrComponent?.let {
                setupComponent(it)
                addEmacsKeyListener(it)
            }
        }

        private fun setupBrowserComponent(browser: JBCefBrowser) {
            val osrComponent = browser.cefBrowser.uiComponent
            val browserComponent = browser.component

            osrComponent?.let { comp ->
                setupComponent(comp)
                addEmacsKeyListener(comp)
            }

            browserComponent?.let { comp ->
                setupComponent(comp)
            }
        }

        private fun setupComponent(component: Component) {
            if (component !is JComponent) return

            component.focusTraversalKeysEnabled = false

            val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            val actionMap = component.actionMap

            val emptyAction = object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                }
            }

            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "block")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, java.awt.event.InputEvent.META_DOWN_MASK), "block")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, java.awt.event.InputEvent.META_DOWN_MASK), "block")
            actionMap.put("block", emptyAction)
        }

        private fun setupComponentHierarchy(component: Component?) {
            if (component == null) return
            setupComponent(component)
            if (component is java.awt.Container) {
                for (i in 0 until component.componentCount) {
                    setupComponentHierarchy(component.getComponent(i))
                }
            }
        }

        private fun addEmacsKeyListener(component: Component) {
            val emacsMappings = mapOf(
                KeyEvent.VK_N to KeyEvent.VK_DOWN,
                KeyEvent.VK_P to KeyEvent.VK_UP,
                KeyEvent.VK_E to KeyEvent.VK_END,
                KeyEvent.VK_A to KeyEvent.VK_HOME,
                KeyEvent.VK_B to KeyEvent.VK_LEFT,
                KeyEvent.VK_F to KeyEvent.VK_RIGHT
            )

            component.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    handleEmacsKey(e, emacsMappings)
                }

                override fun keyReleased(e: KeyEvent) {
                    handleEmacsKey(e, emacsMappings)
                }

                private fun handleEmacsKey(e: KeyEvent, mappings: Map<Int, Int>) {
                    if ((e.modifiersEx and KeyEvent.CTRL_DOWN_MASK) == 0) return
                    val targetKeyCode = mappings[e.keyCode] ?: return
                    sendKeyEvent(component, e.id, targetKeyCode, 0)
                    e.consume()
                }
            })
        }

        private fun sendKeyEvent(target: Component, eventId: Int, keyCode: Int, modifiers: Int) {
            val keyEvent = KeyEvent(
                target,
                eventId,
                System.currentTimeMillis(),
                modifiers,
                keyCode,
                KeyEvent.CHAR_UNDEFINED
            )
            target.dispatchEvent(keyEvent)
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
            ApplicationManager.getApplication().invokeLater { loadProjectPage() }
        }

        private fun onServerStartFailed(e: Exception) {
            thisLogger().error("Error starting OpenCode server: ${e.message}")
            ApplicationManager.getApplication().invokeLater { showErrorInBrowser() }
        }

        private fun loadProjectPage() {
            isShowingStartButton = false
            val projectPath = project.basePath ?: return
            // 获取 session ID 并构建完整 URL
            val sessionId = SessionHelper.getLatestSessionId(projectPath)
            val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
            val url = if (sessionId != null) {
                "http://$HOST:$PORT/$encodedPath/session/$sessionId"
            } else {
                "http://$HOST:$PORT/$encodedPath"
            }
            thisLogger().info("Loading page: $url")
            browserPanel.hideStartButton()
            if (mainBrowser == null) {
                mainBrowser = browserPanel.createMainTab(url, projectPath)
                setupBrowserComponent(mainBrowser!!)
            } else {
                mainBrowser?.loadURL(url)
            }
        }

        private fun buildProjectUrl(projectPath: String, sessionId: String?): String {
            val encodedPath = Base64.getEncoder()
                .encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
            return if (sessionId != null) {
                "http://$HOST:$PORT/$encodedPath/session/$sessionId"
            } else {
                "http://$HOST:$PORT/$encodedPath"
            }
        }

        private fun showErrorInBrowser() {
            val html = """
                <html>
                <body style="background-color: #2B2B2B; color: #A9B7C6; font-family: sans-serif; padding: 20px;">
                    <h2>Failed to start OpenCode server</h2>
                    <p>Please make sure 'opencode' is installed and available in your PATH.</p>
                    <p>Run the following command to start the server manually:</p>
                    <pre style="background: #3C3F41; padding: 10px; border-radius: 4px;">opencode serve --hostname $HOST --port $PORT</pre>
                </body>
                </html>
            """.trimIndent()
            mainBrowser?.loadHTML(html)
        }
    }

    class BrowserPanel(private val host: String, private val port: Int) : JPanel() {
        private var browser: JBCefBrowser? = null

        // 共享的 JBCefClient，所有 BrowserPanel 实例共享同一个渲染进程，localStorage 也因此共享
        private val sharedClient: JBCefClient = sharedJBCefClient
        private var startButtonPanel: JPanel? = null
        private var startCallback: (() -> Unit)? = null

        companion object {
            private const val COPY_LINK_COMMAND_ID = 26500
            private const val REFRESH_COMMAND_ID = 26501
            private const val SHUTDOWN_COMMAND_ID = 26502
            private const val COPY_LINK_AS_PROMPT_COMMAND_ID = 26503
        }

        init {
            layout = java.awt.BorderLayout()
        }

        fun showStartButton(callback: () -> Unit) {
            startCallback = callback
            removeAll()
            revalidate()
            repaint()

            startButtonPanel = JPanel().apply {
                layout = java.awt.GridBagLayout()
                background = java.awt.Color(43, 43, 43)
                isOpaque = true

                val gbc = java.awt.GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    insets = java.awt.Insets(10, 10, 10, 10)
                    anchor = java.awt.GridBagConstraints.CENTER
                }

                val label = javax.swing.JLabel("OpenCode 服务器未运行").apply {
                    foreground = java.awt.Color(169, 183, 198)
                    font = javax.swing.UIManager.getFont("Label.font").deriveFont(18f)
                }
                add(label, gbc)

                gbc.gridy = 1
                val descLabel = javax.swing.JLabel("点击下方按钮启动服务器").apply {
                    foreground = java.awt.Color(169, 183, 198)
                }
                add(descLabel, gbc)

                gbc.gridy = 2
                gbc.insets = java.awt.Insets(20, 10, 10, 10)
                val cmdLabel = javax.swing.JLabel("opencode serve --hostname $host --port $port").apply {
                    foreground = java.awt.Color(126, 180, 180)
                    font = javax.swing.UIManager.getFont("Label.font").deriveFont(12f)
                }
                add(cmdLabel, gbc)

                gbc.gridy = 3
                gbc.insets = java.awt.Insets(20, 10, 10, 10)
                val button = javax.swing.JButton("启动 OpenCode 服务器").apply {
                    background = java.awt.Color(0, 120, 212)
                    foreground = java.awt.Color.WHITE
                    isOpaque = true
                    isContentAreaFilled = true
                    font = javax.swing.UIManager.getFont("Button.font")
                    addActionListener {
                        startCallback?.invoke()
                    }
                }
                add(button, gbc)
            }
            add(startButtonPanel, java.awt.BorderLayout.CENTER)
            revalidate()
            repaint()
        }

        fun hideStartButton() {
            startButtonPanel?.let {
                remove(it)
                startButtonPanel = null
            }
            revalidate()
            repaint()
        }

        // 【七】创建浏览器 Tab 并加载 URL
        fun createMainTab(url: String, projectPath: String): JBCefBrowser {
            if (browser == null) {
                val createdBrowser = JBCefBrowserBuilder().setClient(sharedClient).setUrl(url).build()
                this.browser = createdBrowser
                add(createdBrowser.component, java.awt.BorderLayout.CENTER)
                sharedClient.addContextMenuHandler(LinkContextMenuHandler(), createdBrowser.cefBrowser)
                sharedClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                    override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        thisLogger().info("onLoadEnd called, projectPath: $projectPath")
                        val escapedProjectPath = projectPath.replace("\\", "\\\\").replace("'", "\\'")
                        val js = """
                            (function() {
                                try {
                                    var serverKey = 'opencode.global.dat:server';
                                    var raw = localStorage.getItem(serverKey);
                                    var store = raw ? JSON.parse(raw) : { list: [], projects: {}, lastProject: {} };
                                    store.list = store.list || [];
                                    store.projects = store.projects || {};
                                    store.lastProject = store.lastProject || {};
                                    var origin = location.origin;
                                    var isLocal = origin.includes('localhost') || origin.includes('127.0.0.1');
                                    var serverKeyName = isLocal ? 'local' : origin;
                                    var projectPath = '$escapedProjectPath';
                                    store.projects[serverKeyName] = (store.projects[serverKeyName] || []).filter(function(p) { return p.worktree !== projectPath; });
                                    if (!store.list.includes(origin)) store.list.push(origin);
                                    store.projects[serverKeyName].push({ worktree: projectPath, expanded: true });
                                    store.lastProject[serverKeyName] = projectPath;
                                    localStorage.setItem(serverKey, JSON.stringify(store));
                                } catch(e) {
                                    console.error('opencode localStorage error: ' + e.message);
                                }
                            })();
                        """.trimIndent()
                        cefBrowser?.executeJavaScript(js, "", 0)
                    }
                }, createdBrowser.cefBrowser)
                sharedClient.addDisplayHandler(object : org.cef.handler.CefDisplayHandlerAdapter() {
                    override fun onConsoleMessage(
                        browser: CefBrowser?,
                        level: org.cef.CefSettings.LogSeverity?,
                        message: String?,
                        source: String?,
                        line: Int
                    ): Boolean {
                        thisLogger().info("[JCEF Console] $message (source: $source:$line)")
                        return false
                    }
                }, createdBrowser.cefBrowser)
                return createdBrowser
            } else {
                browser?.loadURL(url)
                return browser!!
            }
        }

        fun getBrowser(): JBCefBrowser? = browser

        fun disposeBrowser() {
            browser?.cefBrowser?.stopLoad()
            browser?.dispose()
            removeAll()
            browser = null
        }

        private inner class LinkContextMenuHandler : org.cef.handler.CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(
                browser: CefBrowser,
                frame: CefFrame,
                params: CefContextMenuParams,
                model: CefMenuModel
            ) {
                model.clear()
                model.addItem(100, "Back")
                model.setEnabled(100, browser.canGoBack())
                model.addItem(101, "Forward")
                model.setEnabled(101, browser.canGoForward())
                model.addItem(REFRESH_COMMAND_ID, "Refresh")
                val linkUrl = params.linkUrl
                if (!linkUrl.isNullOrEmpty()) {
                    model.addItem(COPY_LINK_COMMAND_ID, "Open in Browser")
                    model.addItem(COPY_LINK_AS_PROMPT_COMMAND_ID, "Copy Link")
                }
                model.addItem(SHUTDOWN_COMMAND_ID, "Shutdown Server")
            }

            override fun onContextMenuCommand(
                browser: CefBrowser,
                frame: CefFrame,
                params: CefContextMenuParams,
                commandId: Int,
                eventFlags: Int
            ): Boolean {
                if (commandId == REFRESH_COMMAND_ID) {
                    browser.reload()
                    return true
                }
                if (commandId == COPY_LINK_COMMAND_ID) {
                    val linkUrl = params.linkUrl
                    if (!linkUrl.isNullOrEmpty()) {
                        BrowserUtil.browse(java.net.URI(linkUrl))
                    }
                    return true
                }
                if (commandId == COPY_LINK_AS_PROMPT_COMMAND_ID) {
                    val linkUrl = params.linkUrl
                    if (!linkUrl.isNullOrEmpty()) {
                        val selection = StringSelection(linkUrl)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                    }
                    return true
                }
                if (commandId == SHUTDOWN_COMMAND_ID) {
                    stopServer()
                    return true
                }
                return false
            }
        }
    }
}
