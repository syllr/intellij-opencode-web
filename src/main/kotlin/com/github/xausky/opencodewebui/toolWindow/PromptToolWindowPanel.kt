package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.github.xausky.opencodewebui.utils.PromptEditorService
import com.intellij.notification.Notification
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import com.github.xausky.opencodewebui.utils.PromptEditorService.Session

// ServerState enum for state machine management
private enum class ServerState {
    NOT_STARTED,
    STARTING,
    RUNNING
}

class PromptToolWindowPanel(
    private val project: com.intellij.openapi.project.Project,
    private val onSendSuccess: () -> Unit = {}
) : JPanel() {

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

    private val sendButton = JButton(
        if (OpenCodeServerManager.isServerRunning()) "发送到 OpenCode" else "启动服务"
    ).apply {
        isEnabled = true
    }

    private val copyButton = JButton("复制").apply {
        isEnabled = false
    }

    private val loadingLabel = JLabel(AnimatedIcon.Default()).apply {
        verticalAlignment = SwingConstants.CENTER
        isVisible = false
    }

    private val sessionComboBoxPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
    }

    private val sessionComboBox = ComboBox<Session>().apply {
        preferredSize = Dimension(300, 30)
        maximumRowCount = 10
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                refreshSessions()
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })
    }

    private var currentState = if (OpenCodeServerManager.isServerRunning()) ServerState.RUNNING else ServerState.NOT_STARTED

    init {
        thisLogger().info("PromptToolWindowPanel init: currentState: $currentState")
        layout = BorderLayout(8, 8)
        border = EmptyBorder(12, 12, 8, 12)
        minimumSize = Dimension(250, 200)

        sessionComboBoxPanel.add(sessionComboBox)
        sessionComboBoxPanel.add(loadingLabel)
        add(sessionComboBoxPanel, BorderLayout.NORTH)
        val scrollPane = JScrollPane(textArea)
        add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(copyButton)
            add(sendButton)
        }
        add(buttonPanel, BorderLayout.SOUTH)

        textArea.document.addUndoableEditListener {
            val hasText = textArea.text.isNotBlank()
            copyButton.isEnabled = hasText
        }

        sendButton.addActionListener { sendPrompt() }
        copyButton.addActionListener { copyToClipboard() }

        val projectPath = project.basePath
        if (projectPath != null) {
            Thread {
                val sessions = PromptEditorService.getSessions(projectPath)
                    .filter { !it.isArchived }
                    .sortedByDescending { it.createdAt }
                SwingUtilities.invokeLater {
                    sessions.forEach {
                        sessionComboBox.addItem(it)
                    }
                    if (sessions.isNotEmpty()) {
                        sessionComboBox.selectedItem = sessions.first()
                    }
                    sendButton.isEnabled = sessionComboBox.selectedItem != null
                }
            }.start()
        }
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
        if (text.isEmpty()) return

        val projectPath = project.basePath ?: return

        when (currentState) {
            ServerState.NOT_STARTED -> {
                thisLogger().info("sendPrompt(): state NOT_STARTED, starting service and send")
                startServerAndSend(text, projectPath)
            }
            ServerState.STARTING -> {
                thisLogger().info("sendPrompt(): state STARTING, ignoring duplicate click")
            }
            ServerState.RUNNING -> {
                thisLogger().info("sendPrompt(): state RUNNING, doSend()")
                doSend(text, projectPath)
            }
        }
    }

    private fun startServerAndSend(text: String, projectPath: String) {
        thisLogger().info("startServerAndSend(): called")
        if (currentState == ServerState.STARTING) {
            thisLogger().info("startServerAndSend(): already STARTING, ignoring")
            return
        }

        thisLogger().info("startServerAndSend(): state NOT_STARTED → STARTING")
        currentState = ServerState.STARTING
        SwingUtilities.invokeLater {
            sendButton.isEnabled = false
            sendButton.text = "启动服务..."
        }
        OpenCodeServerManager.startServer(
            project,
            onStarted = {
                thisLogger().info("startServerAndSend(): onStarted")
                currentState = ServerState.RUNNING
                SwingUtilities.invokeLater {
                    sendButton.text = "发送到 OpenCode"
                    sendButton.isEnabled = true
                }
                doSend(text, projectPath)
            },
            onFailed = { e ->
                thisLogger().info("startServerAndSend(): onFailed: ${e.message}")
                currentState = ServerState.NOT_STARTED
                SwingUtilities.invokeLater {
                    sendButton.text = "启动服务"
                    sendButton.isEnabled = true
                }
                showNotification("启动服务失败: ${e.message ?: "未知错误"}")
            }
        )
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

        val selectedSession = sessionComboBox.selectedItem as? Session ?: run {
            showNotification("请先选择一个会话")
            SwingUtilities.invokeLater {
                sendButton.text = "发送到 OpenCode"
                sendButton.isEnabled = true
            }
            return
        }
        thisLogger().info("doSend(): sending, selectedSession: ${selectedSession?.id}")

        Thread {
            sendViaJCEF(text)
            SwingUtilities.invokeLater {
                thisLogger().info("doSend(): sent via JCEF")
                textArea.text = ""
                sendButton.text = "已发送!"
                onSendSuccess()
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
        val browser = MyToolWindowFactory.getMainBrowser() ?: run {
            thisLogger().warn("sendViaJCEF(): main browser not available")
            return
        }

        val cefBrowser = browser.cefBrowser ?: run {
            thisLogger().warn("sendViaJCEF(): cefBrowser is null")
            return
        }

        val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        val js = """
            (function() {
                var editor = document.querySelector('[role="textbox"][contenteditable="true"]');
                if (!editor) {
                    console.error('[OpenCode Plugin] Prompt input not found');
                    return;
                }
                editor.textContent = '$escapedText';
                var inputEvent = new Event('input', { bubbles: true, cancelable: true });
                editor.dispatchEvent(inputEvent);
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
            })()
        """.trimIndent()

        thisLogger().info("sendViaJCEF(): executing JS injection")
        try {
            cefBrowser.executeJavaScript(js, "", 0)
            thisLogger().info("sendViaJCEF(): JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("sendViaJCEF(): JS injection failed: ${e.message}")
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
        if (text.isNotEmpty()) {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            copyButton.text = "已复制!"
            Thread {
                Thread.sleep(1500)
                SwingUtilities.invokeLater {
                    copyButton.text = "复制"
                }
            }.start()
        }
    }

    private fun refreshSessions() {
        thisLogger().info("refreshSessions(): called, currentState: $currentState")
        val projectPath = project.basePath ?: return

        if (currentState == ServerState.NOT_STARTED) {
            thisLogger().info("refreshSessions(): service not started, starting...")
            loadingLabel.isVisible = true
            currentState = ServerState.STARTING
            OpenCodeServerManager.startServer(
                project,
                onStarted = {
                    thisLogger().info("refreshSessions(): service started successfully")
                    currentState = ServerState.RUNNING
                    SwingUtilities.invokeLater {
                        sendButton.text = "发送到 OpenCode"
                        sendButton.isEnabled = true
                        loadingLabel.isVisible = false
                    }
                    doRefreshSessions(projectPath)
                },
                onFailed = { e ->
                    thisLogger().info("refreshSessions(): service start failed: ${e.message}")
                    currentState = ServerState.NOT_STARTED
                    SwingUtilities.invokeLater {
                        loadingLabel.isVisible = false
                    }
                    showNotification("启动服务失败: ${e.message ?: "未知错误"}")
                }
            )
            return
        }

        if (currentState == ServerState.RUNNING) {
            doRefreshSessions(projectPath)
        }
    }

    private fun doRefreshSessions(projectPath: String) {
        thisLogger().info("refreshSessions(): loading sessions...")
        Thread {
            val currentSelected = sessionComboBox.selectedItem as? PromptEditorService.Session
            val sessions = PromptEditorService.getSessions(projectPath)
                .filter { !it.isArchived }
                .sortedWith(compareByDescending<PromptEditorService.Session> {
                    currentSelected != null && it.id == currentSelected.id
                }.thenByDescending {
                    it.createdAt?.toLongOrNull() ?: 0
                })
            
            thisLogger().info("refreshSessions(): loaded ${sessions.size} sessions")
            
            SwingUtilities.invokeLater {
                val previousSelected = sessionComboBox.selectedItem
                sessionComboBox.removeAllItems()
                sessions.forEach {
                    sessionComboBox.addItem(it)
                }
                if (sessions.isNotEmpty()) {
                    if (previousSelected != null && sessions.contains(previousSelected)) {
                        sessionComboBox.selectedItem = previousSelected
                    } else {
                        sessionComboBox.selectedItem = sessions.first()
                    }
                }
                sendButton.isEnabled = sessionComboBox.selectedItem != null
            }
        }.start()
    }
}