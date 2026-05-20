package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor

object IdeaVimIntegration {
    private val logger = thisLogger()

    private val ideaVimAvailable: Boolean by lazy {
        try {
            Class.forName("com.maddyhome.idea.vim.VimPlugin")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    fun isIdeaVimInstalled(): Boolean = ideaVimAvailable

    /**
     * 获取 IdeaVim 的 injector 实例。
     * 优先使用 getInjector() 方法，降级到 injector 字段反射。
     */
    private fun getVimInjector(instance: Any): Any? {
        return try {
            instance::class.java.getMethod("getInjector").invoke(instance)
        } catch (_: NoSuchMethodException) {
            logger.debug("[IdeaVimIntegration] getInjector() not found, using field fallback")
            try {
                val field = instance::class.java.getDeclaredField("injector")
                field.isAccessible = true
                field.get(instance)
            } catch (e: Exception) {
                logger.warn("[IdeaVimIntegration] Field fallback failed: ${e.message}")
                null
            }
        }
    }

    /**
     * 通过反射调用 IdeaVim 的内部 API，获取 visual mode 下的选中文本。
     *
     * 调用链: VimPlugin.getInstance() → injector → vimState → mode
     * 如果当前处于 VISUAL 模式，通过 VimEditor 获取选中范围。
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun getVisualSelection(editor: Editor?): Triple<String, Int, Int>? {
        if (editor == null) return null
        return try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val isEnabled = vimPluginClass.getMethod("isEnabled").invoke(null) as Boolean
            if (!isEnabled) return null

            val instance = vimPluginClass.getMethod("getInstance").invoke(null)

            val injector = getVimInjector(instance)

            if (injector == null) return null

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val mode = vimState::class.java.getMethod("getMode").invoke(vimState)
            val modeClassName = mode::class.java.name ?: ""

            if (!modeClassName.contains("VISUAL", ignoreCase = true)) return null

            val ijVimEditorClass = Class.forName("com.maddyhome.idea.vim.vimscript.model.IjVimEditor")
            val vimEditor = ijVimEditorClass.getConstructor(Editor::class.java).newInstance(editor)

            val textRangeClass = Class.forName("com.maddyhome.idea.vim.api.VimTextRange")
            val getSelectionMethod = vimEditor::class.java.getMethod("getSelection")
            val textRange = getSelectionMethod.invoke(vimEditor)

            if (textRange == null) return null

            val startOffset = textRange::class.java.getMethod("getStartOffset").invoke(textRange) as Int
            val endOffset = textRange::class.java.getMethod("getEndOffset").invoke(textRange) as Int

            if (startOffset < 0 || endOffset <= startOffset || endOffset > editor.document.textLength) return null

            val text = editor.document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
            if (text.isBlank()) return null

            Triple(text, startOffset, endOffset)
        } catch (e: Exception) {
            thisLogger().warn("[IdeaVimIntegration] getVisualSelection failed: ${e.message}")
            null
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun isInVisualMode(editor: Editor?): Boolean {
        if (editor == null) return false
        return try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val isEnabled = vimPluginClass.getMethod("isEnabled").invoke(null) as Boolean
            if (!isEnabled) return false

            val instance = vimPluginClass.getMethod("getInstance").invoke(null)
            val injector = getVimInjector(instance) ?: return false

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val mode = vimState::class.java.getMethod("getMode").invoke(vimState)
            mode::class.java.name.contains("VISUAL", ignoreCase = true)
        } catch (e: Exception) {
            thisLogger().warn("[IdeaVimIntegration] isInVisualMode failed: ${e.message}")
            false
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun exitVisualMode(editor: Editor?) {
        if (editor == null) return
        try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val instance = vimPluginClass.getMethod("getInstance").invoke(null)
            val injector = getVimInjector(instance) ?: return

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val exitMethod = vimState::class.java.getMethod("exitVisualMode")
            exitMethod.invoke(vimState)
            thisLogger().info("[IdeaVimIntegration] Exited visual mode via reflection")
        } catch (e: Exception) {
            thisLogger().warn("[IdeaVimIntegration] exitVisualMode failed: ${e.message}")
        }
    }
}
