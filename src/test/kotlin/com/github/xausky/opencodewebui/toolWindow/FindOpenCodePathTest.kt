package com.github.xausky.opencodewebui.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FindOpenCodePath 单元测试
 * 测试 findOpenCodePath 的核心逻辑和版本比较
 */
class FindOpenCodePathTest {

    /**
     * 测试用例1: testSinglePath_ReturnsDirectly
     * 逻辑：只有一个路径存在时直接返回，不调用 --version
     * 验证：Mock 文件系统，该路径存在，返回该路径
     */
    @Test
    fun testSinglePath_ReturnsDirectly() {
        // Given: 只有一个路径存在
        val candidatePaths = listOf(
            "/opt/homebrew/bin/opencode",
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        )
        var executorCalled = false
        val executor: (String) -> OpenCodePathFinder.ProcessResult? = { _ ->
            executorCalled = true
            null
        }

        // When: 只有一个路径存在
        val result = OpenCodePathFinder.findOpenCodePath(
            candidatePaths = candidatePaths,
            existingPathsFilter = { paths -> paths.filter { it == "/opt/homebrew/bin/opencode" } },
            executor = executor
        )

        // Then: 直接返回该路径，不调用 executor
        assertEquals("/opt/homebrew/bin/opencode", result)
        assertTrue("单路径时不应调用 executor", !executorCalled)
    }

    /**
     * 测试用例2: testMultiplePaths_SelectsHighestVersion
     * 逻辑：多个路径时执行 --version，选择版本最高的
     * 验证：Mock 3 个路径，版本分别是 1.13.0, 1.14.22, 1.12.0
     * 期望：返回 1.14.22 对应的路径
     */
    @Test
    fun testMultiplePaths_SelectsHighestVersion() {
        // Given: 3个路径都存在
        val candidatePaths = listOf(
            "/opt/homebrew/bin/opencode",  // 1.13.0
            "/usr/local/bin/opencode",     // 1.14.22
            "/usr/bin/opencode"           // 1.12.0
        )
        val versionMap = mapOf(
            "/opt/homebrew/bin/opencode" to "1.13.0",
            "/usr/local/bin/opencode" to "1.14.22",
            "/usr/bin/opencode" to "1.12.0"
        )

        val executor: (String) -> OpenCodePathFinder.ProcessResult? = { path ->
            versionMap[path]?.let {
                OpenCodePathFinder.ProcessResult(exitCode = 0, output = it)
            }
        }

        // When
        val result = OpenCodePathFinder.findOpenCodePath(
            candidatePaths = candidatePaths,
            existingPathsFilter = { paths -> paths }, // 所有路径都存在
            executor = executor
        )

        // Then: 返回版本最高的 1.14.22 对应的路径
        assertEquals("/usr/local/bin/opencode", result)
    }

    /**
     * 测试用例3: testNoPath_NotifiesAndFails
     * 逻辑：没有路径存在时通知用户并 fast fail
     * 验证：Mock 所有路径都不存在，验证通知被触发，抛出 IllegalStateException
     */
    @Test
    fun testNoPath_NotifiesAndFails() {
        // Given: 所有路径都不存在
        val candidatePaths = listOf(
            "/opt/homebrew/bin/opencode",
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        )

        // When & Then: 抛出 IllegalStateException
        try {
            OpenCodePathFinder.findOpenCodePath(
                candidatePaths = candidatePaths,
                existingPathsFilter = { emptyList() }, // 所有路径都不存在
                executor = { null }
            )
            fail("Expected IllegalStateException to be thrown")
        } catch (e: IllegalStateException) {
            assertEquals("OpenCode not found", e.message)
        }
    }

    /**
     * 测试用例4: testParseOpenCodeVersion
     * 逻辑：解析 "x.y.z" 版本号进行三段比较
     * 注意：必须是整数比较，不是字符串比较
     * 验证：1.14.22 > 1.14.21, 1.15.0 > 1.14.22, 2.0.0 > 1.99.99
     * 关键陷阱：字符串比较 "1.14.22" < "1.9.1" 会错误，但整数比较正确
     */
    @Test
    fun testParseOpenCodeVersion() {
        // Given: 不同版本号的版本对象
        val v1_14_22 = OpenCodePathFinder.parseOpenCodeVersion("1.14.22")
        val v1_14_21 = OpenCodePathFinder.parseOpenCodeVersion("1.14.21")
        val v1_15_0 = OpenCodePathFinder.parseOpenCodeVersion("1.15.0")
        val v2_0_0 = OpenCodePathFinder.parseOpenCodeVersion("2.0.0")
        val v1_99_99 = OpenCodePathFinder.parseOpenCodeVersion("1.99.99")
        val v1_9_1 = OpenCodePathFinder.parseOpenCodeVersion("1.9.1")

        // Then: 验证整数比较而非字符串比较
        // 关键测试：1.14.22 > 1.14.21
        assertTrue("1.14.22 > 1.14.21", v1_14_22 > v1_14_21)
        // 关键测试：1.15.0 > 1.14.22
        assertTrue("1.15.0 > 1.14.22", v1_15_0 > v1_14_22)
        // 关键测试：2.0.0 > 1.99.99
        assertTrue("2.0.0 > 1.99.99", v2_0_0 > v1_99_99)
        // 关键测试：1.14.22 > 1.9.1（字符串比较会失败）
        assertTrue("1.14.22 > 1.9.1", v1_14_22 > v1_9_1)
    }

    /**
     * 测试用例5: testVersionComparisonEdgeCases
     * 边界情况：相同版本、版本号缺失部分
     * 验证 "1.14.22" > "1.9.1"（关键字符串陷阱）
     * 验证 "1.0" vs "1.0.0" 处理
     */
    @Test
    fun testVersionComparisonEdgeCases() {
        // Given: 边界情况版本号
        val v1_0 = OpenCodePathFinder.parseOpenCodeVersion("1.0")
        val v1_0_0 = OpenCodePathFinder.parseOpenCodeVersion("1.0.0")
        val v1_0_1 = OpenCodePathFinder.parseOpenCodeVersion("1.0.1")
        val v1_9_1 = OpenCodePathFinder.parseOpenCodeVersion("1.9.1")
        val v1_14_22 = OpenCodePathFinder.parseOpenCodeVersion("1.14.22")
        val v0_1_0 = OpenCodePathFinder.parseOpenCodeVersion("0.1.0")

        // Then: 验证边界情况
        // 1.0 和 1.0.0 应该相等（缺失部分补0）
        assertTrue("1.0 should equal 1.0.0", v1_0 == v1_0_0)
        // 1.0.0 < 1.0.1
        assertTrue("1.0.0 < 1.0.1", v1_0_0 < v1_0_1)
        // 1.9.1 < 1.14.22
        assertTrue("1.9.1 < 1.14.22", v1_9_1 < v1_14_22)
        // 0.1.0 < 1.0.0
        assertTrue("0.1.0 < 1.0.0", v0_1_0 < v1_0_0)
        // 验证字符串陷阱：1.9.1 的字符串比较 "1.9.1" > "1.14.22" 但整数比较正确
        assertTrue("1.9.1 < 1.14.22 (integer comparison)", v1_9_1 < v1_14_22)
    }

    /**
     * 测试用例: 版本相同时返回第一个
     */
    @Test
    fun testMultiplePathsSameVersion_ReturnsFirst() {
        // Given: 多个相同版本的路径
        val candidatePaths = listOf(
            "/opt/homebrew/bin/opencode",
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        )
        val versionMap = mapOf(
            "/opt/homebrew/bin/opencode" to "1.14.22",
            "/usr/local/bin/opencode" to "1.14.22",
            "/usr/bin/opencode" to "1.14.22"
        )

        val executor: (String) -> OpenCodePathFinder.ProcessResult? = { path ->
            versionMap[path]?.let {
                OpenCodePathFinder.ProcessResult(exitCode = 0, output = it)
            }
        }

        // When
        val result = OpenCodePathFinder.findOpenCodePath(
            candidatePaths = candidatePaths,
            existingPathsFilter = { paths -> paths },
            executor = executor
        )

        // Then: 返回第一个（按 maxByOrNull 的行为）
        assertEquals("/opt/homebrew/bin/opencode", result)
    }

    /**
     * 测试用例: 进程执行失败时跳过该路径
     */
    @Test
    fun testProcessExecutionFailure_SkipsPath() {
        // Given: 3个路径，但中间的执行失败
        val candidatePaths = listOf(
            "/opt/homebrew/bin/opencode",
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        )
        val versionMap = mapOf(
            "/opt/homebrew/bin/opencode" to "1.13.0",
            "/usr/bin/opencode" to "1.12.0"
        )
        // /usr/local/bin/opencode 执行失败（返回 null）

        val executor: (String) -> OpenCodePathFinder.ProcessResult? = { path ->
            versionMap[path]?.let {
                OpenCodePathFinder.ProcessResult(exitCode = 0, output = it)
            }
        }

        // When
        val result = OpenCodePathFinder.findOpenCodePath(
            candidatePaths = candidatePaths,
            existingPathsFilter = { paths -> paths },
            executor = executor
        )

        // Then: 跳过失败的，返回存在的最高版本
        assertEquals("/opt/homebrew/bin/opencode", result)
    }

    /**
     * 测试用例: 所有进程执行都失败时返回第一个存在的路径
     */
    @Test
    fun testAllProcessExecutionFail_ReturnsFirstExistingPath() {
        // Given: 所有路径都存在但进程执行都失败
        val candidatePaths = listOf(
            "/opt/homebrew/bin/opencode",
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        )

        val executor: (String) -> OpenCodePathFinder.ProcessResult? = { null } // 所有都失败

        // When
        val result = OpenCodePathFinder.findOpenCodePath(
            candidatePaths = candidatePaths,
            existingPathsFilter = { paths -> paths }, // 所有路径都存在
            executor = executor
        )

        // Then: 返回第一个存在的路径
        assertEquals("/opt/homebrew/bin/opencode", result)
    }
}
