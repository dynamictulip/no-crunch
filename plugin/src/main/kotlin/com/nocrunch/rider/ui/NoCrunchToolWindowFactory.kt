package com.nocrunch.rider.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.flow.distinctUntilChanged
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class NoCrunchToolWindowFactory : ToolWindowFactory, DumbAware {
    /** Runs when the tool window is registered (even if never opened), so the sidebar icon always reflects results. */
    override suspend fun manage(toolWindow: ToolWindow, toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
        val service = com.nocrunch.rider.NoCrunchService.getInstance(toolWindow.project)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.EDT) {
            kotlinx.coroutines.flow.combine(service.tests, service.builds, service.runs, service.exclusions, service.state) { t, b, r, e, s ->
                CupcakeIcon.variantFor(ProgressBarModel.forModel(TestTreeModel.buildAll(t.values, b, e, r), b, s).colour, paused = s.state == "paused")
            }.distinctUntilChanged().collect { toolWindow.setIcon(it.icon) }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(null, "", false)
        content.component = NoCrunchPanel(project, content)
        toolWindow.contentManager.addContent(content)
    }
}
