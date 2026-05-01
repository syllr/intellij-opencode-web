package com.github.xausky.opencodewebui.toolWindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

class BrowserPanel(
    private val host: String,
    private val port: Int,
    private val sharedClient: JBCefClient
) : JPanel() {
    private var browser: JBCefBrowser? = null

    private var startButtonPanel: JPanel? = null
    private var startCallback: (() -> Unit)? = null

    companion object {
        private const val COPY_LINK_COMMAND_ID = 26500
        private const val REFRESH_COMMAND_ID = 26501
        private const val SHUTDOWN_COMMAND_ID = 26502
        private const val COPY_LINK_AS_PROMPT_COMMAND_ID = 26503
    }

    init {
        layout = BorderLayout()
    }

    fun showStartButton(callback: () -> Unit) {
        startCallback = callback
        removeAll()
        revalidate()
        repaint()

        startButtonPanel = JPanel().apply {
            layout = GridBagLayout()
            background = Color(43, 43, 43)
            isOpaque = true

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                insets = Insets(10, 10, 10, 10)
                anchor = GridBagConstraints.CENTER
            }

            val label = JLabel("OpenCode 服务器未运行").apply {
                foreground = Color(169, 183, 198)
                font = UIManager.getFont("Label.font").deriveFont(18f)
            }
            add(label, gbc)

            gbc.gridy = 1
            val descLabel = JLabel("点击下方按钮启动服务器").apply {
                foreground = Color(169, 183, 198)
            }
            add(descLabel, gbc)

            gbc.gridy = 2
            gbc.insets = Insets(20, 10, 10, 10)
            val cmdLabel = JLabel("opencode serve --hostname $host --port $port").apply {
                foreground = Color(126, 180, 180)
                font = UIManager.getFont("Label.font").deriveFont(12f)
            }
            add(cmdLabel, gbc)

            gbc.gridy = 3
            gbc.insets = Insets(20, 10, 10, 10)
            val button = JButton("启动 OpenCode 服务器").apply {
                background = Color(0, 120, 212)
                foreground = Color.WHITE
                isOpaque = true
                isContentAreaFilled = true
                font = UIManager.getFont("Button.font")
                addActionListener {
                    startCallback?.invoke()
                }
            }
            add(button, gbc)
        }
        add(startButtonPanel, BorderLayout.CENTER)
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

    fun createMainTab(url: String, projectPath: String): JBCefBrowser {
        thisLogger().info("[Lifecycle] createMainTab: browser is ${if (browser == null) "null (creating new)" else "existing (will reuse)"}, url=$url")
        if (browser == null) {
            val createdBrowser = JBCefBrowserBuilder().setClient(sharedClient).setUrl(url).build()
            this.browser = createdBrowser
            add(createdBrowser.component, BorderLayout.CENTER)
            sharedClient.addContextMenuHandler(LinkContextMenuHandler(), createdBrowser.cefBrowser)
            sharedClient.addLoadHandler(object : CefLoadHandlerAdapter() {
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
            sharedClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int
                ): Boolean {
                    val levelStr = when (level) {
                        CefSettings.LogSeverity.LOGSEVERITY_ERROR -> "ERROR"
                        CefSettings.LogSeverity.LOGSEVERITY_WARNING -> "WARN"
                        CefSettings.LogSeverity.LOGSEVERITY_INFO -> "INFO"
                        CefSettings.LogSeverity.LOGSEVERITY_VERBOSE -> "VERBOSE"
                        CefSettings.LogSeverity.LOGSEVERITY_DISABLE -> "DISABLE"
                        else -> "UNKNOWN"
                    }
                    if (message?.contains("[OpenCode Plugin]") == true) {
                        thisLogger().info("═══════════════════════════════════════════════════════════════")
                        thisLogger().info("[JCEF Console] [$levelStr] $message (source: $source:$line)")
                        thisLogger().info("═══════════════════════════════════════════════════════════════")
                    } else {
                        thisLogger().info("[JCEF Console] [$levelStr] $message (source: $source:$line)")
                    }
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

    private inner class LinkContextMenuHandler : CefContextMenuHandlerAdapter() {
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
                    BrowserUtil.browse(URI(linkUrl))
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
                MyToolWindowFactory.stopServer()
                return true
            }
            return false
        }
    }
}
