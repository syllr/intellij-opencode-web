package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.BROWSER_READY_MAX_RETRIES
import com.github.xausky.opencodewebui.BROWSER_READY_RETRY_DELAY_MS
import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.net.URI
import java.util.Base64

/**
 * JCEF 浏览器 JavaScript 注入工具。
 * 将文本追加到 OpenCode Web 页面的输入框中。
 */
object JcefJsInjector {

    /**
     * 将文本追加到 OpenCode Web 页面的输入框。
     * 自动处理 session 跳转、浏览器等待和 JavaScript 注入。
     */
    fun appendTextToEditor(project: Project, text: String) {
        MyToolWindowFactory.openOpenCodeWebToolWindow(project)

        val projectPath = project.basePath ?: return

        for (i in 1..BROWSER_READY_MAX_RETRIES) {
            val browser = MyToolWindowFactory.getMainBrowser()
            if (browser != null) {
                val cefBrowser = browser.cefBrowser
                try {
                    val currentUrl = cefBrowser.getURL()
                    if (!isCorrectSession(currentUrl, projectPath)) {
                        val sessions = PromptEditorService.getSessions(projectPath)
                        val sessionId = sessions.firstOrNull()?.id
                        if (sessionId != null) {
                            val sessionUrl = buildSessionUrl(projectPath, sessionId)
                            thisLogger().info("[JcefJsInjector] 跳转到正确 session: $sessionUrl")
                            cefBrowser.loadURL(sessionUrl)
                            Thread.sleep(1000)
                            cefBrowser.reload()
                            Thread.sleep(3000)
                        }
                    }
                } catch (e: Exception) {
                    thisLogger().warn("[JcefJsInjector] session 检查失败，直接注入: ${e.message}")
                }
                executeAppend(cefBrowser, text)
                return
            }
            Thread.sleep(BROWSER_READY_RETRY_DELAY_MS)
        }

        thisLogger().warn("[JcefJsInjector] browser not ready after retries")
    }

    private fun isCorrectSession(url: String?, projectPath: String): Boolean {
        if (url == null || url.isBlank()) return false
        try {
            // URL 格式: http://host:port/<encoded>[/session/<session-id>]
            val path = URI(url).path
            // 提取编码部分：从第一个 "/" 到 "/session/" 或到路径末尾
            val sessionIndex = path.indexOf("/session/")
            val endIndex = if (sessionIndex > 0) sessionIndex else path.length
            val encoded = path.substring(1, endIndex)  // 跳过开头的 "/"
            if (encoded.isBlank()) return false
            val decodedPath = try {
                String(Base64.getUrlDecoder().decode(encoded))
            } catch (_: Exception) {
                try {
                    String(Base64.getDecoder().decode(encoded))
                } catch (_: Exception) {
                    null
                }
            }
            return decodedPath == projectPath
        } catch (e: Exception) {
            thisLogger().warn("[JcefJsInjector] 解析 session URL 失败: ${e.message}")
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
                        if (attempt < 100) {
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
            thisLogger().info("[JcefJsInjector] JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("[JcefJsInjector] JS injection failed: ${e.message}")
        }
    }
}
