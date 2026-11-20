package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
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
}
