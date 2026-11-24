package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkerLinesTest {
    private val text = "[TestMethod]\npublic void Total_works()\n{\n}"

    @Test
    fun test_marker_lands_on_the_method_line_not_the_attribute() {
        assertEquals(1, landingLine(text, LineMarker(0, MarkerKind.PASS, "t", method = "Total_works")))
    }

    @Test
    fun cause_marker_keeps_its_line() {
        assertEquals(2, landingLine(text, LineMarker(2, MarkerKind.CAUSE, "t")))
    }

    @Test
    fun line_past_the_end_is_skipped() {
        assertEquals(null, landingLine("a\nb\nc", LineMarker(99, MarkerKind.CAUSE, "t")))
        assertEquals(null, landingLine("a\nb\nc", LineMarker(99, MarkerKind.PASS, "t", method = "M")))
    }

    @Test
    fun last_line_is_valid_even_without_a_trailing_newline() {
        assertEquals(2, landingLine("a\nb\nc", LineMarker(2, MarkerKind.FAIL, "t")))
    }

    @Test
    fun markers_landing_on_the_same_line_collapse_to_the_best_kind() {
        // The test's reported line is the attribute (0); it lands on the method line (1), where the failure was thrown.
        val layout = layout(text, listOf(
            LineMarker(0, MarkerKind.FAIL, "Failing: Total_works", method = "Total_works"),
            LineMarker(1, MarkerKind.CAUSE, "Cause of failure: boom"),
        ))
        assertEquals(mapOf(1 to LineMarker(1, MarkerKind.CAUSE, "Cause of failure: boom")), layout)
    }

    @Test
    fun layout_skips_markers_past_the_end() {
        assertEquals(emptyMap<Int, LineMarker>(), layout("a\nb", listOf(LineMarker(9, MarkerKind.PASS, "t"))))
    }

    @Test
    fun layout_is_equal_for_equal_input_so_unchanged_editors_can_be_skipped() {
        val markers = listOf(LineMarker(0, MarkerKind.PASS, "t", method = "Total_works"))
        assertEquals(layout(text, markers), layout(text, markers.toList()))
    }

    @Test
    fun a_coverage_marker_loses_to_a_cause_or_test_marker_on_the_same_line() {
        val cause = LineMarker(1, MarkerKind.CAUSE, "Cause of failure: boom")
        val dash = LineMarker(1, MarkerKind.NOT_COVERED, "Not covered by any test")
        val pass = LineMarker(1, MarkerKind.PASS, "Hit only by passing tests")

        assertEquals(mapOf(1 to cause), layout(text, listOf(dash, cause)))
        assertEquals(mapOf(1 to pass), layout(text, listOf(dash, pass)))
    }

    @Test
    fun layout_stays_fast_for_a_big_file_with_thousands_of_coverage_markers() {
        val big = (1..20_000).joinToString("\n") { "line $it" }
        val markers = (0 until 10_000).map { LineMarker(it, MarkerKind.PASS, "Hit only by passing tests") }

        var result: Map<Int, LineMarker> = emptyMap()
        val ms = kotlin.system.measureTimeMillis { result = layout(big, markers) }

        assertEquals(10_000, result.size)
        assertTrue(ms < 1_000, "layout took $ms ms: it must not rescan the whole text for every marker")
    }
}
