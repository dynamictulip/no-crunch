package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState
import com.nocrunch.rider.engine.EngineState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressBarModelTest {
    private fun bar(passed: Int, failed: Int, pending: Int, state: EngineState = EngineState.IDLE, queued: Int = 0, brokenBuilds: Int = 0, error: String? = null) =
        ProgressBarModel.from(Counts(passed, failed, pending), EngineUiState(state, queued, error), brokenBuilds)

    @Test
    fun all_passing_is_full_and_green() {
        val b = bar(10, 0, 0)
        assertEquals(BarColour.PASSING, b.colour)
        assertEquals(1.0, b.fraction, 1e-9)
        assertEquals("Idle   ✔ 10  ✖ 0  ○ 0", b.text)
    }

    @Test
    fun any_failure_is_red_even_while_running() {
        val b = bar(3, 1, 4, state = EngineState.TESTING, queued = 2)
        assertEquals(BarColour.FAILING, b.colour)
        assertEquals(0.5, b.fraction, 1e-9)
        assertEquals("Running tests in 2 projects…   ✔ 3  ✖ 1  ○ 4", b.text)
    }

    @Test
    fun in_progress_without_failures_is_blue() {
        assertEquals(BarColour.RUNNING, bar(3, 0, 1, state = EngineState.TESTING, queued = 1).colour)
        assertEquals(BarColour.RUNNING, bar(4, 0, 0, state = EngineState.BUILDING, queued = 2).colour)
    }

    @Test
    fun idle_with_no_failures_is_green_even_with_pending_tests() {
        val b = bar(2039, 0, 7)
        assertEquals(BarColour.PASSING, b.colour)
        assertEquals("Idle   ✔ 2039  ✖ 0  ○ 7", b.text)
    }

    @Test
    fun broken_build_is_red_and_mentioned() {
        val b = bar(4, 0, 0, brokenBuilds = 1)
        assertEquals(BarColour.FAILING, b.colour)
        assertEquals("Idle   ✔ 4  ✖ 0  ○ 0   · 1 build failed", b.text)
    }

    @Test
    fun error_is_red() {
        val b = bar(0, 0, 0, state = EngineState.ERROR, error = "dotnet not found")
        assertEquals(BarColour.FAILING, b.colour)
        assertEquals("Error: dotnet not found   ✔ 0  ✖ 0  ○ 0", b.text)
    }

    @Test
    fun nothing_yet_is_neutral_and_empty() {
        val b = bar(0, 0, 0, state = EngineState.STARTING)
        assertEquals(BarColour.NEUTRAL, b.colour)
        assertEquals(0.0, b.fraction, 1e-9)
    }
}
