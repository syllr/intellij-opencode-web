package com.shenyuanlaolarou.opencodewebui.toolWindow

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
import java.util.concurrent.atomic.AtomicBoolean
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

    // [O6] 页面加载完成标志，供 JcefJsInjector 判断是否可安全注入 JS
    private val pageLoaded = AtomicBoolean(false)

    // [Fix #6] 追踪注册的 handler，dispose 时移除
    private var contextMenuHandler: LinkContextMenuHandler? = null
    private var loadHandler: CefLoadHandlerAdapter? = null
    private var displayHandler: CefDisplayHandlerAdapter? = null

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
            pageLoaded.set(false) // [O6] 新建浏览器时重置就绪标志
            // [O2] 显式禁用 DevTools 菜单项,减少 ~50-100MB 内存占用,防止用户误开
            val createdBrowser = JBCefBrowserBuilder()
                .setClient(sharedClient)
                .setUrl(url)
                .setEnableOpenDevToolsMenuItem(false)
                .build()
            this.browser = createdBrowser
            add(createdBrowser.component, BorderLayout.CENTER)
            val handler = LinkContextMenuHandler()
            sharedClient.addContextMenuHandler(handler, createdBrowser.cefBrowser)
            contextMenuHandler = handler
            val myLoadHandler = object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    thisLogger().info("onLoadEnd called, projectPath: $projectPath")
                    if (frame?.isMain == true) {
                        pageLoaded.set(true) // [O6] 主 frame 加载完成，标记就绪
                    }
                    if (frame?.isMain != true) return
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
            }
            sharedClient.addLoadHandler(myLoadHandler, createdBrowser.cefBrowser)
            loadHandler = myLoadHandler
            val myDisplayHandler = object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int
                ): Boolean {
                    // ERROR → warn；其余（WARNING/INFO/VERBOSE）→ debug，
                    // 避免 OpenCode Web UI 的 JS console 高频打日志撑爆 IDE 日志
                    if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR) {
                        thisLogger().warn("[JCEF Console] [ERROR] $message (source: $source:$line)")
                    } else {
                        thisLogger().debug("[JCEF Console] ${level?.name ?: "UNKNOWN"} $message (source: $source:$line)")
                    }
                    return false
                }
            }
            sharedClient.addDisplayHandler(myDisplayHandler, createdBrowser.cefBrowser)
            displayHandler = myDisplayHandler
            return createdBrowser
        } else {
            pageLoaded.set(false) // [O6] 重新加载时重置就绪标志
            browser?.loadURL(url)
            return browser!!
        }
    }

    fun getBrowser(): JBCefBrowser? = browser

    fun isPageLoaded(): Boolean = pageLoaded.get()

    fun disposeBrowser() {
        pageLoaded.set(false) // [O6] 浏览器销毁时重置就绪标志
        browser?.cefBrowser?.stopLoad()
        browser?.cefBrowser?.let { cefBrowser ->
            // [Fix #6] 移除所有 handler，防止 handler 累积泄漏
            contextMenuHandler?.let {
                sharedClient.removeContextMenuHandler(it, cefBrowser)
                contextMenuHandler = null
            }
            loadHandler?.let {
                sharedClient.removeLoadHandler(it, cefBrowser)
                loadHandler = null
            }
            displayHandler?.let {
                sharedClient.removeDisplayHandler(it, cefBrowser)
                displayHandler = null
            }
        }
        browser?.dispose()
        removeAll()
        browser = null
    }

}
