package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.github.xausky.opencodewebui.utils.SessionHelper
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefClient
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
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
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
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType

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
        private const val PORT = 12396
        private const val HOST = "127.0.0.1"

        // 所有 JBCefBrowser 共享同一个 JBCefClient，从而共享渲染进程和 localStorage
        private val sharedJBCefClient by lazy { JBCefApp.getInstance().createClient() }

        private val serverRunning = AtomicBoolean(false)
        private val serverProcess = AtomicReference<ProcessHandler?>(null)
        private var checkScheduledFuture: ScheduledFuture<*>? = null
        private var myToolWindowInstance: MyToolWindow? = null

        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OpenCode-Server-Checker")
        }

        fun stopServer() {
            try {
                checkScheduledFuture?.cancel(true)
                checkScheduledFuture = null

                // 通过端口号强制关闭进程（确保 IDEA 重启后仍能关闭）
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

        /**
         * 获取完整的环境变量
         *
         * 问题背景：
         * macOS GUI 应用由 launchd 启动，不会加载用户的 shell 配置文件（~/.bashrc, ~/.zshrc 等）
         * 导致 System.getenv() 获取的环境变量不完整，缺少 PATH、Go、Bun、Python 等工具路径
         *
         * 解决方案：
         * 通过三种机制构建完整的环境变量：
         * 1. 默认 macOS 路径（无条件添加）
         * 2. 工具路径检测（仅当目录存在时添加）
         * 3. Shell 配置解析（bashrc/zshrc 等）
         *
         * 测试说明：
         * ⚠️ ./gradlew runIde 无法复现此问题！
         * 因为通过终端启动的 GUI 应用会继承终端的完整环境变量。
         * 必须手动 buildPlugin 然后安装到 IDEA/GoLand 中测试：
         * 1. ./gradlew buildPlugin
         * 2. 在 IDEA/GoLand 中通过 File -> Settings -> Plugins -> Install Plugin from Disk
         * 3. 重启 IDE 并打开 OpenCode 工具窗口
         */
        private fun getFullEnvironment(): Map<String, String> {
            val env = System.getenv().toMutableMap()
            val home = env["HOME"] ?: System.getProperty("user.home")
            val currentPath = env["PATH"] ?: ""

            // 收集所有检测到的路径
            val allPaths = mutableListOf<String>()

            // A. 添加默认 macOS 路径（无条件）
            // 这些是 macOS 系统基础路径，即使不存在也添加
            val defaultPaths = listOf(
                "/usr/bin", "/bin", "/usr/sbin", "/sbin",
                "/usr/local/bin",
                "/opt/homebrew/bin", "/opt/homebrew/sbin",
                "/opt/local/bin", "/opt/local/sbin"
            )
            allPaths.addAll(defaultPaths)

            // B. 检测工具路径（仅当目录存在）
            // 检测常见开发工具的安装路径，如 Cargo, Go, Bun, Deno, NVM 等
            val detectedPaths = detectToolPaths(home)
            allPaths.addAll(detectedPaths)

            // C. 从 shell 配置解析路径
            // 解析 ~/.bashrc, ~/.zshrc 等配置文件中的 PATH 修改
            val shellPaths = parseShellConfigPaths(home)
            allPaths.addAll(shellPaths)

            // 去重并过滤存在的路径
            val uniquePaths = allPaths.distinct().filter { java.io.File(it).exists() }

            // 构建新的 PATH，保留原始 PATH 中的自定义路径
            val existingPaths = currentPath.split(":").filter { path ->
                // 保留不在默认列表中的原始路径
                !defaultPaths.contains(path) && !uniquePaths.contains(path)
            }

            val newPath = (uniquePaths + existingPaths).joinToString(":")
            env["PATH"] = newPath

            return env
        }

        /**
         * 检测常见工具的安装路径
         */
        private fun detectToolPaths(home: String): List<String> {
            val paths = mutableListOf<String>()

            // Cargo
            val cargoBin = "$home/.cargo/bin"
            if (java.io.File(cargoBin).exists()) paths.add(cargoBin)

            // Go (gopath style)
            val gBin = "$home/.g/bin"
            if (java.io.File(gBin).exists()) paths.add(gBin)

            // Go installation
            for (goPath in listOf("$home/.g/go", "$home/go")) {
                val goBinPath = "$goPath/bin"
                if (java.io.File(goBinPath).exists()) {
                    paths.add(goBinPath)
                    break
                }
            }

            // Local bin
            val localBin = "$home/.local/bin"
            if (java.io.File(localBin).exists()) paths.add(localBin)

            // Bun
            val bunBin = "$home/.bun/bin"
            if (java.io.File(bunBin).exists()) paths.add(bunBin)

            // Deno
            val denoBin = "$home/.deno/bin"
            if (java.io.File(denoBin).exists()) paths.add(denoBin)

            // NVM versions
            val nvmVersionsDir = java.io.File("$home/.nvm/versions/node")
            if (nvmVersionsDir.exists()) {
                nvmVersionsDir.listFiles()?.forEach { versionDir ->
                    val nvmBin = "${versionDir.absolutePath}/bin"
                    if (java.io.File(nvmBin).exists()) paths.add(nvmBin)
                }
            }

            // Pyenv
            val pyenvBin = "$home/.pyenv/bin"
            if (java.io.File(pyenvBin).exists()) paths.add(pyenvBin)

            // RBenv
            val rbenvBin = "$home/.rbenv/bin"
            if (java.io.File(rbenvBin).exists()) paths.add(rbenvBin)

            // Yarn
            val yarnBin = "$home/.yarn/bin"
            if (java.io.File(yarnBin).exists()) paths.add(yarnBin)

            // Codeium/Windsurf
            val codeiumBin = "$home/.codeium/windsurf/bin"
            if (java.io.File(codeiumBin).exists()) paths.add(codeiumBin)

            return paths
        }

        /**
         * 从 shell 配置文件解析 PATH
         */
        private fun parseShellConfigPaths(home: String): List<String> {
            val paths = mutableSetOf<String>()
            val visitedFiles = mutableSetOf<String>()
            val depth = 0

            // 默认解析的配置文件
            val configFiles = mutableListOf<String>()

            // 根据 $SHELL 类型添加对应的配置文件
            val shell = System.getenv("SHELL") ?: ""
            when {
                shell.contains("zsh") -> {
                    configFiles.add("$home/.zshrc")
                    configFiles.add("$home/.zprofile")
                    configFiles.add("$home/.zshenv")
                }
                shell.contains("bash") -> {
                    configFiles.add("$home/.bashrc")
                    configFiles.add("$home/.bash_profile")
                }
                shell.contains("fish") -> {
                    configFiles.add("$home/.config/fish/config.fish")
                }
                shell.contains("csh") -> {
                    configFiles.add("$home/.cshrc")
                }
                shell.contains("ksh") -> {
                    configFiles.add("$home/.kshrc")
                }
                else -> {
                    // 默认尝试 bash 和 zsh
                    configFiles.add("$home/.bashrc")
                    configFiles.add("$home/.zshrc")
                }
            }

            for (configFile in configFiles) {
                parseConfigFile(configFile, home, paths, visitedFiles, depth)
            }

            return paths.toList()
        }

        /**
         * 递归解析配置文件，提取 PATH 修改
         */
        private fun parseConfigFile(
            filePath: String,
            home: String,
            paths: MutableSet<String>,
            visitedFiles: MutableSet<String>,
            depth: Int
        ) {
            val maxDepth = 5
            if (depth >= maxDepth) return

            val file = java.io.File(filePath)
            if (!file.exists() || !file.isFile || !file.canRead()) return

            // 避免循环引用
            val canonicalPath = try {
                file.canonicalPath
            } catch (e: Exception) {
                return
            }
            if (visitedFiles.contains(canonicalPath)) return
            visitedFiles.add(canonicalPath)

            try {
                file.readLines().forEach { line ->
                    val trimmedLine = line.trim()

                    // 跳过注释和空行
                    if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) return@forEach

                    // 处理 export PATH=$PATH:xxx 格式
                    if (trimmedLine.startsWith("export PATH=") || trimmedLine.startsWith("PATH=")) {
                        val pathMatch = Regex("""PATH=["']?([^"'`\n]+)["']?""").find(trimmedLine)
                        if (pathMatch != null) {
                            parsePathString(pathMatch.groupValues[1], home, paths)
                        }
                    }

                    // 处理 source xxx 或 . xxx 格式
                    val sourceMatch = Regex("""^\s*(?:source|\.)\s+["']?([^"'\n]+)["']?""").find(trimmedLine)
                    if (sourceMatch != null) {
                        val sourcedFile = sourceMatch.groupValues[1]
                        val expandedFile = sourcedFile.replace("~", home)
                        parseConfigFile(expandedFile, home, paths, visitedFiles, depth + 1)
                    }

                    // 处理 eval "$(...)" 格式（如 pyenv init, nvm use, etc.）
                    if (trimmedLine.contains("eval \"\$(") || trimmedLine.contains("eval $(")) {
                        val evalMatch = Regex("""eval\s+\$?\("([^)]+)"\)""").find(trimmedLine)
                        if (evalMatch != null) {
                            val cmd = evalMatch.groupValues[1]
                            when {
                                cmd.contains("pyenv init") -> {
                                    val pyenvBin = "$home/.pyenv/bin"
                                    if (java.io.File(pyenvBin).exists()) paths.add(pyenvBin)
                                }
                                cmd.contains("rbenv init") -> {
                                    val rbenvBin = "$home/.rbenv/bin"
                                    if (java.io.File(rbenvBin).exists()) paths.add(rbenvBin)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略解析错误
            }
        }

        /**
         * 解析 PATH 字符串，提取各个路径
         */
        private fun parsePathString(pathStr: String, home: String, paths: MutableSet<String>) {
            val parts = pathStr.split(":")
            for (part in parts) {
                var expandedPart = part
                    .replace("~", home)
                    .replace("\$HOME", home)
                    .replace("\${HOME}", home)

                // 移除可能的引号
                expandedPart = expandedPart.trim('"', '\'', '`')

                // 跳过包含变量引用的部分（如 $PATH, ${PATH}）
                if (expandedPart.contains("\$") && !expandedPart.startsWith("/") && !expandedPart.startsWith("~")) {
                    continue
                }

                if (expandedPart.isNotEmpty() && !expandedPart.contains("\$")) {
                    val file = java.io.File(expandedPart)
                    if (file.exists() && file.isDirectory) {
                        paths.add(expandedPart)
                    }
                }
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

            toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
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
            val healthy = OpenCodeApi.isServerHealthySync()
            return if (healthy) {
                serverRunning.set(true)
                true
            } else {
                serverRunning.set(false)
                false
            }
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

        private fun startOpenCodeServer() {
            // 先检查 server 是否已经启动，如果已经启动则直接加载页面
            if (OpenCodeApi.isServerHealthySync()) {
                onServerStarted()
                return
            }

            val task = object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val handler = startOpenCodeProcess()
                        serverProcess.set(handler)

                        val healthy = OpenCodeApi.waitForServerHealthy(30000)

                        if (healthy) {
                            onServerStarted()
                        } else {
                            onServerStartFailed(Exception("Server not healthy after 30s"))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        thisLogger().error("[startOpenCodeServer] Exception: ${e.message}", e)
                        onServerStartFailed(e)
                    }
                }
            }
            ProgressManager.getInstance().run(task)
        }

        private fun startOpenCodeProcess(): ProcessHandler {
            val command = getOpenCodeCommand()
            val homeDir = System.getProperty("user.home", System.getenv("HOME") ?: "/tmp")
            val commandLine = GeneralCommandLine(command)
            commandLine.setWorkDirectory(homeDir)
            val fullEnv = getFullEnvironment()

            commandLine.environment.clear()
            commandLine.environment.putAll(fullEnv)

            thisLogger().info("[startOpenCodeProcess] Working directory: $homeDir, project path: ${project.basePath}")
            return ProcessHandlerFactory.getInstance().createProcessHandler(commandLine)
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

        private fun getOpenCodeCommand(): List<String> {
            val path = findOpenCodePath()
            return listOf(path, "serve", "--hostname", HOST, "--port", PORT.toString())
        }

        private fun findOpenCodePath(): String {
            // 使用 OpenCodePathFinder 查找路径
            val candidatePaths = OpenCodePathFinder.getCandidatePaths()
            return try {
                OpenCodePathFinder.findOpenCodePath(candidatePaths)
            } catch (e: IllegalStateException) {
                // 通知用户并终止
                notifyOpenCodeNotFound()
                throw e
            }
        }

        private fun notifyOpenCodeNotFound() {
            ApplicationManager.getApplication().invokeLater {
                Notification(
                    "OpenCodeWeb.notifications",
                    "无法启动 OpenCode 服务器",
                    "未找到 opencode 可执行文件。请确保 OpenCode 已正确安装。",
                    NotificationType.ERROR
                ).notify(project)
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
                sharedClient.addLifeSpanHandler(ExternalLinkLifeSpanHandler(), createdBrowser.cefBrowser)
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
