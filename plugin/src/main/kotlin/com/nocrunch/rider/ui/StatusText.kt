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

    private fun projects(n: Int) = if (n == 1) "1 project" else "$n projects"
}
