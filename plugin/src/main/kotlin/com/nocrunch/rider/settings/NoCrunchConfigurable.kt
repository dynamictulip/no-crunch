package com.nocrunch.rider.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.nocrunch.rider.NoCrunchService

class NoCrunchConfigurable(private val project: Project) : BoundConfigurable("No Crunch") {
    override fun createPanel(): DialogPanel {
        val state = NoCrunchSettings.getInstance(project).state
        return panel {
            row("Delay after last change (ms):") { intTextField(0..10_000).bindIntText({ state.debounceMs }, { state.debounceMs = it }) }
            row("Max test projects in parallel (0 = auto):") { intTextField(0..64).bindIntText({ state.maxParallel }, { state.maxParallel = it }) }
            row("dotnet executable (blank = auto):") {
                textField().bindText({ state.dotnetPath.orEmpty() }, { state.dotnetPath = it })
                    .comment("Changes apply after the engine restarts.")
            }
        }
    }

    override fun apply() {
        super.apply()
        NoCrunchService.getInstance(project).restart()
    }
}
