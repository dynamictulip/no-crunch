package com.nocrunch.rider.exclusions

import com.google.gson.Gson
import com.nocrunch.rider.engine.ExclusionRuleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

class ExclusionRulesTest {
    private val p = "tests/Unit/Unit.csproj"
    private val other = "tests/Other/Other.csproj"
    private val project = ExclusionRuleDto(p)
    private val calc = ExclusionRuleDto(p, "A.Calc")
    private val adds = ExclusionRuleDto(p, "A.Calc", "Adds")
    private val subtracts = ExclusionRuleDto(p, "A.Calc", "Subtracts")

    private fun excluded(rules: List<ExclusionRuleDto>, target: ExclusionRuleDto) = ExclusionRules.isExcluded(target, rules)

    // ---- Shared cases: testdata/exclusions.json at the repo root, also run by the engine's ExclusionSetTests ----

    private class CaseFile(val cases: List<Case>)
    private class Case(val name: String, val rules: List<ExclusionRuleDto>, val tests: List<CaseTest>)
    /** [variant] is in the file for the engine; the plugin's targets have none. */
    private class CaseTest(val project: String, val type: String?, val method: String?, val uid: String, val variant: String?, val excluded: Boolean) {
        override fun toString() = "($project, $type, $method, $uid${variant?.let { ", $it" } ?: ""})"
    }

    /** Set by the Gradle test task; falls back to the path from the plugin directory. */
    private fun sharedCases(): List<Case> {
        val file = File(System.getProperty("nocrunch.exclusionCases") ?: "../testdata/exclusions.json")
        val cases = file.reader().use { Gson().fromJson(it, CaseFile::class.java) }.cases
        assertTrue(cases.size > 10, "only ${cases.size} cases in ${file.absolutePath}")
        return cases
    }

    @TestFactory
    fun shared_cases(): List<DynamicTest> = sharedCases().map { c ->
        DynamicTest.dynamicTest(c.name) {
            // Gson leaves a missing "include" false, as the data class default would.
            val wrong = c.tests.filter { t ->
                ExclusionRules.isExcluded(ExclusionRuleDto(t.project, t.type, t.method, t.uid), c.rules) != t.excluded
            }.map { t -> "  $t should${if (t.excluded) "" else " not"} be excluded" }
            assertTrue(wrong.isEmpty(), "'${c.name}':\n${wrong.joinToString("\n")}")
        }
    }

    // ---- Plugin-only: the engine has no equivalent of toggle ----

    @Test
    fun re_including_a_test_inside_an_excluded_class_adds_an_include_rule() {
        val rules = ExclusionRules.toggle(listOf(calc), adds, excluded = false)

        assertEquals(listOf(calc, adds.copy(include = true)), rules)
        assertFalse(excluded(rules, adds))
        assertTrue(excluded(rules, subtracts), "the rest of the class stays excluded")
        assertTrue(excluded(rules, calc))
    }

    @Test
    fun re_including_a_project_clears_every_rule_underneath() {
        val start = listOf(project, calc, adds, ExclusionRuleDto(p, "A.Other", include = true), ExclusionRuleDto(p, uid = "u1"), ExclusionRuleDto(other, "X"))

        val rules = ExclusionRules.toggle(start, project, excluded = false)

        assertEquals(listOf(ExclusionRuleDto(other, "X")), rules, "only the other project's rule is left")
        assertFalse(excluded(rules, subtracts))
    }

    @Test
    fun excluding_a_class_clears_include_rules_underneath() {
        val rules = ExclusionRules.toggle(listOf(project, adds.copy(include = true)), calc, excluded = true)

        assertEquals(listOf(project), rules, "the class already follows the project, so it needs no rule of its own")
        assertTrue(excluded(rules, adds))
    }

    @Test
    fun excluding_a_test_inside_an_included_class_of_an_excluded_project() {
        val start = listOf(project, calc.copy(include = true))

        val rules = ExclusionRules.toggle(start, adds, excluded = true)

        assertEquals(listOf(project, calc.copy(include = true), adds), rules)
        assertTrue(excluded(rules, adds))
        assertFalse(excluded(rules, subtracts))
    }

    @Test
    fun toggling_back_to_the_inherited_state_just_removes_the_override() {
        val rules = ExclusionRules.toggle(listOf(calc, adds.copy(include = true)), adds, excluded = true)

        assertEquals(listOf(calc), rules)
    }
}
