package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.util.SystemInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 覆盖 [MyToolWindowFactory.applyMacJcefArgsIfNeeded] 的平台分支契约:
 * - macOS: `ide.browser.jcef.extra.args` 被设为精简值 `--use-angle=metal`
 * - 非 macOS: 不修改 `extra.args`, 让 JetBrains 默认 Metal ANGLE (Chromium 142+) 兜底
 *
 * 设计动机与开关取舍详见 [MyToolWindowFactory.sharedJBCefClient] 注释
 * (Chromium gl_implementation.cc 164-167 自动补全 + IDE 2026.1 / JCEF 142+ 默认 kDefaultANGLEMetal)。
 *
 * 平台依赖:mac 分支测试只在 macOS CI runner 上断言;非 mac 分支通过 [assumeFalse] 跳过,
 * 避免在多平台 CI 上无意义的 fail。
 */
class MyToolWindowFactoryJcefArgsTest {

    private val extraArgsProperty = "ide.browser.jcef.extra.args"
    private val macExtraArgs = "--use-angle=metal"
    private val legacyThreeSwitchValue = "--enable-gpu-compositing,--use-gl=angle,--use-angle=metal"

    private val originalValue: String? = System.getProperty(extraArgsProperty)

    @After
    fun restoreProperty() {
        // 还原副作用, 防止污染其它测试或后续进程
        if (originalValue == null) {
            System.clearProperty(extraArgsProperty)
        } else {
            System.setProperty(extraArgsProperty, originalValue)
        }
    }

    // ── Case 1: macOS 路径 — 覆盖旧 3-switch 值 ──────────────────────

    @Test
    fun `on macOS applyMacJcefArgsIfNeeded overrides legacy value to single --use-angle=metal`() {
        assumeTrue("this assertion requires macOS runner", SystemInfo.isMac)

        // 模拟"修改前的旧状态":旧代码无 guard 时会设的 3 个 switch
        System.setProperty(extraArgsProperty, legacyThreeSwitchValue)

        MyToolWindowFactory.applyMacJcefArgsIfNeeded()

        assertEquals(
            "macOS should override legacy 3-switch value to single --use-angle=metal (gl_implementation.cc auto-completion covers --use-gl=angle; --enable-gpu-compositing is macOS default)",
            macExtraArgs,
            System.getProperty(extraArgsProperty)
        )
    }

    // ── Case 2: 非 macOS 路径 — 完全不设 extra.args ──────────────────

    @Test
    fun `on non-macOS applyMacJcefArgsIfNeeded does not modify extra args`() {
        assumeFalse("this assertion requires non-macOS runner", SystemInfo.isMac)

        // 模拟"调用前无值"状态
        System.clearProperty(extraArgsProperty)

        MyToolWindowFactory.applyMacJcefArgsIfNeeded()

        assertNull(
            "non-macOS should leave extra.args unset, letting JetBrains default Metal ANGLE (Chromium 142+) take over",
            System.getProperty(extraArgsProperty)
        )
    }
}