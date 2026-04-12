package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.sql.DriverManager

object SessionHelper {
    fun getLatestSessionId(projectPath: String): String? {
        println("=== SessionHelper.getLatestSessionId called with: $projectPath")
        val homeDir = System.getenv("HOME") ?: run {
            println("HOME env not set")
            return null
        }
        val dbPath = File(homeDir, ".local/share/opencode/opencode.db")
        
        if (!dbPath.exists()) {
            println("OpenCode database not found at ${dbPath.absolutePath}")
            return null
        }
        
        try {
            Class.forName("org.sqlite.JDBC")
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
                        println("Found session for project $projectPath: $sessionId")
                        return sessionId
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to query OpenCode session: ${e.message}")
        }
        
        println("No session found for project: $projectPath")
        return null
    }
}