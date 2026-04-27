package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.github.xausky.opencodewebui.utils.PromptEditorService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

class PromptToolWindowPanel(
    private val project: com.intellij.openapi.project.Project,
    private val onSendSuccess: () -> Unit
) : JPanel() {

    private val textArea = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 12
        // 支持 Shift+Enter 换行（与 Enter 效果相同）
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

    private val sendButton = JButton("发送到 OpenCode").apply {
        isEnabled = false
    }

    private val copyButton = JButton("复制").apply {
        isEnabled = false
    }

    init {
        layout = BorderLayout(8, 8)
        border = EmptyBorder(12, 12, 8, 12)
        minimumSize = Dimension(250, 200)

        val scrollPane = JScrollPane(textArea)
        add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(copyButton)
            add(sendButton)
        }
        add(buttonPanel, BorderLayout.SOUTH)

        // 监听文本变化，启用/禁用按钮
        textArea.document.addUndoableEditListener {
            val hasText = textArea.text.isNotBlank()
            sendButton.isEnabled = hasText
            copyButton.isEnabled = hasText
        }

        sendButton.addActionListener { sendPrompt() }
        copyButton.addActionListener { copyToClipboard() }
    }

    // 追加文本到文本框
    // 空内容直接添加，有内容时前面空一行分隔
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

    // 请求文本框焦点
    fun requestTextAreaFocus() {
        textArea.requestFocus()
    }

    // 发送 prompt 到 OpenCode
    private fun sendPrompt() {
        val text = textArea.text.trim()
        if (text.isEmpty()) return

        if (!OpenCodeApi.isServerHealthySync()) {
            sendButton.isEnabled = false
            return
        }

        val projectPath = project.basePath ?: return
        val sessionId = PromptEditorService.getOrCreateSession(projectPath) ?: return

        sendButton.isEnabled = false
        sendButton.text = "发送中..."

        Thread {
            val success = PromptEditorService.sendMessage(sessionId, text)
            SwingUtilities.invokeLater {
                if (success) {
                    textArea.text = ""
                    sendButton.text = "已发送!"
                    onSendSuccess()
                    Thread {
                        Thread.sleep(1500)
                        SwingUtilities.invokeLater {
                            sendButton.text = "发送到 OpenCode"
                            sendButton.isEnabled = textArea.text.isNotBlank()
                        }
                    }.start()
                } else {
                    sendButton.text = "发送失败"
                    sendButton.isEnabled = true
                }
            }
        }.start()
    }

    // 复制内容到剪贴板
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
}
