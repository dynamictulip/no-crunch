package com.nocrunch.rider.ui

import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.RunStatus
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.SortMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TreeSelectionTest {
    private val p = "tests/Unit/Unit.csproj"

    private fun t(method: String, variant: String? = null, theory: String? = null) = TestResultDto(
        key = listOfNotNull(p, "A.Calc", method, variant).joinToString("|"), project = p, type = "A.Calc", method = method,
        uid = "A.Calc.$method", variant = variant, displayName = variant ?: method, state = TestState.PASSED, durationMs = null,
        message = null, stackTrace = null, filePath = null, line = null, theoryKey = theory,
    )

    private val theoryKey = "$p|A.Calc|Theory"
    private val project = TestTreeModel.buildAll(
        listOf(t("Adds"), t("Theory"), t("Theory", variant = "Theory(1)", theory = theoryKey)),
        mapOf("src/Core/Core.csproj" to ProjectBuild("src/Core/Core.csproj", BuildStatus.FAILED)), listOf(ExclusionRuleDto(p, "A.Calc", "Adds")),
    )
    private val unit = project.single { it.path == p }
    private val core = project.single { it.path != p }
    private val unitRow = GroupingTree.build(listOf(unit), Grouping(listOf(GroupLevel.PROJECT, GroupLevel.CLASS)), SortMode.NAME).rows.single() as GroupView
    private val adds = unit.children.single().children.single { it.dto.method == "Adds" }
    private val theory = unit.children.single().children.single { it.dto.method == "Theory" }
    /** A class row holding only some of the class: no class rule, so it names its tests. */
    private val splitClass = GroupView("class:split", "Calc", GroupKind.CLASS, listOf(TestView(adds), TestView(theory)), parentExcluded = false)
    private val wholeClass = unitRow.children.single() as GroupView

    @Test
    fun run_keys_cover_every_test_under_the_selection_once() {
        assertEquals(listOf("$p|A.Calc|Adds", theoryKey), TreeSelection.runKeys(listOf(unit, wholeClass, adds, "root")))
        assertEquals(listOf(theoryKey), TreeSelection.runKeys(listOf(theory)))
    }

    @Test
    fun group_rows_run_and_exclude_what_they_show() {
        assertEquals(listOf("$p|A.Calc|Adds", theoryKey), TreeSelection.runKeys(listOf(unitRow)))
        assertEquals(listOf("$p|A.Calc|Adds", theoryKey), TreeSelection.runKeys(listOf(splitClass)))
        assertEquals(listOf(ExclusionRuleDto(p, "A.Calc", "Adds"), ExclusionRuleDto(p, "A.Calc", "Theory")), TreeSelection.checkboxRules(splitClass))
        assertEquals(listOf(ExclusionRuleDto(p)), TreeSelection.checkboxRules(unitRow))
        assertEquals(listOf(ExclusionRuleDto(p, "A.Calc")), TreeSelection.checkboxRules(wholeClass))
    }

    @Test
    fun a_group_row_is_excluded_now_only_when_everything_in_it_is() {
        assertEquals(listOf(false to listOf(ExclusionRuleDto(p))), TreeSelection.exclusionTargets(listOf(unitRow)))
    }

    @Test
    fun a_test_project_that_never_built_is_excluded_by_its_own_rule() {
        val ruled = TestTreeModel.buildAll(emptyList(), mapOf(p to ProjectBuild(p, BuildStatus.FAILED)), listOf(ExclusionRuleDto(p)),
            mapOf(p to RunFinishedDto(p, RunStatus.CRASHED, "exit code 134"))).single()
        val row = GroupView("project:$p", "Unit", GroupKind.PROJECT, emptyList(), false, project = ruled, wholeRule = ruled.rule)
        assertEquals(listOf(true to listOf(ExclusionRuleDto(p))), TreeSelection.exclusionTargets(listOf(row)))
    }

    @Test
    fun a_project_row_without_tests_has_nothing_to_include_or_exclude() {
        val row = GroupView("project:${core.path}", "Core", GroupKind.PROJECT, emptyList(), false, project = core, wholeRule = core.rule)
        assertEquals(emptyList<Any>(), TreeSelection.exclusionTargets(listOf(row)))
        assertNull(TreeSelection.checkboxRules(row))
    }

    @Test
    fun exclusion_targets_skip_theory_rows_and_non_test_projects() {
        assertEquals(
            listOf(false to listOf(ExclusionRuleDto(p)), true to listOf(ExclusionRuleDto(p, "A.Calc", "Adds"))),
            TreeSelection.exclusionTargets(listOf(unit, core, adds, theory.rows.single())),
        )
    }

    @Test
    fun checkbox_rules_name_what_the_row_toggles() {
        assertEquals(listOf(ExclusionRuleDto(p)), TreeSelection.checkboxRules(unit))
        assertEquals(listOf(ExclusionRuleDto(p, "A.Calc")), TreeSelection.checkboxRules(wholeClass))
        assertEquals(listOf(ExclusionRuleDto(p, "A.Calc", "Adds")), TreeSelection.checkboxRules(adds))
        assertNull(TreeSelection.checkboxRules(ProblemsGroup("Build errors", 1)))
    }
}
