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

/**
 * OpenCode Web UI 工具窗口工厂类
 */
class MyToolWindowFactory : ToolWindowFactory {

    companion object {
        private const val PORT = 12396
        private const val HOST = "127.0.0.1"

        private val browserInstance = AtomicReference<JBCefBrowser?>(null)
        private val serverRunning = AtomicBoolean(false)
        private val serverProcess = AtomicReference<Process?>(null)
        private var checkScheduledFuture: ScheduledFuture<*>? = null
        private val hasInitializedOnStartup = AtomicBoolean(false)
        private val isRestarting = AtomicBoolean(false)
        private var myToolWindowInstance: MyToolWindow? = null

        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenCode-Server-Checker")
        }

        fun stopServer() {
            try {
                checkScheduledFuture?.cancel(true)
                checkScheduledFuture = null

                val process = serverProcess.get()
                if (process?.isAlive == true) {
                    process.destroy()
                    thisLogger().info("OpenCode server stopped (via process reference)")
                }

                killProcessByPort(PORT)

                serverRunning.set(false)
                serverProcess.set(null)
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }

        fun restartServer(project: Project?) {
            if (!isRestarting.compareAndSet(false, true)) {
                thisLogger().info("Restart already in progress, skipping")
                return
            }

            try {
                stopServer()

                if (project == null || myToolWindowInstance == null) {
                    return
                }

                val projectPath = project.basePath ?: return
                OpenCodeApi.getLatestSessionId(projectPath) { sessionId ->
                    val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
                    val url = if (sessionId != null) {
                        "http://$HOST:$PORT/$encodedPath/session/$sessionId"
                    } else {
                        "http://$HOST:$PORT/$encodedPath"
                    }

                    startServerInternal(project) {
                        ApplicationManager.getApplication().invokeLater {
                            myToolWindowInstance?.restartBrowser(url, projectPath)
                        }
                    }
                }
            } finally {
                isRestarting.set(false)
            }
        }

        private fun startServerInternal(project: Project, onSuccess: () -> Unit) {
            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val processBuilder = ProcessBuilder()
                            .command(getOpenCodeCommand())
                            .redirectErrorStream(true)

                        processBuilder.environment().putAll(getEnvironment())

                        val process = processBuilder.start()
                        serverProcess.set(process)

                        var maxAttempts = 30
                        val startTime = System.currentTimeMillis()
                        val timeout = 60000L

                        while (maxAttempts-- > 0 && (System.currentTimeMillis() - startTime) < timeout) {
                            if (process.isAlive && checkPortOpenInternal()) {
                                break
                            }
                            Thread.sleep(1000)
                        }

                        if (checkPortOpenInternal()) {
                            thisLogger().info("OpenCode server started successfully")
                            serverRunning.set(true)
                            ApplicationManager.getApplication().invokeLater {
                                onSuccess()
                            }
                        } else {
                            val exitCode = if (process.isAlive) "still running" else "exited with code ${process.exitValue()}"
                            thisLogger().warn("Failed to start OpenCode server, process $exitCode")
                            serverRunning.set(false)
                        }
                    } catch (e: Exception) {
                        thisLogger().error("Error starting OpenCode server: ${e.message}")
                        serverRunning.set(false)
                    }
                }
            }
            ProgressManager.getInstance().run(task)
        }

        private fun getOpenCodeCommand(): List<String> {
            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            return if (isMac) {
                listOf("/bin/zsh", "-l", "-c", "opencode serve --port $PORT")
            } else {
                listOf("opencode", "serve", "--port", PORT.toString())
            }
        }

        private fun getEnvironment(): Map<String, String> {
            val env = mutableMapOf<String, String>()
            env.putAll(System.getenv())

            val originalPath = env["PATH"] ?: ""
            val additionalPaths = listOf(
                "/usr/local/bin",
                "/opt/homebrew/bin",
                "${System.getenv("HOME")}/.npm-global/bin",
                "${System.getenv("HOME")}/.local/bin"
            ).filterNot { originalPath.contains(it) }

            env["PATH"] = (additionalPaths + originalPath).joinToString(":")
            return env
        }

        private fun checkPortOpenInternal(): Boolean {
            return try {
                val socket = java.net.Socket(HOST, PORT)
                socket.soTimeout = 2000
                socket.close()
                true
            } catch (e: Exception) {
                false
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
    }

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
            myToolWindow.startPeriodicCheck()
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

        fun restartBrowser(url: String, projectPath: String) {
            thisLogger().info("Disposing old browser instance...")
            browserPanel.disposeBrowser()
            thisLogger().info("Creating new browser instance...")
            mainBrowser = browserPanel.createMainTab(url, projectPath)
            browserInstance.set(mainBrowser)
            setupBrowserKeyboardHandling()
            thisLogger().info("New browser created and ready")
            isRestarting.set(false)
        }

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

        fun checkAndLoadContent() {
            println("=== checkAndLoadContent START: mainBrowser=${mainBrowser == null}")
            if (mainBrowser == null) {
                val projectPath = project.basePath
                println("=== checkAndLoadContent: projectPath=$projectPath")
                if (projectPath == null) {
                    println("=== checkAndLoadContent: projectPath is null, returning")
                    return
                }
                println("=== checkAndLoadContent: calling OpenCodeApi asynchronously")
                OpenCodeApi.getLatestSessionId(projectPath) { sessionId ->
                    println("=== checkAndLoadContent: sessionId=$sessionId (async callback)")
                    val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
                    val url = if (sessionId != null) {
                        "http://$HOST:$PORT/$encodedPath/session/$sessionId"
                    } else {
                        "http://$HOST:$PORT/$encodedPath"
                    }
                    println("=== checkAndLoadContent: calling createMainTab with url=$url")
                    mainBrowser = browserPanel.createMainTab(url, projectPath)
                    browserInstance.set(mainBrowser)
                    setupBrowserComponent(mainBrowser!!)
                    thisLogger().info("Created main browser with URL: $url")
                }
                return
            }

            if (hasInitializedOnStartup.get()) {
                if (checkPortOpen(HOST, PORT)) {
                    thisLogger().info("Port $PORT is already open, reusing existing server")
                    serverRunning.set(true)
                    loadProjectPage()
                } else {
                    thisLogger().info("Port $PORT is not open, starting opencode serve...")
                    startOpenCodeServer()
                }
            } else {
                hasInitializedOnStartup.set(true)
                thisLogger().info("First initialization on IDE startup, ensuring latest opencode version")
                stopServer()
                startOpenCodeServer()
            }
        }

        private fun checkPortOpen(host: String, port: Int): Boolean {
            return try {
                val socket = java.net.Socket(host, port)
                socket.soTimeout = 2000
                socket.close()
                true
            } catch (e: Exception) {
                thisLogger().info("Port check failed: ${e.message}")
                false
            }
        }

        fun startPeriodicCheck() {
            if (checkScheduledFuture != null) return

            val future = scheduler.scheduleAtFixedRate(
                {
                    try {
                        ApplicationManager.getApplication().invokeLater {
                            checkServerHealth()
                        }
                    } catch (e: Exception) {
                        thisLogger().warn("Periodic check skipped: ${e.message}")
                    }
                },
                30L,
                30L,
                TimeUnit.SECONDS
            )
            checkScheduledFuture = future
            thisLogger().info("Started periodic server health check")
        }

        private fun checkServerHealth() {
            if (!checkPortOpen(HOST, PORT)) {
                thisLogger().warn("Server is not responding, attempting to restart...")
                serverProcess.get()?.let { process ->
                    if (process.isAlive) {
                        process.destroy()
                    }
                }
                startOpenCodeServer()
            }
        }

        private fun startOpenCodeServer() {
            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val processBuilder = ProcessBuilder()
                            .command(getOpenCodeCommand())
                            .redirectErrorStream(true)

                        processBuilder.environment().putAll(getEnvironment())

                        val process = processBuilder.start()
                        serverProcess.set(process)

                        var maxAttempts = 30
                        val startTime = System.currentTimeMillis()
                        val timeout = 60000L

                        while (maxAttempts-- > 0 && (System.currentTimeMillis() - startTime) < timeout) {
                            if (process.isAlive && checkPortOpen(HOST, PORT)) {
                                break
                            }
                            Thread.sleep(1000)
                        }

                        if (checkPortOpen(HOST, PORT)) {
                            thisLogger().info("OpenCode server started successfully")
                            serverRunning.set(true)
                            ApplicationManager.getApplication().invokeLater {
                                loadProjectPage()
                            }
                        } else {
                            thisLogger().error("Failed to start OpenCode server")
                            ApplicationManager.getApplication().invokeLater {
                                showErrorInBrowser()
                            }
                            serverRunning.set(false)
                        }
                    } catch (e: Exception) {
                        thisLogger().error("Error starting OpenCode server: ${e.message}")
                        ApplicationManager.getApplication().invokeLater {
                            showErrorInBrowser()
                        }
                        serverRunning.set(false)
                    }
                }
            }
            ProgressManager.getInstance().run(task)
        }

        private fun loadProjectPage() {
            val projectPath = project.basePath ?: return
            OpenCodeApi.getLatestSessionId(projectPath) { sessionId ->
                val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
                val url = if (sessionId != null) {
                    "http://$HOST:$PORT/$encodedPath/session/$sessionId"
                } else {
                    "http://$HOST:$PORT/$encodedPath"
                }
                thisLogger().info("Loading page: $url")
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

        companion object {
            private const val COPY_LINK_COMMAND_ID = 26500
        }

        init {
            layout = java.awt.BorderLayout()
        }

        fun createMainTab(url: String, projectPath: String): JBCefBrowser {
            println("=== createMainTab START: url=$url, projectPath=$projectPath")
            val createdBrowser = JBCefBrowserBuilder().setClient(sharedClient).setUrl(url).build()
            this.browser = createdBrowser
            println("=== createMainTab: browser created, adding to panel")
            add(createdBrowser.component, java.awt.BorderLayout.CENTER)
            sharedClient.addLifeSpanHandler(ExternalLinkLifeSpanHandler(), createdBrowser.cefBrowser)
            sharedClient.addContextMenuHandler(LinkContextMenuHandler(), createdBrowser.cefBrowser)
            println("=== createMainTab: adding loadHandler")
            sharedClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    println("=== createMainTab onLoadEnd: httpStatusCode=$httpStatusCode")
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
                            // Also set layout to ensure sidebar is visible
                            var layoutKey = 'opencode.global.dat:layout';
                            var layoutRaw = localStorage.getItem(layoutKey);
                            if (!layoutRaw) {
                                localStorage.setItem(layoutKey, JSON.stringify({
                                    sidebar: { opened: true, width: 344, workspaces: {}, workspacesDefault: false }
                                }));
                            }
                        } catch(e) { console.log('opencode localStorage error: ' + e.message); }
                    """.trimIndent()
                    cefBrowser?.executeJavaScript(js, "", 0)
                    println("=== createMainTab onLoadEnd: JS executed")
                }
            }, createdBrowser.cefBrowser)
            println("=== createMainTab END")
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
