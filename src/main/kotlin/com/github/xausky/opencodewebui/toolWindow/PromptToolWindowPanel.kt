package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.utils.OpenCodeApi
import com.github.xausky.opencodewebui.utils.PromptEditorService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Font
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicButtonUI

class PromptToolWindowPanel(
    private val project: com.intellij.openapi.project.Project,
    private val onSendSuccess: () -> Unit
) : JPanel() {

    private val textArea = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 10
        background = Color(60, 63, 65)
        foreground = Color(169, 183, 198)
        caretColor = Color(187, 187, 187)
        selectedTextColor = Color(50, 53, 55)
        selectionColor = Color(75, 110, 175)
        border = EmptyBorder(8, 8, 8, 8)
        font = Font("Menlo", Font.PLAIN, 14)
    }

    private val sendButton = JButton("发送到 OpenCode").apply {
        isEnabled = false
        background = Color(0, 120, 212)
        foreground = Color.WHITE
        isOpaque = true
        isContentAreaFilled = true
        border = EmptyBorder(8, 20, 8, 20)
        font = Font("Helvetica", Font.PLAIN, 13)
        ui = BasicButtonUI()
    }

    private val copyButton = JButton("复制").apply {
        isEnabled = false
        background = Color(97, 97, 97)
        foreground = Color.WHITE
        isOpaque = true
        isContentAreaFilled = true
        border = EmptyBorder(8, 16, 8, 16)
        font = Font("Helvetica", Font.PLAIN, 13)
        ui = BasicButtonUI()
    }

    init {
        layout = BorderLayout(0, 0)
        background = Color(43, 43, 43)
        isOpaque = true
        minimumSize = java.awt.Dimension(250, 200)

        val topPanel = JPanel(BorderLayout()).apply {
            background = Color(43, 43, 43)
            isOpaque = true
            border = EmptyBorder(12, 12, 12, 12)
            add(JLabel("Prompt").apply {
                font = Font("Helvetica", Font.PLAIN, 14)
                foreground = Color(169, 183, 198)
            }, BorderLayout.WEST)
        }
        add(topPanel, BorderLayout.NORTH)

        val scrollPane = JScrollPane(textArea).apply {
            background = Color(43, 43, 43)
            border = EmptyBorder(0, 8, 0, 8)
            viewport.background = Color(43, 43, 43)
        }
        add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
            background = Color(43, 43, 43)
            isOpaque = true
            add(copyButton)
            add(sendButton)
        }
        add(buttonPanel, BorderLayout.SOUTH)

        textArea.document.addUndoableEditListener {
            val hasText = textArea.text.isNotBlank()
            sendButton.isEnabled = hasText
            copyButton.isEnabled = hasText
        }

        sendButton.addActionListener { sendPrompt() }
        copyButton.addActionListener { copyToClipboard() }
    }

    fun appendText(text: String) {
        val currentText = textArea.text
        textArea.text = if (currentText.isEmpty()) {
            text
        } else {
            "$currentText\n$text"
        }
        textArea.caretPosition = textArea.document.length
    }

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
