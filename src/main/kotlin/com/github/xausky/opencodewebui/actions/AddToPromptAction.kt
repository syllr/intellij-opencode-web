package com.github.xausky.opencodewebui.actions

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory
import com.github.xausky.opencodewebui.utils.PromptEditorService
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class AddToPromptAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor?

        var selectedText: String?
        var selStart: Int
        var selEnd: Int

        thisLogger().info("[AddToPromptAction] actionPerformed() called")

        // 优先尝试从 IdeaVim visual mode 获取选中文本
        if (isIdeaVimInstalled()) {
            editor = e.getData(CommonDataKeys.EDITOR)
            val result = getIdeaVimVisualSelection(editor)
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
            editor = e.getData(CommonDataKeys.EDITOR)
            selectedText = editor?.selectionModel?.selectedText
            selStart = editor?.selectionModel?.selectionStart ?: -1
            selEnd = editor?.selectionModel?.selectionEnd ?: -1
        }

        if (selectedText.isNullOrBlank() || selStart < 0 || selEnd <= selStart) {
            thisLogger().info("[AddToPromptAction] No valid selection, opening OpenCode Web")
            MyToolWindowFactory.openOpenCodeWebToolWindow(project)
            return
        }

        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor!!.document) ?: return
        val filePath = psiFile.virtualFile?.path ?: return

        val document = editor.document
        val startLine = document.getLineNumber(selStart) + 1
        val endLine = document.getLineNumber(selEnd) + 1

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText)

        // 直接追加到 OpenCode Web 输入框
        appendToOpenCodeWeb(project, formattedContent)

        // 清除选中
        if (isIdeaVimInstalled() && isInVisualMode(editor)) {
            exitVisualMode(editor)
        } else {
            editor.caretModel.primaryCaret.removeSelection()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    /**
     * 将文本追加到 OpenCode Web 页面的输入框
     */
    private fun appendToOpenCodeWeb(project: Project, text: String) {
        MyToolWindowFactory.openOpenCodeWebToolWindow(project)

        val projectPath = project.basePath ?: return

        for (i in 1..20) {
            val browser = MyToolWindowFactory.getMainBrowser()
            if (browser != null && browser.cefBrowser != null) {
                val cefBrowser = browser.cefBrowser!!
                // 尝试检查当前 session 是否匹配当前项目（可选，不阻塞注入）
                try {
                    val currentUrl = cefBrowser.getURL()
                    if (!isCorrectSession(currentUrl, projectPath)) {
                        val sessions = PromptEditorService.getSessions(projectPath)
                        val sessionId = sessions.firstOrNull()?.id
                        if (sessionId != null) {
                            val sessionUrl = buildSessionUrl(projectPath, sessionId)
                            thisLogger().info("[AddToPromptAction] 跳转到正确 session: $sessionUrl")
                            cefBrowser.loadURL(sessionUrl)
                            Thread.sleep(2000)
                        }
                    }
                } catch (e: Exception) {
                    thisLogger().warn("[AddToPromptAction] session 检查失败，直接注入: ${e.message}")
                }
                executeAppend(cefBrowser, text)
                return
            }
            Thread.sleep(500)
        }

        thisLogger().warn("[AddToPromptAction] browser not ready after 10s")
    }

    private fun isCorrectSession(url: String?, projectPath: String): Boolean {
        if (url == null || url.isBlank()) return false
        try {
            // URL 格式: http://host:port/<base64-project>/session/<session-id>
            val segments = URI(url).path.split("/")
            if (segments.size < 2) return false
            val encodedPath = try {
                java.net.URLDecoder.decode(segments[1], "UTF-8")
            } catch (_: Exception) {
                // 可能是 base64 编码
                try {
                    String(Base64.getUrlDecoder().decode(segments[1]))
                } catch (_: Exception) {
                    // 也可能是标准 Base64
                    try {
                        String(Base64.getDecoder().decode(segments[1]))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return encodedPath == projectPath
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] 解析 session URL 失败: ${e.message}")
            return false
        }
    }

    private fun buildSessionUrl(projectPath: String, sessionId: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(projectPath.toByteArray())
        return "http://$OPENCODE_HOST:$OPENCODE_PORT/$encoded/session/$sessionId"
    }

    private fun executeAppend(cefBrowser: org.cef.browser.CefBrowser, text: String) {
        val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        val js = """
            (function() {
                var attempt = 0;
                function tryAppend() {
                    var editor = document.querySelector('[role="textbox"][contenteditable="true"]');
                    if (!editor) {
                        if (attempt < 30) {
                            attempt++;
                            setTimeout(tryAppend, 100);
                        }
                        return;
                    }
                    var text = '$escapedText';
                    var hasContent = editor.innerText.trim().length > 0;
                    if (hasContent) {
                        // 已有内容，在新行追加
                        var textNode = document.createTextNode('\n' + text);
                        editor.appendChild(textNode);
                    } else {
                        // 空编辑器，先清除占位 <br> 再设置内容
                        editor.innerHTML = '';
                        editor.innerText = text;
                    }
                    // 追加一个 <br> 创建换行，再追加一个 <br> 创建光标空行
                    editor.appendChild(document.createElement('br'));
                    var cursorBr = document.createElement('br');
                    editor.appendChild(cursorBr);
                    // 光标放在最后一个 <br> 之后（新空行上）
                    var range = document.createRange();
                    range.setStartAfter(cursorBr);
                    var sel = window.getSelection();
                    sel.removeAllRanges();
                    sel.addRange(range);
                    ['input', 'change', 'keyup', 'keydown'].forEach(function(eventType) {
                        editor.dispatchEvent(new Event(eventType, { bubbles: true, cancelable: true }));
                    });
                }
                tryAppend();
            })()
        """.trimIndent()

        try {
            cefBrowser.executeJavaScript(js, "", 0)
            thisLogger().info("[AddToPromptAction] JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] JS injection failed: ${e.message}")
        }
    }

    // === IdeaVim 兼容代码 ===

    private fun isIdeaVimInstalled(): Boolean {
        return try {
            PluginManagerCore.getPlugin(PluginId.getId("IdeaVIM")) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过反射调用 IdeaVim 的内部 API，获取 visual mode 下的选中文本。
     *
     * 调用链: VimPlugin.getInstance() → injector → vimState → mode
     * 如果当前处于 VISUAL 模式，通过 VimEditor 获取选中范围。
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun getIdeaVimVisualSelection(editor: Editor?): Triple<String, Int, Int>? {
        if (editor == null) return null
        return try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val isEnabled = vimPluginClass.getMethod("isEnabled").invoke(null) as Boolean
            if (!isEnabled) return null

            val instance = vimPluginClass.getMethod("getInstance").invoke(null)

            val injector = try {
                instance::class.java.getMethod("getInjector").invoke(instance)
            } catch (_: NoSuchMethodException) {
                val field = instance::class.java.getDeclaredField("injector")
                field.isAccessible = true
                field.get(instance)
            }

            if (injector == null) return null

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val mode = vimState::class.java.getMethod("getMode").invoke(vimState)
            val modeClassName = mode::class.java.name ?: ""

            if (!modeClassName.contains("VISUAL", ignoreCase = true)) return null

            val ijVimEditorClass = Class.forName("com.maddyhome.idea.vim.vimscript.model.IjVimEditor")
            val vimEditor = ijVimEditorClass.getConstructor(Editor::class.java).newInstance(editor)

            val textRangeClass = Class.forName("com.maddyhome.idea.vim.api.VimTextRange")
            val getSelectionMethod = vimEditor::class.java.getMethod("getSelection")
            val textRange = getSelectionMethod.invoke(vimEditor)

            if (textRange == null) return null

            val startOffset = textRange::class.java.getMethod("getStartOffset").invoke(textRange) as Int
            val endOffset = textRange::class.java.getMethod("getEndOffset").invoke(textRange) as Int

            if (startOffset < 0 || endOffset <= startOffset || endOffset > editor.document.textLength) return null

            val text = editor.document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
            if (text.isBlank()) return null

            Triple(text, startOffset, endOffset)
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] IdeaVim reflection failed: ${e.message}")
            null
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun isInVisualMode(editor: Editor?): Boolean {
        if (editor == null) return false
        return try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val isEnabled = vimPluginClass.getMethod("isEnabled").invoke(null) as Boolean
            if (!isEnabled) return false

            val instance = vimPluginClass.getMethod("getInstance").invoke(null)
            val injector = try {
                instance::class.java.getMethod("getInjector").invoke(instance)
            } catch (_: NoSuchMethodException) {
                val field = instance::class.java.getDeclaredField("injector")
                field.isAccessible = true
                field.get(instance)
            } ?: return false

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val mode = vimState::class.java.getMethod("getMode").invoke(vimState)
            mode::class.java.name.contains("VISUAL", ignoreCase = true)
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] isInVisualMode failed: ${e.message}")
            false
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun exitVisualMode(editor: Editor?) {
        if (editor == null) return
        try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val instance = vimPluginClass.getMethod("getInstance").invoke(null)
            val injector = try {
                instance::class.java.getMethod("getInjector").invoke(instance)
            } catch (_: NoSuchMethodException) {
                val field = instance::class.java.getDeclaredField("injector")
                field.isAccessible = true
                field.get(instance)
            } ?: return

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val exitMethod = vimState::class.java.getMethod("exitVisualMode")
            exitMethod.invoke(vimState)
            thisLogger().info("[AddToPromptAction] Exited visual mode via reflection")
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] exitVisualMode failed: ${e.message}")
        }
    }
}