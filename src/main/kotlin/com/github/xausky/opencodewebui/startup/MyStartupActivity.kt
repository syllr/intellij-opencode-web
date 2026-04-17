package com.github.xausky.opencodewebui.startup

import com.github.xausky.opencodewebui.listeners.MyApplicationDisposable
import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer

class MyStartupActivity : StartupActivity {

    override fun runActivity(project: com.intellij.openapi.project.Project) {
        // 启动全局健康检查
        MyToolWindowFactory.startGlobalHealthCheck()

        val applicationDisposable = ApplicationManager.getApplication()
        val disposable = MyApplicationDisposable()
        Disposer.register(applicationDisposable, disposable)
    }
}
