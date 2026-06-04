package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.ide.util.PropertiesComponent

object OpenCodeConfig {

    private fun props() = PropertiesComponent.getInstance()

    var notificationEnabled: Boolean
        get() = props().getBoolean("opencode.settings.notification", true)
        set(value) = props().setValue("opencode.settings.notification", value)

    var showProjectName: Boolean
        get() = props().getBoolean("opencode.settings.showProjectName", true)
        set(value) = props().setValue("opencode.settings.showProjectName", value)

    var showSessionTitle: Boolean
        get() = props().getBoolean("opencode.settings.showSessionTitle", true)
        set(value) = props().setValue("opencode.settings.showSessionTitle", value)

    var minDuration: Int
        get() = props().getInt("opencode.settings.minDuration", 0)
        set(value) = props().setValue("opencode.settings.minDuration", value.toString())

}
