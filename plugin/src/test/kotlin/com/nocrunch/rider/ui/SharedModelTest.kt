package com.nocrunch.rider.ui

import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.SortMode
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The whole tree is built once per batch and shared; view filters and aggregates come from that one build. */
class SharedModelTest {
    private val unit = "tests/Unit/Unit.csproj"
    private val e2e = "tests/E2E/E2E.csproj"
    private val core = "src/Core/Core.csproj"

    private fun t(project: String, type: String, method: String, state: TestState, lastOutcome: TestState? = null) = TestResultDto(
        key = "$project|$type|$method", project = project, type = type, method = method, uid = "$type.$method", variant = null,
        displayName = method, state = state, durationMs = 5.0, message = null, stackTrace = null,
        filePath = "/repo/${project.substringBeforeLast('/')}/${type.substringAfterLast('.')}.cs", line = 1, lastOutcome = lastOutcome,
    )

    private val tests = listOf(
        t(unit, "Shop.Unit.Calc", "Adds", TestState.PASSED),
        t(unit, "Shop.Unit.Calc", "Subtracts", TestState.STALE, lastOutcome = TestState.FAILED),
        t(unit, "Shop.Unit.Pricing.Tax", "Vat", TestState.FAILED),
        t(e2e, "Shop.E2E.Flow", "Checkout", TestState.PASSED),
        t(e2e, "Shop.E2E.Flow", "Refund", TestState.EXCLUDED),
    )
    private val builds = mapOf(core to ProjectBuild(core, BuildStatus.FAILED, "error", null))
    private val rules = listOf(ExclusionRuleDto(e2e, "Shop.E2E.Flow", "Refund"))

    @Test
    fun filtering_the_full_build_matches_building_with_the_filters() {
        val full = TestTreeModel.build(tests, builds, rules, failuresOnly = false)
        for (failuresOnly in listOf(false, true)) for (hideExcluded in listOf(false, true)) {
            assertEquals(
                TestTreeModel.build(tests, builds, rules, failuresOnly, hideExcluded = hideExcluded),
                TestTreeModel.filter(full, failuresOnly, hideExcluded),
                "failuresOnly=$failuresOnly hideExcluded=$hideExcluded",
            )
        }
    }

    @Test
    fun totals_are_worked_out_once_not_on_every_read() {
        val project = TestTreeModel.build(tests, builds, rules, failuresOnly = false).single { it.path == unit }
        assertSame(project.counts, project.counts)
        assertSame(project.children.first().counts, project.children.first().counts)
        for (grouping in listOf(Grouping.fromViewMode(com.nocrunch.rider.view.ViewMode.NAMESPACES), Grouping(listOf(GroupLevel.FOLDER, GroupLevel.FILE)))) {
            val group = GroupingTree.build(listOf(project), grouping, SortMode.NAME).rows.filterIsInstance<GroupView>().first()
            assertSame(group.leaves, group.leaves, "$grouping")
            assertSame(group.counts, group.counts, "$grouping")
        }
    }

    @Test
    fun the_bar_counts_every_test_whatever_the_view_filters() {
        val full = TestTreeModel.build(tests, builds, rules, failuresOnly = false)
        assertEquals(Counts(passed = 2, failed = 1, pending = 1), ProgressBarModel.totals(full))
    }
}
