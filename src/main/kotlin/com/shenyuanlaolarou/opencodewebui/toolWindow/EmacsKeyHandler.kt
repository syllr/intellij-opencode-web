package com.shenyuanlaolarou.opencodewebui.toolWindow

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * JCEF 浏览器 Emacs 风格键盘映射 + Ctrl+W 删除单词快捷键。
 *
 * 通过 [KeyboardFocusManager.addKeyEventDispatcher] 全局拦截,替代 OSR 模式下不可靠的
 * 组件级 KeyListener(MyPanel ↔ uiComponent 焦点异步传递竞态)。
 */
object EmacsKeyHandler {

    private const val SPECIAL_DELETE_WORD: Int = -1

    private val emacsMappings: Map<Int, Int> = mapOf(
        KeyEvent.VK_N to KeyEvent.VK_DOWN,
        KeyEvent.VK_P to KeyEvent.VK_UP,
        KeyEvent.VK_E to KeyEvent.VK_END,
        KeyEvent.VK_A to KeyEvent.VK_HOME,
        KeyEvent.VK_B to KeyEvent.VK_LEFT,
        KeyEvent.VK_F to KeyEvent.VK_RIGHT,
        KeyEvent.VK_W to SPECIAL_DELETE_WORD
    )

    private val rootToTarget: ConcurrentHashMap<Component, Component> = ConcurrentHashMap()

    private val dispatcher: KeyEventDispatcher = KeyEventDispatcher { e -> handleKeyEvent(e) }

    private val dispatcherInstalled = AtomicBoolean(false)

    fun install(rootComponent: Component, dispatchTarget: Component) {
        rootToTarget[rootComponent] = dispatchTarget
        if (dispatcherInstalled.compareAndSet(false, true)) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        }
    }

    fun uninstall(rootComponent: Component) {
        rootToTarget.remove(rootComponent)
        if (rootToTarget.isEmpty() && dispatcherInstalled.compareAndSet(true, false)) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
    }

    private fun handleKeyEvent(e: KeyEvent): Boolean {
        val mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val synthetic = synthesize(e, mgr.focusOwner) ?: return false
        synthetic.first.dispatchEvent(synthetic.second)
        return true
    }

    /** 纯函数式映射,内部测试可访问 — headless AWT dispatch 不可靠,直接验证映射逻辑更稳。 */
    internal fun synthesize(original: KeyEvent, focusOwner: Component?): Pair<Component, KeyEvent>? {
        if (original.id != KeyEvent.KEY_PRESSED) return null
        if ((original.modifiersEx and KeyEvent.CTRL_DOWN_MASK) == 0) return null
        if (focusOwner == null) return null

        // Snapshot to avoid CME during dispatch if install/uninstall runs concurrently
        val snapshot: List<Map.Entry<Component, Component>> = rootToTarget.entries.toList()
        val matchedTarget: Component = snapshot
            .firstOrNull { (root, _) -> SwingUtilities.isDescendingFrom(focusOwner, root) }
            ?.value
            ?: return null

        val targetKeyCode: Int = emacsMappings[original.keyCode] ?: return null
        return matchedTarget to buildSyntheticEvent(original, matchedTarget, targetKeyCode)
    }

    private fun buildSyntheticEvent(original: KeyEvent, target: Component, targetKeyCode: Int): KeyEvent {
        return if (targetKeyCode == SPECIAL_DELETE_WORD) {
            KeyEvent(
                target,
                original.id,
                System.currentTimeMillis(),
                KeyEvent.ALT_DOWN_MASK,
                KeyEvent.VK_BACK_SPACE,
                KeyEvent.CHAR_UNDEFINED
            )
        } else {
            KeyEvent(
                target,
                original.id,
                System.currentTimeMillis(),
                0,
                targetKeyCode,
                KeyEvent.CHAR_UNDEFINED
            )
        }
    }
}
