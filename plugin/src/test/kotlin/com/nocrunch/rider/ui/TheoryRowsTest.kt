package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TheoryRowsTest {
    private val p = "tests/T/T.csproj"
    private val method = "Enrich_Line(System.String,IBlock)"

    private val theoryKey = "$p|N.C|$method|N.C.Enrich_Line"

    private fun t(variant: String?, state: TestState, uid: String = "u1", m: String = method, theory: String? = null) = TestResultDto(
        key = listOf(p, "N.C", m, variant ?: "N.C.Enrich_Line").joinToString("|"), project = p, type = "N.C", method = m, uid = uid, variant = variant,
        displayName = variant ?: "N.C.Enrich_Line", state = state, durationMs = 10.0, message = null, stackTrace = null,
        filePath = null, line = null, theoryKey = theory,
    )

    private val tests = listOf(
        t(null, TestState.FAILED),
        t("N.C.Enrich_Line(scenario: \"no date\")", TestState.PASSED, theory = theoryKey),
        t("N.C.Enrich_Line(scenario: \"day zero\")", TestState.FAILED, theory = theoryKey),
        // A theory whose rows were listed at discovery: separate UIDs, no placeholder. Nests under its method name.
        t("N.C.Other(x: 1)", TestState.PASSED, uid = "u2", m = "Other(System.Int32)"),
        t("N.C.Other(x: 2)", TestState.PASSED, uid = "u3", m = "Other(System.Int32)"),
    )

    @Test
    fun test_names_drop_the_class_prefix() {
        val cls = TestTreeModel.build(tests, emptyMap(), emptyList(), false).single().children.single()
        assertEquals(listOf("Enrich_Line", "Other"), cls.children.map { it.label })
    }

    @Test
    fun rows_reported_only_at_run_time_nest_under_their_theory() {
        val cls = TestTreeModel.build(tests, emptyMap(), emptyList(), false).single().children.single()
        assertEquals(2, cls.children.size, "the theory and the listed rows' method")
        val theory = cls.children.single { it.dto.key == theoryKey }
        assertEquals(listOf("(scenario: \"day zero\")", "(scenario: \"no date\")"), theory.rows.map { it.label })
        assertEquals(Counts(passed = 1, failed = 1), theory.counts, "the theory counts its rows, not itself")
        assertEquals(Counts(passed = 3, failed = 1), cls.counts)
    }

    @Test
    fun failures_only_keeps_a_theory_whose_rows_are_failing() {
        val reporting = listOf(
            t(null, TestState.RUNNING).copy(lastOutcome = null),
            t("N.C.Enrich_Line(scenario: \"no date\")", TestState.PASSED, theory = theoryKey),
            t("N.C.Enrich_Line(scenario: \"day zero\")", TestState.FAILED, theory = theoryKey),
        )
        val cls = TestTreeModel.build(reporting, emptyMap(), emptyList(), failuresOnly = true).single().children.single()
        assertEquals(listOf(theoryKey), cls.children.map { it.dto.key })
    }

    @Test
    fun rows_listed_as_separate_tests_nest_under_their_method() {
        val listed = listOf(
            t("N.C.Other(x: 1)", TestState.PASSED, uid = "u2", m = "Other(System.Int32)"),
            t("N.C.Other(x: 2)", TestState.FAILED, uid = "u3", m = "Other(System.Int32)"),
            t("N.C.Other(x: 3)", TestState.QUEUED, uid = "u4", m = "Other(System.Int32)"),
        )
        val group = TestTreeModel.build(listed, emptyMap(), emptyList(), false).single().children.single().children.single()
        assertEquals("Other", group.label)
        assertEquals(listOf("(x: 1)", "(x: 2)", "(x: 3)"), group.rows.map { it.label })
        assertEquals(TestState.FAILED, group.shownOutcome)
        assertEquals(Counts(passed = 1, failed = 1, pending = 1), group.counts)
        assertEquals(listed.map { it.key }, group.runKeys, "running the group runs its rows")
        assertEquals(listOf(true, true, true), group.rows.map { it.locked && it.isRow })
    }

    @Test
    fun a_single_listed_row_stays_flat() {
        val one = listOf(t("N.C.Other(x: 1)", TestState.PASSED, uid = "u2", m = "Other(System.Int32)"))
        val cls = TestTreeModel.build(one, emptyMap(), emptyList(), false).single().children.single()
        assertEquals(listOf("Other(x: 1)"), cls.children.map { it.label })
    }
}
