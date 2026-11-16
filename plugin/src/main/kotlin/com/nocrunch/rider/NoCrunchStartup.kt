package com.nocrunch.rider

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class NoCrunchStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        NoCrunchService.getInstance(project).start()
    }
}
