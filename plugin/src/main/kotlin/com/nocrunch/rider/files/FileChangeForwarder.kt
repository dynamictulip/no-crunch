package com.nocrunch.rider.files

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.nocrunch.rider.NoCrunchService

/**
 * Forwards every file change under the repo root (saves, creates, deletes, moves, renames, external changes
 * picked up by VFS refresh such as branch switches) to the engine.
 */
class FileChangeForwarder(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val service = project.getServiceIfCreated(NoCrunchService::class.java) ?: return
        val paths = LinkedHashSet<String>()
        for (event in events) {
            when (event) {
                is VFileMoveEvent -> { paths += event.oldPath; paths += event.newPath }
                is VFilePropertyChangeEvent ->
                    if (event.propertyName == VirtualFile.PROP_NAME) { paths += event.oldPath; paths += event.newPath } else paths += event.path
                is VFileCopyEvent -> paths += event.newParent.path + "/" + event.newChildName
                is VFileCreateEvent -> paths += event.path
                else -> paths += event.path
            }
        }
        service.filesChanged(paths)
    }
}
