package com.nocrunch.rider.files

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeManager

/**
 * Adds `.nocrunch` to the IDE's ignored files and folders, so the mirror is never indexed, searched
 * or reported as VFS changes (which would otherwise feed mirror churn back into the engine).
 */
object MirrorFolderExcluder {
    private const val PATTERN = ".nocrunch"
    private val LOG = logger<MirrorFolderExcluder>()

    fun ensureIgnored() {
        val manager = FileTypeManager.getInstance()
        val current = manager.ignoredFilesList
        if (current.split(';').any { it == PATTERN }) return
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                manager.ignoredFilesList = if (current.isEmpty()) PATTERN else "$current;$PATTERN"
            }
            LOG.info("Added '$PATTERN' to ignored files and folders")
        }
    }
}
