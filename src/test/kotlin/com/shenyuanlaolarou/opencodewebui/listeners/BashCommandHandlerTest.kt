package com.shenyuanlaolarou.opencodewebui.listeners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BashCommandHandlerTest {

    private fun buildBashEvent(
        command: String,
        exitCode: Int = 0,
        partType: String = "tool",
        toolName: String = "bash",
        status: String = "completed"
    ): Map<String, Any?> {
        return mapOf(
            "properties" to mapOf(
                "part" to mapOf(
                    "type" to partType,
                    "tool" to toolName,
                    "state" to mapOf(
                        "status" to status,
                        "input" to mapOf("command" to command),
                        "metadata" to mapOf("exit" to exitCode.toDouble())
                    )
                )
            )
        )
    }

    @Test
    fun bashCompleted_readOnlyCommand_triggersRefresh() {
        val event = buildBashEvent("ls -la")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_chainedReadonlyCommands_triggersRefresh() {
        val event = buildBashEvent("ls && cat file.txt")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_pipedReadonlyCommands_triggersRefresh() {
        val event = buildBashEvent("ls | grep foo")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_semicolonReadonlyCommands_triggersRefresh() {
        val event = buildBashEvent("ls; pwd; date")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_writeCommand_triggersRefresh() {
        val event = buildBashEvent("touch file.txt")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_mixedChain_triggersRefresh() {
        val event = buildBashEvent("ls && rm file.txt")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun notBashEvent_skipped() {
        val event = buildBashEvent("ls", partType = "text")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun notBashEvent_wrongTool() {
        val event = buildBashEvent("ls", toolName = "grep")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun notBashEvent_wrongStatus() {
        val event = buildBashEvent("ls", status = "running")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_emptyCommand_triggersRefresh() {
        val event = buildBashEvent("")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_exitCodeNonZero_triggersRefresh() {
        val event = buildBashEvent("rm file.txt", exitCode = 1)
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun bashCompleted_nullProjectDir_triggersRefresh() {
        val event = buildBashEvent("echo hello > file.txt")
        assertTrue(BashCommandHandler.handleBashEvent(event, null))
    }

    @Test
    fun bashCompleted_missingCommand_triggersRefresh() {
        val event = mapOf(
            "properties" to mapOf(
                "part" to mapOf(
                    "type" to "tool",
                    "tool" to "bash",
                    "state" to mapOf(
                        "status" to "completed"
                    )
                )
            )
        )
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }
}
