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
import kotlinx.coroutines.flow.combine
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
            combine(service.state, service.tests, service.builds) { s, t, b -> Triple(s, t, b) }.collect { (s, t, b) ->
                val failed = t.values.count { it.state == "failed" }
                val brokenBuilds = b.values.count { it.status == "failed" }
                text = when {
                    s.state == "error" -> "No Crunch: error"
                    s.state == "paused" -> "No Crunch: paused"
                    s.state == "building" || s.state == "syncing" -> "No Crunch: building…"
                    s.state == "testing" -> "No Crunch: testing…"
                    brokenBuilds > 0 -> "No Crunch: ✖ build"
                    failed > 0 -> "No Crunch: ✖ $failed"
                    s.state == "starting" || s.state == "stopped" -> "No Crunch"
                    else -> "No Crunch: ✔"
                }
                statusBar.updateWidget(WIDGET_ID)
            }
        }
    }

    override fun dispose() {
        job?.cancel()
    }
}
