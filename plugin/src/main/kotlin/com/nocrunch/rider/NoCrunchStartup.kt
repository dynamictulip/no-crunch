package com.nocrunch.rider

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.nocrunch.rider.ui.EditorMarkerPainter

class NoCrunchStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        NoCrunchService.getInstance(project).start()
        EditorMarkerPainter.getInstance(project)
    }
}
