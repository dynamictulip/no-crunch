package com.nocrunch.rider.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.ui.CoverageNotice

class NoCrunchConfigurable(private val project: Project) : BoundConfigurable("No Crunch") {
    override fun createPanel(): DialogPanel {
        val state = NoCrunchSettings.getInstance(project).state
        return panel {
            row("Delay after last change (ms):") { intTextField(0..10_000).bindIntText({ state.debounceMs }, { state.debounceMs = it }) }
            row("Max test projects in parallel (0 = auto):") { intTextField(0..64).bindIntText({ state.maxParallel }, { state.maxParallel = it }) }
            row("Test timeout (seconds, 0 = none):") {
                intTextField(0..86_400).bindIntText({ state.testTimeoutSeconds }, { state.testTimeoutSeconds = it })
                    .comment("A test run that reports no result for this long is stopped and its unfinished tests fail.")
            }
            row {
                checkBox("Collect line coverage").bindSelected({ state.enableCoverage }, { state.enableCoverage = it })
                    .comment("Shows which lines tests cover, as dots in the editor. Needs the dotnet-coverage tool; changes apply after the engine restarts.")
            }
            CoverageNotice.text(NoCrunchService.getInstance(project).coverageStatus.value)?.let { notice -> row { comment(notice) } }
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
