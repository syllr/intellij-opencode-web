package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

class AddToPromptAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText

        if (selectedText.isNullOrBlank()) {
            com.github.xausky.opencodewebui.toolWindow.PromptToolWindowFactory.toggleToolWindowVisibility(project)
            return
        }

        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val filePath = psiFile.virtualFile?.path ?: return

        val document = editor.document
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd

        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber(endOffset) + 1

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText)
        val contentWithBlankLine = "\n$formattedContent"

        val panel = com.github.xausky.opencodewebui.toolWindow.PromptToolWindowFactory.getPanel(project)
        if (panel != null) {
            panel.appendText(contentWithBlankLine)
            com.github.xausky.opencodewebui.toolWindow.PromptToolWindowFactory.getOrActivateToolWindow(project)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor != null
    }
}