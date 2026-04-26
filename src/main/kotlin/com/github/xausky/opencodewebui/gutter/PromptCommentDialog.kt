package com.github.xausky.opencodewebui.gutter

import com.github.xausky.opencodewebui.toolWindow.PromptToolWindowFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.Gray
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PromptCommentDialog(
    private val project: Project,
    private val startLine: Int,
    private val endLine: Int,
    private val document: com.intellij.openapi.editor.Document,
    private val virtualFile: VirtualFile?
) : DialogWrapper(project, true) {

    var commentText = ""
        private set

    init {
        title = "添加评论"
        setOKButtonText("评论")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(12, 12))
        panel.background = com.intellij.util.ui.UIUtil.getPanelBackground()

        val lineInfo = if (startLine == endLine) {
            "正在评论第 ${startLine + 1} 行"
        } else {
            "正在评论第 ${startLine + 1}-${endLine + 1} 行"
        }
        val titleLabel = JLabel(lineInfo).apply {
            font = font.deriveFont(16f)
        }
        panel.add(titleLabel, BorderLayout.NORTH)

        val textArea = JTextArea(5, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            font = Font("Menlo", Font.PLAIN, 14)
        }
        val scrollPane = JScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                commentText = textArea.text
            }

            override fun removeUpdate(e: DocumentEvent) {
                commentText = textArea.text
            }

            override fun changedUpdate(e: DocumentEvent) {
                commentText = textArea.text
            }
        })

        return panel
    }

    override fun doOKAction() {
        super.doOKAction()
        val panel = PromptToolWindowFactory.getPanel(project) ?: return
        val filePath = virtualFile?.path ?: "unknown"
        val lineInfo = if (startLine == endLine) {
            "$filePath:${startLine + 1}"
        } else {
            "$filePath:${startLine + 1}-${endLine + 1}"
        }
        val codeContent = buildString {
            for (i in startLine..endLine) {
                val start = document.getLineStartOffset(i)
                val end = document.getLineEndOffset(i)
                if (i > startLine) append("\n")
                append(document.getText(TextRange(start, end)))
            }
        }
        val contentToAdd = if (commentText.isBlank()) {
            "location:$lineInfo\ncontent:\n```\n$codeContent\n```"
        } else {
            "location:$lineInfo\ncomment:\n$commentText\ncontent:\n```\n$codeContent\n```"
        }
        panel.appendText(contentToAdd)
    }
}
