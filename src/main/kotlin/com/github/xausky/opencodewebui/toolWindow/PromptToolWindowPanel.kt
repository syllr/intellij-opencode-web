package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.intellij.notification.Notification
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.Executors
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import java.util.concurrent.ScheduledFuture

private enum class ServerState {
    NOT_STARTED,
    STARTING,
    RUNNING
}

class PromptToolWindowPanel(
    private val project: com.intellij.openapi.project.Project,
) : JPanel(), Disposable {

    private val textArea = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 12
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.modifiersEx == KeyEvent.SHIFT_DOWN_MASK) {
                    val caret = caretPosition
                    val text = this@apply.text
                    this@apply.text = text.substring(0, caret) + "\n" + text.substring(caret)
                    this@apply.caretPosition = caret + 1
                    e.consume()
                }
            }
        })
    }

    private val sendButton = JButton("启动服务").apply {
        isEnabled = false
    }

    private val copyButton = JButton("复制").apply {
        isEnabled = false
    }

    private val loadingLabel = JLabel().apply {
        verticalAlignment = SwingConstants.CENTER
        isVisible = false
    }

    private var currentState = ServerState.NOT_STARTED

    private fun updateButtonEnabled() {
        val hasText = textArea.text.isNotBlank()
        if (currentState == ServerState.NOT_STARTED) {
            // 服务未启动时，按钮总是可用
            sendButton.isEnabled = true
        } else {
            sendButton.isEnabled = hasText
        }
    }

    private val stateListener: (Boolean) -> Unit = { running ->
        SwingUtilities.invokeLater {
            if (running) {
                currentState = ServerState.RUNNING
                sendButton.text = "发送到 OpenCode"
            } else {
                currentState = ServerState.NOT_STARTED
                sendButton.text = "启动服务"
            }
            updateButtonEnabled()
            thisLogger().info("PromptToolWindowPanel: server state changed to $currentState")
        }
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OpenCode-Prompt-Server-Checker")
    }
    private var checkScheduledFuture: ScheduledFuture<*>? = null

    init {
        thisLogger().info("PromptToolWindowPanel init: currentState: $currentState")
        layout = BorderLayout(8, 8)
        border = EmptyBorder(12, 12, 8, 12)
        minimumSize = Dimension(250, 200)

        OpenCodeServerManager.addStateListener(stateListener)

        add(loadingLabel, BorderLayout.NORTH)
        val scrollPane = JScrollPane(textArea)
        add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(copyButton)
            add(sendButton)
        }
        add(buttonPanel, BorderLayout.SOUTH)

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateButtonEnabled()
            override fun removeUpdate(e: DocumentEvent) = updateButtonEnabled()
            override fun changedUpdate(e: DocumentEvent) = updateButtonEnabled()
        })

        textArea.document.addUndoableEditListener {
            val hasText = textArea.text.isNotBlank()
            copyButton.isEnabled = hasText
        }

        sendButton.addActionListener { sendPrompt() }
        copyButton.addActionListener { copyToClipboard() }

        // 启动定期检查，确保即使服务是通过 OpenCode Web 页面启动的，我们也能检测到
        startPeriodicCheck()
    }

    private fun startPeriodicCheck() {
        checkScheduledFuture?.cancel(false)
        checkScheduledFuture = scheduler.scheduleWithFixedDelay({
            val serverHealthy = com.github.xausky.opencodewebui.utils.OpenCodeApi.isServerHealthySync()
            val browser = MyToolWindowFactory.getMainBrowser()
            val hasBrowser = browser != null
            val hasCefBrowser = browser?.cefBrowser != null

            // 只有服务器健康 + 浏览器已创建 + cefBrowser 不为空，才算准备好
            val fullyReady = serverHealthy && hasBrowser && hasCefBrowser

            if (fullyReady && currentState != ServerState.RUNNING) {
                thisLogger().info("PromptToolWindowPanel: detected fully ready (server healthy + browser ready)")
                stateListener(true)
            } else if (!serverHealthy && currentState != ServerState.NOT_STARTED) {
                thisLogger().info("PromptToolWindowPanel: detected server is not running via periodic check")
                stateListener(false)
            }
        }, 0, 2, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun appendText(text: String) {
        val currentText = textArea.text
        val newText = if (currentText.isEmpty()) {
            "$text\n"
        } else {
            "$currentText\n\n$text\n"
        }
        textArea.text = newText
        textArea.caretPosition = textArea.document.length
    }

    fun requestTextAreaFocus() {
        textArea.requestFocus()
    }

    private fun sendPrompt() {
        val text = textArea.text.trim()
        thisLogger().info("sendPrompt(): called, text length: ${text.length}, currentState: $currentState")

        val projectPath = project.basePath ?: return

        when (currentState) {
            ServerState.NOT_STARTED -> {
                thisLogger().info("sendPrompt(): state NOT_STARTED, opening OpenCode Web tool window")
                MyToolWindowFactory.openOpenCodeWebToolWindow(project)
            }
            ServerState.STARTING -> {
                thisLogger().info("sendPrompt(): state STARTING, ignoring duplicate click")
            }
            ServerState.RUNNING -> {
                if (text.isEmpty()) {
                    thisLogger().info("sendPrompt(): state RUNNING, but text is empty, ignoring")
                    return
                }
                thisLogger().info("sendPrompt(): state RUNNING, opening OpenCode Web first")
                MyToolWindowFactory.openOpenCodeWebToolWindow(project)
                
                // 等待浏览器准备好，最多等待10秒
                Thread {
                    for (i in 1..20) {
                        Thread.sleep(500)
                        val browser = MyToolWindowFactory.getMainBrowser()
                        if (browser != null && browser.cefBrowser != null) {
                            thisLogger().info("sendPrompt(): browser ready after ${i * 500}ms, calling doSend()")
                            doSend(text, projectPath)
                            return@Thread
                        }
                    }
                    thisLogger().warn("sendPrompt(): browser not ready after 10s, calling doSend() anyway")
                    doSend(text, projectPath)
                }.start()
            }
        }
    }



    private fun doSend(text: String, projectPath: String) {
        thisLogger().info("doSend(): called")
        if (!OpenCodeApi.isServerHealthySync()) {
            thisLogger().info("doSend(): server not healthy, try to restart")
            SwingUtilities.invokeLater {
                sendButton.text = "启动服务"
                sendButton.isEnabled = true
            }
            OpenCodeServerManager.startServer(
                project,
                onStarted = {
                    SwingUtilities.invokeLater {
                        sendButton.text = "发送到 OpenCode"
                        sendButton.isEnabled = true
                    }
                    doSend(text, projectPath)
                },
                onFailed = { e ->
                    SwingUtilities.invokeLater {
                        sendButton.text = "启动服务"
                        sendButton.isEnabled = true
                    }
                    showNotification("启动服务失败: ${e.message ?: "未知错误"}")
                }
            )
            return
        }

        SwingUtilities.invokeLater {
            sendButton.isEnabled = false
            sendButton.text = "发送中..."
        }

        Thread {
            sendViaJCEF(text)
            SwingUtilities.invokeLater {
                thisLogger().info("doSend(): sent via JCEF")
                textArea.text = ""
                sendButton.text = "已发送!"
                Thread {
                    Thread.sleep(1500)
                    SwingUtilities.invokeLater {
                        sendButton.text = "发送到 OpenCode"
                        sendButton.isEnabled = true
                    }
                }.start()
            }
        }.start()
    }

    private fun sendViaJCEF(text: String) {
        thisLogger().info("sendViaJCEF(): start")

        // 注意: openOpenCodeWebToolWindow() 已经在 sendPrompt() 中调用过了
        // 这里不能再调用，因为 sendViaJCEF 可能在后台线程执行
        // 而 toolWindow.activate(null) 必须在 EDT 线程执行

        // 等待浏览器准备好，最多等待10秒
        for (i in 1..20) {
            val browser = MyToolWindowFactory.getMainBrowser()
            if (browser != null && browser.cefBrowser != null) {
                thisLogger().info("sendViaJCEF(): browser ready after ${i * 500}ms")
                executeSendViaJCEF(browser.cefBrowser!!, text)
                return
            }
            thisLogger().info("sendViaJCEF(): waiting for browser... attempt $i/20")
            Thread.sleep(500)
        }

        thisLogger().warn("sendViaJCEF(): browser not ready after 10s, trying anyway")
        val browser = MyToolWindowFactory.getMainBrowser()
        if (browser != null && browser.cefBrowser != null) {
            executeSendViaJCEF(browser.cefBrowser!!, text)
        } else {
            thisLogger().error("sendViaJCEF(): browser still not available")
        }
    }

    private fun executeSendViaJCEF(cefBrowser: org.cef.browser.CefBrowser, text: String) {
        thisLogger().info("executeSendViaJCEF(): executing JS")

        val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        val js = """
            (function() {
                console.log('[OpenCode Plugin] JS injected successfully, starting...');

                var attempt = 0;
                function trySend() {
                    console.log('[OpenCode Plugin] trySend() attempt ' + attempt);

                    var editor = document.querySelector('[role="textbox"][contenteditable="true"]');
                    if (!editor) {
                        console.log('[OpenCode Plugin] Prompt input not found in DOM');

                        if (attempt < 50) {
                            attempt++;
                            console.log('[OpenCode Plugin] Will retry in 100ms');
                            setTimeout(trySend, 100);
                        } else {
                            console.error('[OpenCode Plugin] Prompt input not found after 50 attempts');
                        }
                        return;
                    }

                    console.log('[OpenCode Plugin] Prompt input found, element:', editor);
                    console.log('[OpenCode Plugin] Setting text:', '$escapedText');
                    editor.innerText = '$escapedText';

                    console.log('[OpenCode Plugin] Triggering input event');
                    var inputEvent = new Event('input', { bubbles: true, cancelable: true });
                    editor.dispatchEvent(inputEvent);

                    console.log('[OpenCode Plugin] Triggering keydown event (Enter)');
                    var enterEvent = new KeyboardEvent('keydown', {
                        bubbles: true,
                        cancelable: true,
                        key: 'Enter',
                        code: 'Enter',
                        keyCode: 13,
                        which: 13,
                        shiftKey: false,
                        ctrlKey: false,
                        altKey: false,
                        metaKey: false
                    });
                    editor.dispatchEvent(enterEvent);

                    console.log('[OpenCode Plugin] Trying to find send button');
                    var sendButton = document.querySelector('button[aria-label*="send" i]')
                        || document.querySelector('button[aria-label*="发送" i]')
                        || document.querySelector('button[type="submit"]')
                        || document.querySelector('button');
                    if (sendButton) {
                        console.log('[OpenCode Plugin] Found send button, clicking it');
                        sendButton.click();
                    } else {
                        console.warn('[OpenCode Plugin] Could not find send button');
                    }
                }

                console.log('[OpenCode Plugin] Waiting a bit for page to be ready');
                setTimeout(trySend, 200);
            })()
        """.trimIndent()

        thisLogger().info("executeSendViaJCEF(): executing JS injection")
        try {
            cefBrowser.executeJavaScript(js, "", 0)
            thisLogger().info("executeSendViaJCEF(): JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("executeSendViaJCEF(): JS injection failed: ${e.message}")
        }
    }

    private fun showNotification(message: String) {
        ApplicationManager.getApplication().invokeLater {
            Notification(
                "OpenCodeWeb",
                "OpenCode",
                message,
                NotificationType.WARNING
            ).notify(project)
        }
    }

    private fun copyToClipboard() {
        val text = textArea.text.trim()
        if (text.isEmpty()) return

        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        appendToJCEF(text)
        textArea.text = ""
        copyButton.text = "已复制!"
        Thread {
            Thread.sleep(1500)
            SwingUtilities.invokeLater {
                copyButton.text = "复制"
            }
        }.start()
    }

    private fun appendToJCEF(text: String) {
        // 注意: openOpenCodeWebToolWindow() 可能会从后台线程被调用
        // 所以不在 appendToJCEF 中调用它

        for (i in 1..20) {
            val browser = MyToolWindowFactory.getMainBrowser()
            if (browser != null && browser.cefBrowser != null) {
                thisLogger().info("appendToJCEF(): browser ready after ${i * 500}ms")
                executeAppendToJCEF(browser.cefBrowser!!, text)
                return
            }
            thisLogger().info("appendToJCEF(): waiting for browser... attempt $i/20")
            Thread.sleep(500)
        }

        thisLogger().warn("appendToJCEF(): browser not ready after 10s")
    }

    private fun executeAppendToJCEF(cefBrowser: org.cef.browser.CefBrowser, text: String) {
        val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        val js = """
            (function() {
                var attempt = 0;
                function tryAppend() {
                    var editor = document.querySelector('[role="textbox"][contenteditable="true"]');
                    if (!editor) {
                        if (attempt < 30) {
                            attempt++;
                            console.log('[OpenCode Plugin] Prompt input not found, retrying... attempt ' + attempt);
                            setTimeout(tryAppend, 100);
                        } else {
                            console.error('[OpenCode Plugin] Prompt input not found after 30 attempts');
                        }
                        return;
                    }
                    console.log('[OpenCode Plugin] Prompt input found, appending text');
                    var text = '$escapedText';
                    var current = editor.innerText || '';
                    if (current.trim().length > 0) {
                        editor.innerText = current + '\n' + text;
                    } else {
                        editor.innerText = text;
                    }
                    ['input', 'change', 'keyup', 'keydown'].forEach(function(eventType) {
                        var event = new Event(eventType, { bubbles: true, cancelable: true });
                        editor.dispatchEvent(event);
                    });
                }
                tryAppend();
            })()
        """.trimIndent()

        thisLogger().info("executeAppendToJCEF(): executing JS injection")
        try {
            cefBrowser.executeJavaScript(js, "", 0)
            thisLogger().info("executeAppendToJCEF(): JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("executeAppendToJCEF(): JS injection failed: ${e.message}")
        }
    }

    override fun dispose() {
        OpenCodeServerManager.removeStateListener(stateListener)
        checkScheduledFuture?.cancel(false)
        scheduler.shutdown()
    }
}
