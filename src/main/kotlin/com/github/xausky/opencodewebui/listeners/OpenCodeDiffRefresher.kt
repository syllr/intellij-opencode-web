package com.github.xausky.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue

object OpenCodeDiffRefresher {
    private val logger = thisLogger()

    fun refreshFiles(directory: String, files: List<DiffFile>) {
        logger.info("[DiffRefresher] refreshFiles called with directory='$directory', ${files.size} files")

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

    /**
     * 强制重新读取项目根目录的磁盘内容（递归）。
     * 使用 refreshIoFiles 直接读取磁盘，不依赖 NativeFileWatcher 的变更列表，
     * 这样能正确捕获文件删除（watcher 可能延迟报告删除事件）。
     */
    fun refreshProjectRoot(projectPath: String) {
        logger.info("[DiffRefresher] refreshProjectRoot called with projectPath='$projectPath'")

        try {
            val projectDir = java.io.File(projectPath)
            if (projectDir.exists()) {
                logger.info("[DiffRefresher] Refreshing directory via refreshIoFiles: $projectPath")
                LocalFileSystem.getInstance().refreshIoFiles(
                    listOf(projectDir),
                    /* async */ true,
                    /* recursive */ true,
                    null
                )
                logger.info("[DiffRefresher] refreshIoFiles completed for: $projectPath")
            } else {
                logger.warn("[DiffRefresher] Project root does not exist: $projectPath")
            }
        } catch (e: Exception) {
            logger.error("[DiffRefresher] Project root refresh failed: ${e.message}", e)
        }
    }
}
