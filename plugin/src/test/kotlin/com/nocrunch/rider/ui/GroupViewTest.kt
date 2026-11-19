package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.exclusions.TestExclusions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GroupViewTest {
    private val p = "tests/Unit/Unit.csproj"

    private fun node(type: String, method: String, excluded: Boolean = false) = TestNode(
        TestResultDto(
            key = "$p|$type|$method", project = p, type = type, method = method, uid = "$type.$method", variant = null,
            displayName = method, state = TestState.PASSED, durationMs = 1.0, message = null, stackTrace = null,
            filePath = null, line = 1,
        ),
        excluded = excluded,
    )

    private fun group(kind: GroupKind, children: List<ViewNode>, whole: ExclusionRuleDto? = null) =
        GroupView("k", "label", kind, children, parentExcluded = false, wholeRule = whole)

    private val a = node("Ns.C", "A")
    private val b = node("Ns.C", "B")

    @Test
    fun whole_class_row_writes_its_class_rule() {
        val rule = ExclusionRuleDto(p, "Ns.C")
        assertEquals(listOf(rule), group(GroupKind.CLASS, listOf(TestView(a), TestView(b)), rule).rules)
    }

    @Test
    fun partial_class_row_writes_its_tests() {
        val rules = group(GroupKind.CLASS, listOf(TestView(a), TestView(b))).rules
        assertEquals(listOf(TestExclusions.ruleFor(a.dto), TestExclusions.ruleFor(b.dto)), rules)
    }

    @Test
    fun outer_class_covers_nested_classes() {
        val inner = ExclusionRuleDto(p, "Ns.Outer+Inner")
        val outer = ExclusionRuleDto(p, "Ns.Outer")
        val nested = group(GroupKind.CLASS, listOf(TestView(node("Ns.Outer+Inner", "X"))), inner)
        val row = group(GroupKind.CLASS, listOf(TestView(node("Ns.Outer", "Y")), nested), outer)
        assertEquals(listOf(outer, inner), row.rules)
    }

    @Test
    fun exclusion_state() {
        fun state(vararg ex: Boolean): Triple<Boolean, Boolean, Int> {
            val g = group(GroupKind.CLASS, ex.mapIndexed { i, e -> TestView(node("Ns.C", "T$i", e)) })
            return Triple(g.excluded, g.partlyExcluded, g.excludedCount)
        }
        assertEquals(Triple(false, false, 0), state(false, false))
        assertEquals(Triple(false, true, 1), state(true, false))
        assertEquals(Triple(true, false, 2), state(true, true))
    }

    @Test
    fun whole_project_row_writes_only_its_project_rule() {
        val projectRule = ExclusionRuleDto(p)
        val cls = group(GroupKind.CLASS, listOf(TestView(a)), ExclusionRuleDto(p, "Ns.C"))
        val ns = group(GroupKind.NAMESPACE, listOf(cls))
        assertEquals(listOf(projectRule), group(GroupKind.PROJECT, listOf(ns, cls), projectRule).rules)
    }

    @Test
    fun partial_project_row_writes_its_childrens_rules() {
        val cls = group(GroupKind.CLASS, listOf(TestView(a)), ExclusionRuleDto(p, "Ns.C"))
        val other = group(GroupKind.CLASS, listOf(TestView(node("Ns.D", "Z"))), ExclusionRuleDto(p, "Ns.D"))
        assertEquals(listOf(ExclusionRuleDto(p, "Ns.C"), ExclusionRuleDto(p, "Ns.D")), group(GroupKind.PROJECT, listOf(cls, other)).rules)
    }

    @Test
    fun class_excluded_by_rule_with_a_reincluded_test_is_partly_excluded() {
        val g = group(GroupKind.CLASS, listOf(TestView(node("Ns.C", "A", true)), TestView(node("Ns.C", "B", false))), ExclusionRuleDto(p, "Ns.C"))
        assertEquals(false, g.excluded)
        assertEquals(true, g.partlyExcluded)
    }
}
