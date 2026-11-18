package com.nocrunch.rider.ui

import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.view.ViewMode
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
    private val cls = TreeView.of(unit, ViewMode.CLASSES).single()
    private val adds = unit.children.single().children.single { it.dto.method == "Adds" }
    private val theory = unit.children.single().children.single { it.dto.method == "Theory" }

    @Test
    fun run_keys_cover_every_test_under_the_selection_once() {
        assertEquals(listOf("$p|A.Calc|Adds", theoryKey), TreeSelection.runKeys(listOf(unit, cls, adds, "root")))
        assertEquals(listOf(theoryKey), TreeSelection.runKeys(listOf(theory)))
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
        assertEquals(listOf(ExclusionRuleDto(p, "A.Calc")), TreeSelection.checkboxRules(cls))
        assertEquals(listOf(ExclusionRuleDto(p, "A.Calc", "Adds")), TreeSelection.checkboxRules(adds))
        assertNull(TreeSelection.checkboxRules(BuildErrorsGroup(1)))
    }
}
