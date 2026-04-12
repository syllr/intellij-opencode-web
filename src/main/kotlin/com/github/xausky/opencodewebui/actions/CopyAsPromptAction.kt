package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

fun formatAsPrompt(filePath: String, startLine: Int, endLine: Int, selectedText: String): String {
    val lineRange = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
    return "location:$filePath:$lineRange\ncontent:\n```\n$selectedText\n```"
}

class CopyAsPromptAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return

        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val filePath = psiFile.virtualFile?.path ?: return

        val document = editor.document
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        
        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber(endOffset) + 1

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText)

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(formattedContent), null)
    }

    override fun update(e: AnActionEvent) {
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            e.presentation.isEnabled = false
            return
        }

        val selectionModel = editor.selectionModel
        val hasSelection = selectionModel.hasSelection() && 
                          !selectionModel.selectedText.isNullOrBlank()
        
        e.presentation.isEnabled = hasSelection
    }
}
