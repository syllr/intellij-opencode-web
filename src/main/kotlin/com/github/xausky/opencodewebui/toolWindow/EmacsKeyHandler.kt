package com.github.xausky.opencodewebui.toolWindow

import java.awt.Component
import java.awt.event.KeyEvent

object EmacsKeyHandler {

    /** 为指定组件添加 Emacs 风格的键盘映射 */
    fun addEmacsKeyMapping(component: Component) {
        val emacsMappings = mapOf(
            KeyEvent.VK_N to KeyEvent.VK_DOWN,
            KeyEvent.VK_P to KeyEvent.VK_UP,
            KeyEvent.VK_E to KeyEvent.VK_END,
            KeyEvent.VK_A to KeyEvent.VK_HOME,
            KeyEvent.VK_B to KeyEvent.VK_LEFT,
            KeyEvent.VK_F to KeyEvent.VK_RIGHT,
            KeyEvent.VK_W to -1  // Ctrl+W：删除上一个单词（Option+Backspace on Mac）
        )

        component.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                handleEmacsKey(e, emacsMappings, component)
            }
        })
    }

    private fun handleEmacsKey(e: KeyEvent, mappings: Map<Int, Int>, target: Component) {
        if ((e.modifiersEx and KeyEvent.CTRL_DOWN_MASK) == 0) return
        val targetKeyCode = mappings[e.keyCode] ?: return
        // Ctrl+W 特殊处理：删除上一个单词，在 Mac 下对应 Option+Backspace
        if (targetKeyCode == -1) {
            sendKeyEvent(target, e.id, KeyEvent.VK_BACK_SPACE, KeyEvent.ALT_DOWN_MASK)
        } else {
            sendKeyEvent(target, e.id, targetKeyCode, 0)
        }
        e.consume()
    }

    private fun sendKeyEvent(target: Component, eventId: Int, keyCode: Int, modifiers: Int) {
        val keyEvent = KeyEvent(
            target,
            eventId,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED
        )
        target.dispatchEvent(keyEvent)
    }
}
