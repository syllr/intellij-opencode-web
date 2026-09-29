package com.shenyuanlaolarou.copyasprompt.actions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.shenyuanlaolarou.copyasprompt.format.DiagnosticSeverity

/**
 * 诊断采集的平台集成测试。
 *
 * 这是本项目里**唯一依赖平台**的测试：它验证 `collectDiagnostics` 真的能从
 * `DocumentMarkupModel`（daemon 存放高亮的地方）里把编译错误捞出来。
 *
 * 之所以必须测平台这一层：`DaemonCodeAnalyzerImpl.getHighlights` 带 `@TestOnly`，生产代码只能走
 * markup model；如果哪天平台改了高亮的存放位置，这个测试会失败，而不是让用户"点了没反应"。
 *
 * 注意：`BasePlatformTestCase` 继承 JUnit 3 的 `TestCase`，因此测试方法必须以 `test` 开头
 * （`@Test` 注解在 JUnit 3 运行器下不生效）。
 */
class EditorDiagnosticsTest : BasePlatformTestCase() {

    fun testCollectsCompileErrorFromMarkupModel() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                void m() {
                    undefinedMethod();
                }
            }
            """.trimIndent()
        )
        myFixture.doHighlighting() // 强制跑一次 daemon，把高亮写进 markup model

        val diagnostics = collectDiagnostics(project, myFixture.editor.document)

        assertFalse("应至少采集到一条诊断，实际为空", diagnostics.isEmpty())
        val error = diagnostics.first { it.severity == DiagnosticSeverity.ERROR }
        assertEquals("错误应在第 3 行", 3, error.line)
        assertTrue("消息应包含未解析的方法名，实际: ${error.message}", error.message.contains("undefinedMethod"))
        assertTrue("应带上出错行原文，实际: ${error.codeLine}", error.codeLine?.contains("undefinedMethod") == true)
    }

    fun testHasDiagnosticsTrueForBrokenFile() {
        myFixture.configureByText("Foo.java", "class Foo { void m() { undefinedMethod(); } }")
        myFixture.doHighlighting()

        assertTrue(hasDiagnostics(project, myFixture.editor.document))
    }

    fun testHasDiagnosticsFalseForCleanFile() {
        myFixture.configureByText("Foo.java", "class Foo { void m() { } }")
        myFixture.doHighlighting()

        assertFalse("无问题的文件不应被判定为有诊断", hasDiagnostics(project, myFixture.editor.document))
        assertTrue(collectDiagnostics(project, myFixture.editor.document).isEmpty())
    }

    fun testDiagnosticsAreSortedByLine() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                void m() {
                    unknownB();
                    unknownA();
                }
            }
            """.trimIndent()
        )
        myFixture.doHighlighting()

        val lines = collectDiagnostics(project, myFixture.editor.document).map { it.line }
        assertEquals(lines.sorted(), lines)
    }
}
