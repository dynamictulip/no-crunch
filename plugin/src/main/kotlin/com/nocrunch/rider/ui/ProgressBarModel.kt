package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState

enum class BarColour { PASSING, FAILING, RUNNING, NEUTRAL }

data class BarState(val colour: BarColour, val fraction: Double, val text: String)

/** NCrunch-style status bar: red if anything failed, blue while the engine is working, otherwise green. */
object ProgressBarModel {
    private val BUSY = setOf("starting", "syncing", "building", "testing")

    /** Based on every test, regardless of the "failures only" / "hide excluded" view filters. */
    fun forService(
        tests: Map<String, com.nocrunch.rider.engine.TestResultDto>,
        builds: Map<String, com.nocrunch.rider.engine.BuildFinishedDto>,
        runs: Map<String, com.nocrunch.rider.engine.RunFinishedDto>,
        rules: List<com.nocrunch.rider.engine.ExclusionRuleDto>,
        state: EngineUiState,
    ): BarState {
        val counts = TestTreeModel.build(tests.values, builds, rules, failuresOnly = false, runs = runs)
            .fold(Counts()) { acc, p -> acc + p.counts }
        return from(counts, state, builds.values.count { it.status == "failed" })
    }

    fun from(counts: Counts, state: EngineUiState, brokenBuilds: Int): BarState {
        val done = counts.passed + counts.failed
        val total = done + counts.pending
        val fraction = if (total == 0) 0.0 else done.toDouble() / total
        val colour = when {
            state.state == "error" || brokenBuilds > 0 || counts.failed > 0 -> BarColour.FAILING
            total == 0 -> BarColour.NEUTRAL
            // Blue only while the engine is working; idle with no failures is green.
            state.state in BUSY -> BarColour.RUNNING
            else -> BarColour.PASSING
        }
        val builds = if (brokenBuilds > 0) "   · $brokenBuilds build${if (brokenBuilds == 1) "" else "s"} failed" else ""
        return BarState(colour, fraction, "${StatusText.describe(state)}   ${counts.label()}$builds")
    }
}
