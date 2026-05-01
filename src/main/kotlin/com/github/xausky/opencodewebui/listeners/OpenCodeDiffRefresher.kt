package com.github.xausky.opencodewebui.listeners

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue

object OpenCodeDiffRefresher {
    private val logger = thisLogger()

    fun refreshFiles(directory: String, files: List<DiffFile>) {
        logger.info("[DiffRefresher] refreshFiles called with directory='$directory', ${files.size} files")

        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFiles = files.mapNotNull { diffFile ->
                    val absolutePath = "$directory/${diffFile.file}"
                    logger.info("[DiffRefresher] Looking up file: $absolutePath")
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
                    if (vf != null) {
                        logger.info("[DiffRefresher]   -> FOUND: ${vf.path}")
                    } else {
                        logger.warn("[DiffRefresher]   -> NOT FOUND: $absolutePath")
                    }
                    vf
                }

                if (virtualFiles.isNotEmpty()) {
                    val pathList = virtualFiles.joinToString(", ") { it.path }
                    logger.info("[DiffRefresher] Refreshing ${virtualFiles.size} files via RefreshQueue: $pathList")
                    RefreshQueue.getInstance().refresh(
                        /* async */ true,
                        /* recursive */ false,
                        /* finishRunnable */ null,
                        /* files */ *virtualFiles.toTypedArray<VirtualFile>()
                    )
                    logger.info("[DiffRefresher] RefreshQueue.refresh() called successfully")
                } else {
                    logger.warn("[DiffRefresher] NO virtual files found from ${files.size} paths - refresh skipped")
                }
            } catch (e: Exception) {
                logger.error("[DiffRefresher] Refresh failed: ${e.message}", e)
            }
        }
    }
}
