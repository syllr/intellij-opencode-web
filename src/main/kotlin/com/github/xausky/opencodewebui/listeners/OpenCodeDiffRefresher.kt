package com.github.xausky.opencodewebui.listeners

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object OpenCodeDiffRefresher {
    private val logger = thisLogger()

    fun refreshFiles(directory: String, files: List<DiffFile>) {
        logger.debug("[DiffRefresher] refreshFiles called with directory='$directory', ${files.size} files")

        try {
            val virtualFiles = files.mapNotNull { diffFile ->
                val absolutePath = "$directory/${diffFile.file}"
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
                if (vf == null) {
                    logger.warn("[DiffRefresher] File not found: $absolutePath")
                }
                vf
            }

            if (virtualFiles.isNotEmpty()) {
                val pathList = virtualFiles.joinToString(", ") { it.path }
                logger.debug("[DiffRefresher] Refreshing ${virtualFiles.size} files via refreshIoFiles: $pathList")
                val ioFiles = virtualFiles.map { File(it.path) }
                LocalFileSystem.getInstance().refreshIoFiles(
                    ioFiles,
                    /* async */ false,
                    /* recursive */ false,
                    null
                )
                logger.debug("[DiffRefresher] refreshIoFiles completed for: $pathList")
            } else {
                logger.warn("[DiffRefresher] NO virtual files found from ${files.size} paths - refresh skipped")
            }
        } catch (e: Exception) {
            logger.error("[DiffRefresher] Refresh failed: ${e.message}", e)
        }
    }
}
