package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusTextTest {
    @Test
    fun describes_each_engine_state() {
        assertEquals("Starting No Crunch…", StatusText.describe(EngineUiState("starting")))
        assertEquals("Syncing changes…", StatusText.describe(EngineUiState("syncing")))
        assertEquals("Building 4 projects…", StatusText.describe(EngineUiState("building", 4)))
        assertEquals("Building 1 project…", StatusText.describe(EngineUiState("building", 1)))
        assertEquals("Running tests in 3 projects…", StatusText.describe(EngineUiState("testing", 3)))
        assertEquals("Idle", StatusText.describe(EngineUiState("idle")))
        assertEquals("Paused", StatusText.describe(EngineUiState("paused")))
        assertEquals("Stopped", StatusText.describe(EngineUiState("stopped")))
        assertEquals("Error: boom", StatusText.describe(EngineUiState("error", error = "boom")))
    }

    @Test
    fun empty_tree_explains_what_is_happening() {
        assertEquals("Starting No Crunch…", StatusText.emptyTree(EngineUiState("starting")))
        assertEquals("Building 4 projects… tests appear once they're built", StatusText.emptyTree(EngineUiState("building", 4)))
        assertEquals("No tests found", StatusText.emptyTree(EngineUiState("idle")))
        assertEquals("No Crunch error: boom", StatusText.emptyTree(EngineUiState("error", error = "boom")))
    }

    @Test
    fun service_state_starts_as_starting() {
        assertEquals("starting", EngineUiState.initial().state)
    }
}
