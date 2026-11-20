package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorMarkersTest {
    private val testFile = "/repo/tests/OrderTests.cs"

    private fun result(
        key: String,
        state: TestState,
        method: String = "Total_works",
        lastOutcome: TestState? = null,
        line: Int? = 12,
        filePath: String? = testFile,
        message: String? = null,
        stackTrace: String? = null,
    ) = TestResultDto(
        key = key, project = "Orders.Tests", type = "OrderTests", method = method, uid = key, variant = null,
        displayName = method, state = state, durationMs = null, message = message, stackTrace = stackTrace,
        filePath = filePath, line = line, lastOutcome = lastOutcome,
    )

    private fun compute(vararg results: TestResultDto, isRepoFile: (String) -> Boolean = { true }) =
        EditorMarkers.compute(results.toList(), isRepoFile)

    @Test
    fun passing_test_gets_a_pass_marker_on_its_line() {
        val markers = compute(result("a", TestState.PASSED))
        assertEquals(listOf(LineMarker(11, MarkerKind.PASS, "Passing: Total_works", "Total_works")), markers[testFile])
    }

    @Test
    fun failed_test_gets_fail() {
        val m = compute(result("a", TestState.FAILED))[testFile]!!.single()
        assertEquals(MarkerKind.FAIL, m.kind)
        assertEquals("Failing: Total_works", m.tooltip)
    }

    @Test
    fun stale_result_shows_last_outcome_hollow() {
        assertEquals(MarkerKind.PASS_STALE, compute(result("a", TestState.STALE, lastOutcome = TestState.PASSED))[testFile]!!.single().kind)
        val failed = compute(result("a", TestState.QUEUED, lastOutcome = TestState.FAILED))[testFile]!!.single()
        assertEquals(MarkerKind.FAIL_STALE, failed.kind)
        assertEquals("Failing, stale: Total_works", failed.tooltip)
    }

    @Test
    fun never_run_skipped_and_excluded_get_nothing() {
        val markers = compute(
            result("a", TestState.DISCOVERED, line = 1),
            result("b", TestState.SKIPPED, line = 2),
            result("c", TestState.EXCLUDED, line = 3),
            result("d", TestState.EXCLUDED, line = 4, lastOutcome = TestState.PASSED),
        )
        assertTrue(markers.isEmpty(), markers.toString())
    }

    @Test
    fun theory_with_a_failing_variant_shows_one_fail_marker() {
        val markers = compute(
            result("a", TestState.PASSED),
            result("b", TestState.FAILED),
            result("c", TestState.PASSED),
        )
        assertEquals(listOf(MarkerKind.FAIL), markers[testFile]!!.map { it.kind })
    }

    @Test
    fun theory_stale_only_when_the_worst_outcome_is_stale() {
        val markers = compute(
            result("a", TestState.PASSED),
            result("b", TestState.STALE, lastOutcome = TestState.FAILED),
        )
        assertEquals(MarkerKind.FAIL_STALE, markers[testFile]!!.single().kind)
    }

    @Test
    fun results_without_a_location_are_ignored() {
        assertTrue(compute(result("a", TestState.PASSED, filePath = null), result("b", TestState.PASSED, line = null)).isEmpty())
    }

    @Test
    fun markers_are_sorted_by_line() {
        val markers = compute(result("a", TestState.PASSED, line = 30), result("b", TestState.FAILED, line = 5))
        assertEquals(listOf(4, 29), markers[testFile]!!.map { it.line })
    }
}
