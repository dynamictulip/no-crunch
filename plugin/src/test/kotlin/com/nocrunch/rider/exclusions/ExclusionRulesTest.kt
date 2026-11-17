package com.nocrunch.rider.exclusions

import com.nocrunch.rider.engine.ExclusionRuleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExclusionRulesTest {
    private val p = "tests/Unit/Unit.csproj"
    private val other = "tests/Other/Other.csproj"
    private val project = ExclusionRuleDto(p)
    private val calc = ExclusionRuleDto(p, "A.Calc")
    private val adds = ExclusionRuleDto(p, "A.Calc", "Adds")
    private val subtracts = ExclusionRuleDto(p, "A.Calc", "Subtracts")

    private fun excluded(rules: List<ExclusionRuleDto>, target: ExclusionRuleDto) = ExclusionRules.isExcluded(target, rules)

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
