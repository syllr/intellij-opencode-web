package com.github.xausky.opencodewebui.toolWindow

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
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.SwingUtilities

class MyToolWindowFactory : ToolWindowFactory {

    companion object {
        private const val PORT = 10086
        private const val HOST = "127.0.0.1"
        
        private var browserInstance: JBCefBrowser? = null
        private var serverRunning = false
        private var serverProcess: Process? = null
        private var checkScheduledFuture: ScheduledFuture<*>? = null
        private var currentProject: Project? = null
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenCode-Server-Checker")
        }

        fun getBrowser(): JBCefBrowser? = browserInstance
        fun setBrowser(browser: JBCefBrowser) {
            browserInstance = browser
        }
        fun isServerRunning(): Boolean = serverRunning
        fun setServerRunning(running: Boolean) {
            serverRunning = running
        }
        fun getServerProcess(): Process? = serverProcess
        fun setServerProcess(process: Process?) {
            serverProcess = process
        }
        fun getProject(): Project? = currentProject
        fun setProject(project: Project) {
            currentProject = project
        }
        fun getCheckScheduledFuture(): ScheduledFuture<*>? = checkScheduledFuture
        fun setCheckScheduledFuture(future: ScheduledFuture<*>?) {
            checkScheduledFuture = future
        }
        fun getScheduler() = scheduler
        
        fun stopServer() {
            try {
                getCheckScheduledFuture()?.cancel(true)
                setCheckScheduledFuture(null)
                
                val process = getServerProcess()
                if (process?.isAlive == true) {
                    process.destroy()
                    thisLogger().info("OpenCode server stopped")
                }
                setServerRunning(false)
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        setProject(project)
        val myToolWindow = MyToolWindow(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
            toolWindow.contentManager.addContent(content)
            myToolWindow.setupBrowserKeyboardHandling()
            myToolWindow.checkAndLoadContent()
            myToolWindow.startPeriodicCheck()
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    class MyToolWindow(toolWindow: ToolWindow) {

        private val project = toolWindow.project
        private val browser = JBCefBrowser()

        init {
            setBrowser(browser)
        }

        fun getContent() = browser.component

        private var keyEventDispatcher: KeyEventDispatcher? = null
        
        fun setupBrowserKeyboardHandling() {
            val browserComponent = browser.component
            val osrComponent = browser.cefBrowser.uiComponent
            
            osrComponent?.let { comp ->
                setupComponent(comp)
            }
            
            browserComponent?.let { comp ->
                setupComponent(comp)
            }
            
            setupComponentHierarchy(osrComponent)
            setupComponentHierarchy(browserComponent)
            
            keyEventDispatcher = object : KeyEventDispatcher {
                private var isDispatching = false
                
                override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                    if (isDispatching) {
                        return false
                    }
                    
                    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    val isInBrowser = isDescendantOfBrowser(focusOwner) || 
                                      isDescendantOfBrowser(e.source as? Component)
                    
                    if (isInBrowser) {
                        val keyCode = e.keyCode
                        val modifiers = e.modifiersEx
                        
                        val isToggleShortcut = (keyCode == KeyEvent.VK_BACK_SLASH) && 
                            ((modifiers and KeyEvent.CTRL_DOWN_MASK) != 0 || 
                             (modifiers and KeyEvent.META_DOWN_MASK) != 0)
                        
                        if (isToggleShortcut) {
                            thisLogger().info("Allowing toggle shortcut to IDEA: keyCode=$keyCode")
                            return false
                        }
                        
                        thisLogger().info("Key in browser: keyCode=$keyCode, modifiers=$modifiers, id=${e.id}")
                        
                        val target = focusOwner ?: osrComponent ?: browserComponent
                        target?.let { targetComp ->
                            isDispatching = true
                            try {
                                val newEvent = KeyEvent(
                                    targetComp,
                                    e.id,
                                    e.`when`,
                                    e.modifiers,
                                    e.keyCode,
                                    e.keyChar,
                                    e.keyLocation
                                )
                                targetComp.dispatchEvent(newEvent)
                            } finally {
                                isDispatching = false
                            }
                        }
                        
                        return true
                    }
                    
                    return false
                }
            }
            
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
        }
        
        private fun isDescendantOfBrowser(component: Component?): Boolean {
            if (component == null) return false
            
            val browserComponent = browser.component
            val osrComponent = browser.cefBrowser.uiComponent
            
            var current: Component? = component
            while (current != null) {
                if (current == browserComponent || current == osrComponent) {
                    return true
                }
                current = current.parent
            }
            return false
        }
        
        private fun setupComponent(component: Component) {
            if (component is JComponent) {
                component.focusTraversalKeysEnabled = false
            }
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

        fun checkAndLoadContent() {
            if (checkPortOpen(HOST, PORT)) {
                thisLogger().info("Port $PORT is already open")
                setServerRunning(true)
                loadProjectPage()
            } else {
                thisLogger().info("Port $PORT is not open, starting opencode serve...")
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
            if (getCheckScheduledFuture() != null) return
            
            val future = getScheduler().scheduleAtFixedRate(
                {
                    try {
                        ApplicationManager.getApplication().invokeAndWait {
                            checkServerHealth()
                        }
                    } catch (e: Exception) {
                        thisLogger().error("Error during periodic check: ${e.message}")
                    }
                },
                30L,
                30L,
                TimeUnit.SECONDS
            )
            setCheckScheduledFuture(future)
            thisLogger().info("Started periodic server health check")
        }

        private fun checkServerHealth() {
            if (!checkPortOpen(HOST, PORT)) {
                thisLogger().warn("Server is not responding, attempting to restart...")
                if (getServerProcess()?.isAlive == true) {
                    getServerProcess()?.destroy()
                }
                startOpenCodeServer()
            }
        }

        private fun startOpenCodeServer() {
            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val process = ProcessBuilder()
                            .command("opencode", "serve", "--hostname", HOST, "--port", PORT.toString())
                            .redirectErrorStream(true)
                            .start()
                        
                        setServerProcess(process)

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
                            setServerRunning(true)
                            ApplicationManager.getApplication().invokeLater {
                                loadProjectPage()
                            }
                        } else {
                            thisLogger().error("Failed to start OpenCode server")
                            ApplicationManager.getApplication().invokeLater {
                                showErrorInBrowser()
                            }
                            setServerRunning(false)
                        }
                    } catch (e: Exception) {
                        thisLogger().error("Error starting OpenCode server: ${e.message}")
                        ApplicationManager.getApplication().invokeLater {
                            showErrorInBrowser()
                        }
                        setServerRunning(false)
                    }
                }
            }
            ProgressManager.getInstance().run(task)
        }

        private fun loadProjectPage() {
            val projectPath = project.basePath ?: return
            val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
            val url = "http://$HOST:$PORT/$encodedPath"
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
