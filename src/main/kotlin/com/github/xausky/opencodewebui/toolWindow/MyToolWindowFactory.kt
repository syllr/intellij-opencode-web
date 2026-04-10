package com.github.xausky.opencodewebui.toolWindow

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
import com.intellij.ui.content.ContentFactory
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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

        private var browserInstance: JBCefBrowser? = null
        private var serverRunning = false
        private var serverProcess: Process? = null
        private var checkScheduledFuture: ScheduledFuture<*>? = null
        private var hasInitializedOnStartup = false

        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenCode-Server-Checker")
        }

        fun stopServer() {
            try {
                checkScheduledFuture?.cancel(true)
                checkScheduledFuture = null

                val process = serverProcess
                if (process?.isAlive == true) {
                    process.destroy()
                    thisLogger().info("OpenCode server stopped (via process reference)")
                }

                killProcessByPort(PORT)

                serverRunning = false
                serverProcess = null
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }

        fun restartServer(project: Project?) {
            stopServer()

            if (project == null || browserInstance == null) {
                return
            }

            val projectPath = project.basePath ?: return
            val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
            val sessionId = SessionHelper.getLatestSessionId(projectPath)
            val url = if (sessionId != null) {
                "http://$HOST:$PORT/$encodedPath?session=$sessionId"
            } else {
                "http://$HOST:$PORT/$encodedPath"
            }

            startServerInternal(project) {
                browserInstance?.cefBrowser?.reload()
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
                        serverProcess = process

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
                            serverRunning = true
                            ApplicationManager.getApplication().invokeLater {
                                onSuccess()
                            }
                        } else {
                            val exitCode = if (process.isAlive) "still running" else "exited with code ${process.exitValue()}"
                            thisLogger().warn("Failed to start OpenCode server, process $exitCode")
                            serverRunning = false
                        }
                    } catch (e: Exception) {
                        thisLogger().error("Error starting OpenCode server: ${e.message}")
                        serverRunning = false
                    }
                }
            }
            ProgressManager.getInstance().run(task)
        }

        private fun getOpenCodeCommand(): List<String> {
            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            return if (isMac) {
                listOf("/bin/zsh", "-l", "-c", "opencode serve --hostname $HOST --port $PORT")
            } else {
                listOf("opencode", "serve", "--hostname", HOST, "--port", PORT.toString())
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
        private val browser = JBCefBrowser()

        init {
            browserInstance = browser
            setupWindowFocusListener(toolWindow)
        }

        private fun setupWindowFocusListener(toolWindow: ToolWindow) {
            browser.component.addHierarchyListener {
                SwingUtilities.getWindowAncestor(browser.component)?.let { window ->
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

        fun getContent() = browser.component

        fun setupBrowserKeyboardHandling() {
            val browserComponent = browser.component
            val osrComponent = browser.cefBrowser.uiComponent

            osrComponent?.let { comp ->
                setupComponent(comp)
                addEmacsKeyListener(comp)
            }

            browserComponent?.let { comp ->
                setupComponent(comp)
            }

            setupComponentHierarchy(osrComponent)
            setupComponentHierarchy(browserComponent)
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

        fun requestBrowserFocus() {
            ApplicationManager.getApplication().invokeLater {
                try {
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
            if (hasInitializedOnStartup) {
                if (checkPortOpen(HOST, PORT)) {
                    thisLogger().info("Port $PORT is already open, reusing existing server")
                    serverRunning = true
                    loadProjectPage()
                } else {
                    thisLogger().info("Port $PORT is not open, starting opencode serve...")
                    startOpenCodeServer()
                }
            } else {
                hasInitializedOnStartup = true
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
                        ApplicationManager.getApplication().invokeAndWait {
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
                if (serverProcess?.isAlive == true) {
                    serverProcess?.destroy()
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
                        serverProcess = process

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
                            serverRunning = true
                            ApplicationManager.getApplication().invokeLater {
                                loadProjectPage()
                            }
                        } else {
                            thisLogger().error("Failed to start OpenCode server")
                            ApplicationManager.getApplication().invokeLater {
                                showErrorInBrowser()
                            }
                            serverRunning = false
                        }
                    } catch (e: Exception) {
                        thisLogger().error("Error starting OpenCode server: ${e.message}")
                        ApplicationManager.getApplication().invokeLater {
                            showErrorInBrowser()
                        }
                        serverRunning = false
                    }
                }
            }
            ProgressManager.getInstance().run(task)
        }

        private fun loadProjectPage() {
            val projectPath = project.basePath ?: return
            val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
            val sessionId = SessionHelper.getLatestSessionId(projectPath)
            val url = if (sessionId != null) {
                "http://$HOST:$PORT/$encodedPath?session=$sessionId"
            } else {
                "http://$HOST:$PORT/$encodedPath"
            }
            thisLogger().info("Loading page: $url")
            browser.loadURL(url)
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
            browser.loadHTML(html)
        }
    }
}
