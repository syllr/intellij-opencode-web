package com.github.xausky.opencodewebui.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

class BrowserPanel(
    private val host: String,
    private val port: Int,
    private val sharedClient: JBCefClient
) : JPanel() {
    @Volatile
    private var browser: JBCefBrowser? = null

    private var startButtonPanel: JPanel? = null
    private var startCallback: (() -> Unit)? = null

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
                    // 仅在主 frame 加载完成时注入，避免 iframe 导致重复注入
                    if (frame?.isMain != true) return
                    // 注入 JS：在 DOM capture phase 拦截 Cmd+, 和 Cmd+K，
                    // 阻止它们被 JCEF 页面（JS）消费，确保 IDEA 快捷键系统正常处理
                    cefBrowser?.executeJavaScript("""
                        (function() {
                            document.addEventListener('keydown', function(e) {
                                if (e.metaKey && (e.key === ',' || e.key === 'k' || e.key === 'K')) {
                                    e.preventDefault();
                                    e.stopImmediatePropagation();
                                }
                            }, true);
                        })();
                    """.trimIndent(), cefBrowser.url, 0)
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

}
