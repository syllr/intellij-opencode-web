package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * [OpenCodeCliPathConfig] 的路径校验 + JSON 配置读写测试。
 * 全部使用 [TemporaryFolder] 注入临时目录，不触碰用户真实 `~/.config/opencode-web-ui`。
 */
class OpenCodeCliPathConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- validate: 格式校验 ----------

    @Test
    fun validateAcceptsEmptyAsClearConfig() {
        assertNull(OpenCodeCliPathConfig.validate(""))
        assertNull(OpenCodeCliPathConfig.validate("   "))
    }

    @Test
    fun validateRejectsRelativePath() {
        assertNotNull(OpenCodeCliPathConfig.validate("hello"))
        assertNotNull(OpenCodeCliPathConfig.validate("usr/local/bin/opencode"))
        assertNotNull(OpenCodeCliPathConfig.validate("../bin/opencode"))
    }

    @Test
    fun validateRejectsControlCharacters() {
        assertNotNull(OpenCodeCliPathConfig.validate("/usr/bin/opencode\u0000evil"))
        assertNotNull(OpenCodeCliPathConfig.validate("/usr/bin/opencode\n"))
    }

    @Test
    fun validateRejectsNonExistentFile() {
        assertNotNull(OpenCodeCliPathConfig.validate("/usr/local/bin/definitely-not-exists-opencode"))
    }

    // ---------- validate: 存在性 / 可执行性 ----------

    @Test
    fun validateRejectsNonExecutableFile() {
        val file = tmp.newFile("opencode-non-exec")
        assertNotNull(OpenCodeCliPathConfig.validate(file.absolutePath))
    }

    @Test
    fun validateAcceptsExecutableFile() {
        val file = tmp.newFile("opencode-exec")
        file.setExecutable(true)
        assertNull(OpenCodeCliPathConfig.validate(file.absolutePath))
    }

    // ---------- JSON 读写 ----------

    @Test
    fun getReturnsEmptyWhenNotConfigured() {
        assertEquals("", OpenCodeCliPathConfig.get(tmp.root))
    }

    @Test
    fun getReturnsEmptyOnCorruptJson() {
        File(tmp.root, "config.json").writeText("{invalid json", StandardCharsets.UTF_8)
        assertEquals("", OpenCodeCliPathConfig.get(tmp.root))
    }

    @Test
    fun setThenGetRoundTrips() {
        val path = tmp.newFile("opencode").apply { setExecutable(true) }.absolutePath
        OpenCodeCliPathConfig.set(path, tmp.root)
        assertEquals(path, OpenCodeCliPathConfig.get(tmp.root))
    }

    @Test
    fun setWritesCliPathKeyIntoConfigJson() {
        val path = tmp.newFile("opencode").apply { setExecutable(true) }.absolutePath
        OpenCodeCliPathConfig.set(path, tmp.root)
        val root = Gson().fromJson(
            File(tmp.root, "config.json").readText(StandardCharsets.UTF_8),
            JsonObject::class.java
        )
        assertEquals(path, root.get("cliPath").asString)
    }

    @Test
    fun setPreservesOtherConfigKeys() {
        // 预置一个含其他配置项的 config.json
        val existing = JsonObject().apply { addProperty("otherKey", "keep-me") }
        val configFile = File(tmp.root, "config.json")
        configFile.writeText(Gson().toJson(existing), StandardCharsets.UTF_8)

        val path = tmp.newFile("opencode").apply { setExecutable(true) }.absolutePath
        OpenCodeCliPathConfig.set(path, tmp.root)

        val root = Gson().fromJson(configFile.readText(StandardCharsets.UTF_8), JsonObject::class.java)
        assertEquals("keep-me", root.get("otherKey").asString)
        assertEquals(path, root.get("cliPath").asString)
    }

    @Test
    fun setCreatesMissingDir() {
        val nested = File(tmp.root, "a/b/c")
        OpenCodeCliPathConfig.set("/usr/local/bin/opencode", nested)
        assertEquals("/usr/local/bin/opencode", OpenCodeCliPathConfig.get(nested))
    }

    @Test
    fun setBlankRemovesCliPathAndDeletesEmptyConfig() {
        val path = tmp.newFile("opencode").apply { setExecutable(true) }.absolutePath
        OpenCodeCliPathConfig.set(path, tmp.root)
        OpenCodeCliPathConfig.set("   ", tmp.root)
        assertEquals("", OpenCodeCliPathConfig.get(tmp.root))
        // 唯一配置项被移除 → config.json 应被删除
        assertEquals(false, File(tmp.root, "config.json").exists())
    }
}
