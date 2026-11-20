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

    private val repoOnly: (String) -> Boolean = { it.startsWith("/repo/") && !it.contains("/.nocrunch/") }
    private fun frame(path: String, line: Int) = "   at Orders.Order.Total() in $path:line $line"

    @Test
    fun top_repo_frame_skips_framework_frames() {
        val trace = frame("/usr/share/dotnet/Assert.cs", 5) + "\n" + frame("/repo/src/A.cs", 40) + "\n" + frame("/repo/src/B.cs", 7)
        assertEquals("/repo/src/A.cs" to 40, EditorMarkers.topRepoFrame(trace, repoOnly))
    }

    @Test
    fun top_repo_frame_ignores_frames_outside_the_repo_and_mirror_and_missing_files() {
        val trace = frame("/usr/share/dotnet/Assert.cs", 5) + "\n" + frame("/repo/.nocrunch/mirror/A.cs", 40) + "\n" + frame("/repo/src/Gone.cs", 3)
        assertEquals(null, EditorMarkers.topRepoFrame(trace) { repoOnly(it) && it != "/repo/src/Gone.cs" })
        assertEquals(null, EditorMarkers.topRepoFrame(null, repoOnly))
        assertEquals(null, EditorMarkers.topRepoFrame("no frames here", repoOnly))
    }

    @Test
    fun failing_test_gets_a_cause_marker_in_production_code() {
        val markers = compute(
            result("a", TestState.FAILED, message = "boom\nmore", stackTrace = frame("/repo/src/Order.cs", 40)),
            isRepoFile = repoOnly,
        )
        assertEquals(listOf(LineMarker(39, MarkerKind.CAUSE, "Cause of failure: boom")), markers["/repo/src/Order.cs"])
        assertEquals(MarkerKind.FAIL, markers[testFile]!!.single().kind)
    }

    @Test
    fun framework_only_trace_gives_no_cause_marker() {
        val markers = compute(
            result("a", TestState.FAILED, stackTrace = frame("/usr/share/dotnet/Assert.cs", 5)),
            isRepoFile = repoOnly,
        )
        assertEquals(setOf(testFile), markers.keys)
    }

    @Test
    fun stale_failure_cause_shows_as_fail_stale() {
        val markers = compute(
            result("a", TestState.STALE, lastOutcome = TestState.FAILED, stackTrace = frame("/repo/src/Order.cs", 40)),
            isRepoFile = repoOnly,
        )
        val cause = markers["/repo/src/Order.cs"]!!.single()
        assertEquals(MarkerKind.FAIL_STALE, cause.kind)
        assertEquals("Failing, stale: Total_works", cause.tooltip)
    }

    @Test
    fun cause_on_the_test_method_line_wins_over_fail() {
        val markers = compute(
            result("a", TestState.FAILED, message = "boom", stackTrace = frame(testFile, 12)),
            isRepoFile = repoOnly,
        )
        assertEquals(listOf(MarkerKind.CAUSE), markers[testFile]!!.map { it.kind })
    }

    @Test
    fun cause_beats_a_passing_marker_on_the_same_line() {
        val markers = compute(
            result("a", TestState.PASSED),
            result("b", TestState.FAILED, method = "Other", line = 50, message = "boom", stackTrace = frame(testFile, 12)),
            isRepoFile = repoOnly,
        )
        assertEquals(MarkerKind.CAUSE, markers[testFile]!!.single { it.line == 11 }.kind)
    }
}
