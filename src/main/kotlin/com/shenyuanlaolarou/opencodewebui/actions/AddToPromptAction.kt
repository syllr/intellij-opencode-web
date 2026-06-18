package com.shenyuanlaolarou.opencodewebui.actions

import com.shenyuanlaolarou.opencodewebui.toolWindow.MyToolWindowFactory
import com.shenyuanlaolarou.opencodewebui.utils.IdeaVimIntegration
import com.shenyuanlaolarou.opencodewebui.utils.JcefJsInjector
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

class AddToPromptAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        // 如果焦点在 OpenCode Web 面板中，将焦点移回 IDE 编辑器
        if (isFocusInOpenCodeWeb(project)) {
            thisLogger().debug("[AddToPromptAction] Focus in OpenCodeWeb, moving focus to editor")
            val editorManager = FileEditorManager.getInstance(project)
            val editor = editorManager.selectedTextEditor
            if (editor != null) {
                editor.contentComponent.requestFocusInWindow()
            } else {
                editorManager.allEditors.firstOrNull()?.component?.requestFocusInWindow()
            }
            return
        }

        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)

        var selectedText: String?
        var selStart: Int
        var selEnd: Int

        if (IdeaVimIntegration.isIdeaVimInstalled()) {
            val result = IdeaVimIntegration.getVisualSelection(editor)
            if (result != null) {
                selectedText = result.first
                selStart = result.second
                selEnd = result.third
            } else {
                selectedText = editor?.selectionModel?.selectedText
                selStart = editor?.selectionModel?.selectionStart ?: -1
                selEnd = editor?.selectionModel?.selectionEnd ?: -1
            }
        } else {
            selectedText = editor?.selectionModel?.selectedText
            selStart = editor?.selectionModel?.selectionStart ?: -1
            selEnd = editor?.selectionModel?.selectionEnd ?: -1
        }

        if (selectedText.isNullOrBlank() || selStart < 0 || selEnd <= selStart) {
            thisLogger().info("[AddToPromptAction] No valid selection, opening OpenCode Web")
            MyToolWindowFactory.openOpenCodeWebToolWindow(project)
            return
        }
        val safeEditor = editor ?: return

        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(safeEditor.document) ?: return
        val filePath = psiFile.virtualFile?.path ?: return

        val document = safeEditor.document
        val startLine = document.getLineNumber(selStart) + 1
        val endLine = document.getLineNumber(selEnd) + 1

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText)

        JcefJsInjector.appendTextToEditor(project, formattedContent)

        if (IdeaVimIntegration.isIdeaVimInstalled() && IdeaVimIntegration.isInVisualMode(safeEditor)) {
            IdeaVimIntegration.exitVisualMode(safeEditor)
        } else {
            safeEditor.caretModel.primaryCaret.removeSelection()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun isFocusInOpenCodeWeb(project: Project): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        val instance = MyToolWindowFactory.myToolWindowInstances[project] ?: return false
        return instance.getBrowser()?.let { browser ->
            SwingUtilities.isDescendingFrom(focusOwner, browser.component)
        } ?: false
    }
}
