package com.github.xausky.opencodewebui.listeners

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue

object OpenCodeDiffRefresher {
    private val logger = thisLogger()

    fun refreshFiles(directory: String, files: List<DiffFile>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFiles = files.mapNotNull { diffFile ->
                    val absolutePath = "$directory/${diffFile.file}"
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
                }

                if (virtualFiles.isNotEmpty()) {
                    RefreshQueue.getInstance().refresh(
                        /* async */ true,
                        /* recursive */ false,
                        /* finishRunnable */ null,
                        /* files */ *virtualFiles.toTypedArray<VirtualFile>()
                    )
                    logger.info("[DiffRefresher] Refreshed ${virtualFiles.size} files: ${files.map { it.file }}")
                } else {
                    logger.warn("[DiffRefresher] No virtual files found for ${files.size} paths")
                }
            } catch (e: Exception) {
                logger.error("[DiffRefresher] Refresh failed: ${e.message}", e)
            }
        }
    }
}
