package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.JComponent
import javax.swing.SwingUtilities

class MyToolWindow(toolWindow: ToolWindow) {

    private val project = toolWindow.project
    private val logger = thisLogger()
    private val browserPanel = BrowserPanel(OPENCODE_HOST, OPENCODE_PORT, MyToolWindowFactory.sharedJBCefClient)
    private var mainBrowser: JBCefBrowser? = null

    init {
        setupWindowFocusListener(toolWindow)
        MyToolWindowFactory.myToolWindowInstance = this
    }

    private var isShowingStartButton = false

    private val healthMonitor = HealthMonitor(
        onUnhealthy = { showServerNotRunning() },
        onRecovered = { loadProjectPage() }
    )

    fun checkAndLoadContent() {
        val healthy = OpenCodeApi.isServerHealthySync()
        healthMonitor.lastHealthState = healthy
        healthMonitor.start()
        if (healthy) {
            logger.info("[checkAndLoadContent] Server healthy, ensuring SSE consumer via OpenCodeServerManager")
            OpenCodeServerManager.ensureSSEConsumer(project)
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

    fun getBrowser() = browserPanel.getBrowser()

    fun setupBrowserKeyboardHandling() {
        val panel = browserPanel
        JcefKeyboardInterceptor.interceptKeys(panel)
        JcefKeyboardInterceptor.interceptKeysRecursive(panel)
        val osrComponent = (browserPanel.getBrowser()?.cefBrowser?.uiComponent as? JComponent)
        osrComponent?.let {
            JcefKeyboardInterceptor.interceptKeys(it)
            EmacsKeyHandler.addEmacsKeyMapping(it)
        }
    }

    private fun setupBrowserComponent(browser: JBCefBrowser) {
        val osrComponent = browser.cefBrowser.uiComponent
        val browserComponent = browser.component

        osrComponent?.let { comp ->
            JcefKeyboardInterceptor.interceptKeys(comp)
            EmacsKeyHandler.addEmacsKeyMapping(comp)
        }

        browserComponent?.let { comp ->
            JcefKeyboardInterceptor.interceptKeys(comp)
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
        val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
        val url = "http://$OPENCODE_HOST:$OPENCODE_PORT/$encodedPath"
        thisLogger().info("Loading page: $url")
        browserPanel.hideStartButton()
        val startTime = System.currentTimeMillis()
        if (mainBrowser == null) {
            thisLogger().info("[Lifecycle] loadProjectPage: mainBrowser is null, creating new browser")
            mainBrowser = browserPanel.createMainTab(url, projectPath)
            thisLogger().info("[Lifecycle] loadProjectPage: new browser created in ${System.currentTimeMillis() - startTime}ms")
            setupBrowserComponent(mainBrowser!!)
        } else {
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
                <pre style="background: #3C3F41; padding: 10px; border-radius: 4px;">opencode serve --hostname $OPENCODE_HOST --port $OPENCODE_PORT</pre>
            </body>
            </html>
        """.trimIndent()
        mainBrowser?.loadHTML(html)
    }
}
