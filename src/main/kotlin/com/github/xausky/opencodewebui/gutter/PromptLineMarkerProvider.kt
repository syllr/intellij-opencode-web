package com.github.xausky.opencodewebui.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import java.awt.Font

class PromptLineMarkerProvider : LineMarkerProvider {

    private val logger = thisLogger()
    private val GUTTER_ICON = IconLoader.getIcon("/icons/ocw.svg", javaClass.classLoader)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val project = elements.firstOrNull()?.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("PromptEditor") ?: return
        
        if (!toolWindow.isVisible) {
            return
        }

        val file = elements.first().containingFile
        val document = file.viewProvider.document ?: return
        val virtualFile = file.viewProvider.virtualFile ?: return

        for (i in 0 until document.lineCount) {
            val start = document.getLineStartOffset(i)
            val end = document.getLineEndOffset(i)

            result.add(object : LineMarkerInfo<PsiElement>(
                elements.first(),
                TextRange(start, end),
                GUTTER_ICON,
                { "添加评论到第 ${i + 1} 行" },
                null,
                GutterIconRenderer.Alignment.LEFT
            ) {
                override fun createGutterRenderer(): GutterIconRenderer {
                    return object : GutterIconRenderer() {
                        override fun getIcon(): javax.swing.Icon = GUTTER_ICON
                        override fun getTooltipText(): String = "添加评论到第 ${i + 1} 行"
                        override fun isNavigateAction(): Boolean = true
                        override fun getClickAction(): AnAction {
                            return object : AnAction() {
                                override fun actionPerformed(e: AnActionEvent) {
                                    PromptCommentDialog(project, i, i, document, virtualFile).show()
                                }
                            }
                        }
                        override fun equals(other: Any?): Boolean {
                            if (this === other) return true
                            if (other !is GutterIconRenderer) return false
                            return true
                        }
                        override fun hashCode(): Int = i
                    }
                }
            })
        }
    }
}
