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

    /**
     * 刷新整个项目根目录（递归）。
     * 有 NativeFileWatcher 时只检查有变动的文件，不会遍历所有文件。
     */
    fun refreshProjectRoot(projectPath: String) {
        logger.info("[DiffRefresher] refreshProjectRoot called with projectPath='$projectPath'")

        ApplicationManager.getApplication().invokeLater {
            try {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(projectPath)
                if (vf != null) {
                    logger.info("[DiffRefresher] Found project root: ${vf.path}, refreshing recursively")
                    RefreshQueue.getInstance().refresh(
                        /* async */ true,
                        /* recursive */ true,
                        /* finishRunnable */ null,
                        vf
                    )
                    logger.info("[DiffRefresher] Project root refresh completed")
                } else {
                    logger.warn("[DiffRefresher] Project root not found: $projectPath")
                }
            } catch (e: Exception) {
                logger.error("[DiffRefresher] Project root refresh failed: ${e.message}", e)
            }
        }
    }
}
