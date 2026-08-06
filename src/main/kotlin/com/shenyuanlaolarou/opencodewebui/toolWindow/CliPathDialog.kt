package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * OpenCode CLI 路径配置对话框。
 *
 * 点击 Dashboard 的 `CLI` 按钮弹出：
 * - 文本框预填当前已配置的绝对路径（首次为空）
 * - `Browse…` 打开系统文件选择器选可执行文件（避免手打路径出错）
 * - 确定时走 [OpenCodeCliPathConfig.validate] 实时校验：非法路径红框提示且不允许关闭；
 *   文本框留空 = 清除配置（恢复默认 PATH 查找）
 */
internal class CliPathDialog(
    private val project: Project?,
    initialPath: String,
) : DialogWrapper(project, true) {

    private val pathField = JBTextField(initialPath, PATH_FIELD_COLUMNS)
    private val browseButton = JButton("Browse…")

    private companion object {
        /** 路径文本框的列宽（Swing 列数，容纳典型 /usr/local/bin/xxx 路径） */
        const val PATH_FIELD_COLUMNS = 40
    }

    /** 确定后调用方读取的最终路径（已 trim；空串 = 清除配置） */
    val cliPath: String
        get() = pathField.text.trim()

    init {
        title = "OpenCode CLI Path"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        val label = JBLabel("Absolute path to the opencode executable (leave empty to use PATH):")
        val row = JPanel(BorderLayout(8, 0))
        row.add(pathField, BorderLayout.CENTER)
        row.add(browseButton, BorderLayout.EAST)
        panel.add(label, BorderLayout.NORTH)
        panel.add(row, BorderLayout.CENTER)

        browseButton.addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select opencode executable")
            val file = FileChooser.chooseFile(descriptor, project, null)
            if (file != null) pathField.text = file.path
        }
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val error = OpenCodeCliPathConfig.validate(pathField.text)
        return error?.let { ValidationInfo(it, pathField) }
    }
}
