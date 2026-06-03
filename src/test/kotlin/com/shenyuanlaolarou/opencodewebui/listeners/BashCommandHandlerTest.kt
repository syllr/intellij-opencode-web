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
            "payload" to mapOf(
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
        )
    }

    @Test
    fun readOnly_singleCommand() {
        val event = buildBashEvent("ls -la")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun readOnly_chainedWithDoubleAmpersand() {
        val event = buildBashEvent("ls && cat file.txt")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun readOnly_chainedWithPipe() {
        val event = buildBashEvent("ls | grep foo")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun readOnly_chainedWithSemicolon() {
        val event = buildBashEvent("ls; pwd; date")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun writeCommand_detected() {
        val event = buildBashEvent("touch file.txt")
        assertTrue(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun writeCommand_mixedChain() {
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
    fun emptyCommand_skipped() {
        val event = buildBashEvent("")
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun exitCodeNonZero_skipped() {
        val event = buildBashEvent("rm file.txt", exitCode = 1)
        assertFalse(BashCommandHandler.handleBashEvent(event, "/tmp"))
    }

    @Test
    fun nullProjectDir_skipped() {
        val event = buildBashEvent("echo hello > file.txt")
        assertFalse(BashCommandHandler.handleBashEvent(event, null))
    }
}
