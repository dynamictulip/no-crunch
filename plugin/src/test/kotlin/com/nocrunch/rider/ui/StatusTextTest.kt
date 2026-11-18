package com.nocrunch.rider.ui

import com.nocrunch.rider.EngineUiState
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
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

    @Test
    fun status_bar_widget_says_what_the_engine_is_doing_then_how_things_stand() {
        fun widget(state: String, failed: Int = 0, brokenBuilds: Int = 0) =
            StatusText.widget(EngineUiState(state), Counts(passed = 3, failed = failed), brokenBuilds)
        assertEquals("No Crunch: error", widget("error", failed = 2))
        assertEquals("No Crunch: paused", widget("paused", failed = 2))
        assertEquals("No Crunch: building…", widget("building", failed = 2))
        assertEquals("No Crunch: building…", widget("syncing"))
        assertEquals("No Crunch: testing…", widget("testing", failed = 2))
        assertEquals("No Crunch: ✖ build", widget("idle", failed = 2, brokenBuilds = 1))
        assertEquals("No Crunch: ✖ 2", widget("idle", failed = 2))
        assertEquals("No Crunch: starting…", widget("starting"))
        assertEquals("No Crunch", widget("stopped"))
        assertEquals("No Crunch: ✔", widget("idle"))
    }

    @Test
    fun status_bar_widget_counts_failures_the_way_the_tree_and_bar_do() {
        val p = "tests/T/T.csproj"
        fun t(method: String, state: String, variant: String? = null, theory: String? = null, runKind: String = "auto") = TestResultDto(
            key = listOfNotNull(p, "N.C", method, variant).joinToString("|"), project = p, type = "N.C", method = method, uid = method,
            variant = variant, displayName = variant ?: method, state = state, durationMs = 1.0, message = null, stackTrace = null,
            filePath = null, line = null, runKind = runKind, theoryKey = theory,
        )
        val theoryKey = "$p|N.C|Theory"
        val tests = listOf(
            // A theory whose rows were only known at run time: it fails along with its failing row.
            t("Theory", "failed"),
            t("Theory", "failed", variant = "Theory(1)", theory = theoryKey),
            t("Theory", "passed", variant = "Theory(2)", theory = theoryKey),
            // Excluded, and its manual run's failure has gone stale: no longer counted.
            t("Excluded", "failed", runKind = "manual").copy(state = "stale", lastOutcome = "failed"),
        )
        val model = TestTreeModel.buildAll(tests, emptyMap(), listOf(ExclusionRuleDto(p, "N.C", "Excluded")))

        assertEquals("No Crunch: ✖ 1", StatusText.widget(EngineUiState("idle"), ProgressBarModel.totals(model), 0))
    }

    @Test
    fun waiting_changes_while_paused() {
        assertEquals("No changes waiting", StatusText.waiting(0))
        assertEquals("1 changed file waiting", StatusText.waiting(1))
        assertEquals("12 changed files waiting", StatusText.waiting(12))
        assertEquals("200+ changed files waiting", StatusText.waiting(201))
    }
}
