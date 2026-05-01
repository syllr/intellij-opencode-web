package com.github.xausky.opencodewebui.toolWindow

import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * JCEF 键盘快捷键拦截器。
 *
 * 拦截 JCEF 浏览器组件的键盘快捷键（ESC、Meta+,、Meta+K），
 * 阻止 IntelliJ 处理这些按键事件，使其自然传递给 JCEF 浏览器。
 */
object JcefKeyboardInterceptor {

    private val emptyAction = object : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {}
    }

    /**
     * 拦截单个组件的键盘快捷键。
     *
     * 对指定组件注册空的 KeyStroke→Action 映射，以"消耗"掉 ESC、Meta+,、Meta+K 按键，
     * 防止 IntelliJ 框架在 WHEN_IN_FOCUSED_WINDOW 条件下处理它们。
     */
    fun interceptKeys(component: Component) {
        if (component !is JComponent) return

        component.focusTraversalKeysEnabled = false

        val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = component.actionMap

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "block")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, java.awt.event.InputEvent.META_DOWN_MASK), "block")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, java.awt.event.InputEvent.META_DOWN_MASK), "block")
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
