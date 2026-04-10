package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.sql.DriverManager

object SessionHelper {
    fun getLatestSessionId(projectPath: String): String? {
        val homeDir = System.getenv("HOME") ?: return null
        val dbPath = File(homeDir, ".local/share/opencode/opencode.db")
        
        if (!dbPath.exists()) {
            thisLogger().info("OpenCode database not found at ${dbPath.absolutePath}")
            return null
        }
        
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.absolutePath}").use { conn ->
                val sql = """
                    SELECT id FROM session 
                    WHERE directory = ? 
                    ORDER BY time_created DESC 
                    LIMIT 1
                """.trimIndent()
                
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, projectPath)
                    val rs = stmt.executeQuery()
                    
                    if (rs.next()) {
                        val sessionId = rs.getString("id")
                        thisLogger().info("Found session for project $projectPath: $sessionId")
                        return sessionId
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to query OpenCode session: ${e.message}")
        }
        
        return null
    }
}