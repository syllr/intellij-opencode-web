package com.github.xausky.opencodewebui

import com.intellij.openapi.util.registry.Registry

/**
 * 在 IDE 启动时禁用 JCEF GPU 硬件加速。
 *
 * 多个 IDE 同时运行时，每个 IDE 各自启动独立的 GPU 进程导致 CPU 占用飙升。
 * 通过 applicationService + preload 在插件加载阶段实例化，在构造函数中设置
 * Registry key ide.browser.jcef.gpu.disable，确保 JBCefApp 初始化前注入
 * --disable-gpu / --disable-gpu-compositing。
 *
 * 此方式替代 JBCefAppRequiredArgumentsProvider EP，原因是 cef_server（OOP CEF）
 * 架构下 EP 不被调用，但 Registry 代码路径仍正常执行。
 *
 * 注意：preload="true" 是 Internal API，若未来 IDE 版本不再支持，
 * 需改用其他方式（如 AppLifecycleListener + 最早可用钩子触发的 service 调用）。
 */
@Suppress("Unused")
internal class OpenCodeCefArgsProvider {

    init {
        Registry.get("ide.browser.jcef.gpu.disable").setValue(true)
    }
}
