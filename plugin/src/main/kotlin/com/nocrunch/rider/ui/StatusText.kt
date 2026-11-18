package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState

/** Human-readable wording for the engine state, used by the tool window and status bar. */
object StatusText {
    fun describe(s: EngineUiState): String = when (s.state) {
        "starting" -> "Starting No Crunch…"
        "syncing" -> "Syncing changes…"
        "building" -> "Building ${projects(s.queued)}…"
        "testing" -> "Running tests in ${projects(s.queued)}…"
        "idle" -> "Idle"
        "paused" -> "Paused"
        "stopped" -> "Stopped"
        "error" -> "Error: ${s.error ?: "unknown"}"
        else -> s.state.replaceFirstChar { it.uppercase() }
    }

    /** Shown in the tree while it has no tests yet. */
    fun emptyTree(s: EngineUiState): String = when (s.state) {
        "building", "syncing", "testing" -> describe(s) + " tests appear once they're built"
        "idle" -> "No tests found"
        "error" -> "No Crunch error: ${s.error ?: "unknown"}"
        else -> describe(s)
    }

    /**
     * Status bar widget text. [counts] come from the same unfiltered model as the progress bar, so a theory and its
     * failing rows count once, and excluded tests only count while they hold a current manual result.
     */
    fun widget(s: EngineUiState, counts: Counts, brokenBuilds: Int): String = when {
        s.state == "error" -> "No Crunch: error"
        s.state == "paused" -> "No Crunch: paused"
        s.state == "building" || s.state == "syncing" -> "No Crunch: building…"
        s.state == "testing" -> "No Crunch: testing…"
        brokenBuilds > 0 -> "No Crunch: ✖ build"
        counts.failed > 0 -> "No Crunch: ✖ ${counts.failed}"
        s.state == "starting" -> "No Crunch: starting…"
        s.state == "stopped" -> "No Crunch"
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
