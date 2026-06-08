package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

class LinkContextMenuHandler(private val project: Project) : CefContextMenuHandlerAdapter() {
    override fun onBeforeContextMenu(
        browser: CefBrowser,
        frame: CefFrame,
        params: CefContextMenuParams,
        model: CefMenuModel
    ) {
        model.clear()
        model.addItem(TOGGLE_TOOL_WINDOW_COMMAND_ID, "Rebuild")
        model.addItem(REFRESH_COMMAND_ID, "Refresh")
        model.addItem(100, "Back")
        model.setEnabled(100, browser.canGoBack())
        model.addItem(101, "Forward")
        model.setEnabled(101, browser.canGoForward())
        val linkUrl = params.linkUrl
        if (!linkUrl.isNullOrEmpty()) {
            model.addItem(COPY_LINK_COMMAND_ID, "Open in Browser")
            model.addItem(COPY_LINK_AS_PROMPT_COMMAND_ID, "Copy Link")
        }
        model.addItem(SHUTDOWN_COMMAND_ID, "Shutdown Server")
    }

    override fun onContextMenuCommand(
        browser: CefBrowser,
        frame: CefFrame,
        params: CefContextMenuParams,
        commandId: Int,
        eventFlags: Int
    ): Boolean {
        if (commandId == REFRESH_COMMAND_ID) {
            browser.reload()
            return true
        }
        if (commandId == COPY_LINK_COMMAND_ID) {
            val linkUrl = params.linkUrl
            if (!linkUrl.isNullOrEmpty()) {
                BrowserUtil.browse(URI(linkUrl))
            }
            return true
        }
        if (commandId == COPY_LINK_AS_PROMPT_COMMAND_ID) {
            val linkUrl = params.linkUrl
            if (!linkUrl.isNullOrEmpty()) {
                val selection = StringSelection(linkUrl)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            }
            return true
        }
        if (commandId == TOGGLE_TOOL_WINDOW_COMMAND_ID) {
            MyToolWindowFactory.toggleOpenCodeWebToolWindow(project)
            return true
        }
        if (commandId == SHUTDOWN_COMMAND_ID) {
            MyToolWindowFactory.shutdownServer()
            return true
        }
        return false
    }

    companion object {
        const val COPY_LINK_COMMAND_ID = 26500
        const val REFRESH_COMMAND_ID = 26501
        const val SHUTDOWN_COMMAND_ID = 26502
        const val COPY_LINK_AS_PROMPT_COMMAND_ID = 26503
        const val TOGGLE_TOOL_WINDOW_COMMAND_ID = 26504
    }
}
