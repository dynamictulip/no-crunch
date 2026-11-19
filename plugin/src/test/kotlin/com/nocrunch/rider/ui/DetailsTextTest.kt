package com.nocrunch.rider.ui

import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.RunKind
import com.nocrunch.rider.engine.RunStatus
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.SortMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DetailsTextTest {
    private val unit = "tests/Unit/Unit.csproj"

    private fun t(state: TestState, last: TestState? = null, runKind: RunKind = RunKind.AUTO, ms: Double? = 12.0,
                  message: String? = null, stack: String? = null) = TestResultDto(
        key = "$unit|A.Calc|Adds", project = unit, type = "A.Calc", method = "Adds", uid = "A.Calc.Adds", variant = null,
        displayName = "Adds", state = state, durationMs = ms, message = message, stackTrace = stack, filePath = null, line = null,
        runKind = runKind, lastOutcome = last,
    )

    private fun node(dto: TestResultDto, rules: List<ExclusionRuleDto> = emptyList()) =
        TestTreeModel.buildAll(listOf(dto), emptyMap(), rules).single().children.single().children.single()

    /** Red text in [brackets], muted text in {braces}. */
    private fun text(o: Any?) = DetailsText.of(o)?.joinToString("") {
        when (it.style) {
            DetailStyle.NORMAL -> it.text
            DetailStyle.RED -> "[${it.text}]"
            DetailStyle.MUTED -> "{${it.text}}"
        }
    }

    @Test
    fun a_failing_test_shows_its_status_message_and_stack_trace() {
        assertEquals(
            "Adds — [failed] (12 ms)\n{Result from an automatic run}\n\nExpected 3\n\n  at A.Calc.Adds()\n",
            text(node(t(TestState.FAILED, message = "Expected 3", stack = "  at A.Calc.Adds()"))),
        )
    }

    @Test
    fun a_passing_test_is_not_red() {
        assertEquals("Adds — passed (12 ms)\n{Result from an automatic run}\n", text(node(t(TestState.PASSED))))
    }

    @Test
    fun an_earlier_result_says_why_it_is_not_current() {
        assertEquals("Adds — last result: [failed] (stale — the code changed since this ran) (12 ms)\n{Result from an automatic run}\n",
            text(node(t(TestState.STALE, last = TestState.FAILED))))
        assertEquals("Adds — last result: passed (running)\n{Result from an automatic run}\n",
            text(node(t(TestState.RUNNING, last = TestState.PASSED, ms = null))))
    }

    @Test
    fun a_test_with_no_result_shows_its_state() {
        assertEquals("Adds — queued\n{Result from an automatic run}\n", text(node(t(TestState.QUEUED, ms = null))))
    }

    @Test
    fun a_manual_run_of_an_excluded_test_says_both() {
        assertEquals("Adds — passed (12 ms)\n{Result from a manual run}{ (excluded from automatic runs)}\n",
            text(node(t(TestState.PASSED, runKind = RunKind.MANUAL), rules = listOf(ExclusionRuleDto(unit)))))
    }

    @Test
    fun a_project_shows_its_build_and_run_problems() {
        val core = "src/Core/Core.csproj"
        val builds = mapOf(
            core to ProjectBuild(core, BuildStatus.FAILED, "error CS1002"),
            unit to ProjectBuild(unit, BuildStatus.BLOCKED, blockedBy = core),
        )
        val projects = TestTreeModel.buildAll(listOf(t(TestState.PASSED)), builds, emptyList(),
            mapOf(unit to RunFinishedDto(unit, RunStatus.CRASHED, "exit code 134")))

        assertEquals("$core\nBuild: [failed]\n\nerror CS1002\n", text(projects.single { it.path == core }))
        assertEquals("$unit\nBuild: blocked (blocked by $core)\n\nRun: [crashed] — exit code 134\n", text(projects.single { it.path == unit }))
    }

    @Test
    fun a_project_not_built_this_session_shows_just_its_path() {
        assertEquals("$unit\n", text(TestTreeModel.buildAll(listOf(t(TestState.PASSED)), emptyMap(), emptyList()).single()))
    }

    private fun rows(projects: List<ProjectNode>, vararg levels: GroupLevel) =
        GroupingTree.build(projects, Grouping(levels.toList()), SortMode.NAME).rows

    @Test
    fun a_project_group_shows_what_a_project_row_shows() {
        val core = "src/Core/Core.csproj"
        val builds = mapOf(
            core to ProjectBuild(core, BuildStatus.FAILED, "error CS1002"),
            unit to ProjectBuild(unit, BuildStatus.BLOCKED, blockedBy = core),
        )
        val projects = TestTreeModel.buildAll(listOf(t(TestState.PASSED)), builds, emptyList(),
            mapOf(unit to RunFinishedDto(unit, RunStatus.CRASHED, "exit code 134")))
        val row = rows(projects, GroupLevel.PROJECT, GroupLevel.CLASS).single { it.label == "Unit" }

        assertEquals("$unit\nBuild: blocked (blocked by $core)\n\nRun: [crashed] — exit code 134\n", text(row))
        assertEquals(text(projects.single { it.path == unit }), text(row))
    }

    @Test
    fun a_project_group_with_no_build_shows_just_its_path() {
        val projects = TestTreeModel.buildAll(listOf(t(TestState.PASSED)), emptyMap(), emptyList())
        assertEquals("$unit\n", text(rows(projects, GroupLevel.PROJECT, GroupLevel.CLASS).single()))
    }

    @Test
    fun a_class_shows_its_counts_run_time_and_freshness() {
        val project = TestTreeModel.buildAll(listOf(t(TestState.STALE, last = TestState.FAILED)), emptyMap(), emptyList()).single()
        assertEquals("Calc\n✔ 0  ✖ 0  ○ 1   12 ms   (stale)\n", text(rows(listOf(project), GroupLevel.CLASS).single()))
        val failing = TestTreeModel.buildAll(listOf(t(TestState.FAILED)), emptyMap(), emptyList()).single()
        assertEquals("Calc\n✔ 0  [✖ 1]  ○ 0   12 ms\n", text(rows(listOf(failing), GroupLevel.CLASS).single()))
    }

    @Test
    fun nothing_selected_clears_the_pane() {
        assertNull(DetailsText.of(null))
        assertNull(DetailsText.of(ProblemsGroup("Build errors", 1)))
    }
}
