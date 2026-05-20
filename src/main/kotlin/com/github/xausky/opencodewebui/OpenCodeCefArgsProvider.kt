package com.github.xausky.opencodewebui

import com.intellij.openapi.util.registry.Registry

@Suppress("Unused")
internal class OpenCodeCefArgsProvider {

    init {
        Registry.get("ide.browser.jcef.gpu.disable").setValue(false)
    }
}
