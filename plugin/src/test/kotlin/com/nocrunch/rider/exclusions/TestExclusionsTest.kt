package com.nocrunch.rider.exclusions

import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestExclusionsTest {
    private val unit = "tests/Unit/Unit.csproj"
    private val e2e = "tests/E2E/E2E.csproj"

    private fun t(project: String, type: String?, method: String?, uid: String = "$type.$method") = TestResultDto(
        key = if (type != null && method != null) "$project|$type|$method" else "$project|uid:$uid",
        project = project, type = type, method = method, uid = uid, variant = null,
        displayName = method ?: uid, state = TestState.PASSED, durationMs = 1.0, message = null, stackTrace = null, filePath = null, line = null,
    )

    private val tests = listOf(
        t(unit, "A.Calc", "Adds"),
        t(unit, "A.Calc", "Subtracts"),
        t(unit, "A.Other", "Works"),
        t(e2e, "B.Flow", "Checkout"),
    )

    @Test
    fun excluded_keys_lists_every_excluded_test() {
        val rules = listOf(ExclusionRuleDto(e2e), ExclusionRuleDto(unit, "A.Calc", "Adds"))
        assertEquals(setOf("$e2e|B.Flow|Checkout", "$unit|A.Calc|Adds"), TestExclusions.excludedKeys(tests, rules).toSet())
    }

    @Test
    fun an_include_rule_overrides_a_broader_exclusion() {
        val rules = listOf(ExclusionRuleDto(unit, "A.Calc"), ExclusionRuleDto(unit, "A.Calc", "Adds", include = true))
        assertFalse(TestExclusions.isExcluded(t(unit, "A.Calc", "Adds"), rules))
        assertTrue(TestExclusions.isExcluded(t(unit, "A.Calc", "Subtracts"), rules))
    }

    @Test
    fun tests_without_a_class_are_matched_by_uid() {
        val noClass = t(unit, null, null, uid = "u-1")
        assertTrue(TestExclusions.isExcluded(noClass, listOf(ExclusionRuleDto(unit, uid = "u-1"))))
        assertFalse(TestExclusions.isExcluded(noClass, listOf(ExclusionRuleDto(unit, uid = "u-2"))))
    }

    @Test
    fun the_rule_for_a_test_names_its_method_or_else_its_uid() {
        assertEquals(ExclusionRuleDto(unit, "A.Calc", "Adds"), TestExclusions.ruleFor(t(unit, "A.Calc", "Adds")))
        assertEquals(ExclusionRuleDto(unit, uid = "u-1"), TestExclusions.ruleFor(t(unit, null, null, uid = "u-1")))
    }
}
