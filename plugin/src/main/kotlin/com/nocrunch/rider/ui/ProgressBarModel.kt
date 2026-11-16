package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState

enum class BarColour { PASSING, FAILING, RUNNING, NEUTRAL }

data class BarState(val colour: BarColour, val fraction: Double, val text: String)

/** NCrunch-style status bar: red if anything failed, blue while work remains, green when all current results pass. */
object ProgressBarModel {
    private val BUSY = setOf("starting", "syncing", "building", "testing")

    fun from(counts: Counts, state: EngineUiState, brokenBuilds: Int): BarState {
        val done = counts.passed + counts.failed
        val total = done + counts.pending
        val fraction = if (total == 0) 0.0 else done.toDouble() / total
        val colour = when {
            state.state == "error" || brokenBuilds > 0 || counts.failed > 0 -> BarColour.FAILING
            total == 0 -> BarColour.NEUTRAL
            counts.pending > 0 || state.state in BUSY -> BarColour.RUNNING
            else -> BarColour.PASSING
        }
        val builds = if (brokenBuilds > 0) "   · $brokenBuilds build${if (brokenBuilds == 1) "" else "s"} failed" else ""
        return BarState(colour, fraction, "${StatusText.describe(state)}   ${counts.label()}$builds")
    }
}
