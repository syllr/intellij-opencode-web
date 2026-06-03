package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * JCEF 键盘快捷键拦截器。
 *
 * 拦截 JCEF 浏览器组件的 ESC 快捷键，
 * 阻止 IntelliJ 处理 ESC 按键事件，使其自然传递给 JCEF 浏览器。
 *
 * 注意：Cmd+, 和 Cmd+K 不在此拦截，采用 JS 注入方式在页面 DOM capture phase 拦截，
 * 阻止 JCEF 页面消费这两个快捷键，确保 IDEA 原生快捷键系统正常响应。
 */
object JcefKeyboardInterceptor {

    private val emptyAction = object : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {}
    }

    /**
     * 拦截单个组件的键盘快捷键。
     *
     * 对指定组件注册空的 KeyStroke→Action 映射，以"消耗"掉 ESC 按键，
     * 防止 IntelliJ 框架在 WHEN_IN_FOCUSED_WINDOW 条件下处理它。
     */
    fun interceptKeys(component: Component) {
        if (component !is JComponent) {
            thisLogger().debug("JCEF component is not JComponent, key interception skipped")
            return
        }

        component.focusTraversalKeysEnabled = false

        val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = component.actionMap

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "block")
        actionMap.put("block", emptyAction)
    }

    /**
     * 递归拦截组件层次结构中的所有组件。
     *
     * 遍历以 [component] 为根的整个 Container 树，对每个子组件调用 [interceptKeys]。
     */
    fun interceptKeysRecursive(component: Component?) {
        if (component == null) return
        interceptKeys(component)
        if (component is Container) {
            for (i in 0 until component.componentCount) {
                interceptKeysRecursive(component.getComponent(i))
            }
        }
    }
}
