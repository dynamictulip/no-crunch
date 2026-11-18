package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState
import com.nocrunch.rider.engine.EngineState

/** Human-readable wording for the engine state, used by the tool window and status bar. */
/** The view filter that leaves the tree empty although there are tests. */
enum class HiddenBy {
    FAILURES_ONLY, EXCLUDED;

    companion object {
        /** Show Failures Only is named first: with both filters on, "no failing tests" is the more useful thing to say. */
        fun of(hasTests: Boolean, shownEmpty: Boolean, failuresOnly: Boolean, hideExcluded: Boolean): HiddenBy? = when {
            !hasTests || !shownEmpty -> null
            failuresOnly -> FAILURES_ONLY
            hideExcluded -> EXCLUDED
            else -> null
        }
    }
}

object StatusText {
    fun describe(s: EngineUiState): String = when (s.state) {
        EngineState.STARTING -> "Starting No Crunch…"
        EngineState.SYNCING -> "Syncing changes…"
        EngineState.BUILDING -> "Building ${projects(s.queued)}…"
        EngineState.TESTING -> "Running tests in ${projects(s.queued)}…"
        EngineState.IDLE -> "Idle"
        EngineState.PAUSED -> "Paused"
        EngineState.STOPPED -> "Stopped"
        EngineState.ERROR -> "Error: ${s.error ?: "unknown"}"
        EngineState.UNKNOWN -> "Unknown"
    }

    /** Shown in the tree while it has nothing to show: no tests yet, or a view filter is hiding them all. */
    fun emptyTree(s: EngineUiState, hiddenBy: HiddenBy? = null): String = when {
        s.state == EngineState.ERROR -> "No Crunch error: ${s.error ?: "unknown"}"
        hiddenBy == HiddenBy.FAILURES_ONLY -> "No failing tests"
        hiddenBy == HiddenBy.EXCLUDED -> "All tests are excluded"
        s.state.isUpdating -> describe(s) + " tests appear once they're built"
        s.state == EngineState.IDLE -> "No tests found"
        else -> describe(s)
    }

    /**
     * Status bar widget text. [counts] come from the same unfiltered model as the progress bar, so a theory and its
     * failing rows count once, and excluded tests only count while they hold a current manual result.
     */
    fun widget(s: EngineUiState, counts: Counts, brokenBuilds: Int): String = when {
        s.state == EngineState.ERROR -> "No Crunch: error"
        s.state == EngineState.PAUSED -> "No Crunch: paused"
        s.state == EngineState.BUILDING || s.state == EngineState.SYNCING -> "No Crunch: building…"
        s.state == EngineState.TESTING -> "No Crunch: testing…"
        brokenBuilds > 0 -> "No Crunch: ✖ build"
        counts.failed > 0 -> "No Crunch: ✖ ${counts.failed}"
        s.state == EngineState.STARTING -> "No Crunch: starting…"
        s.state == EngineState.STOPPED -> "No Crunch"
        else -> "No Crunch: ✔"
    }

    /** Shown on the paused screen. The engine reports more than 200 when a full re-scan is pending. */
    fun waiting(files: Int): String = when {
        files <= 0 -> "No changes waiting"
        files == 1 -> "1 changed file waiting"
        files > 200 -> "200+ changed files waiting"
        else -> "$files changed files waiting"
    }

    private fun projects(n: Int) = if (n == 1) "1 project" else "$n projects"
}
