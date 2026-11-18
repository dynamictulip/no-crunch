package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.engine.RunKind
import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestTreeModelTest {
    private val unit = "tests/Unit/Unit.csproj"
    private val e2e = "tests/E2E/E2E.csproj"

    private fun t(project: String, type: String?, method: String?, state: TestState, uid: String = "$type.$method") =
        TestResultDto(
            key = if (type != null && method != null) "$project|$type|$method" else "$project|uid:$uid",
            project = project, type = type, method = method, uid = uid, variant = null,
            displayName = method ?: uid, state = state, durationMs = 1.0, message = null, stackTrace = null, filePath = null, line = null,
        )

    private val tests = listOf(
        t(unit, "A.Calc", "Adds", TestState.PASSED),
        t(unit, "A.Calc", "Subtracts", TestState.FAILED),
        t(unit, "A.Other", "Works", TestState.PASSED),
        t(e2e, "B.Flow", "Checkout", TestState.PASSED),
    )

    @Test
    fun groups_by_project_then_class() {
        val tree = TestTreeModel.build(tests, emptyMap(), emptyList(), failuresOnly = false)

        assertEquals(listOf("E2E", "Unit"), tree.map { it.name })
        val unitNode = tree.single { it.path == unit }
        assertEquals(listOf("A.Calc", "A.Other"), unitNode.children.map { it.type })
        assertEquals(listOf("Adds", "Subtracts"), unitNode.children[0].children.map { it.dto.method })
    }

    @Test
    fun parent_exclusion_marks_children_excluded() {
        val tree = TestTreeModel.build(tests, emptyMap(), listOf(ExclusionRuleDto(e2e), ExclusionRuleDto(unit, "A.Other")), false)

        val e2eNode = tree.single { it.path == e2e }
        assertTrue(e2eNode.excluded)
        assertTrue(e2eNode.children.all { c -> c.excluded && c.children.all { it.excluded } })
        val unitNode = tree.single { it.path == unit }
        assertFalse(unitNode.excluded)
        assertTrue(unitNode.children.single { it.type == "A.Other" }.excluded)
        assertFalse(unitNode.children.single { it.type == "A.Calc" }.excluded)
    }

    @Test
    fun method_rule_excludes_single_test() {
        val tree = TestTreeModel.build(tests, emptyMap(), listOf(ExclusionRuleDto(unit, "A.Calc", "Adds")), false)
        val calc = tree.single { it.path == unit }.children.single { it.type == "A.Calc" }
        assertTrue(calc.children.single { it.dto.method == "Adds" }.excluded)
        assertFalse(calc.children.single { it.dto.method == "Subtracts" }.excluded)
        assertFalse(calc.excluded)
    }

    @Test
    fun counts_roll_up() {
        val tree = TestTreeModel.build(tests, emptyMap(), emptyList(), false)
        val unitNode = tree.single { it.path == unit }
        assertEquals(Counts(passed = 2, failed = 1, pending = 0), unitNode.counts)
        assertEquals(Counts(passed = 1, failed = 1, pending = 0), unitNode.children.single { it.type == "A.Calc" }.counts)
    }

    @Test
    fun failures_only_keeps_failed_and_ancestors() {
        val tree = TestTreeModel.build(tests, emptyMap(), emptyList(), failuresOnly = true)
        assertEquals(listOf(unit), tree.map { it.path })
        assertEquals(listOf("A.Calc"), tree.single().children.map { it.type })
        assertEquals(listOf("Subtracts"), tree.single().children.single().children.map { it.dto.method })
    }

    @Test
    fun failures_only_keeps_failures_while_they_are_stale_queued_or_running() {
        val staleFail = t(unit, "A.Calc", "Subtracts", TestState.STALE).copy(lastOutcome = TestState.FAILED)
        val runningFail = t(unit, "A.Calc", "Divides", TestState.RUNNING).copy(lastOutcome = TestState.FAILED)
        val stalePass = t(unit, "A.Calc", "Adds", TestState.STALE).copy(lastOutcome = TestState.PASSED)
        val tree = TestTreeModel.build(listOf(staleFail, runningFail, stalePass), emptyMap(), emptyList(), failuresOnly = true)

        assertEquals(listOf("Divides", "Subtracts"), tree.single().children.single().children.map { it.dto.method })
    }

    @Test
    fun filtered_rows_count_only_the_tests_they_show() {
        val tree = TestTreeModel.build(listOf(
            t(unit, "A.Calc", "Adds", TestState.PASSED),
            t(unit, "A.Calc", "Divides", TestState.FAILED),
            t(unit, "A.Calc", "Subtracts", TestState.PASSED),
        ), emptyMap(), emptyList(), failuresOnly = true)

        val project = tree.single()
        assertEquals(Counts(passed = 0, failed = 1), project.children.single().counts)
        assertEquals(Counts(passed = 0, failed = 1), project.counts)
    }

    @Test
    fun build_failed_project_has_status() {
        val builds = mapOf(
            "src/Core/Core.csproj" to ProjectBuild("src/Core/Core.csproj", BuildStatus.FAILED, "error CS1002", null),
            unit to ProjectBuild(unit, BuildStatus.BLOCKED, "", "src/Core/Core.csproj"),
            "src/Lib/Lib.csproj" to ProjectBuild("src/Lib/Lib.csproj", BuildStatus.SUCCEEDED, "", null),
        )
        val tree = TestTreeModel.build(tests, builds, emptyList(), failuresOnly = true)

        assertEquals(BuildStatus.FAILED, tree.single { it.path == "src/Core/Core.csproj" }.buildStatus)
        assertEquals(BuildStatus.BLOCKED, tree.single { it.path == unit }.buildStatus)
        assertFalse(tree.any { it.path == "src/Lib/Lib.csproj" }, "healthy non-test projects are not shown")
    }

    @Test
    fun uid_only_tests_grouped_under_unknown_class() {
        val tree = TestTreeModel.build(listOf(t(unit, null, null, TestState.PASSED, uid = "u-1")), emptyMap(),
            listOf(ExclusionRuleDto(unit, uid = "u-1")), false)
        val cls = tree.single().children.single()
        assertEquals(TestTreeModel.UNKNOWN_CLASS, cls.type)
        assertTrue(cls.children.single().excluded)
    }

    @Test
    fun unknown_class_node_excludes_its_tests_by_uid_not_the_project() {
        val tree = TestTreeModel.build(listOf(t(unit, null, null, TestState.PASSED, uid = "u-1"), t(unit, null, null, TestState.PASSED, uid = "u-2")),
            emptyMap(), emptyList(), false)
        val rules = tree.single().children.single().rules
        assertEquals(listOf(ExclusionRuleDto(unit, uid = "u-1"), ExclusionRuleDto(unit, uid = "u-2")), rules)
    }

    @Test
    fun tests_inside_an_excluded_class_can_be_toggled_on_their_own() {
        val rules = listOf(ExclusionRuleDto(unit, "A.Calc"), ExclusionRuleDto(unit, "A.Calc", "Adds", include = true))
        val calc = TestTreeModel.build(tests, emptyMap(), rules, false).single { it.path == unit }.children.single { it.type == "A.Calc" }

        assertTrue(calc.excluded)
        assertTrue(calc.children.none { it.locked }, "nothing is locked by its parent any more")
        assertFalse(calc.children.single { it.dto.method == "Adds" }.excluded, "re-included by its own rule")
        assertTrue(calc.children.single { it.dto.method == "Subtracts" }.excluded)
        assertTrue(calc.children.all { it.parentExcluded })
    }

    @Test
    fun hide_excluded_keeps_a_test_re_included_inside_an_excluded_project() {
        val rules = listOf(ExclusionRuleDto(unit), ExclusionRuleDto(unit, "A.Calc", "Adds", include = true))
        val tree = TestTreeModel.build(tests, emptyMap(), rules, failuresOnly = false, hideExcluded = true)

        val unitNode = tree.single { it.path == unit }
        assertEquals(listOf("Adds"), unitNode.children.flatMap { c -> c.children.map { it.dto.method } })
    }

    @Test
    fun pending_counts_queued_running_stale_and_discovered_but_not_excluded_or_skipped() {
        val mixed = listOf(
            t(unit, "A.C", "Q", TestState.QUEUED), t(unit, "A.C", "R", TestState.RUNNING), t(unit, "A.C", "S", TestState.STALE),
            t(unit, "A.C", "D", TestState.DISCOVERED), t(unit, "A.C", "K", TestState.SKIPPED), t(unit, "A.C", "X", TestState.EXCLUDED),
            t(unit, "A.C", "P", TestState.PASSED),
        )
        val tree = TestTreeModel.build(mixed, emptyMap(), listOf(ExclusionRuleDto(unit, "A.C", "P")), false)

        assertEquals(Counts(passed = 0, failed = 0, pending = 4), tree.single().children.single().counts)
        assertEquals(Counts(passed = 0, failed = 0, pending = 4), tree.single().counts)
    }

    @Test
    fun counts_label_shows_all_three() {
        assertEquals("✔ 2  ✖ 1  ○ 3", Counts(passed = 2, failed = 1, pending = 3).label())
    }

    @Test
    fun manual_results_are_flagged_and_excluded_ones_keep_their_outcome() {
        val manualFail = t(e2e, "B.Flow", "Checkout", TestState.FAILED).copy(runKind = RunKind.MANUAL)
        val auto = t(unit, "A.Calc", "Adds", TestState.PASSED).copy(runKind = RunKind.AUTO)
        val tree = TestTreeModel.build(listOf(manualFail, auto), emptyMap(), listOf(ExclusionRuleDto(e2e)), false)

        val manualNode = tree.single { it.path == e2e }.children.single().children.single()
        assertTrue(manualNode.manual)
        assertTrue(manualNode.excluded)
        assertEquals(TestState.FAILED, manualNode.dto.state)
        assertFalse(tree.single { it.path == unit }.children.single().children.single().manual)
    }

    @Test
    fun manual_results_of_excluded_tests_count_at_every_level() {
        val manualPass = t(e2e, "B.Flow", "Checkout", TestState.PASSED).copy(runKind = RunKind.MANUAL)
        val notRun = t(e2e, "B.Flow", "Refund", TestState.EXCLUDED)
        val tree = TestTreeModel.build(listOf(manualPass, notRun), emptyMap(), listOf(ExclusionRuleDto(e2e)), false)

        val project = tree.single()
        assertEquals(Counts(passed = 1, failed = 0, pending = 0), project.counts)
        assertEquals(Counts(passed = 1, failed = 0, pending = 0), project.children.single().counts)
        assertEquals(1, project.manualCount)
        assertEquals(1, project.children.single().manualCount)
    }

    @Test
    fun stale_tests_show_their_last_outcome_and_are_marked_not_fresh() {
        val staleFail = t(unit, "A.Calc", "Adds", TestState.STALE).copy(lastOutcome = TestState.FAILED)
        val queuedPass = t(unit, "A.Calc", "Subtracts", TestState.QUEUED).copy(lastOutcome = TestState.PASSED)
        val neverRun = t(unit, "A.Other", "New", TestState.QUEUED)
        val fresh = t(unit, "A.Other", "Works", TestState.PASSED)
        val tree = TestTreeModel.build(listOf(staleFail, queuedPass, neverRun, fresh), emptyMap(), emptyList(), false)
        val calc = tree.single().children.single { it.type == "A.Calc" }
        val other = tree.single().children.single { it.type == "A.Other" }
        fun node(m: String) = tree.single().children.flatMap { it.children }.single { it.dto.method == m }

        assertEquals(TestState.FAILED, node("Adds").shownOutcome)
        assertEquals(TestState.STALE, node("Adds").freshness)
        assertEquals(TestState.PASSED, node("Subtracts").shownOutcome)
        assertEquals(TestState.QUEUED, node("Subtracts").freshness)
        assertEquals(null, node("New").shownOutcome)
        assertEquals(null, node("Works").freshness)

        assertEquals(TestState.FAILED, calc.shownOutcome, "worst last outcome")
        assertFalse(calc.isFresh)
        assertEquals(TestState.PASSED, other.shownOutcome)
        assertFalse(other.isFresh)
        assertEquals(TestState.FAILED, tree.single().shownOutcome)
    }

    @Test
    fun parent_rows_summarise_freshness_running_then_queued_then_stale() {
        fun tree(vararg states: TestState) = TestTreeModel.build(
            states.mapIndexed { i, st -> t(unit, "A.C", "M$i", st).copy(lastOutcome = TestState.PASSED) }, emptyMap(), emptyList(), false).single()

        assertEquals(TestState.STALE, tree(TestState.STALE, TestState.PASSED).freshness)
        assertEquals(TestState.STALE, tree(TestState.STALE, TestState.PASSED).children.single().freshness)
        assertEquals(TestState.QUEUED, tree(TestState.STALE, TestState.QUEUED).freshness)
        assertEquals(TestState.RUNNING, tree(TestState.QUEUED, TestState.RUNNING, TestState.STALE).freshness)
        assertEquals(null, tree(TestState.PASSED, TestState.FAILED).freshness)
    }

    @Test
    fun excluded_tests_never_run_by_hand_do_not_make_parents_stale() {
        val tree = TestTreeModel.build(listOf(t(e2e, "B.Flow", "Checkout", TestState.EXCLUDED), t(e2e, "B.Flow", "Other", TestState.PASSED)),
            emptyMap(), listOf(ExclusionRuleDto(e2e, "B.Flow", "Checkout")), false).single()
        assertEquals(null, tree.freshness)
    }

    @Test
    fun hide_excluded_removes_excluded_projects_classes_and_tests() {
        val rules = listOf(ExclusionRuleDto(e2e), ExclusionRuleDto(unit, "A.Other"), ExclusionRuleDto(unit, "A.Calc", "Adds"))
        val tree = TestTreeModel.build(tests, emptyMap(), rules, failuresOnly = false, hideExcluded = true)

        assertEquals(listOf(unit), tree.map { it.path })
        assertEquals(listOf("A.Calc"), tree.single().children.map { it.type })
        assertEquals(listOf("Subtracts"), tree.single().children.single().children.map { it.dto.method })
    }

    @Test
    fun excluded_tests_not_run_this_session_show_stale_with_no_outcome() {
        val tree = TestTreeModel.build(listOf(t(e2e, "B.Flow", "Checkout", TestState.EXCLUDED), t(e2e, "B.Flow", "Stored", TestState.STALE)),
            emptyMap(), listOf(ExclusionRuleDto(e2e)), false)
        tree.single().children.single().children.forEach {
            assertEquals(TestState.STALE, it.freshness, it.dto.method)
            assertEquals(null, it.shownOutcome, it.dto.method)
        }
    }

    @Test
    fun run_times_add_up_at_class_and_project_level() {
        val timed = listOf(
            t(unit, "A.Calc", "Adds", TestState.PASSED).copy(durationMs = 120.0),
            t(unit, "A.Calc", "Subtracts", TestState.FAILED).copy(durationMs = 30.0),
            t(unit, "A.Other", "Works", TestState.STALE).copy(durationMs = 850.0, lastOutcome = TestState.PASSED),
            t(unit, "A.Other", "NeverRun", TestState.QUEUED).copy(durationMs = null),
            t(unit, "A.Other", "Skipped", TestState.EXCLUDED).copy(durationMs = 999.0),
        )
        val tree = TestTreeModel.build(timed, emptyMap(), listOf(ExclusionRuleDto(unit, "A.Other", "Skipped")), false).single()

        assertEquals(150.0, tree.children.single { it.type == "A.Calc" }.durationMs)
        assertEquals(850.0, tree.children.single { it.type == "A.Other" }.durationMs, "excluded (not run by hand) and unrun tests don't count")
        assertEquals(1000.0, tree.durationMs)
    }

    @Test
    fun durations_format_readably() {
        assertEquals("0 ms", TestTreeModel.formatDuration(0.4))
        assertEquals("850 ms", TestTreeModel.formatDuration(850.0))
        assertEquals("2.4 s", TestTreeModel.formatDuration(2_400.0))
        assertEquals("59.9 s", TestTreeModel.formatDuration(59_900.0))
        assertEquals("1 m 05 s", TestTreeModel.formatDuration(65_000.0))
    }

    @Test
    fun a_state_from_a_newer_engine_keeps_the_last_outcome_and_counts_nothing() {
        val unknown = t(unit, "A.Calc", "Adds", TestState.UNKNOWN).copy(lastOutcome = TestState.PASSED)
        val node = TestTreeModel.build(listOf(unknown), emptyMap(), emptyList(), false).single().children.single().children.single()

        assertEquals(TestState.PASSED, node.shownOutcome)
        assertEquals(TestState.UNKNOWN, node.freshness)
        assertEquals(Counts(), node.counts)
    }

    @Test
    fun a_build_in_progress_is_neither_broken_nor_shown_on_its_own() {
        val tree = TestTreeModel.build(tests, mapOf("src/Lib/Lib.csproj" to ProjectBuild.started("src/Lib/Lib.csproj")), emptyList(), false)
        assertFalse(tree.any { it.path == "src/Lib/Lib.csproj" })

        val building = TestTreeModel.build(tests, mapOf(unit to ProjectBuild.started(unit)), emptyList(), false)
        assertEquals(BuildStatus.BUILDING, building.single { it.path == unit }.buildStatus)
    }

    @Test
    fun a_stale_manual_result_of_an_excluded_test_stops_counting_for_its_parents() {
        val stale = t(unit, "A.Other", "Always_passes", TestState.STALE).copy(runKind = RunKind.MANUAL, lastOutcome = TestState.FAILED)
        val pass = t(unit, "A.Calc", "Adds", TestState.PASSED)
        val tree = TestTreeModel.build(listOf(stale, pass), emptyMap(), listOf(ExclusionRuleDto(unit, "A.Other")), false)
        val project = tree.single()
        assertEquals(TestState.PASSED, project.shownOutcome, "the excluded test's old failure doesn't turn the project red")
        assertEquals(null, project.freshness)
        assertEquals(Counts(passed = 1), project.counts)
        assertEquals(0, project.manualCount)
        val other = project.children.single { it.type == "A.Other" }
        assertEquals(null, other.shownOutcome)
        assertEquals(Counts(), other.counts)
    }
}
