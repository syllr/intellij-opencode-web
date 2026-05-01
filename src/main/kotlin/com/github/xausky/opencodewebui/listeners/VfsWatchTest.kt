package com.github.xausky.opencodewebui.listeners

import com.github.xausky.opencodewebui.OPENCODE_HOST
import com.github.xausky.opencodewebui.OPENCODE_PORT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager

object VfsWatchTest {

    private val logger = thisLogger()
    private var registered = false

    fun start() {
        if (registered) {
            logger.warn("[VfsWatchTest] Already registered, skipping")
            return
        }

        val listener = object : VirtualFileListener {
            override fun beforePropertyChange(event: com.intellij.openapi.vfs.VirtualFilePropertyEvent) {
                logger.info("[VfsWatchTest] beforePropertyChange: file=${event.file.path}, property=${event.propertyName}, old=${event.oldValue}, new=${event.newValue}")
            }

            override fun propertyChanged(event: com.intellij.openapi.vfs.VirtualFilePropertyEvent) {
                logger.info("[VfsWatchTest] propertyChanged: file=${event.file.path}, property=${event.propertyName}, old=${event.oldValue}, new=${event.newValue}")
            }

            override fun beforeContentsChange(event: VirtualFileEvent) {
                logger.info("[VfsWatchTest] beforeContentsChange: file=${event.file.path}")
            }

            override fun contentsChanged(event: VirtualFileEvent) {
                logger.info("[VfsWatchTest] *** contentsChanged: file=${event.file.path}")
            }

            override fun beforeFileMovement(event: com.intellij.openapi.vfs.VirtualFileMoveEvent) {
                logger.info("[VfsWatchTest] beforeFileMovement: file=${event.file.path}, oldParent=${event.oldParent.path}, newParent=${event.newParent.path}")
            }

            override fun fileMoved(event: com.intellij.openapi.vfs.VirtualFileMoveEvent) {
                logger.info("[VfsWatchTest] fileMoved: file=${event.file.path}, oldParent=${event.oldParent.path}, newParent=${event.newParent.path}")
            }

            override fun beforeFileDeletion(event: VirtualFileEvent) {
                logger.info("[VfsWatchTest] beforeFileDeletion: file=${event.file.path}, parent=${event.parent?.path}")
            }

            override fun fileDeleted(event: VirtualFileEvent) {
                logger.info("[VfsWatchTest] *** fileDeleted: file=${event.file.path}")
            }

            override fun fileCreated(event: VirtualFileEvent) {
                logger.info("[VfsWatchTest] *** fileCreated: file=${event.file.path}, parent=${event.parent?.path}")
            }

            override fun fileCopied(event: com.intellij.openapi.vfs.VirtualFileCopyEvent) {
                logger.info("[VfsWatchTest] fileCopied: file=${event.file.path}")
            }
        }

        VirtualFileManager.getInstance().addVirtualFileListener(listener)
        registered = true
        logger.info("[VfsWatchTest] VirtualFileListener registered. All VFS events will be logged.")
        logger.info("[VfsWatchTest] OpenCode SSE URL: http://$OPENCODE_HOST:$OPENCODE_PORT/global/event")
    }
}