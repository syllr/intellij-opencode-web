package com.shenyuanlaolarou.opencodewebui.settings

import com.shenyuanlaolarou.opencodewebui.utils.ALL_EVENT_TYPES
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeConfig
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.FlowLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class OpenCodeConfigurable : Configurable {

    private var panel: JPanel? = null
    private val eventCheckboxes = mutableMapOf<String, JCheckBox>()
    private val templateFields = mutableMapOf<String, JTextField>()
    private var globalToggle: JCheckBox? = null
    private var showProjectNameToggle: JCheckBox? = null
    private var showSessionTitleToggle: JCheckBox? = null
    private var minDurationField: JTextField = JBTextField("0")

    override fun getDisplayName(): String = "OpenCode"

    override fun createComponent(): JComponent {
        val builder = FormBuilder.createFormBuilder()

        builder.addComponent(JLabel("<html><b>通知配置</b></html>"))
        builder.addVerticalGap(8)

        globalToggle = JCheckBox("启用通知")
        builder.addComponent(globalToggle!!, 0)

        showProjectNameToggle = JCheckBox("在通知标题中显示项目名称")
        builder.addComponent(showProjectNameToggle!!, 0)

        showSessionTitleToggle = JCheckBox("在通知内容中显示 Session 标题")
        builder.addComponent(showSessionTitleToggle!!, 0)

        builder.addLabeledComponent("最短会话时长（秒，0=不限制）", minDurationField, 0, false)

        builder.addVerticalGap(8)
        builder.addComponent(JLabel("<html><b>事件通知开关与消息模板</b></html>"))
        builder.addVerticalGap(4)

        for (eventType in ALL_EVENT_TYPES) {
            val cb = JCheckBox(eventType)
            if (eventType == "interrupted") {
                cb.isEnabled = false
                cb.toolTipText = "当前环境不支持"
            }
            eventCheckboxes[eventType] = cb

            val tf = JBTextField(OpenCodeConfig.getMessageTemplate(eventType))
            tf.columns = 30
            templateFields[eventType] = tf

            val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            row.add(cb)
            row.add(tf)
            builder.addComponent(row, 0)
        }

        builder.addVerticalGap(8)
        builder.addComponent(JLabel("<html><i>占位符: {sessionTitle} {projectName} {timestamp} {agentName}</i></html>"))

        panel = builder.panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        if (globalToggle == null) return false
        if (globalToggle!!.isSelected != OpenCodeConfig.notificationEnabled) return true
        if (showProjectNameToggle!!.isSelected != OpenCodeConfig.showProjectName) return true
        if (showSessionTitleToggle!!.isSelected != OpenCodeConfig.showSessionTitle) return true
        if ((minDurationField.text.toIntOrNull() ?: 0) != OpenCodeConfig.minDuration) return true
        for ((eventType, cb) in eventCheckboxes) {
            if (cb.isSelected != OpenCodeConfig.isEventEnabled(eventType)) return true
        }
        for ((eventType, tf) in templateFields) {
            if (tf.text != OpenCodeConfig.getMessageTemplate(eventType)) return true
        }
        return false
    }

    override fun apply() {
        OpenCodeConfig.notificationEnabled = globalToggle!!.isSelected
        OpenCodeConfig.showProjectName = showProjectNameToggle!!.isSelected
        OpenCodeConfig.showSessionTitle = showSessionTitleToggle!!.isSelected
        OpenCodeConfig.minDuration = minDurationField.text.toIntOrNull() ?: 0
        for ((eventType, cb) in eventCheckboxes) {
            OpenCodeConfig.setEventEnabled(eventType, cb.isSelected)
        }
        for ((eventType, tf) in templateFields) {
            OpenCodeConfig.setMessageTemplate(eventType, tf.text)
        }
    }

    override fun reset() {
        globalToggle!!.isSelected = OpenCodeConfig.notificationEnabled
        showProjectNameToggle!!.isSelected = OpenCodeConfig.showProjectName
        showSessionTitleToggle!!.isSelected = OpenCodeConfig.showSessionTitle
        minDurationField.text = OpenCodeConfig.minDuration.toString()
        for ((eventType, cb) in eventCheckboxes) {
            cb.isSelected = OpenCodeConfig.isEventEnabled(eventType)
        }
        for ((eventType, tf) in templateFields) {
            tf.text = OpenCodeConfig.getMessageTemplate(eventType)
        }
    }
}
