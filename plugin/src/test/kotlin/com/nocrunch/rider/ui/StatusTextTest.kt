package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState
import com.nocrunch.rider.engine.EngineState
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunKind
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusTextTest {
    @Test
    fun describes_each_engine_state() {
        assertEquals("Starting No Crunch…", StatusText.describe(EngineUiState(EngineState.STARTING)))
        assertEquals("Syncing changes…", StatusText.describe(EngineUiState(EngineState.SYNCING)))
        assertEquals("Building 4 projects…", StatusText.describe(EngineUiState(EngineState.BUILDING, 4)))
        assertEquals("Building 1 project…", StatusText.describe(EngineUiState(EngineState.BUILDING, 1)))
        assertEquals("Running tests in 3 projects…", StatusText.describe(EngineUiState(EngineState.TESTING, 3)))
        assertEquals("Idle", StatusText.describe(EngineUiState(EngineState.IDLE)))
        assertEquals("Paused", StatusText.describe(EngineUiState(EngineState.PAUSED)))
        assertEquals("Stopped", StatusText.describe(EngineUiState(EngineState.STOPPED)))
        assertEquals("Error: boom", StatusText.describe(EngineUiState(EngineState.ERROR, error = "boom")))
    }

    @Test
    fun a_state_from_a_newer_engine_reads_as_unknown_and_is_not_busy() {
        assertEquals("Unknown", StatusText.describe(EngineUiState(EngineState.UNKNOWN)))
        assertEquals("Unknown", StatusText.emptyTree(EngineUiState(EngineState.UNKNOWN)))
        assertEquals("No Crunch: ✔", StatusText.widget(EngineUiState(EngineState.UNKNOWN), Counts(passed = 1), 0))
        assertEquals(BarColour.PASSING, ProgressBarModel.from(Counts(passed = 1), EngineUiState(EngineState.UNKNOWN), 0).colour)
    }

    @Test
    fun empty_tree_explains_what_is_happening() {
        assertEquals("Starting No Crunch…", StatusText.emptyTree(EngineUiState(EngineState.STARTING)))
        assertEquals("Building 4 projects… tests appear once they're built", StatusText.emptyTree(EngineUiState(EngineState.BUILDING, 4)))
        assertEquals("No tests found", StatusText.emptyTree(EngineUiState(EngineState.IDLE)))
        assertEquals("No Crunch error: boom", StatusText.emptyTree(EngineUiState(EngineState.ERROR, error = "boom")))
    }

    @Test
    fun empty_tree_says_when_a_view_filter_is_hiding_every_test() {
        assertEquals("No failing tests", StatusText.emptyTree(EngineUiState(EngineState.IDLE), HiddenBy.FAILURES_ONLY))
        assertEquals("No failing tests", StatusText.emptyTree(EngineUiState(EngineState.TESTING, 2), HiddenBy.FAILURES_ONLY))
        assertEquals("All tests are excluded", StatusText.emptyTree(EngineUiState(EngineState.IDLE), HiddenBy.EXCLUDED))
        assertEquals("No Crunch error: boom", StatusText.emptyTree(EngineUiState(EngineState.ERROR, error = "boom"), HiddenBy.FAILURES_ONLY),
            "an engine error matters more than the filter")
    }

    @Test
    fun hidden_by_names_the_filter_only_when_it_hides_every_test() {
        assertEquals(HiddenBy.FAILURES_ONLY, HiddenBy.of(hasTests = true, shownEmpty = true, failuresOnly = true, hideExcluded = true))
        assertEquals(HiddenBy.EXCLUDED, HiddenBy.of(hasTests = true, shownEmpty = true, failuresOnly = false, hideExcluded = true))
        assertEquals(null, HiddenBy.of(hasTests = false, shownEmpty = true, failuresOnly = true, hideExcluded = false), "no tests at all")
        assertEquals(null, HiddenBy.of(hasTests = true, shownEmpty = false, failuresOnly = true, hideExcluded = false), "something shows")
    }

    @Test
    fun service_state_starts_as_starting() {
        assertEquals(EngineState.STARTING, EngineUiState.initial().state)
    }

    @Test
    fun status_bar_widget_says_what_the_engine_is_doing_then_how_things_stand() {
        fun widget(state: EngineState, failed: Int = 0, brokenBuilds: Int = 0) =
            StatusText.widget(EngineUiState(state), Counts(passed = 3, failed = failed), brokenBuilds)
        assertEquals("No Crunch: error", widget(EngineState.ERROR, failed = 2))
        assertEquals("No Crunch: paused", widget(EngineState.PAUSED, failed = 2))
        assertEquals("No Crunch: building…", widget(EngineState.BUILDING, failed = 2))
        assertEquals("No Crunch: building…", widget(EngineState.SYNCING))
        assertEquals("No Crunch: testing…", widget(EngineState.TESTING, failed = 2))
        assertEquals("No Crunch: ✖ build", widget(EngineState.IDLE, failed = 2, brokenBuilds = 1))
        assertEquals("No Crunch: ✖ 2", widget(EngineState.IDLE, failed = 2))
        assertEquals("No Crunch: starting…", widget(EngineState.STARTING))
        assertEquals("No Crunch", widget(EngineState.STOPPED))
        assertEquals("No Crunch: ✔", widget(EngineState.IDLE))
    }

    @Test
    fun status_bar_widget_counts_failures_the_way_the_tree_and_bar_do() {
        val p = "tests/T/T.csproj"
        fun t(method: String, state: TestState, variant: String? = null, theory: String? = null, runKind: RunKind = RunKind.AUTO) = TestResultDto(
            key = listOfNotNull(p, "N.C", method, variant).joinToString("|"), project = p, type = "N.C", method = method, uid = method,
            variant = variant, displayName = variant ?: method, state = state, durationMs = 1.0, message = null, stackTrace = null,
            filePath = null, line = null, runKind = runKind, theoryKey = theory,
        )
        val theoryKey = "$p|N.C|Theory"
        val tests = listOf(
            // A theory whose rows were only known at run time: it fails along with its failing row.
            t("Theory", TestState.FAILED),
            t("Theory", TestState.FAILED, variant = "Theory(1)", theory = theoryKey),
            t("Theory", TestState.PASSED, variant = "Theory(2)", theory = theoryKey),
            // Excluded, and its manual run's failure has gone stale: no longer counted.
            t("Excluded", TestState.FAILED, runKind = RunKind.MANUAL).copy(state = TestState.STALE, lastOutcome = TestState.FAILED),
        )
        val model = TestTreeModel.buildAll(tests, emptyMap(), listOf(ExclusionRuleDto(p, "N.C", "Excluded")))

        assertEquals("No Crunch: ✖ 1", StatusText.widget(EngineUiState(EngineState.IDLE), ProgressBarModel.totals(model), 0))
    }

    @Test
    fun waiting_changes_while_paused() {
        assertEquals("No changes waiting", StatusText.waiting(0))
        assertEquals("1 changed file waiting", StatusText.waiting(1))
        assertEquals("12 changed files waiting", StatusText.waiting(12))
        assertEquals("200+ changed files waiting", StatusText.waiting(201))
    }
}
