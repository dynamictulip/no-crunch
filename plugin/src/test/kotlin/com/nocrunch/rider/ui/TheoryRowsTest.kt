package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TheoryRowsTest {
    private val p = "tests/T/T.csproj"
    private val method = "Enrich_Line(System.String,IBlock)"

    private val theoryKey = "$p|N.C|$method|N.C.Enrich_Line"

    private fun t(variant: String?, state: String, uid: String = "u1", m: String = method, theory: String? = null) = TestResultDto(
        key = listOf(p, "N.C", m, variant ?: "N.C.Enrich_Line").joinToString("|"), project = p, type = "N.C", method = m, uid = uid, variant = variant,
        displayName = variant ?: "N.C.Enrich_Line", state = state, durationMs = 10.0, message = null, stackTrace = null,
        filePath = null, line = null, theoryKey = theory,
    )

    private val tests = listOf(
        t(null, "failed"),
        t("N.C.Enrich_Line(scenario: \"no date\")", "passed", theory = theoryKey),
        t("N.C.Enrich_Line(scenario: \"day zero\")", "failed", theory = theoryKey),
        // A theory whose rows were listed at discovery: separate UIDs, no placeholder. Stays flat.
        t("N.C.Other(x: 1)", "passed", uid = "u2", m = "Other(System.Int32)"),
        t("N.C.Other(x: 2)", "passed", uid = "u3", m = "Other(System.Int32)"),
    )

    @Test
    fun rows_reported_only_at_run_time_nest_under_their_theory() {
        val cls = TestTreeModel.build(tests, emptyMap(), emptyList(), false).single().children.single()
        assertEquals(3, cls.children.size, "theory plus the two listed rows")
        val theory = cls.children.single { it.dto.key == theoryKey }
        assertEquals(listOf("(scenario: \"day zero\")", "(scenario: \"no date\")"), theory.rows.map { it.label })
        assertEquals(Counts(passed = 1, failed = 1), theory.counts, "the theory counts its rows, not itself")
        assertEquals(Counts(passed = 3, failed = 1), cls.counts)
    }
}
