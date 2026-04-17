package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.github.xausky.opencodewebui.utils.SessionHelper
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.content.ContentFactory
import com.intellij.ide.BrowserUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import java.awt.Component
import java.io.File
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JPanel
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessHandler

/**
 * OpenCode Web UI 工具窗口工厂类
 */
class MyToolWindowFactory : ToolWindowFactory {

    companion object {
        private const val PORT = 12396
        private const val HOST = "127.0.0.1"

        private val serverRunning = AtomicBoolean(false)
        private val serverProcess = AtomicReference<ProcessHandler?>(null)
        private var checkScheduledFuture: ScheduledFuture<*>? = null
        private var updateScheduledFuture: ScheduledFuture<*>? = null
        private var myToolWindowInstance: MyToolWindow? = null

        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenCode-Server-Checker")
        }

        fun stopServer() {
            try {
                checkScheduledFuture?.cancel(true)
                checkScheduledFuture = null
                updateScheduledFuture?.cancel(true)
                updateScheduledFuture = null

                serverProcess.get()?.let { handler ->
                    if (!handler.isProcessTerminated) {
                        handler.destroyProcess()
                        thisLogger().info("OpenCode server stopped (via ProcessHandler)")
                    }
                }

                killProcessByPort(PORT)

                serverRunning.set(false)
                serverProcess.set(null)
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }

        private fun killProcessByPort(port: Int) {
            try {
                val os = System.getProperty("os.name").lowercase()
                val command = when {
                    os.contains("mac") || os.contains("nix") || os.contains("nux") ->
                        listOf("sh", "-c", "lsof -i :$port -t | xargs kill -9 2>/dev/null || true")
                    os.contains("win") ->
                        listOf("cmd", "/c", "for /f \"tokens=5\" %a in ('netstat -ano ^| findstr :$port ^| findstr LISTENING') do taskkill /F /PID %a")
                    else -> {
                        thisLogger().warn("Unsupported OS for killProcessByPort: $os")
                        return
                    }
                }

                val process = ProcessBuilder(command).start()
                process.waitFor(5, TimeUnit.SECONDS)
                thisLogger().info("Attempted to kill process on port $port")
            } catch (e: Exception) {
                thisLogger().error("Error killing process by port: ${e.message}")
            }
        }

        // 新增：全局健康状态（由健康检查线程写入）
        private val serverHealthy = AtomicBoolean(false)

        // 新增：启动全局健康检查（IDEA启动时调用）
        fun startGlobalHealthCheck() {
            Thread({
                while (true) {
                    val healthy = OpenCodeApi.isServerHealthySync()
                    serverHealthy.set(healthy)
                    thisLogger().info("Global health check: $healthy")
                    Thread.sleep(5000)
                }
            }).start()
        }

        // 供工具窗口读取
        fun isServerHealthy() = serverHealthy.get()

        /**
         * 获取完整的环境变量，包含用户在 shell 配置文件中设置的内容
         */
        private fun getFullEnvironment(): Map<String, String> {
            val env = System.getenv().toMutableMap()

            val home = System.getProperty("user.home")
            listOf(".bashrc", ".zshrc").forEach { fileName ->
                val file = File(home, fileName)
                if (file.exists()) {
                    file.readLines()
                        .filter { it.trim().startsWith("export ") }
                        .forEach { line ->
                            val match = Regex("""export\s+([A-Za-z_][A-Za-z0-9_]*)=["']?([^"'$\s]+)["']?""")
                                .find(line)
                            match?.let {
                                val key = it.groupValues[1]
                                val value = it.groupValues[2]
                                    .removeSurrounding("\"", "'")
                                if (env[key] == null || value.length > (env[key]?.length ?: 0)) {
                                    env[key] = value
                                }
                            }
                        }
                }
            }

            return env
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

            toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
                override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                    if (event.content === content) {
                        myToolWindow.requestBrowserFocus()
                    }
                }
            })

            myToolWindow.setupBrowserKeyboardHandling()
            myToolWindow.checkAndLoadContent()
            myToolWindow.startPeriodicUpdateCheck()
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    inner class MyToolWindow(toolWindow: ToolWindow) {

        private val project = toolWindow.project
        private val browserPanel = BrowserPanel(HOST, PORT)
        private var mainBrowser: JBCefBrowser? = null

        init {
            setupWindowFocusListener(toolWindow)
            myToolWindowInstance = this
        }

        private fun isServerHealthy(): Boolean = serverHealthy.get()

        private var isShowingStartButton = false

        private fun startHealthMonitoring() {
            Thread({
                while (true) {
                    Thread.sleep(2000)
                    val healthy = serverHealthy.get()
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
            }).start()
        }

        private fun isServerRunning(): Boolean {
            return if (isServerHealthy()) {
                serverRunning.set(true)
                true
            } else {
                serverRunning.set(false)
                false
            }
        }

        fun checkAndLoadContent() {
            startHealthMonitoring()
            if (isServerHealthy()) {
                loadProjectPage()
            } else {
                showServerNotRunning()
            }
        }

        private fun showServerNotRunning() {
            serverRunning.set(false)
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

        // 【九】定期检查更新（定时任务，每1小时执行一次）
        // 调用此函数 → 每1小时调用 OpenCodeApi.checkAndPerformUpgrade
        fun startPeriodicUpdateCheck() {
            if (updateScheduledFuture != null) return

            val future = scheduler.scheduleAtFixedRate(
                {
                    try {
                        ApplicationManager.getApplication().invokeLater {
                            checkAndPerformUpgrade()
                        }
                    } catch (e: Exception) {
                        thisLogger().warn("Periodic update check skipped: ${e.message}")
                    }
                },
                1L,
                1L,
                TimeUnit.HOURS
            )
            updateScheduledFuture = future
            thisLogger().info("Started periodic update check (every 1 hour)")
        }

        private fun checkAndPerformUpgrade() {
            if (!isServerHealthy()) return
            OpenCodeApi.checkAndPerformUpgrade { result ->
                if (result.success) {
                    thisLogger().info("OpenCode upgraded successfully: ${result.message}")
                } else {
                    thisLogger().warn("OpenCode upgrade check: ${result.message}")
                }
            }
        }

        private fun startOpenCodeServer() {
            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val handler = startOpenCodeProcess()
                        serverProcess.set(handler)

                        if (OpenCodeApi.waitForServerHealthy(30000)) {
                            onServerStarted()
                        } else {
                            thisLogger().warn("Server may not be ready, proceeding anyway")
                            onServerStarted()
                        }
                    } catch (e: Exception) {
                        onServerStartFailed(e)
                    }
                }
            }
            ProgressManager.getInstance().run(task)
        }

        private fun startOpenCodeProcess(): ProcessHandler {
            val commandLine = GeneralCommandLine(getOpenCodeCommand())
            val fullEnv = getFullEnvironment()
            commandLine.environment.clear()
            commandLine.environment.putAll(fullEnv)

            return ProcessHandlerFactory.getInstance()
                .createProcessHandler(commandLine)
        }

        private fun onServerStarted() {
            serverRunning.set(true)
            ApplicationManager.getApplication().invokeLater { loadProjectPage() }
        }

        private fun onServerStartFailed(e: Exception) {
            thisLogger().error("Error starting OpenCode server: ${e.message}")
            serverRunning.set(false)
            ApplicationManager.getApplication().invokeLater { showErrorInBrowser() }
        }

        private fun loadProjectPage() {
            isShowingStartButton = false
            // 简化：直接加载首页，不指定 project 和 session
            val url = "http://$HOST:$PORT"
            // val projectPath = project.basePath ?: return
            // val sessionId = SessionHelper.getLatestSessionId(projectPath)
            // val url = buildProjectUrl(projectPath, sessionId)
            thisLogger().info("Loading page: $url")
            browserPanel.hideStartButton()
            if (mainBrowser == null) {
                // mainBrowser = browserPanel.createMainTab(url, projectPath)
                mainBrowser = browserPanel.createMainTab(url)
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

        private fun getOpenCodeCommand(): List<String> {
            val path = findOpenCodePath()
            return listOf(path, "serve", "--hostname", HOST, "--port", PORT.toString())
        }

        private fun findOpenCodePath(): String {
            listOf("/opt/homebrew/bin/opencode", "/usr/local/bin/opencode")
                .forEach { if (java.io.File(it).exists()) return it }
            return "opencode"
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
        private val sharedClient = JBCefApp.getInstance().createClient()
        private var startButtonPanel: JPanel? = null
        private var startCallback: (() -> Unit)? = null

        companion object {
            private const val COPY_LINK_COMMAND_ID = 26500
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
        fun createMainTab(url: String): JBCefBrowser {
            browser?.cefBrowser?.stopLoad()
            browser?.dispose()
            removeAll()
            val createdBrowser = JBCefBrowserBuilder().setClient(sharedClient).setUrl(url).build()
            this.browser = createdBrowser
            add(createdBrowser.component, java.awt.BorderLayout.CENTER)
            sharedClient.addLifeSpanHandler(ExternalLinkLifeSpanHandler(), createdBrowser.cefBrowser)
            sharedClient.addContextMenuHandler(LinkContextMenuHandler(), createdBrowser.cefBrowser)
            sharedClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    /*
                    val js = """
                        try {
                            var serverKey = 'opencode.global.dat:server';
                            var raw = localStorage.getItem(serverKey);
                            var store = raw ? JSON.parse(raw) : { list: [], projects: {}, lastProject: {} };
                            if (!store.list) store.list = [];
                            if (!store.projects) store.projects = {};
                            if (!store.lastProject) store.lastProject = {};
                            var origin = location.origin;
                            var isLocal = origin.includes('localhost') || origin.includes('127.0.0.1');
                            var serverKeyName = isLocal ? 'local' : origin;
                            var alreadySet = store.projects[serverKeyName] && store.projects[serverKeyName].some(function(p) { return p.worktree === '$projectPath'; });
                            if (!alreadySet) {
                                if (!store.list.includes(origin)) store.list.push(origin);
                                if (!store.projects[serverKeyName]) store.projects[serverKeyName] = [];
                                store.projects[serverKeyName].push({ worktree: '$projectPath', expanded: true });
                                store.lastProject[serverKeyName] = '$projectPath';
                                localStorage.setItem(serverKey, JSON.stringify(store));
                                setTimeout(function() { location.reload(); }, 100);
                            }
                        } catch(e) { console.log('opencode localStorage error: ' + e.message); }
                    """.trimIndent()
                    cefBrowser?.executeJavaScript(js, "", 0)
                    */
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
        }

        fun getBrowser(): JBCefBrowser? = browser

        fun disposeBrowser() {
            browser?.cefBrowser?.stopLoad()
            browser?.dispose()
            removeAll()
            browser = null
        }

        private inner class ExternalLinkLifeSpanHandler : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(
                browser: CefBrowser,
                frame: CefFrame,
                target_url: String,
                target_frame_name: String
            ): Boolean {
                if (isExternalUrl(target_url)) {
                    ApplicationManager.getApplication().invokeLater {
                        BrowserUtil.browse(java.net.URI(target_url))
                    }
                    return true
                }
                return false
            }
        }

        private fun isExternalUrl(url: String?): Boolean {
            if (url == null) return false
            return try {
                val uri = java.net.URI(url)
                val host = uri.host ?: return false
                val scheme = uri.scheme ?: return false

                if (scheme !in listOf("http", "https")) return false

                if (host == "localhost" || host == "127.0.0.1" ||
                    host.startsWith("192.168.") || host.startsWith("10.") ||
                    host == "0.0.0.0" || host == this@BrowserPanel.host) {
                    return false
                }

                true
            } catch (e: Exception) {
                false
            }
        }

        private inner class LinkContextMenuHandler : org.cef.handler.CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(
                browser: CefBrowser,
                frame: CefFrame,
                params: CefContextMenuParams,
                model: CefMenuModel
            ) {
                val linkUrl = params.linkUrl
                if (!linkUrl.isNullOrEmpty()) {
                    model.addItem(COPY_LINK_COMMAND_ID, "在浏览器中打开 Open in Browser")
                }
            }

            override fun onContextMenuCommand(
                browser: CefBrowser,
                frame: CefFrame,
                params: CefContextMenuParams,
                commandId: Int,
                eventFlags: Int
            ): Boolean {
                if (commandId == COPY_LINK_COMMAND_ID) {
                    val linkUrl = params.linkUrl
                    if (!linkUrl.isNullOrEmpty() && isExternalUrl(linkUrl)) {
                        BrowserUtil.browse(java.net.URI(linkUrl))
                    }
                    return true
                }
                return false
            }
        }
    }
}
