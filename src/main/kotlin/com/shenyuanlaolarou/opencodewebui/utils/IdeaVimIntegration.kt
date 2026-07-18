package com.shenyuanlaolarou.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

object IdeaVimIntegration {
    private val logger = thisLogger()

    // 每次 CopyAsPromptAction.actionPerformed 触发 ~10 次 Class.forName + getMethod;
    // 用 lazy + runCatching 缓存 Method/Field/Constructor 引用,首次解析后命中。
    // "动态卸载" caveat:IdeaVim 运行时卸载会拿到 stale handle → 抛异常被外层 catch,
    // 安全降级为 null/false,符合现有契约。
    private val vimPluginClass: Class<*>? by lazy {
        runCatching { Class.forName("com.maddyhome.idea.vim.VimPlugin") }.getOrNull()
    }
    private val isEnabledMethod: Method? by lazy {
        vimPluginClass?.let { cls -> runCatching { cls.getMethod("isEnabled") }.getOrNull() }
    }
    private val getInstanceMethod: Method? by lazy {
        vimPluginClass?.let { cls -> runCatching { cls.getMethod("getInstance") }.getOrNull() }
    }
    private val getInjectorMethod: Method? by lazy {
        vimPluginClass?.let { cls -> runCatching { cls.getMethod("getInjector") }.getOrNull() }
    }
    private val injectorField: Field? by lazy {
        vimPluginClass?.let { cls ->
            runCatching { cls.getDeclaredField("injector").apply { isAccessible = true } }.getOrNull()
        }
    }
    private val getVimStateMethod: Method? by lazy {
        val injectorType = getInjectorMethod?.returnType ?: injectorField?.type ?: return@lazy null
        runCatching { injectorType.getMethod("getVimState") }.getOrNull()
    }
    private val getModeMethod: Method? by lazy {
        getVimStateMethod?.returnType?.let { vimStateType ->
            runCatching { vimStateType.getMethod("getMode") }.getOrNull()
        }
    }
    private val exitVisualModeMethod: Method? by lazy {
        getVimStateMethod?.returnType?.let { vimStateType ->
            runCatching { vimStateType.getMethod("exitVisualMode") }.getOrNull()
        }
    }
    private val ijVimEditorClass: Class<*>? by lazy {
        runCatching { Class.forName("com.maddyhome.idea.vim.vimscript.model.IjVimEditor") }.getOrNull()
    }
    private val ijVimEditorConstructor: Constructor<*>? by lazy {
        ijVimEditorClass?.let { cls ->
            runCatching { cls.getConstructor(Editor::class.java) }.getOrNull()
        }
    }
    private val getSelectionMethod: Method? by lazy {
        ijVimEditorClass?.let { cls ->
            runCatching { cls.getMethod("getSelection") }.getOrNull()
        }
    }
    private val vimTextRangeClass: Class<*>? by lazy {
        runCatching { Class.forName("com.maddyhome.idea.vim.api.VimTextRange") }.getOrNull()
    }
    private val getStartOffsetMethod: Method? by lazy {
        vimTextRangeClass?.let { cls -> runCatching { cls.getMethod("getStartOffset") }.getOrNull() }
    }
    private val getEndOffsetMethod: Method? by lazy {
        vimTextRangeClass?.let { cls -> runCatching { cls.getMethod("getEndOffset") }.getOrNull() }
    }

    fun isIdeaVimInstalled(): Boolean = vimPluginClass != null

    private fun getVimInjector(instance: Any): Any? {
        return try {
            getInjectorMethod?.invoke(instance) ?: injectorField?.get(instance)
        } catch (e: Exception) {
            logger.debug("[IdeaVimIntegration] injector resolve failed: ${e.message}")
            null
        }
    }

    /**
     * 通过反射调用 IdeaVim 的内部 API，获取 visual mode 下的选中文本。
     *
     * 调用链: VimPlugin.getInstance() → injector → vimState → mode
     * 如果当前处于 VISUAL 模式，通过 VimEditor 获取选中范围。
     */
    fun getVisualSelection(editor: Editor?): Triple<String, Int, Int>? {
        if (editor == null) return null
        return try {
            if (isEnabledMethod?.invoke(null) as? Boolean != true) return null

            val instance = getInstanceMethod?.invoke(null) ?: return null
            val injector = getVimInjector(instance) ?: return null
            val vimState = getVimStateMethod?.invoke(injector) ?: return null
            val mode = getModeMethod?.invoke(vimState) ?: return null

            if (!mode::class.java.name.contains("VISUAL", ignoreCase = true)) return null

            val vimEditor = ijVimEditorConstructor?.newInstance(editor) ?: return null
            val textRange = getSelectionMethod?.invoke(vimEditor) ?: return null

            val startOffset = getStartOffsetMethod?.invoke(textRange) as? Int ?: return null
            val endOffset = getEndOffsetMethod?.invoke(textRange) as? Int ?: return null

            if (startOffset < 0 || endOffset <= startOffset || endOffset > editor.document.textLength) return null

            val text = editor.document.getText(TextRange(startOffset, endOffset))
            if (text.isBlank()) return null

            Triple(text, startOffset, endOffset)
        } catch (e: Exception) {
            thisLogger().warn("[IdeaVimIntegration] getVisualSelection failed: ${e.message}")
            null
        }
    }

    fun isInVisualMode(editor: Editor?): Boolean {
        if (editor == null) return false
        return try {
            if (isEnabledMethod?.invoke(null) as? Boolean != true) return false

            val instance = getInstanceMethod?.invoke(null) ?: return false
            val injector = getVimInjector(instance) ?: return false
            val vimState = getVimStateMethod?.invoke(injector) ?: return false
            val mode = getModeMethod?.invoke(vimState) ?: return false
            mode::class.java.name.contains("VISUAL", ignoreCase = true)
        } catch (e: Exception) {
            thisLogger().warn("[IdeaVimIntegration] isInVisualMode failed: ${e.message}")
            false
        }
    }

    fun exitVisualMode(editor: Editor?) {
        if (editor == null) return
        try {
            val instance = getInstanceMethod?.invoke(null) ?: return
            val injector = getVimInjector(instance) ?: return
            val vimState = getVimStateMethod?.invoke(injector) ?: return
            exitVisualModeMethod?.invoke(vimState)
            thisLogger().debug("[IdeaVimIntegration] Exited visual mode via reflection")
        } catch (e: Exception) {
            thisLogger().warn("[IdeaVimIntegration] exitVisualMode failed: ${e.message}")
        }
    }
}
