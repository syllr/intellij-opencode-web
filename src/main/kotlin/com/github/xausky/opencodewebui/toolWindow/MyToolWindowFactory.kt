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
import java.awt.Component
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.KeyStroke

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
        private var currentProject: Project? = null
        private var hasInitializedOnStartup = false  // 标记 IDE 启动后是否已初始化过
        private var openCodePathCache: String? = null  // 缓存 opencode 路径

        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenCode-Server-Checker")
        }
        
        /**
         * 获取 opencode 命令
         * 使用 npx opencode-ai@latest 确保使用最新版本
         */
        private fun getOpenCodeCommand(): List<String> {
            // 使用 npx opencode-ai@latest，不需要本地安装
            // 完整命令：npx -y opencode-ai@latest serve --hostname 127.0.0.1 --port 10086
            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            
            return if (isMac) {
                // macOS 需要使用完整的 node 路径或者通过 shell 运行
                // 添加 -y 自动确认安装
                listOf("/bin/zsh", "-l", "-c", "npx -y opencode-ai@latest serve --hostname $HOST --port $PORT")
            } else {
                listOf("npx", "-y", "opencode-ai@latest", "serve", "--hostname", HOST, "--port", PORT.toString())
            }
        }
        
        /**
         * 获取完整的环境变量，确保包含 shell 的 PATH
         */
        private fun getEnvironment(): Map<String, String> {
            val env = mutableMapOf<String, String>()
            env.putAll(System.getenv())
            
            // 添加常见的 PATH 路径
            val originalPath = env["PATH"] ?: ""
            val additionalPaths = listOf(
                "/usr/local/bin",
                "/opt/homebrew/bin",
                "${System.getenv("HOME")}/.npm-global/bin",
                "${System.getenv("HOME")}/.local/bin"
            ).filterNot { originalPath.contains(it) }
            
            val newPath = (additionalPaths + originalPath).joinToString(":")
            env["PATH"] = newPath
            
            println("[ENV] Original PATH: $originalPath")
            println("[ENV] New PATH: $newPath")
            
            return env
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

        fun setProject(project: Project) {
            currentProject = project
        }

        fun getCheckScheduledFuture(): ScheduledFuture<*>? = checkScheduledFuture
        fun setCheckScheduledFuture(future: ScheduledFuture<*>?) {
            checkScheduledFuture = future
        }

        fun stopServer() {
            try {
                getCheckScheduledFuture()?.cancel(true)
                setCheckScheduledFuture(null)

                // 先尝试 kill 保存的进程引用
                val process = getServerProcess()
                if (process?.isAlive == true) {
                    process.destroy()
                    thisLogger().info("OpenCode server stopped (via process reference)")
                }

                // 再尝试通过端口 kill（确保彻底清理）
                killProcessByPort(PORT)

                setServerRunning(false)
                setServerProcess(null)
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }

        /**
         * 重启 OpenCode Server
         * 用于手动刷新，会 kill 旧进程并启动新进程
         */
        fun restartServer(project: Project?) {
            println("[RESTART] Manual restart requested")
            stopServer()
            
            if (project == null) {
                println("[RESTART] Project is null, cannot restart server")
                return
            }
            
            if (browserInstance == null) {
                println("[RESTART] Browser instance is null, cannot reload page")
            }
            
            val projectPath = project.basePath
            if (projectPath == null) {
                println("[RESTART] Project path is null, cannot restart server")
                return
            }
            
            val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
            val url = "http://$HOST:$PORT/$encodedPath"
            
            println("[RESTART] Will load URL after restart: $url")
            
            startServerInternal(project) {
                println("[RESTART] Server started, reloading page, browserInstance: ${browserInstance != null}")
                // 使用 cefBrowser.reload() 强制刷新
                browserInstance?.cefBrowser?.reload()
            }
        }

        /**
         * 内部启动服务器方法
         */
        private fun startServerInternal(project: Project, onSuccess: () -> Unit) {
            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val openCodeCmd = getOpenCodeCommand()
                        println("[SERVER] Starting server with command: ${openCodeCmd.joinToString(" ")}")
                        
                        val processBuilder = ProcessBuilder()
                            .command(openCodeCmd)
                            .redirectErrorStream(true)
                        
                        // 设置正确的环境变量
                        processBuilder.environment().putAll(getEnvironment())
                        
                        val process = processBuilder.start()
                        println("[SERVER] Process started, PID: ${process.pid()}, isAlive: ${process.isAlive}")
                        
                        setServerProcess(process)

                        // 启动一个线程读取进程输出
                        Thread {
                            try {
                                val reader = process.inputStream.bufferedReader()
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    println("[SERVER OUTPUT] $line")
                                }
                            } catch (e: Exception) {
                                println("[SERVER OUTPUT ERROR] ${e.message}")
                            }
                        }.start()

                        var maxAttempts = 30
                        val startTime = System.currentTimeMillis()
                        val timeout = 60000L

                        while (maxAttempts-- > 0 && (System.currentTimeMillis() - startTime) < timeout) {
                            val portOpen = checkPortOpenInternal()
                            val alive = process.isAlive
                            println("[SERVER] Attempt ${30 - maxAttempts}: isAlive=$alive, portOpen=$portOpen")
                            
                            if (alive && portOpen) {
                                break
                            }
                            Thread.sleep(1000)
                        }

                        if (checkPortOpenInternal()) {
                            println("[SERVER] OpenCode server started successfully")
                            setServerRunning(true)
                            ApplicationManager.getApplication().invokeLater {
                                onSuccess()
                            }
                        } else {
                            val exitCode = if (process.isAlive) "still running" else "exited with code ${process.exitValue()}"
                            println("[SERVER] Failed to start OpenCode server, process $exitCode")
                            setServerRunning(false)
                        }
                    } catch (e: Exception) {
                        println("[SERVER] Error starting OpenCode server: ${e.message}")
                        e.printStackTrace()
                        setServerRunning(false)
                    }
                }
            }
            ProgressManager.getInstance().run(task)
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

        /**
         * 通过端口号 kill 占用该端口的进程
         * 支持 macOS/Linux 和 Windows
         */
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

    /**
     * OpenCode Web UI 工具窗口
     */
    inner class MyToolWindow(toolWindow: ToolWindow) {

        private val project = toolWindow.project
        private val browser = JBCefBrowser()

        init {
            setBrowser(browser)
        }

        fun getContent() = browser.component

        /**
         * 设置 JCEF 浏览器的键盘事件处理
         *
         * 核心原理：
         * - JCEF 使用 OSR (Off-Screen Rendering)，浏览器在独立渲染层处理输入
         * - 在 InputMap 中注册空 Action 可以阻止 IDEA 拦截快捷键
         * - JCEF 仍然能收到按键事件，因为 OSR 在更底层处理
         */
        fun setupBrowserKeyboardHandling() {
            val browserComponent = browser.component
            val osrComponent = browser.cefBrowser.uiComponent

            // 设置 JCEF OSR 组件和浏览器组件的键盘处理
            osrComponent?.let { comp ->
                setupComponent(comp)
                addEmacsKeyListener(comp)
            }

            browserComponent?.let { comp ->
                setupComponent(comp)
            }

            // 递归设置组件层级
            setupComponentHierarchy(osrComponent)
            setupComponentHierarchy(browserComponent)
        }

        /**
         * Emacs 风格按键映射
         *
         * Ctrl+N -> Down     下一行
         * Ctrl+P -> Up       上一行
         * Ctrl+E -> End      行尾
         * Ctrl+A -> Home     行首
         * Ctrl+B -> Left     左移
         * Ctrl+F -> Right    右移
         */
        private fun addEmacsKeyListener(component: Component) {
            // Emacs 按键映射表：原始键 -> 目标键
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

                    println("[EMACS] Ctrl+${KeyEvent.getKeyText(e.keyCode)} -> ${KeyEvent.getKeyText(targetKeyCode)}")
                    sendKeyEvent(component, e.id, targetKeyCode, 0)
                    e.consume()
                }
            })
        }

        /**
         * 发送键盘事件到组件
         */
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

        /**
         * 设置组件的键盘处理属性
         *
         * 工作原理：
         * 1. focusTraversalKeysEnabled = false：禁用 Tab 键焦点遍历，让 Tab 传递给 JCEF
         * 2. 在 InputMap 注册空 Action：拦截特定按键，阻止 IDEA 处理
         */
        private fun setupComponent(component: Component) {
            if (component !is JComponent) return

            component.focusTraversalKeysEnabled = false

            val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            val actionMap = component.actionMap

            val emptyAction = object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    // 空操作：阻止 IDEA 处理此按键
                }
            }

            // 拦截 ESC 键
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "block")

            // 拦截 Cmd+, 和 Cmd+K (macOS)
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, java.awt.event.InputEvent.META_DOWN_MASK), "block")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, java.awt.event.InputEvent.META_DOWN_MASK), "block")

            actionMap.put("block", emptyAction)
        }

        /**
         * 递归设置组件层级中所有组件的键盘处理
         */
        private fun setupComponentHierarchy(component: Component?) {
            if (component == null) return

            setupComponent(component)

            if (component is java.awt.Container) {
                for (i in 0 until component.componentCount) {
                    setupComponentHierarchy(component.getComponent(i))
                }
            }
        }

        /**
         * 检查端口并加载内容
         *
         * 核心逻辑：
         * - IDE 启动后第一次打开工具窗口：kill 旧 server，启动新版本
         * - 后续打开：直接使用现有 server
         */
        fun checkAndLoadContent() {
            if (hasInitializedOnStartup) {
                // 已经初始化过了，直接检查并使用现有 server
                if (checkPortOpen(HOST, PORT)) {
                    thisLogger().info("Port $PORT is already open, reusing existing server")
                    setServerRunning(true)
                    loadProjectPage()
                } else {
                    thisLogger().info("Port $PORT is not open, starting opencode serve...")
                    startOpenCodeServer()
                }
            } else {
                // IDE 启动后第一次打开：强制重启 server 以使用最新版本
                hasInitializedOnStartup = true
                thisLogger().info("First initialization on IDE startup, ensuring latest opencode version")
                stopServer()
                startOpenCodeServer()
            }
        }

        /**
         * 检查端口是否开放
         */
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

        /**
         * 启动定期健康检查
         */
        fun startPeriodicCheck() {
            if (getCheckScheduledFuture() != null) return

            val future = scheduler.scheduleAtFixedRate(
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

        /**
         * 检查服务器健康状态
         */
        private fun checkServerHealth() {
            if (!checkPortOpen(HOST, PORT)) {
                thisLogger().warn("Server is not responding, attempting to restart...")
                if (serverProcess?.isAlive == true) {
                    serverProcess?.destroy()
                }
                startOpenCodeServer()
            }
        }

        /**
         * 启动 OpenCode 服务器
         */
        private fun startOpenCodeServer() {
            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val openCodeCmd = getOpenCodeCommand()
                        println("[SERVER] Starting server with command: ${openCodeCmd.joinToString(" ")}")
                        
                        val processBuilder = ProcessBuilder()
                            .command(openCodeCmd)
                            .redirectErrorStream(true)
                        
                        // 设置正确的环境变量
                        processBuilder.environment().putAll(getEnvironment())
                        
                        val process = processBuilder.start()

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

        /**
         * 加载项目页面
         */
        private fun loadProjectPage() {
            val projectPath = project.basePath ?: return
            val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
            val url = "http://$HOST:$PORT/$encodedPath"
            thisLogger().info("Loading page: $url")
            browser.loadURL(url)
        }

        /**
         * 在浏览器中显示错误信息
         */
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
