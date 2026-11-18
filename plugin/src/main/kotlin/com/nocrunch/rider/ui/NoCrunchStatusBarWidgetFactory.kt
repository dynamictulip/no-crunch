package com.nocrunch.rider.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.nocrunch.rider.NoCrunchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.event.MouseEvent

private const val WIDGET_ID = "NoCrunchStatus"

class NoCrunchStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "No Crunch"
    override fun createWidget(project: Project): StatusBarWidget = NoCrunchStatusWidget(project)
}

private class NoCrunchStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var text = "No Crunch"
    private var job: Job? = null

    override fun ID() = WIDGET_ID
    override fun getPresentation() = this
    override fun getText() = text
    override fun getAlignment() = Component.CENTER_ALIGNMENT
    override fun getTooltipText() = "No Crunch continuous testing — click to open"
    override fun getClickConsumer() = Consumer<MouseEvent> {
        ToolWindowManager.getInstance(project).getToolWindow("No Crunch")?.activate(null)
    }

    override fun install(statusBar: StatusBar) {
        val service = NoCrunchService.getInstance(project)
        job = service.scope.launch(Dispatchers.EDT) {
            // Same model and counts as the tree and progress bar, so all three agree.
            NoCrunchResults.getInstance(project).snapshots
                .map { StatusText.widget(it.state, it.counts, it.brokenBuilds) }
                .distinctUntilChanged()
                .collect {
                    text = it
                    statusBar.updateWidget(WIDGET_ID)
                }
        }
    }

    override fun dispose() {
        job?.cancel()
    }
}
