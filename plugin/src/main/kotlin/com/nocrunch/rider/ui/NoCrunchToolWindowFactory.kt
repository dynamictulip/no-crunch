package com.nocrunch.rider.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class NoCrunchToolWindowFactory : ToolWindowFactory, DumbAware {
    /** Runs when the tool window is registered (even if never opened), so the sidebar icon always reflects results. */
    override suspend fun manage(toolWindow: ToolWindow, toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
        // The shared model is built off the EDT; only picking the icon happens here.
        NoCrunchResults.getInstance(toolWindow.project).snapshots
            .map { CupcakeIcon.variantFor(it.bar.colour, paused = it.state.state == com.nocrunch.rider.engine.EngineState.PAUSED) }
            .distinctUntilChanged()
            .collectLatest { variant ->
                // An animated variant cycles its frames until the next variant arrives and cancels this block.
                do {
                    for (icon in variant.frames) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.EDT) { toolWindow.setIcon(icon) }
                        if (variant.isAnimated) kotlinx.coroutines.delay(CupcakeIcon.FRAME_MILLIS)
                    }
                } while (variant.isAnimated)
            }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(null, "", false)
        content.component = NoCrunchPanel(project, content)
        toolWindow.contentManager.addContent(content)
    }
}
