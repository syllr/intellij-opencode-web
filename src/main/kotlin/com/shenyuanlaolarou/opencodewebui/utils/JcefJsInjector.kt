package com.shenyuanlaolarou.opencodewebui.utils

import com.shenyuanlaolarou.opencodewebui.BROWSER_READY_MAX_RETRIES
import com.shenyuanlaolarou.opencodewebui.BROWSER_READY_RETRY_DELAY_MS
import com.shenyuanlaolarou.opencodewebui.toolWindow.MyToolWindowFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

private val JS_APPEND_PREFIX: String = """
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
            var text = '""".trimIndent()

private val JS_APPEND_SUFFIX: String = """';
            var hasContent = editor.innerText.trim().length > 0;
            if (hasContent) {
                var textNode = document.createTextNode('\n' + text);
                editor.appendChild(textNode);
            } else {
                editor.innerHTML = '';
                editor.innerText = text;
            }
            var range = document.createRange();
            range.selectNodeContents(editor);
            range.collapse(false);
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

internal fun escapeForJsTemplate(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (c in text) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '\'' -> sb.append("\\'")
            '`' -> sb.append("\\`")
            '\n' -> sb.append("\\n")
            '\r' -> {}
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

internal fun buildAppendJs(escapedText: String): String =
    JS_APPEND_PREFIX + escapedText + JS_APPEND_SUFFIX

/**
 * JCEF 浏览器 JavaScript 注入工具。
 * 将文本追加到 OpenCode Web 页面的输入框中。
 */
object JcefJsInjector {

    /**
     * 将文本追加到 OpenCode Web 页面的输入框。
     *
     * 快速路径——浏览器已就绪：EDT 上直接注入。
     * 慢速路径——浏览器未就绪：Alarm 非阻塞定时重试，不占用 BB 线程池。
     */
    fun appendTextToEditor(project: Project, text: String) {
        MyToolWindowFactory.openOpenCodeWebToolWindow(project)
        if (project.basePath == null) return

        if (MyToolWindowFactory.isBrowserPageLoaded(project)) {
            val browser = MyToolWindowFactory.getMainBrowser(project)
            if (browser != null) {
                executeAppend(browser.cefBrowser, text)
                return
            }
        }

        scheduleRetry(project, text, retryCount = 0)
    }

    private fun scheduleRetry(project: Project, text: String, retryCount: Int) {
        if (retryCount >= BROWSER_READY_MAX_RETRIES) {
            thisLogger().warn("[JcefJsInjector] browser not ready after $retryCount retries, giving up")
            return
        }

        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
        alarm.addRequest({
            if (project.isDisposed) return@addRequest

            if (MyToolWindowFactory.isBrowserPageLoaded(project)) {
                val browser = MyToolWindowFactory.getMainBrowser(project)
                if (browser != null) {
                    executeAppend(browser.cefBrowser, text)
                    return@addRequest
                }
            }
            scheduleRetry(project, text, retryCount + 1)
        }, BROWSER_READY_RETRY_DELAY_MS)
    }

    private fun executeAppend(cefBrowser: org.cef.browser.CefBrowser, text: String) {
        val js = buildAppendJs(escapeForJsTemplate(text))
        try {
            cefBrowser.executeJavaScript(js, "", 0)
            thisLogger().debug("[JcefJsInjector] JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("[JcefJsInjector] JS injection failed: ${e.message}")
        }
    }

    /**
     * 把 JCEF 页面里当前选中的文本追加到 OpenCode Web prompt 输入框,光标落末尾。
     *
     * 用于 LinkContextMenuHandler 的 "PasteTo" 菜单项:用户在 Web UI 上选中一段文字
     * (如 AI 上一轮回复),右键 → PasteTo → 这段文字被 append 到 prompt 编辑器,
     * 适合 "引用上一轮回复追问" 的场景。
     *
     * 无选中文本时静默 return(由菜单项 setEnabled(false) 拦截,正常路径不会到这里)。
     *
     * NOTE: JS 体内的 hasContent 检查 / createTextNode / range+collapse+addRange / 4 事件派发
     * 与文件顶部的 JS_APPEND_SUFFIX 几乎逐字相同,差异只在文本来源(页面选区 vs Kotlin 注入)
     * 和有无重试循环。当前与既有"内联常量模板"风格保持一致,若再出现第三个调用点
     * 建议抽成预加载的全局 JS 辅助函数统一。
     */
    fun pasteSelectionToEditor(cefBrowser: org.cef.browser.CefBrowser) {
        val js = """
            (function() {
                var sel = window.getSelection();
                var text = sel ? sel.toString() : '';
                if (!text) return;
                var editor = document.querySelector('[role="textbox"][contenteditable="true"]');
                if (!editor) return;
                var hasContent = editor.innerText.trim().length > 0;
                if (hasContent) {
                    var textNode = document.createTextNode('\n' + text);
                    editor.appendChild(textNode);
                } else {
                    editor.innerHTML = '';
                    editor.innerText = text;
                }
                var range = document.createRange();
                range.selectNodeContents(editor);
                range.collapse(false);
                sel.removeAllRanges();
                sel.addRange(range);
                ['input', 'change', 'keyup', 'keydown'].forEach(function(eventType) {
                    editor.dispatchEvent(new Event(eventType, { bubbles: true, cancelable: true }));
                });
            })()
        """.trimIndent()
        try {
            cefBrowser.executeJavaScript(js, "", 0)
            thisLogger().debug("[JcefJsInjector] paste-selection executed")
        } catch (e: Exception) {
            thisLogger().warn("[JcefJsInjector] paste-selection JS injection failed: ${e.message}")
        }
    }
}
