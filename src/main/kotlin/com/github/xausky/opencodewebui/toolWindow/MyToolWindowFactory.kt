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
import javax.swing.KeyStroke
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

        /**
         * 设置 JCEF 浏览器的键盘事件处理
         * 
         * 背景：当焦点在 JCEF 浏览器中时，我们需要：
         * 1. 让 toggleOpenCode 快捷键（Ctrl+\ 或 Cmd+\）被 IDEA 处理
         * 2. 其他所有键盘事件都应该被 JCEF 网页处理，而不是被 IDEA 的 Action 系统拦截
         * 
         * 踩过的坑：
         * 
         * 1. 尝试使用客户端属性（不生效）：
         *    - component.putClientProperty("ide.shortcut.disabled", true)
         *    - component.putClientProperty("JComponent.isEmbeddedBrowser", true)
         *    - component.putClientProperty("preventShortcutProcessing", true)
         *    这些属性都没有实际效果，IDEA 的 Action 系统仍然会处理快捷键
         * 
         * 2. 尝试使用 AWTEventListener 重新分派事件（导致无限循环或重复处理）：
         *    - 在 AWTEventListener 中拦截键盘事件，然后手动重新分派给 JCEF 组件
         *    - 这会导致：重新分派的事件又被 AWTEventListener 捕获 -> 无限循环
         *    - 添加 isDispatching 标志可以防止循环，但事件会被处理两次
         *    - 最终放弃这个方案
         * 
         * 3. 尝试使用 KeyEventDispatcher 消费事件（JCEF 收不到事件）：
         *    - 在 KeyEventDispatcher 中调用 e.consume() 消费事件
         *    - 这确实阻止了 IDEA 的 Action 系统处理事件
         *    - 但是：消费事件会导致 JCEF 也收不到这个事件！
         *    - 结果：普通字符键（a-z）正常，但 ESC、DELETE、快捷键等特殊按键 JCEF 收不到
         * 
         * 4. 最终解决方案：使用 InputMap/ActionMap 注册空 Action（成功）
         *    - 在 JCEF 组件的 InputMap 中注册空的 Action 来拦截特定按键
         *    - 工作原理：Swing 的 ActionMap 系统会先处理这些按键，阻止 IDEA 的 Action 系统接收到它们
         *    - 但是：JCEF 的 OSR（Off-Screen Rendering）机制仍然能接收这些按键
         *    - 原因：CEF 是独立于 Swing 的渲染系统，它在更底层处理输入事件
         *    - 这是完美的解决方案！既阻止了 IDEA 处理快捷键，又让 JCEF 能正常接收按键
         * 
         * 重要提示：
         * - KeyEventDispatcher 目前只用于日志调试，不影响功能
         * - 如需添加新的需要拦截的快捷键，在 setupComponent 方法中添加即可
         * - focusTraversalKeysEnabled = false 用于禁用 Tab 键的焦点遍历，让 Tab 键传递给 JCEF
         */
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
            
            keyEventDispatcher = KeyEventDispatcher { e ->
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                val sourceComponent = e.source as? Component
                val isInBrowserByFocus = isDescendantOfBrowser(focusOwner)
                val isInBrowserBySource = isDescendantOfBrowser(sourceComponent)
                val isInBrowser = isInBrowserByFocus || isInBrowserBySource
                
                if (e.id == KeyEvent.KEY_PRESSED || e.id == KeyEvent.KEY_RELEASED) {
                    println("[KEY] KeyEvent: id=${e.id}, keyCode=${e.keyCode}, keyChar='${e.keyChar}', modifiers=${e.modifiersEx}")
                    println("[KEY] Focus owner: $focusOwner (class: ${focusOwner?.javaClass?.simpleName})")
                    println("[KEY] Source component: $sourceComponent (class: ${sourceComponent?.javaClass?.simpleName})")
                    println("[KEY] isInBrowserByFocus=$isInBrowserByFocus, isInBrowserBySource=$isInBrowserBySource, isInBrowser=$isInBrowser")
                }
                
                if (isInBrowser && e.id == KeyEvent.KEY_PRESSED) {
                    val keyCode = e.keyCode
                    val modifiers = e.modifiersEx
                    
                    val isToggleShortcut = (keyCode == KeyEvent.VK_BACK_SLASH) && 
                        ((modifiers and KeyEvent.CTRL_DOWN_MASK) != 0 || 
                         (modifiers and KeyEvent.META_DOWN_MASK) != 0)
                    
                    if (isToggleShortcut) {
                        println("[KEY] Allowing toggle shortcut to IDEA: keyCode=$keyCode")
                        return@KeyEventDispatcher false
                    }
                    
                    if (keyCode == KeyEvent.VK_ESCAPE || keyCode == KeyEvent.VK_DELETE || modifiers != 0) {
                        println("[KEY] Special key or shortcut in browser, not consuming: keyCode=$keyCode, modifiers=$modifiers")
                    }
                }
                
                false
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
        
        /**
         * 设置组件的键盘处理属性
         * 
         * 这是解决问题的核心方法！
         * 
         * 工作原理：
         * 1. focusTraversalKeysEnabled = false：禁用 Tab 键等焦点遍历键，让它们传递给 JCEF
         * 2. 在 InputMap 中注册空 Action：拦截特定按键，阻止 IDEA 的 Action 系统处理它们
         * 
         * 为什么这能工作：
         * - Swing 的 ActionMap 机制会先检查 InputMap 中是否有注册的 Action
         * - 如果有，就会执行该 Action，而不会触发 IDEA 的全局快捷键处理
         * - 但 JCEF 使用 OSR（Off-Screen Rendering），它直接从操作系统层面接收输入事件
         * - 所以 JCEF 仍然能收到这些按键事件，不会被 InputMap 的拦截影响
         * 
         * 如果需要拦截其他按键，在这里添加：
         * inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "doNothing")
         */
        private fun setupComponent(component: Component) {
            if (component is JComponent) {
                component.focusTraversalKeysEnabled = false
                
                val inputMap = component.getInputMap(JComponent.WHEN_FOCUSED)
                val actionMap = component.actionMap
                
                val emptyAction = object : javax.swing.AbstractAction() {
                    override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                        println("[ACTION] Empty action triggered for: ${e?.actionCommand}")
                    }
                }
                
                inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "doNothing")
                inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "doNothing")
                actionMap.put("doNothing", emptyAction)
                
                println("[SETUP] Registered empty actions for ESC and DELETE on ${component.javaClass.simpleName}")
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
