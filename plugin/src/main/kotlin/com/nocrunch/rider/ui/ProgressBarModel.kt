package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState

enum class BarColour { PASSING, FAILING, RUNNING, NEUTRAL }

data class BarState(val colour: BarColour, val fraction: Double, val text: String)

/** NCrunch-style status bar: red if anything failed, blue while the engine is working, otherwise green. */
object ProgressBarModel {
    private val BUSY = setOf("starting", "syncing", "building", "testing")

    /** [projects] is the model built without the "failures only" / "hide excluded" view filters: the bar counts every test. */
    fun forModel(
        projects: List<ProjectNode>,
        builds: Map<String, com.nocrunch.rider.engine.BuildFinishedDto>,
        state: EngineUiState,
    ): BarState = from(totals(projects), state, brokenBuilds(builds))

    /** Counts of every test in an unfiltered model, the same way the tree counts them. */
    fun totals(projects: List<ProjectNode>): Counts = projects.fold(Counts()) { acc, p -> acc + p.counts }

    fun brokenBuilds(builds: Map<String, com.nocrunch.rider.engine.BuildFinishedDto>): Int = builds.values.count { it.status == "failed" }

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
