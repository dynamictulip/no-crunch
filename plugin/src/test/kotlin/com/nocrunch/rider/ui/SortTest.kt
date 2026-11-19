package com.nocrunch.rider.ui

import com.nocrunch.rider.view.SortMode
import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortTest {
    private fun t(project: String, type: String, method: String, state: TestState, ms: Double?, last: TestState? = null) = TestResultDto(
        key = "$project|$type|$method", project = project, type = type, method = method, uid = "$type.$method", variant = null,
        displayName = method, state = state, durationMs = ms, message = null, stackTrace = null, filePath = null, line = null,
        lastOutcome = last,
    )

    private val a = "tests/A/A.csproj"
    private val b = "tests/B/B.csproj"

    private val tests = listOf(
        t(a, "N.Zeta", "pass_fast", TestState.PASSED, 5.0),
        t(a, "N.Zeta", "fail", TestState.FAILED, 50.0),
        t(a, "N.Zeta", "stale_pass", TestState.STALE, 900.0, last = TestState.PASSED),
        t(a, "N.Zeta", "queued", TestState.QUEUED, null),
        t(a, "N.Zeta", "Excluded", TestState.EXCLUDED, null),
        t(a, "N.alpha", "slow", TestState.PASSED, 2_000.0),
        t(b, "M.Only", "pass", TestState.PASSED, 1.0),
    )

    private val rules = listOf(ExclusionRuleDto(a, "N.Zeta", "Excluded"))
    private fun projects() = TestTreeModel.build(tests, emptyMap(), rules, false)
    private fun rows(sort: SortMode, vararg levels: GroupLevel) =
        GroupingTree.build(projects(), Grouping(levels.toList()), sort).rows
    private fun classLabels(sort: SortMode) =
        (rows(sort, GroupLevel.PROJECT, GroupLevel.CLASS).first { it.label == "A" } as GroupView).children.map { it.label }
    private fun testOrder(sort: SortMode) =
        ((rows(sort, GroupLevel.PROJECT, GroupLevel.CLASS).first { it.label == "A" } as GroupView).children
            .single { it.label == "Zeta" } as GroupView).children.map { (it as TestView).node.dto.method }

    @Test
    fun name_sort_is_alphabetical_ignoring_case() {
        assertEquals(listOf("alpha", "Zeta"), classLabels(SortMode.NAME))
        assertEquals(listOf("Excluded", "fail", "pass_fast", "queued", "stale_pass"), testOrder(SortMode.NAME))
    }

    @Test
    fun status_sort_puts_problems_first() {
        assertEquals(listOf("fail", "queued", "stale_pass", "pass_fast", "Excluded"), testOrder(SortMode.STATUS))
        assertEquals(listOf("Zeta", "alpha"), classLabels(SortMode.STATUS))
        assertEquals(listOf("A", "B"), rows(SortMode.STATUS, GroupLevel.PROJECT, GroupLevel.CLASS).map { it.label })
    }

    @Test
    fun run_time_sort_is_slowest_first_with_unrun_last() {
        assertEquals(listOf("stale_pass", "fail", "pass_fast", "Excluded", "queued"), testOrder(SortMode.DURATION))
        assertEquals(listOf("alpha", "Zeta"), classLabels(SortMode.DURATION))
        assertEquals(listOf("A", "B"), rows(SortMode.DURATION, GroupLevel.PROJECT, GroupLevel.CLASS).map { it.label })
    }
}
