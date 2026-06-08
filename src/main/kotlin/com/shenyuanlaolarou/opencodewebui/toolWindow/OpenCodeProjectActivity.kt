package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.ProjectActivity

class OpenCodeProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection = project.messageBus.connect(project)
        connection.subscribe(com.intellij.openapi.project.ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosing(project: Project) {
                MyToolWindowFactory.contentManagerListeners.remove(project)?.let { listener ->
                    try {
                        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                            .getToolWindow(MyToolWindowFactory.OPCODE_WEB_TOOL_WINDOW_ID)
                            ?.contentManager
                            ?.removeContentManagerListener(listener)
                    } catch (_: IllegalStateException) {
                        // 项目已 dispose,清理无意义
                    } catch (e: Exception) {
                        thisLogger().debug("[OpenCodeProjectActivity] Listener removal failed: ${e.message}")
                    }
                }
            }
        })
    }
}
