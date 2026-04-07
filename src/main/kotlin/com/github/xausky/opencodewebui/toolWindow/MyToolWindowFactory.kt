package com.github.xausky.opencodewebui.toolWindow

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
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
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
        
        private var actionListener: AnActionListener? = null
        private var awtEventListener: AWTEventListener? = null

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
                
                actionListener?.let {
                    ApplicationManager.getApplication().messageBus.connect().disconnect()
                }
                
                awtEventListener?.let {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(it)
                    awtEventListener = null
                }
                
                val process = getServerProcess()
                if (process?.isAlive == true) {
                    process.destroy()
                    println("[SERVER] OpenCode server stopped")
                }
                setServerRunning(false)
            } catch (e: Exception) {
                println("[SERVER] Error stopping OpenCode server: ${e.message}")
            }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        setProject(project)
        val myToolWindow = MyToolWindow(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
            toolWindow.contentManager.addContent(content)
            myToolWindow.setupActionListener()
            myToolWindow.setupAWTEventListener()
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

        private fun isFocusInBrowser(browserComponent: Component): Boolean {
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val permanentFocusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner
            
            println("[FOCUS] focusOwner: ${focusOwner?.javaClass?.simpleName}, permanentFocusOwner: ${permanentFocusOwner?.javaClass?.simpleName}")
            println("[FOCUS] browserComponent: ${browserComponent.javaClass.simpleName}")
            
            if (focusOwner == null && permanentFocusOwner == null) {
                println("[FOCUS] No focus owner found")
                return false
            }
            
            var component: Component? = focusOwner ?: permanentFocusOwner
            var depth = 0
            while (component != null && depth < 50) {
                if (browserComponent == component) {
                    println("[FOCUS] Found browser component in focus chain at depth $depth")
                    return true
                }
                println("[FOCUS] Checking component at depth $depth: ${component.javaClass.simpleName}")
                
                var parent: Component? = component.parent
                while (parent != null) {
                    if (parent == browserComponent) {
                        println("[FOCUS] Found browser component as ancestor at depth $depth")
                        return true
                    }
                    parent = parent.parent
                }
                
                component = component.parent
                depth++
            }
            
            println("[FOCUS] Browser component NOT in focus chain (checked $depth levels)")
            return false
        }

        fun setupActionListener() {
            if (actionListener != null) return
            
            val listener = object : AnActionListener {
                override fun beforeActionPerformed(action: com.intellij.openapi.actionSystem.AnAction, event: AnActionEvent) {
                    val browserComponent = getBrowser()?.component ?: return
                    
                    val isBrowserFocused = isFocusInBrowser(browserComponent)
                    println("[ACTION] isBrowserFocused: $isBrowserFocused, action: ${action.javaClass.simpleName}")
                    
                    if (isBrowserFocused) {
                        val inputEvent = event.inputEvent
                        val actionClassName = action.javaClass.simpleName
                        
                        if (inputEvent is KeyEvent) {
                            val keyText = KeyEvent.getKeyText(inputEvent.keyCode)
                            val modifiers = StringBuilder()
                            if (inputEvent.isControlDown) modifiers.append("Ctrl+")
                            if (inputEvent.isMetaDown) modifiers.append("Cmd+")
                            if (inputEvent.isAltDown) modifiers.append("Alt+")
                            if (inputEvent.isShiftDown) modifiers.append("Shift+")
                            
                            val keyCombo = "$modifiers$keyText"
                            println("[ACTION] [$keyCombo] Action triggered: $actionClassName")
                        } else {
                            println("[ACTION] Action triggered (no key event): $actionClassName")
                        }
                        
                        val isToggleOpenCodeAction = action is com.github.xausky.opencodewebui.actions.ToggleOpenCodeAction
                        
                        if (!isToggleOpenCodeAction) {
                            println("[ACTION] Blocking IDEA action: $actionClassName")
                            event.presentation.isEnabled = false
                        } else {
                            println("[ACTION] Allowing ToggleOpenCode action")
                        }
                    }
                }
            }
            
            actionListener = listener
            ApplicationManager.getApplication().messageBus.connect().subscribe(AnActionListener.TOPIC, listener)
            println("[ACTION] AnActionListener added - only ToggleOpenCodeAction will be allowed in browser")
        }

        fun setupAWTEventListener() {
            if (awtEventListener != null) return
            
            val listener = AWTEventListener { event ->
                if (event !is KeyEvent) return@AWTEventListener
                
                val browserComponent = getBrowser()?.component ?: return@AWTEventListener
                
                val isBrowserFocused = isFocusInBrowser(browserComponent)
                
                val keyText = if (event.id == KeyEvent.KEY_TYPED) {
                    "'${event.keyChar}'"
                } else {
                    KeyEvent.getKeyText(event.keyCode)
                }
                val modifiers = StringBuilder()
                if (event.isControlDown) modifiers.append("Ctrl+")
                if (event.isMetaDown) modifiers.append("Cmd+")
                if (event.isAltDown) modifiers.append("Alt+")
                if (event.isShiftDown) modifiers.append("Shift+")
                
                val keyCombo = "$modifiers$keyText"
                val eventType = when (event.id) {
                    KeyEvent.KEY_PRESSED -> "PRESSED"
                    KeyEvent.KEY_RELEASED -> "RELEASED"
                    KeyEvent.KEY_TYPED -> "TYPED"
                    else -> "UNKNOWN"
                }
                
                if (isBrowserFocused) {
                    val isEscape = event.keyCode == KeyEvent.VK_ESCAPE
                    val isTab = event.keyCode == KeyEvent.VK_TAB
                    
                    if (isEscape || isTab) {
                        println("[AWT] [$eventType] [FOCUSED] Consuming special key: $keyCombo")
                        event.consume()
                    } else {
                        println("[AWT] [$eventType] [FOCUSED] Key in browser: $keyCombo")
                    }
                } else {
                    println("[AWT] [$eventType] [NOT FOCUSED] Key: $keyCombo")
                }
            }
            
            awtEventListener = listener
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK)
            println("[AWT] AWT event listener added for all keyboard events")
        }

        fun checkAndLoadContent() {
            if (checkPortOpen(HOST, PORT)) {
                println("[SERVER] Port $PORT is already open")
                setServerRunning(true)
                loadProjectPage()
            } else {
                println("[SERVER] Port $PORT is not open, starting opencode serve...")
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
                println("[SERVER] Port check failed: ${e.message}")
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
                        println("[SERVER] Error during periodic check: ${e.message}")
                    }
                },
                30L,
                30L,
                TimeUnit.SECONDS
            )
            setCheckScheduledFuture(future)
            println("[SERVER] Started periodic server health check")
        }

        private fun checkServerHealth() {
            if (!checkPortOpen(HOST, PORT)) {
                println("[SERVER] Server is not responding, attempting to restart...")
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
                            println("[SERVER] OpenCode server started successfully")
                            setServerRunning(true)
                            ApplicationManager.getApplication().invokeLater {
                                loadProjectPage()
                            }
                        } else {
                            println("[SERVER] Failed to start OpenCode server")
                            ApplicationManager.getApplication().invokeLater {
                                showErrorInBrowser()
                            }
                            setServerRunning(false)
                        }
                    } catch (e: Exception) {
                        println("[SERVER] Error starting OpenCode server: ${e.message}")
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
            println("[BROWSER] Loading page: $url")
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
