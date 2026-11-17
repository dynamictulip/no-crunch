package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortTest {
    private fun t(project: String, type: String, method: String, state: String, ms: Double?, last: String? = null) = TestResultDto(
        key = "$project|$type|$method", project = project, type = type, method = method, uid = "$type.$method", variant = null,
        displayName = method, state = state, durationMs = ms, message = null, stackTrace = null, filePath = null, line = null,
        lastOutcome = last,
    )

    private val a = "tests/A/A.csproj"
    private val b = "tests/B/B.csproj"

    private val tests = listOf(
        t(a, "N.Zeta", "pass_fast", "passed", 5.0),
        t(a, "N.Zeta", "fail", "failed", 50.0),
        t(a, "N.Zeta", "stale_pass", "stale", 900.0, last = "passed"),
        t(a, "N.Zeta", "queued", "queued", null),
        t(a, "N.Zeta", "Excluded", "excluded", null),
        t(a, "N.alpha", "slow", "passed", 2_000.0),
        t(b, "M.Only", "pass", "passed", 1.0),
    )

    private val rules = listOf(ExclusionRuleDto(a, "N.Zeta", "Excluded"))
    private fun projects() = TestTreeModel.build(tests, emptyMap(), rules, false)
    private fun testOrder(sort: SortMode) =
        (TreeView.of(projects().single { it.path == a }, ViewMode.CLASSES, sort).single { it.label == "Zeta" } as ClassView)
            .let { TreeView.sortTests(it.children, sort) }.map { it.node.dto.method }

    @Test
    fun name_sort_is_alphabetical_ignoring_case() {
        assertEquals(listOf("alpha", "Zeta"), TreeView.of(projects().first(), ViewMode.CLASSES, SortMode.NAME).map { it.label })
        assertEquals(listOf("Excluded", "fail", "pass_fast", "queued", "stale_pass"), testOrder(SortMode.NAME))
    }

    @Test
    fun status_sort_puts_problems_first() {
        assertEquals(listOf("fail", "queued", "stale_pass", "pass_fast", "Excluded"), testOrder(SortMode.STATUS))
        assertEquals(listOf("Zeta", "alpha"), TreeView.of(projects().first(), ViewMode.CLASSES, SortMode.STATUS).map { it.label })
        assertEquals(listOf(a, b), TreeView.sortProjects(projects(), SortMode.STATUS).map { it.path })
    }

    @Test
    fun status_sort_puts_build_failures_above_test_failures() {
        val broken = "src/Broken/Broken.csproj"
        val blocked = "tests/Blocked/Blocked.csproj"
        val builds = mapOf(
            broken to BuildFinishedDto(broken, "failed", "error CS1002", null),
            blocked to BuildFinishedDto(blocked, "blocked", "", broken),
            b to BuildFinishedDto(b, "succeeded", "", null),
        )
        val withBuilds = TestTreeModel.build(tests, builds, rules, false)
        assertEquals(listOf(broken, blocked, a, b), TreeView.sortProjects(withBuilds, SortMode.STATUS).map { it.path })
    }

    @Test
    fun run_time_sort_is_slowest_first_with_unrun_last() {
        assertEquals(listOf("stale_pass", "fail", "pass_fast", "Excluded", "queued"), testOrder(SortMode.DURATION))
        assertEquals(listOf("alpha", "Zeta"), TreeView.of(projects().first(), ViewMode.CLASSES, SortMode.DURATION).map { it.label })
        assertEquals(listOf(a, b), TreeView.sortProjects(projects(), SortMode.DURATION).map { it.path })
    }
}
